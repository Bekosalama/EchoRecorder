package com.echo.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Foreground microphone recorder. Records microphone audio only; never call audio. */
class RecordingService : Service() {
    private var recorder: MediaRecorder? = null
    private var pendingFile: File? = null
    private var finalFile: File? = null
    private var startedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        cleanStalePartials()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startUserRecording()
            ACTION_STOP -> stopUserRecording()
        }
        return START_NOT_STICKY
    }

    private fun startUserRecording() {
        if (recorder != null) return
        startForeground(NOTIFICATION_ID, notification("جارٍ تجهيز التسجيل الصوتي"))
        try {
            val dir = File(filesDir, "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val safeName = "تسجيل_$stamp.m4a"
            val final = File(dir, safeName)
            // Write to a hidden temporary file and publish only after a successful stop.
            val temp = File(dir, ".${safeName}.partial")
            temp.delete()
            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mediaRecorder.setAudioEncodingBitRate(128000)
            mediaRecorder.setAudioSamplingRate(44100)
            mediaRecorder.setOutputFile(temp.absolutePath)
            mediaRecorder.prepare()
            mediaRecorder.start()
            recorder = mediaRecorder
            pendingFile = temp
            finalFile = final
            startedAt = System.currentTimeMillis()
            getSharedPreferences("echo_settings", MODE_PRIVATE).edit()
                .putBoolean("recording_active", true)
                .putLong("recording_started_at", startedAt)
                .apply()
            sendStatus(true, safeName)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("جارٍ تسجيل الصوت من الميكروفون"))
        } catch (e: Exception) {
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
            pendingFile?.delete()
            pendingFile = null
            finalFile = null
            clearRecordingState()
            sendStatus(false, "تعذر بدء التسجيل: ${e.localizedMessage ?: "خطأ"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }


    private fun stopUserRecording() {
        if (recorder == null) {
            clearRecordingState()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val temp = pendingFile
        val destination = finalFile
        var completed = false
        try {
            recorder?.stop()
            completed = temp != null && destination != null && temp.exists() && temp.length() > 1024 && temp.renameTo(destination)
        } catch (_: Exception) {
            // MediaRecorder may throw if stopped before producing a valid clip.
        } finally {
            try { recorder?.release() } catch (_: Exception) { }
            recorder = null
            if (!completed) temp?.delete()
            pendingFile = null
            finalFile = null
            clearRecordingState()
        }
        sendStatus(false, if (completed) destination?.name ?: "تم حفظ التسجيل" else "لم يتم حفظ التسجيل؛ قد تكون مدته قصيرة جدًا")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }


    private fun clearRecordingState() {
        getSharedPreferences("echo_settings", MODE_PRIVATE).edit()
            .putBoolean("recording_active", false)
            .remove("recording_started_at")
            .apply()
        startedAt = 0L
    }

    private fun cleanStalePartials() {
        val dir = File(filesDir, "recordings")
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".partial") && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun notification(message: String): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Echo Recorder")
            .setContentText(message)
            .setContentIntent(pendingOpen)
            .setOngoing(recorder != null)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف وحفظ", pendingStop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "التسجيل الصوتي", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun sendStatus(isRecording: Boolean, detail: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName)
            .putExtra(EXTRA_RECORDING, isRecording).putExtra(EXTRA_DETAIL, detail))
    }

    override fun onDestroy() {
        // Try to finalize the recording if the service is torn down unexpectedly.
        if (recorder != null) stopUserRecording()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null


    companion object {
        const val ACTION_START = "com.echo.recorder.action.START_RECORDING"
        const val ACTION_STOP = "com.echo.recorder.action.STOP_RECORDING"
        const val ACTION_STATUS = "com.echo.recorder.action.RECORDING_STATUS"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_DETAIL = "detail"
        const val CHANNEL_ID = "echo_recording_channel"
        const val NOTIFICATION_ID = 4401
    }
}
