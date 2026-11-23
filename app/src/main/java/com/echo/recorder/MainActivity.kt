package com.echo.recorder

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var recording by mutableStateOf(false)
    private var files by mutableStateOf(listOf<File>())
    private var errorMessage by mutableStateOf<String?>(null)
    private var player: MediaPlayer? = null
    private var playingFile by mutableStateOf<File?>(null)
    private var playing by mutableStateOf(false)
    private var callAlertsEnabled by mutableStateOf(false)
    private var recordingStartedAt by mutableStateOf(0L)

    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecordingService() else errorMessage = "يحتاج التطبيق إلى إذن الميكروفون للتسجيل الصوتي."
    }
    private val phonePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) setCallAlerts(true) else errorMessage = "لم يتم منح إذن حالة الهاتف، لذلك لم تُفعّل تنبيهات المكالمات."
    }
    private val notificationsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecordingService.ACTION_STATUS) return
            recording = intent.getBooleanExtra(RecordingService.EXTRA_RECORDING, false)
            recordingStartedAt = getSharedPreferences("echo_settings", MODE_PRIVATE).getLong("recording_started_at", 0L)
            val detail = intent.getStringExtra(RecordingService.EXTRA_DETAIL).orEmpty()
            if (!recording && detail.startsWith("تعذر")) errorMessage = detail
            refreshFiles()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callAlertsEnabled = getSharedPreferences("echo_settings", MODE_PRIVATE).getBoolean("call_alerts", false)
        val settings = getSharedPreferences("echo_settings", MODE_PRIVATE)
        recording = settings.getBoolean("recording_active", false)
        recordingStartedAt = settings.getLong("recording_started_at", 0L)
        refreshFiles()
        setContent {
            EchoTheme {
                EchoScreen(
                    recording = recording,
                    recordingStartedAt = recordingStartedAt,
                    recordings = files,
                    callAlertsEnabled = callAlertsEnabled,
                    playingFile = playingFile,
                    playing = playing,
                    error = errorMessage,
                    onRecordToggle = ::toggleRecording,
                    onCallAlertsToggle = ::toggleCallAlerts,
                    onPlay = ::playFile,
                    onPause = ::pausePlayback,
                    onRename = ::renameFile,
                    onDelete = ::deleteFile,
                    onShare = ::shareFile,
                    onDismissError = { errorMessage = null }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val settings = getSharedPreferences("echo_settings", MODE_PRIVATE)
        recording = settings.getBoolean("recording_active", false)
        recordingStartedAt = settings.getLong("recording_started_at", 0L)
        val filter = IntentFilter(RecordingService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(statusReceiver, filter)
    }


    override fun onStop() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) { }
        super.onStop()
    }


    private fun toggleRecording() {
        if (recording) stopRecordingService()
        else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecordingService()
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }


    private fun startRecordingService() {
        val intent = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        recording = true
    }


    private fun stopRecordingService() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }


    private fun toggleCallAlerts() {
        if (callAlertsEnabled) setCallAlerts(false)
        else if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) setCallAlerts(true)
        else phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
    }


    private fun setCallAlerts(enabled: Boolean) {
        callAlertsEnabled = enabled
        getSharedPreferences("echo_settings", MODE_PRIVATE).edit().putBoolean("call_alerts", enabled).apply()
        if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationsPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }


    private fun refreshFiles() {
        val dir = File(filesDir, "recordings")
        files = dir.listFiles()?.filter { it.isFile && it.extension.equals("m4a", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }


    private fun playFile(file: File) {
        try {
            if (playingFile == file && player != null) {
                player?.start()
                playing = true
                return
            }
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); player = null; playing = false; playingFile = null }
                prepare(); start()
            }
            playingFile = file; playing = true; errorMessage = null
        } catch (_: Exception) {
            errorMessage = "تعذر تشغيل التسجيل. قد يكون الملف غير مكتمل."
            player?.release(); player = null; playing = false
        }
    }


    private fun pausePlayback() {
        try { player?.pause(); playing = false } catch (_: Exception) { }
    }


    private fun deleteFile(file: File) {
        if (playingFile == file) { player?.release(); player = null; playing = false; playingFile = null }
        if (!file.delete()) errorMessage = "تعذر حذف التسجيل."
        refreshFiles()
    }


    private fun renameFile(file: File, rawName: String) {
        val name = rawName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        if (name.isBlank()) { errorMessage = "أدخل اسمًا صالحًا."; return }
        val target = File(file.parentFile, "$name.m4a")
        if (target.exists()) { errorMessage = "يوجد تسجيل بهذا الاسم بالفعل."; return }
        if (!file.renameTo(target)) errorMessage = "تعذر تغيير الاسم."
        refreshFiles()
    }


    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "مشاركة التسجيل الصوتي"))
        } catch (_: Exception) { errorMessage = "تعذر مشاركة الملف." }
    }


    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }
}


private val Navy = Color(0xFF14243A)
private val Teal = Color(0xFF159E97)
private val Backdrop = Color(0xFFF3F6F9)


@Composable
private fun EchoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, background = Backdrop, surface = Color.White)) { content() }
}


@Composable
private fun EchoScreen(
    recording: Boolean, recordingStartedAt: Long, recordings: List<File>, callAlertsEnabled: Boolean, playingFile: File?, playing: Boolean, error: String?,
    onRecordToggle: () -> Unit, onCallAlertsToggle: () -> Unit, onPlay: (File) -> Unit, onPause: () -> Unit,
    onRename: (File, String) -> Unit, onDelete: (File) -> Unit, onShare: (File) -> Unit, onDismissError: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(recording, recordingStartedAt) {
        while (recording && recordingStartedAt > 0L) {
            elapsedSeconds = ((System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L) / 1000L)
            delay(1000)
        }
        if (!recording) elapsedSeconds = 0L
    }
    val filtered = recordings.filter { it.nameWithoutExtension.contains(query, ignoreCase = true) }
    Surface(Modifier.fillMaxSize(), color = Backdrop) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(Teal, CircleShape), contentAlignment = Alignment.Center) { Text("◉", color = Color.White, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("Echo Recorder", color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("مسجل الصوت الشخصي", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(18.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Navy)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.Center) {
                    Text(if (recording) "التسجيل نشط" else "جاهز للتسجيل", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(if (recording) "● الميكروفون قيد الاستخدام · ${formatClock(elapsedSeconds)}" else "التسجيل الصوتي يبدأ عند الضغط", color = if (recording) Color(0xFFFF9B91) else Color(0xFFC6D4E2))
                    Spacer(Modifier.height(15.dp))
                    Button(onClick = onRecordToggle, modifier = Modifier.size(78.dp), shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = if (recording) Color(0xFFE15F5F) else Teal)) {
                        Text(if (recording) "■" else "●", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(if (recording) "إيقاف وحفظ التسجيل" else "ابدأ تسجيلًا جديدًا", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("يظهر إشعار دائم أثناء التسجيل ويمكن إيقافه من الإشعار.", color = Color(0xFFC6D4E2), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("تنبيهات حالة المكالمات", color = Navy, fontWeight = FontWeight.Bold)
                        Text("إشعارات فقط؛ لا تسجل صوت الهاتف", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = callAlertsEnabled, onCheckedChange = { onCallAlertsToggle() })
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("تسجيلاتي", color = Navy, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${recordings.size} ملف · ${formatStorage(recordings.sumOf { it.length() })}", color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("بحث بالاسم") }, shape = RoundedCornerShape(14.dp))
            Spacer(Modifier.height(8.dp))
            if (filtered.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (recordings.isEmpty()) "لا توجد تسجيلات بعد" else "لا توجد نتائج", color = Color.Gray)
                        Text(if (recordings.isEmpty()) "ابدأ تسجيلًا وستجد الملف هنا" else "جرّب اسمًا آخر", color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.absolutePath }) { file ->
                        RecordingCard(file, isPlaying = playing && playingFile == file, onPlay = onPlay, onPause = onPause, onRename = onRename, onDelete = onDelete, onShare = onShare)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("ملاحظة: هذا التطبيق يسجل الميكروفون فقط. لا يلتقط صوت الطرف الآخر من المكالمات الهاتفية.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (error != null) AlertDialog(onDismissRequest = onDismissError, title = { Text("تنبيه") }, text = { Text(error) }, confirmButton = { TextButton(onClick = onDismissError) { Text("حسنًا") } })
}



private fun formatStorage(bytes: Long): String {
    return if (bytes < 1024L * 1024L) String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    else String.format(Locale.getDefault(), "%.1f MB", bytes / 1024.0 / 1024.0)
}

private fun readDuration(file: File): String {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        formatClock(millis / 1000L)
    } catch (_: Exception) {
        "--:--"
    } finally {
        try { retriever.release() } catch (_: Exception) { }
    }
}

private fun formatClock(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, secs)
    else String.format(Locale.getDefault(), "%02d:%02d", minutes, secs)
}


@Composable
private fun RecordingCard(file: File, isPlaying: Boolean, onPlay: (File) -> Unit, onPause: () -> Unit, onRename: (File, String) -> Unit, onDelete: (File) -> Unit, onShare: (File) -> Unit) {
    var showRename by remember { mutableStateOf(false) }
    var newName by remember(file.nameWithoutExtension) { mutableStateOf(file.nameWithoutExtension) }
    var confirmDelete by remember { mutableStateOf(false) }
    val date = SimpleDateFormat("yyyy/MM/dd · HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
    val size = String.format(Locale.getDefault(), "%.1f MB", file.length() / 1024f / 1024f)
    val duration = readDuration(file)
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFFE4F4F2), CircleShape), contentAlignment = Alignment.Center) { Text("♫", color = Teal) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.nameWithoutExtension, color = Navy, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("$date · $duration · $size", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { if (isPlaying) onPause() else onPlay(file) }) { Text(if (isPlaying) "إيقاف مؤقت" else "تشغيل", color = Teal) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showRename = true }) { Text("تسمية") }
                TextButton(onClick = { onShare(file) }) { Text("مشاركة") }
                TextButton(onClick = { confirmDelete = true }) { Text("حذف", color = Color(0xFFE15F5F)) }
            }
        }
    }
    if (showRename) AlertDialog(onDismissRequest = { showRename = false }, title = { Text("تغيير اسم التسجيل") },
        text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, label = { Text("الاسم") }) },
        confirmButton = { TextButton(onClick = { onRename(file, newName); showRename = false }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = { showRename = false }) { Text("إلغاء") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("حذف التسجيل؟") }, text = { Text("سيتم حذف الملف نهائيًا.") },
        confirmButton = { TextButton(onClick = { onDelete(file); confirmDelete = false }) { Text("حذف", color = Color(0xFFE15F5F)) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } })
}
