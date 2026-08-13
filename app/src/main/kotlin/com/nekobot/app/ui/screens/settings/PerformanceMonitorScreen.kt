package com.nekobot.app.ui.screens.settings

import android.app.ActivityManager
import android.content.Context
import android.net.TrafficStats
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.view.Choreographer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nekobot.app.R
import com.nekobot.app.ui.components.GlassCard
import kotlinx.coroutines.delay
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ceil

private data class FrameMetrics(
    val fps: Double = 0.0,
    val averageMs: Double = 0.0,
    val p95Ms: Double = 0.0,
    val jankPercent: Double = 0.0,
    val frameCount: Int = 0
)

private data class PerformanceSnapshot(
    val frames: FrameMetrics = FrameMetrics(),
    val javaUsedBytes: Long = 0,
    val javaMaxBytes: Long = 0,
    val nativeBytes: Long = 0,
    val pssBytes: Long = 0,
    val cpuPercent: Double = 0.0,
    val threads: Int = 0,
    val cores: Int = 0,
    val uptimeMs: Long = 0,
    val rxTotal: Long = 0,
    val txTotal: Long = 0,
    val rxRate: Long = 0,
    val txRate: Long = 0
)

private class FrameStatsCollector : Choreographer.FrameCallback {
    private val samples = ArrayDeque<Double>()
    private var lastFrameNanos = 0L
    private var running = false

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (lastFrameNanos > 0L) {
            val elapsedMs = (frameTimeNanos - lastFrameNanos) / 1_000_000.0
            if (elapsedMs in 0.1..1_000.0) {
                samples.addLast(elapsedMs)
                while (samples.size > MAX_FRAME_SAMPLES) samples.removeFirst()
            }
        }
        lastFrameNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun start() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        running = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun reset() {
        samples.clear()
        lastFrameNanos = 0L
    }

    fun snapshot(): FrameMetrics {
        val values = samples.toList()
        if (values.isEmpty()) return FrameMetrics()
        val average = values.average()
        val sorted = values.sorted()
        val p95Index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return FrameMetrics(
            fps = if (average > 0.0) (1_000.0 / average).coerceAtMost(240.0) else 0.0,
            averageMs = average,
            p95Ms = sorted[p95Index],
            jankPercent = values.count { it > JANK_FRAME_MS } * 100.0 / values.size,
            frameCount = values.size
        )
    }

    private companion object {
        const val MAX_FRAME_SAMPLES = 240
        const val JANK_FRAME_MS = 24.0
    }
}

private data class SampleCounters(
    val wallMs: Long,
    val cpuMs: Long,
    val networkMs: Long,
    val rxBytes: Long,
    val txBytes: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceMonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val frameCollector = remember { FrameStatsCollector() }
    var liveSampling by remember { mutableStateOf(true) }
    var snapshot by remember { mutableStateOf(PerformanceSnapshot()) }

    DisposableEffect(frameCollector, liveSampling) {
        if (liveSampling) frameCollector.start() else frameCollector.stop()
        onDispose { frameCollector.stop() }
    }

    LaunchedEffect(liveSampling) {
        if (!liveSampling) return@LaunchedEffect
        var previous: SampleCounters? = null
        while (true) {
            val result = readPerformanceSnapshot(context, frameCollector.snapshot(), previous)
            snapshot = result.first
            previous = result.second
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.performance_monitor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { liveSampling = !liveSampling }) {
                        Icon(
                            if (liveSampling) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (liveSampling) R.string.performance_monitor_pause
                                else R.string.performance_monitor_resume
                            )
                        )
                    }
                    IconButton(onClick = {
                        frameCollector.reset()
                        snapshot = snapshot.copy(frames = FrameMetrics())
                    }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.performance_monitor_reset)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LiveSamplingCard(liveSampling)
            }
            item {
                PerformanceSection(
                    title = stringResource(R.string.performance_monitor_rendering),
                    icon = Icons.Filled.Speed,
                    status = renderingStatus(snapshot.frames),
                    rows = listOf(
                        stringResource(R.string.performance_monitor_fps) to
                            if (snapshot.frames.frameCount == 0) "—" else formatDecimal(snapshot.frames.fps),
                        stringResource(R.string.performance_monitor_frame_average) to
                            formatMilliseconds(snapshot.frames.averageMs, snapshot.frames.frameCount),
                        stringResource(R.string.performance_monitor_frame_p95) to
                            formatMilliseconds(snapshot.frames.p95Ms, snapshot.frames.frameCount),
                        stringResource(R.string.performance_monitor_jank) to
                            if (snapshot.frames.frameCount == 0) "—" else "${formatDecimal(snapshot.frames.jankPercent)}%",
                        stringResource(R.string.performance_monitor_frames_sampled) to snapshot.frames.frameCount.toString()
                    )
                )
            }
            item {
                val heapPressure = if (snapshot.javaMaxBytes > 0L) {
                    snapshot.javaUsedBytes.toDouble() / snapshot.javaMaxBytes
                } else 0.0
                PerformanceSection(
                    title = stringResource(R.string.performance_monitor_memory),
                    icon = Icons.Filled.Memory,
                    status = when {
                        heapPressure >= 0.85 -> PerformanceStatus.POOR
                        heapPressure >= 0.65 -> PerformanceStatus.ATTENTION
                        else -> PerformanceStatus.GOOD
                    },
                    rows = listOf(
                        stringResource(R.string.performance_monitor_java_heap) to
                            "${formatBytes(snapshot.javaUsedBytes)} / ${formatBytes(snapshot.javaMaxBytes)}",
                        stringResource(R.string.performance_monitor_heap_pressure) to
                            "${formatDecimal(heapPressure * 100)}%",
                        stringResource(R.string.performance_monitor_native_heap) to formatBytes(snapshot.nativeBytes),
                        stringResource(R.string.performance_monitor_pss) to formatBytes(snapshot.pssBytes)
                    )
                )
            }
            item {
                PerformanceSection(
                    title = stringResource(R.string.performance_monitor_process),
                    icon = Icons.Filled.Tune,
                    status = when {
                        snapshot.cpuPercent >= 150.0 -> PerformanceStatus.POOR
                        snapshot.cpuPercent >= 80.0 -> PerformanceStatus.ATTENTION
                        else -> PerformanceStatus.GOOD
                    },
                    rows = listOf(
                        stringResource(R.string.performance_monitor_cpu) to "${formatDecimal(snapshot.cpuPercent)}%",
                        stringResource(R.string.performance_monitor_threads) to snapshot.threads.toString(),
                        stringResource(R.string.performance_monitor_cores) to snapshot.cores.toString(),
                        stringResource(R.string.performance_monitor_uptime) to formatDuration(snapshot.uptimeMs)
                    )
                )
            }
            item {
                PerformanceSection(
                    title = stringResource(R.string.performance_monitor_network),
                    icon = Icons.Filled.NetworkCheck,
                    status = PerformanceStatus.GOOD,
                    rows = listOf(
                        stringResource(R.string.performance_monitor_download_rate) to "${formatBytes(snapshot.rxRate)}/s",
                        stringResource(R.string.performance_monitor_upload_rate) to "${formatBytes(snapshot.txRate)}/s",
                        stringResource(R.string.performance_monitor_download_total) to formatBytes(snapshot.rxTotal),
                        stringResource(R.string.performance_monitor_upload_total) to formatBytes(snapshot.txTotal)
                    )
                )
            }
            item {
                Text(
                    text = stringResource(R.string.performance_monitor_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LiveSamplingCard(liveSampling: Boolean) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Speed,
                contentDescription = null,
                tint = if (liveSampling) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.performance_monitor_live),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.performance_monitor_live_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private enum class PerformanceStatus { GOOD, ATTENTION, POOR, WAITING }

@Composable
private fun PerformanceSection(
    title: String,
    icon: ImageVector,
    status: PerformanceStatus,
    rows: List<Pair<String, String>>
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            PerformanceStatusBadge(status)
        }
        Spacer(Modifier.size(10.dp))
        rows.forEachIndexed { index, row ->
            MetricRow(row.first, row.second)
            if (index != rows.lastIndex) Spacer(Modifier.size(7.dp))
        }
    }
}

@Composable
private fun PerformanceStatusBadge(status: PerformanceStatus) {
    val color = when (status) {
        PerformanceStatus.GOOD -> MaterialTheme.colorScheme.primary
        PerformanceStatus.ATTENTION -> MaterialTheme.colorScheme.tertiary
        PerformanceStatus.POOR -> MaterialTheme.colorScheme.error
        PerformanceStatus.WAITING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = stringResource(
        when (status) {
            PerformanceStatus.GOOD -> R.string.performance_monitor_status_good
            PerformanceStatus.ATTENTION -> R.string.performance_monitor_status_attention
            PerformanceStatus.POOR -> R.string.performance_monitor_status_poor
            PerformanceStatus.WAITING -> R.string.performance_monitor_no_frames
        }
    )
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun renderingStatus(frames: FrameMetrics): PerformanceStatus = when {
    frames.frameCount == 0 -> PerformanceStatus.WAITING
    frames.fps < 40.0 || frames.jankPercent >= 20.0 -> PerformanceStatus.POOR
    frames.fps < 55.0 || frames.jankPercent >= 8.0 -> PerformanceStatus.ATTENTION
    else -> PerformanceStatus.GOOD
}

private fun readPerformanceSnapshot(
    context: Context,
    frames: FrameMetrics,
    previous: SampleCounters?
): Pair<PerformanceSnapshot, SampleCounters> {
    val runtime = Runtime.getRuntime()
    val javaUsed = runtime.totalMemory() - runtime.freeMemory()
    val nowWall = SystemClock.elapsedRealtime()
    val nowCpu = Process.getElapsedCpuTime()
    val uid = Process.myUid()
    val rawRx = TrafficStats.getUidRxBytes(uid)
    val rawTx = TrafficStats.getUidTxBytes(uid)
    val rx = rawRx.takeUnless { it == TrafficStats.UNSUPPORTED.toLong() } ?: 0L
    val tx = rawTx.takeUnless { it == TrafficStats.UNSUPPORTED.toLong() } ?: 0L
    val elapsed = previous?.let { (nowWall - it.wallMs).coerceAtLeast(1L) }
    val networkElapsed = previous?.let { (nowWall - it.networkMs).coerceAtLeast(1L) }
    val cpuPercent = if (previous != null && elapsed != null) {
        ((nowCpu - previous.cpuMs).coerceAtLeast(0L) * 100.0 / elapsed)
            .coerceAtMost(Runtime.getRuntime().availableProcessors() * 100.0)
    } else 0.0
    val rxRate = if (previous != null && networkElapsed != null) {
        ((rx - previous.rxBytes).coerceAtLeast(0L) * 1_000L / networkElapsed)
    } else 0L
    val txRate = if (previous != null && networkElapsed != null) {
        ((tx - previous.txBytes).coerceAtLeast(0L) * 1_000L / networkElapsed)
    } else 0L
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val pssBytes = activityManager.getProcessMemoryInfo(intArrayOf(Process.myPid()))
        .firstOrNull()?.totalPss?.toLong()?.times(1_024L) ?: 0L
    val counters = SampleCounters(nowWall, nowCpu, nowWall, rx, tx)
    return PerformanceSnapshot(
        frames = frames,
        javaUsedBytes = javaUsed,
        javaMaxBytes = runtime.maxMemory(),
        nativeBytes = Debug.getNativeHeapAllocatedSize(),
        pssBytes = pssBytes,
        cpuPercent = cpuPercent,
        threads = Thread.activeCount(),
        cores = runtime.availableProcessors(),
        uptimeMs = nowWall - Process.getStartElapsedRealtime(),
        rxTotal = rx,
        txTotal = tx,
        rxRate = rxRate,
        txRate = txRate
    ) to counters
}

private fun formatDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

private fun formatMilliseconds(value: Double, samples: Int): String =
    if (samples == 0) "—" else "${formatDecimal(value)} ms"

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1_024.0 && index < units.lastIndex) {
        value /= 1_024.0
        index++
    }
    return String.format(Locale.getDefault(), if (index == 0) "%.0f %s" else "%.1f %s", value, units[index])
}

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, remainingSeconds)
    }
}
