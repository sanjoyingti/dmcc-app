package com.dmcc26.app.data

import com.google.firebase.firestore.DocumentSnapshot

const val APP_FULL_NAME = "26-DUARBAGORI MANDAL CONGRESS COMMITTEE"
const val APP_SHORT_NAME = "26-DMCC"
const val DEVELOPER_NOTE = "Developed by Sanjoy Ingti · Ph: 7002397372"

/** Designations in display order (highest first). "Member" is the default. */
val DESIGNATIONS = listOf(
    "President",
    "Working President",
    "Vice President",
    "General Secretary",
    "Secretary",
    "Joint Secretary",
    "Organising Secretary",
    "Treasurer",
    "Publicity Secretary",
    "Executive Member",
    "Member",
)

fun designationRank(d: String): Int = DESIGNATIONS.indexOf(d).let { if (it < 0) DESIGNATIONS.size - 1 else it }

data class UserProfile(
    val uid: String,
    val name: String,
    val phone: String,
    val email: String,
    val photo: String,
)

data class Group(
    val id: String,
    val name: String,
    val about: String,
    val ownerId: String,
    val admins: List<String>,
    val memberIds: List<String>,
    val inviteCode: String,
    val lastMessage: String,
    val lastAt: Long,
)

data class Member(
    val uid: String,
    val name: String,
    val phone: String,
    val role: String, // owner | admin | member
    val pccId: String,
    val designation: String,
    val addedAt: Long,
) {
    val isAdmin get() = role == "owner" || role == "admin"
}

data class Pcc(val id: String, val name: String)

data class DocRef(val id: String, val name: String, val mime: String, val kind: String)

data class PrivateInfo(
    val voterId: String,
    val aadhaar: String,
    val docs: List<DocRef>,
)

data class Message(
    val id: String,
    val from: String,
    val fromName: String,
    val text: String,
    val blobId: String,
    val mime: String,
    val fileName: String,
    val at: Long,
)

data class Notice(
    val id: String,
    val groupId: String,
    val groupName: String,
    val title: String,
    val body: String,
    val byName: String,
    val at: Long,
)

data class Dm(
    val id: String,
    val members: List<String>,
    val names: Map<String, String>,
    val lastMessage: String,
    val lastFrom: String,
    val lastAt: Long,
)

// ---- mapping from Firestore ----

fun DocumentSnapshot.str(k: String) = getString(k) ?: ""
fun DocumentSnapshot.long(k: String) = getLong(k) ?: 0L

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.strList(k: String): List<String> = (get(k) as? List<Any?>)?.mapNotNull { it as? String } ?: emptyList()

fun DocumentSnapshot.toUser() = UserProfile(id, str("name"), str("phone"), str("email"), str("photo"))

fun DocumentSnapshot.toGroup() = Group(
    id, str("name"), str("about"), str("ownerId"), strList("admins"), strList("memberIds"),
    str("inviteCode"), str("lastMessage"), long("lastAt"),
)

fun DocumentSnapshot.toMember() = Member(
    id, str("name"), str("phone"), str("role").ifBlank { "member" }, str("pccId"),
    str("designation").ifBlank { "Member" }, long("addedAt"),
)

fun DocumentSnapshot.toPcc() = Pcc(id, str("name"))

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toPrivate(): PrivateInfo {
    val docs = (get("docs") as? List<Map<String, Any?>>)?.map {
        DocRef(
            it["id"] as? String ?: "", it["name"] as? String ?: "",
            it["mime"] as? String ?: "", it["kind"] as? String ?: "",
        )
    } ?: emptyList()
    return PrivateInfo(str("voterId"), str("aadhaar"), docs)
}

fun DocumentSnapshot.toMessage() = Message(
    id, str("from"), str("fromName"), str("text"), str("blobId"), str("mime"), str("fileName"), long("at"),
)

fun DocumentSnapshot.toNotice(groupId: String, groupName: String) = Notice(
    id, groupId, groupName, str("title"), str("body"), str("byName"), long("at"),
)

@Suppress("UNCHECKED_CAST")
fun DocumentSnapshot.toDm() = Dm(
    id, strList("members"),
    (get("names") as? Map<String, Any?>)?.mapValues { it.value as? String ?: "" } ?: emptyMap(),
    str("lastMessage"), str("lastFrom"), long("lastAt"),
)

/** Shows only the last 4 digits of an Aadhaar number. */
fun maskAadhaar(a: String): String {
    val d = a.filter { it.isDigit() }
    return if (d.length < 4) a else "XXXX XXXX " + d.takeLast(4)
}

/** Normalises an Indian phone number to +91XXXXXXXXXX. Returns "" if it doesn't look like one. */
fun normalizePhone(raw: String): String {
    val t = raw.trim()
    val digits = t.filter { it.isDigit() }
    return when {
        t.startsWith("+") && digits.length in 8..15 -> "+$digits"
        digits.length == 10 -> "+91$digits"
        digits.length == 11 && digits.startsWith("0") -> "+91" + digits.drop(1)
        digits.length == 12 && digits.startsWith("91") -> "+$digits"
        digits.length == 13 && digits.startsWith("091") -> "+" + digits.drop(1)
        else -> ""
    }
}
