package com.dmcc26.app.data

import com.dmcc26.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom

fun <T> Query.listFlow(map: (DocumentSnapshot) -> T): Flow<List<T>> = callbackFlow {
    val reg = addSnapshotListener { s, e ->
        if (e != null) trySend(emptyList()) else if (s != null) trySend(s.documents.map(map))
    }
    awaitClose { reg.remove() }
}

fun <T> DocumentReference.docFlow(map: (DocumentSnapshot) -> T): Flow<T?> = callbackFlow {
    val reg = addSnapshotListener { s, e ->
        if (e != null) trySend(null) else trySend(if (s != null && s.exists()) map(s) else null)
    }
    awaitClose { reg.remove() }
}

class BlobData(val bytes: ByteArray, val mime: String, val name: String)

object Repo {
    val ready get() = BuildConfig.FIREBASE_READY
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val uid: String get() = auth.currentUser?.uid ?: ""
    private fun now() = System.currentTimeMillis()

    fun authFlow(): Flow<FirebaseUser?> = callbackFlow {
        val l = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(l)
        awaitClose { auth.removeAuthStateListener(l) }
    }

    // ---------- users ----------
    fun userRef(u: String) = db.collection("users").document(u)
    fun userFlow(u: String) = userRef(u).docFlow { it.toUser() }
    suspend fun getUser(u: String): UserProfile? = userRef(u).get().await().let { if (it.exists()) it.toUser() else null }

    private var cachedMe: UserProfile? = null
    suspend fun me(): UserProfile {
        cachedMe?.takeIf { it.uid == uid }?.let { return it }
        val p = getUser(uid) ?: UserProfile(uid, auth.currentUser?.displayName ?: "", auth.currentUser?.phoneNumber ?: "", auth.currentUser?.email ?: "", "")
        cachedMe = p
        return p
    }

    /** Saves the signed-in user's profile and the phone/email lookup used to find app users. */
    suspend fun saveProfile(name: String, phoneRaw: String, photo: String?) {
        val u = auth.currentUser ?: error("Not signed in")
        val phone = u.phoneNumber?.takeIf { it.isNotBlank() } ?: normalizePhone(phoneRaw)
        if (phoneRaw.isNotBlank() && phone.isBlank()) error("Enter a valid 10-digit mobile number")
        val email = u.email ?: ""
        val old = getUser(u.uid)
        if (phone.isNotBlank() && phone != old?.phone) {
            val p = db.collection("phones").document(phone).get().await()
            val owner = p.getString("uid")
            if (owner != null && owner != u.uid) error("This mobile number is already registered with another account")
            if (owner == null) db.collection("phones").document(phone).set(mapOf("uid" to u.uid)).await()
            if (!old?.phone.isNullOrBlank()) runCatching { db.collection("phones").document(old!!.phone).delete().await() }
        }
        if (email.isNotBlank()) {
            val key = email.lowercase()
            val e = db.collection("emails").document(key).get().await()
            if (!e.exists()) db.collection("emails").document(key).set(mapOf("uid" to u.uid)).await()
        }
        val data = mutableMapOf<String, Any>("name" to name.trim(), "phone" to phone, "email" to email, "updatedAt" to now())
        if (photo != null) data["photo"] = photo
        if (old == null) data["createdAt"] = now()
        userRef(u.uid).set(data, SetOptions.merge()).await()
        cachedMe = null
        runCatching { saveFcmToken() }
    }

    suspend fun saveFcmToken() {
        if (uid.isBlank()) return
        val t = FirebaseMessaging.getInstance().token.await()
        userRef(uid).set(mapOf("fcmToken" to t), SetOptions.merge()).await()
    }

    /** Finds an app user by phone number or email. Returns null if they haven't installed / registered. */
    suspend fun findUser(input: String): UserProfile? {
        val t = input.trim()
        val idx = if (t.contains("@")) db.collection("emails").document(t.lowercase())
        else {
            val p = normalizePhone(t)
            if (p.isBlank()) error("Enter a valid mobile number or email")
            db.collection("phones").document(p)
        }
        val owner = idx.get().await().getString("uid") ?: return null
        return getUser(owner)
    }

    fun signOut() {
        cachedMe = null
        runCatching { userRef(uid).update("fcmToken", "") }
        auth.signOut()
    }

    // ---------- groups ----------
    fun groupRef(g: String) = db.collection("groups").document(g)
    fun myGroupsFlow() = db.collection("groups").whereArrayContains("memberIds", uid).listFlow { it.toGroup() }
    fun groupFlow(g: String) = groupRef(g).docFlow { it.toGroup() }
    fun membersFlow(g: String) = groupRef(g).collection("members").listFlow { it.toMember() }
    fun memberFlow(g: String, u: String) = groupRef(g).collection("members").document(u).docFlow { it.toMember() }
    fun pccsFlow(g: String) = groupRef(g).collection("pccs").orderBy("name").listFlow { it.toPcc() }
    fun privateFlow(g: String, u: String) = groupRef(g).collection("private").document(u).docFlow { it.toPrivate() }

    private fun newCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun memberMap(u: UserProfile, role: String, pccId: String, designation: String) = mapOf(
        "name" to u.name, "phone" to u.phone, "role" to role, "pccId" to pccId,
        "designation" to designation, "addedAt" to now(),
    )

    suspend fun createGroup(name: String, about: String): String {
        val me = me()
        val ref = db.collection("groups").document()
        val code = newCode()
        val t = now()
        db.batch().apply {
            set(ref, mapOf(
                "name" to name.trim(), "about" to about.trim(), "ownerId" to me.uid,
                "admins" to listOf(me.uid), "memberIds" to listOf(me.uid), "inviteCode" to code,
                "createdAt" to t, "lastMessage" to "", "lastAt" to t,
            ))
            set(ref.collection("members").document(me.uid), memberMap(me, "owner", "", "Member"))
            set(db.collection("invites").document(code), mapOf("groupId" to ref.id, "groupName" to name.trim(), "by" to me.uid, "at" to t))
        }.commit().await()
        return ref.id
    }

    suspend fun updateGroup(g: String, name: String, about: String) {
        groupRef(g).update(mapOf("name" to name.trim(), "about" to about.trim())).await()
    }

    /** Admin adds an app user to the group (optionally into a PCC with a designation and IDs). */
    suspend fun addMember(g: String, user: UserProfile, pccId: String, designation: String, voterId: String, aadhaar: String) {
        val ref = groupRef(g)
        db.batch().apply {
            update(ref, "memberIds", FieldValue.arrayUnion(user.uid))
            set(ref.collection("members").document(user.uid), memberMap(user, "member", pccId, designation.ifBlank { "Member" }))
            if (voterId.isNotBlank() || aadhaar.isNotBlank()) {
                set(ref.collection("private").document(user.uid), mapOf("voterId" to voterId.trim(), "aadhaar" to aadhaar.filter { it.isDigit() }), SetOptions.merge())
            }
        }.commit().await()
    }

    suspend fun setPost(g: String, u: String, pccId: String, designation: String) {
        groupRef(g).collection("members").document(u).update(mapOf("pccId" to pccId, "designation" to designation.ifBlank { "Member" })).await()
    }

    suspend fun setAdmin(g: String, u: String, admin: Boolean) {
        val ref = groupRef(g)
        db.batch().apply {
            update(ref, "admins", if (admin) FieldValue.arrayUnion(u) else FieldValue.arrayRemove(u))
            update(ref.collection("members").document(u), "role", if (admin) "admin" else "member")
        }.commit().await()
    }

    suspend fun removeMember(g: String, u: String) {
        val ref = groupRef(g)
        db.batch().apply {
            update(ref, mapOf("memberIds" to FieldValue.arrayRemove(u), "admins" to FieldValue.arrayRemove(u)))
            delete(ref.collection("members").document(u))
            delete(ref.collection("private").document(u))
        }.commit().await()
    }

    suspend fun leaveGroup(g: String) {
        val ref = groupRef(g)
        ref.collection("private").document(uid).delete().await()
        ref.collection("members").document(uid).delete().await()
        ref.update(mapOf("memberIds" to FieldValue.arrayRemove(uid), "admins" to FieldValue.arrayRemove(uid))).await()
    }

    suspend fun deleteGroup(g: String, inviteCode: String) {
        val ref = groupRef(g)
        if (inviteCode.isNotBlank()) runCatching { db.collection("invites").document(inviteCode).delete().await() }
        ref.delete().await()
    }

    // ---------- invites ----------
    fun inviteText(groupName: String, code: String): String {
        val dl = if (BuildConfig.REPO.isNotBlank()) "https://github.com/${BuildConfig.REPO}/releases/latest" else "(ask the administrator for the app)"
        return "You are invited to join \"$groupName\" on the $APP_SHORT_NAME app ($APP_FULL_NAME).\n\n" +
            "1. Install the app: $dl  (download 26-DMCC.apk)\n" +
            "2. Register with your mobile number or email.\n" +
            "3. Tap Join group and enter code: $code\n\n" +
            "Or open: dmcc26://join/$code"
    }

    suspend fun resetInvite(g: String, oldCode: String, groupName: String): String {
        val code = newCode()
        db.batch().apply {
            if (oldCode.isNotBlank()) delete(db.collection("invites").document(oldCode))
            set(db.collection("invites").document(code), mapOf("groupId" to g, "groupName" to groupName, "by" to uid, "at" to now()))
            update(groupRef(g), "inviteCode", code)
        }.commit().await()
        return code
    }

    /** Joins a group with an invite code. Returns the group id. */
    suspend fun joinWithCode(codeRaw: String): String {
        val code = codeRaw.trim().uppercase()
        val inv = db.collection("invites").document(code).get().await()
        if (!inv.exists()) error("Invite code not found or expired")
        val g = inv.str("groupId")
        val me = me()
        val ref = groupRef(g)
        ref.update("memberIds", FieldValue.arrayUnion(me.uid)).await()
        val m = ref.collection("members").document(me.uid)
        if (!m.get().await().exists()) m.set(memberMap(me, "member", "", "Member")).await()
        return g
    }

    // ---------- PCCs ----------
    suspend fun addPcc(g: String, name: String): String {
        val ref = groupRef(g).collection("pccs").document()
        ref.set(mapOf("name" to name.trim(), "createdAt" to now())).await()
        return ref.id
    }

    suspend fun renamePcc(g: String, p: String, name: String) {
        groupRef(g).collection("pccs").document(p).update("name", name.trim()).await()
    }

    suspend fun deletePcc(g: String, p: String) {
        val ref = groupRef(g)
        val ms = ref.collection("members").whereEqualTo("pccId", p).get().await()
        db.batch().apply {
            ms.documents.forEach { update(it.reference, mapOf("pccId" to "", "designation" to "Member")) }
            delete(ref.collection("pccs").document(p))
        }.commit().await()
    }

    // ---------- IDs & documents ----------
    suspend fun saveIds(g: String, u: String, voterId: String, aadhaar: String) {
        groupRef(g).collection("private").document(u)
            .set(mapOf("voterId" to voterId.trim(), "aadhaar" to aadhaar.filter { it.isDigit() }), SetOptions.merge()).await()
    }

    suspend fun addDocument(g: String, u: String, data: BlobData, kind: String) {
        val id = putBlob(data, scope = "private", groupId = g, subject = u)
        groupRef(g).collection("private").document(u).set(
            mapOf("docs" to FieldValue.arrayUnion(mapOf("id" to id, "name" to data.name, "mime" to data.mime, "kind" to kind))),
            SetOptions.merge(),
        ).await()
    }

    suspend fun removeDocument(g: String, u: String, d: DocRef) {
        groupRef(g).collection("private").document(u).update(
            "docs", FieldValue.arrayRemove(mapOf("id" to d.id, "name" to d.name, "mime" to d.mime, "kind" to d.kind)),
        ).await()
        runCatching { db.collection("blobs").document(d.id).delete().await() }
    }

    // ---------- blobs (files stored inside Firestore, max ~900 KB each) ----------
    private val blobCache = object : android.util.LruCache<String, BlobData>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: BlobData) = value.bytes.size
    }

    suspend fun putBlob(data: BlobData, scope: String, groupId: String = "", subject: String = ""): String {
        val ref = db.collection("blobs").document()
        ref.set(mapOf(
            "owner" to uid, "scope" to scope, "groupId" to groupId, "subject" to subject,
            "name" to data.name, "mime" to data.mime, "size" to data.bytes.size,
            "data" to Blob.fromBytes(data.bytes), "at" to now(),
        )).await()
        blobCache.put(ref.id, data)
        return ref.id
    }

    suspend fun getBlob(id: String): BlobData? {
        if (id.isBlank()) return null
        blobCache.get(id)?.let { return it }
        val d = db.collection("blobs").document(id).get().await()
        val b = d.getBlob("data") ?: return null
        val out = BlobData(b.toBytes(), d.str("mime"), d.str("name"))
        blobCache.put(id, out)
        return out
    }

    // ---------- group chat ----------
    fun groupMessagesFlow(g: String) = groupRef(g).collection("messages").orderBy("at", Query.Direction.DESCENDING).limit(300).listFlow { it.toMessage() }

    suspend fun sendGroupMessage(g: String, text: String, file: BlobData?) {
        val me = me()
        val blobId = file?.let { putBlob(it, scope = "group", groupId = g) } ?: ""
        val t = now()
        val preview = text.ifBlank { if (file?.mime?.startsWith("image/") == true) "📷 Photo" else "📎 ${file?.name ?: ""}" }
        db.batch().apply {
            set(groupRef(g).collection("messages").document(), mapOf(
                "from" to me.uid, "fromName" to me.name, "text" to text, "blobId" to blobId,
                "mime" to (file?.mime ?: ""), "fileName" to (file?.name ?: ""), "at" to t,
            ))
            update(groupRef(g), mapOf("lastMessage" to "${me.name}: $preview", "lastAt" to t))
        }.commit().await()
    }

    // ---------- direct chat ----------
    fun dmId(a: String, b: String) = listOf(a, b).sorted().joinToString("_")
    fun dmRef(id: String) = db.collection("dms").document(id)
    fun myDmsFlow() = db.collection("dms").whereArrayContains("members", uid).listFlow { it.toDm() }
    fun dmMessagesFlow(id: String) = dmRef(id).collection("messages").orderBy("at", Query.Direction.DESCENDING).limit(300).listFlow { it.toMessage() }

    suspend fun sendDm(peer: UserProfile, text: String, file: BlobData?) {
        val me = me()
        val id = dmId(me.uid, peer.uid)
        val blobId = file?.let { putBlob(it, scope = "dm", groupId = id) } ?: ""
        val t = now()
        val preview = text.ifBlank { if (file?.mime?.startsWith("image/") == true) "📷 Photo" else "📎 ${file?.name ?: ""}" }
        dmRef(id).set(mapOf(
            "members" to listOf(me.uid, peer.uid).sorted(),
            "names" to mapOf(me.uid to me.name, peer.uid to peer.name),
            "lastMessage" to preview, "lastFrom" to me.uid, "lastAt" to t,
        ), SetOptions.merge()).await()
        dmRef(id).collection("messages").document().set(mapOf(
            "from" to me.uid, "fromName" to me.name, "text" to text, "blobId" to blobId,
            "mime" to (file?.mime ?: ""), "fileName" to (file?.name ?: ""), "at" to t,
        )).await()
    }

    // ---------- notices ----------
    fun noticesFlow(g: String, groupName: String) = groupRef(g).collection("notices")
        .orderBy("at", Query.Direction.DESCENDING).limit(100).listFlow { it.toNotice(g, groupName) }

    suspend fun postNotice(g: String, title: String, body: String) {
        val me = me()
        groupRef(g).collection("notices").document().set(mapOf(
            "title" to title.trim(), "body" to body.trim(), "by" to me.uid, "byName" to me.name, "at" to now(),
        )).await()
    }

    suspend fun deleteNotice(g: String, n: String) {
        groupRef(g).collection("notices").document(n).delete().await()
    }
}
