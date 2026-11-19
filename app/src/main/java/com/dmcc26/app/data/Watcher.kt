package com.dmcc26.app.data

import android.content.Context
import android.content.Intent
import com.dmcc26.app.call.CallActivity
import com.dmcc26.app.call.CallManager
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.ListenerRegistration

/**
 * While the app process is alive, listens for incoming calls, new notices and new messages
 * and shows notifications. (When the app is closed, Firebase Cloud Messaging takes over if
 * the optional cloud function is deployed, and the periodic NoticeWorker as a fallback.)
 */
object Watcher {
    @Volatile var active = false
        private set
    @Volatile var inForeground = false
    @Volatile var openChat: String = "" // "g:<gid>" or "d:<dmId>"

    private val regs = mutableListOf<ListenerRegistration>()
    private val groupRegs = mutableMapOf<String, List<ListenerRegistration>>()
    private var startAt = 0L
    private var uid = ""

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!Repo.ready || Repo.uid.isBlank()) return
        if (active && uid == Repo.uid) return
        stop()
        active = true
        uid = Repo.uid
        startAt = System.currentTimeMillis()
        val db = Repo.db

        regs += db.collection("calls").whereEqualTo("to", uid).whereEqualTo("status", "ringing")
            .addSnapshotListener { s, _ ->
                s?.documentChanges?.forEach { ch ->
                    if (ch.type != DocumentChange.Type.ADDED) return@forEach
                    val d = ch.document
                    val at = d.getLong("at") ?: 0
                    if (System.currentTimeMillis() - at > 50_000) return@forEach
                    if (CallManager.busy) return@forEach
                    val name = d.getString("fromName") ?: "Call"
                    val video = d.getBoolean("video") ?: false
                    if (inForeground) {
                        app.startActivity(Intent(app, CallActivity::class.java)
                            .putExtra(CallActivity.EXTRA_CALL_ID, d.id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } else {
                        Notifier.incomingCall(app, d.id, name, video)
                    }
                }
            }

        regs += db.collection("groups").whereArrayContains("memberIds", uid).addSnapshotListener { s, _ ->
            val groups = s?.documents?.map { it.toGroup() } ?: return@addSnapshotListener
            val ids = groups.map { it.id }.toSet()
            (groupRegs.keys - ids).forEach { k -> groupRegs.remove(k)?.forEach { it.remove() } }
            groups.filter { it.id !in groupRegs }.forEach { g -> groupRegs[g.id] = watchGroup(app, g) }
        }

        regs += db.collection("dms").whereArrayContains("members", uid).addSnapshotListener { s, _ ->
            s?.documentChanges?.forEach { ch ->
                if (ch.type == DocumentChange.Type.REMOVED) return@forEach
                val dm = ch.document.toDm()
                if (dm.lastAt <= startAt || dm.lastFrom == uid || openChat == "d:${dm.id}") return@forEach
                val name = dm.names[dm.lastFrom] ?: "New message"
                Notifier.show(app, Notifier.CH_MESSAGES, dm.id.hashCode(), name, dm.lastMessage, "chat/d/${dm.lastFrom}")
            }
        }
    }

    private fun watchGroup(app: Context, g: Group): List<ListenerRegistration> {
        val ref = Repo.groupRef(g.id)
        val r1 = ref.collection("notices").whereGreaterThan("at", startAt).addSnapshotListener { s, _ ->
            s?.documentChanges?.forEach { ch ->
                if (ch.type != DocumentChange.Type.ADDED) return@forEach
                val n = ch.document
                if (n.getString("by") == uid) return@forEach
                Notifier.show(app, Notifier.CH_NOTICES, n.id.hashCode(), "📢 ${g.name}: ${n.str("title")}", n.str("body"), "notices")
            }
        }
        val r2 = ref.collection("messages").whereGreaterThan("at", startAt).addSnapshotListener { s, _ ->
            s?.documentChanges?.forEach { ch ->
                if (ch.type != DocumentChange.Type.ADDED) return@forEach
                val m = ch.document.toMessage()
                if (m.from == uid || openChat == "g:${g.id}") return@forEach
                val text = m.text.ifBlank { if (m.mime.startsWith("image/")) "📷 Photo" else "📎 ${m.fileName}" }
                Notifier.show(app, Notifier.CH_MESSAGES, g.id.hashCode(), g.name, "${m.fromName}: $text", "chat/g/${g.id}")
            }
        }
        return listOf(r1, r2)
    }

    fun stop() {
        regs.forEach { it.remove() }
        regs.clear()
        groupRegs.values.flatten().forEach { it.remove() }
        groupRegs.clear()
        active = false
        uid = ""
    }
}
