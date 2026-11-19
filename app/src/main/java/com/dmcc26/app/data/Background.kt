package com.dmcc26.app.data

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dmcc26.app.call.CallManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Push messages sent by the optional cloud function (firebase/functions). */
class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        if (!Repo.ready || Repo.uid.isBlank()) return
        CoroutineScope(Dispatchers.IO).launch { runCatching { Repo.saveFcmToken() } }
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "call" -> {
                val id = d["callId"] ?: return
                if (CallManager.busy) return
                if (Watcher.active && Watcher.inForeground) return // already handled in-app
                Notifier.incomingCall(this, id, d["name"] ?: "Call", d["video"] == "true")
            }
            else -> {
                if (Watcher.active) return // the in-app watcher already shows these
                Notifier.show(
                    this,
                    if (d["type"] == "notice") Notifier.CH_NOTICES else Notifier.CH_MESSAGES,
                    (d["tag"] ?: d["title"] ?: "x").hashCode(),
                    d["title"] ?: APP_SHORT_NAME, d["body"] ?: "", d["route"] ?: "",
                )
            }
        }
    }
}

/** Every ~15 minutes, checks for new notices even if no push service is set up. */
class NoticeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!Repo.ready || Repo.uid.isBlank() || Watcher.active) return Result.success()
        val prefs = applicationContext.getSharedPreferences("worker", Context.MODE_PRIVATE)
        val since = prefs.getLong("since", System.currentTimeMillis())
        val now = System.currentTimeMillis()
        return try {
            val groups = Repo.db.collection("groups").whereArrayContains("memberIds", Repo.uid).get().await().documents.map { it.toGroup() }
            for (g in groups) {
                val ns = Repo.groupRef(g.id).collection("notices").whereGreaterThan("at", since).get().await()
                ns.documents.filter { it.getString("by") != Repo.uid }.forEach { n ->
                    Notifier.show(applicationContext, Notifier.CH_NOTICES, n.id.hashCode(), "📢 ${g.name}: ${n.str("title")}", n.str("body"), "notices")
                }
                if (g.lastAt > since && !g.lastMessage.startsWith(Repo.me().name + ":")) {
                    Notifier.show(applicationContext, Notifier.CH_MESSAGES, g.id.hashCode(), g.name, g.lastMessage, "chat/g/${g.id}")
                }
            }
            prefs.edit().putLong("since", now).apply()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<NoticeWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("notices", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String, val pageUrl: String)

/** Checks the GitHub releases of this app's repo for a newer build. */
object Updates {
    suspend fun check(repo: String): UpdateInfo? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (repo.isBlank()) return@withContext null
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        try {
            if (c.responseCode != 200) error("Update server returned ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val tag = j.optString("tag_name")
            val body = j.optString("body")
            val code = Regex("versionCode=(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets")
            var apk = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            UpdateInfo(code, tag.removePrefix("v"), apk, j.optString("html_url"))
        } finally {
            c.disconnect()
        }
    }

    fun openDownload(ctx: Context, info: UpdateInfo) {
        val url = info.apkUrl.ifBlank { info.pageUrl }
        ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
