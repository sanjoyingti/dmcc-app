package com.dmcc26.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dmcc26.app.MainActivity
import com.dmcc26.app.R
import com.dmcc26.app.call.CallActivity

object Notifier {
    const val CH_CALLS = "calls"
    const val CH_MESSAGES = "messages"
    const val CH_NOTICES = "notices"
    const val CALL_NOTIF_ID = 4242

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val calls = NotificationChannel(CH_CALLS, "Calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(ring, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        }
        nm.createNotificationChannel(calls)
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_NOTICES, "Important notices", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun allowed(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** route: in-app destination opened when tapped, e.g. "group/ID" or "chat/d/UID". */
    fun show(ctx: Context, channel: String, id: Int, title: String, text: String, route: String) {
        if (!allowed(ctx)) return
        val i = Intent(ctx, MainActivity::class.java).putExtra("route", route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }

    fun incomingCall(ctx: Context, callId: String, name: String, video: Boolean) {
        if (!allowed(ctx)) return
        val i = Intent(ctx, CallActivity::class.java)
            .putExtra(CallActivity.EXTRA_CALL_ID, callId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(if (video) "Incoming video call" else "Incoming voice call")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(true)
            .setTimeoutAfter(45_000)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(CALL_NOTIF_ID, n) }
    }

    fun cancelCall(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(CALL_NOTIF_ID)
}
