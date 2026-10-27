package com.victor.stream.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-app "bug capturer" (readable timeline).
 *
 * Everything the app logs through [Logger] is mirrored here into a rolling in-memory
 * buffer AND a file inside the app's private storage, so the record survives app
 * restarts and can be viewed / shared from Settings without needing adb or Logcat.
 *
 * It intentionally records a READABLE TIMELINE of meaningful events (screens opened,
 * which content type + IDs a play attempt used, each server attempt and its result,
 * player errors), NOT raw finger touches.
 *
 * This object does not change any existing behavior; it only observes.
 */
object DebugCapture {

    private const val FILE_NAME = "debug_capture.txt"
    private const val MAX_LINES_IN_MEMORY = 6000
    private const val MAX_FILE_BYTES = 1_500_000L // ~1.5 MB; trimmed on open

    /** Master on/off. When false, nothing new is recorded (existing log stays). */
    @Volatile
    var enabled: Boolean = true

    private val lock = Any()
    private val buffer = ArrayDeque<String>()
    @Volatile private var appContext: Context? = null
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val sessionStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Call once, early (e.g. in MainActivity.onCreate). Safe to call more than once. */
    fun attach(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            appContext = app
            try {
                val f = logFile()
                if (f != null && f.exists()) {
                    if (f.length() > MAX_FILE_BYTES) {
                        // Keep only the most recent lines so the file can't grow forever.
                        val tail = f.readLines().takeLast(MAX_LINES_IN_MEMORY)
                        f.writeText(tail.joinToString("\n") + "\n")
                        buffer.clear()
                        buffer.addAll(tail)
                    } else {
                        buffer.clear()
                        buffer.addAll(f.readLines().takeLast(MAX_LINES_IN_MEMORY))
                    }
                }
            } catch (_: Throwable) {
            }
        }
        // Mark the start of a fresh run so sessions are easy to tell apart.
        appendRaw("")
        appendRaw("──────────── APP OPENED @ ${sessionStamp.format(Date())} ────────────")
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, FILE_NAME) }

    /** Low-level: add one already-formatted line to memory + file. */
    private fun appendRaw(line: String) {
        if (!enabled) return
        synchronized(lock) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES_IN_MEMORY) buffer.removeFirst()
            try {
                logFile()?.appendText(line + "\n")
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Called by [Logger] for every d/i/w/e message. Must never call Logger back.
     * level is one of D / I / W / E.
     */
    fun record(level: String, tag: String, message: String) {
        if (!enabled) return
        val mark = when (level) {
            "E" -> "✗"
            "W" -> "⚠"
            "I" -> "•"
            else -> "·"
        }
        appendRaw("${stamp.format(Date())} $mark $tag: $message")
    }

    /** Explicit readable marker for a screen the user opened. */
    fun screen(name: String) = appendRaw("${stamp.format(Date())} ▶ SCREEN: $name")

    /** Explicit readable marker for a meaningful action (play, server switch, episode change). */
    fun action(what: String) = appendRaw("${stamp.format(Date())} ➜ ACTION: $what")

    /** Full captured text, oldest first. */
    fun snapshotText(): String = synchronized(lock) { buffer.joinToString("\n") }

    /** Captured lines, oldest first (for the viewer). */
    fun snapshotLines(): List<String> = synchronized(lock) { buffer.toList() }

    /** Wipe memory + file. */
    fun clear() {
        synchronized(lock) {
            buffer.clear()
            try {
                logFile()?.writeText("")
            } catch (_: Throwable) {
            }
        }
    }
}

// ============================================================================
//  UI — a Settings card + a full-screen viewer dialog (Share / Copy / Clear)
// ============================================================================

/** Drop this into SettingsScreen to expose the capturer. */
@Composable
fun DebugCaptureCard() {
    var captureOn by remember { mutableStateOf(DebugCapture.enabled) }
    var showViewer by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B1B)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Debug Capture", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Records a readable timeline of what the app does (screens, play attempts, each server, errors). Survives restarts.",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = captureOn,
                    onCheckedChange = {
                        captureOn = it
                        DebugCapture.enabled = it
                    }
                )
            }

            Button(
                onClick = { showViewer = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2962FF)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Text("View Captured Log", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showViewer) {
        DebugLogViewer(onClose = { showViewer = false })
    }
}

@Composable
private fun DebugLogViewer(onClose: () -> Unit) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    // refreshKey is read so that bumping it recomputes the snapshot.
    val lines = remember(refreshKey) { DebugCapture.snapshotLines() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0E0E0E))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Captured Log (${lines.size} lines)",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Close", color = Color.White, fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val text = DebugCapture.snapshotText()
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Stream app debug capture")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(send, "Share debug log"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2962FF)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Share", color = Color.White, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("debug log", DebugCapture.snapshotText()))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Copy", color = Color.White, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = { refreshKey++ },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Refresh", color = Color.White, fontSize = 13.sp)
                }
                OutlinedButton(
                    onClick = {
                        DebugCapture.clear()
                        refreshKey++
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).height(40.dp)
                ) {
                    Text("Clear", color = Color(0xFFFF6E6E), fontSize = 13.sp)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (lines.isEmpty()) {
                    Text(
                        "Nothing captured yet. Play something, then come back and tap Refresh.",
                        color = Color.Gray,
                        fontSize = 13.sp
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(lines) { line ->
                            Text(
                                text = line,
                                color = colorForLine(line),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Light color-coding so the timeline is scannable. */
private fun colorForLine(line: String): Color {
    val l = line.lowercase()
    return when {
        l.contains("captured") || l.contains("preparing exoplayer") ||
            l.contains("switching immediately") || l.contains("cache hit") -> Color(0xFF66BB6A) // success/green
        l.contains("timed out") || l.contains("error") || l.contains("exhausted") ||
            l.contains("failed") || line.contains("✗") -> Color(0xFFFF6E6E) // problem/red
        line.contains("PLAN ") -> Color(0xFF4DD0E1) // plan/cyan
        line.contains("▶ SCREEN") -> Color(0xFF90CAF9) // screen/blue
        line.contains("➜ ACTION") -> Color(0xFFFFCC80) // action/amber
        line.contains("APP OPENED") -> Color(0xFFCE93D8) // session divider/purple
        else -> Color(0xFFBDBDBD) // default/gray
    }
}
