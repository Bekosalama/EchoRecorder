package com.echo.recorder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat

/** Call-state detection is informational only; no call audio is captured. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val prefs = context.getSharedPreferences("echo_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("call_alerts", false)) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val (title, message, id) = when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> Triple("مكالمة واردة", "تم اكتشاف رنين. لا يسجل التطبيق صوت المكالمة.", 4101)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> Triple("مكالمة نشطة", "المكالمة بدأت. تسجيل الميكروفون منفصل ويبدأ يدويًا.", 4102)
            TelephonyManager.EXTRA_STATE_IDLE -> Triple("انتهت المكالمة", "انتهت حالة المكالمة.", 4103)
            else -> return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelId = "echo_call_alerts"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "تنبيهات حالة المكالمة", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            manager.notify(id, notification)
        }
    }
}
