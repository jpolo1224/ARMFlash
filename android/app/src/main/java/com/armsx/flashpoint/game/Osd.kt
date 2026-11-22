package com.armsx.flashpoint.game

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The performance overlay's settings: shared by every game, kept with the app's settings. */
object OsdPrefs {
    enum class Stat(val label: String) { FPS("FPS"), FRAME("Frame time"), CPU("CPU"), GPU("GPU"), RAM("Memory"), MAPS("Mappings"), BATTERY("Battery"), CLOCK("Clock") }

    val Colors = listOf(
        "Green" to Color(0xFF7FE04E), "White" to Color.White, "Yellow" to Color(0xFFFFE04A),
        "Cyan" to Color(0xFF52E0F0), "Orange" to Color(0xFFF0A030), "Pink" to Color(0xFFFF7AC8),
    )

    /** ARMSX2's overlay modes: Minimal is the frame rate alone, Full every stat, Custom the picks below. */
    enum class Mode(val label: String) { OFF("Off"), MINIMAL("Minimal"), FULL("Full"), CUSTOM("Custom") }
    enum class Corner(val label: String) { TOP_LEFT("Top left"), TOP_RIGHT("Top right"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right") }

    val mode = mutableStateOf(Mode.OFF)
    val corner = mutableStateOf(Corner.TOP_LEFT)
    val color = mutableStateOf(0)
    /** The Custom mode's stats. */
    val stats = mutableStateOf(setOf(Stat.FPS))

    /** What is on screen now, by mode. */
    val shown: Set<Stat> get() = when (mode.value) {
        Mode.OFF -> emptySet()
        Mode.MINIMAL -> setOf(Stat.FPS)
        Mode.FULL -> Stat.entries.toSet()
        Mode.CUSTOM -> stats.value
    }
    private var prefs: android.content.SharedPreferences? = null

    fun load(ctx: Context) {
        if (prefs != null) return
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        mode.value = runCatching { Mode.valueOf(p.getString("osd.mode", null)!!) }.getOrElse { if (p.getBoolean("osd", false)) Mode.CUSTOM else Mode.OFF }
        corner.value = runCatching { Corner.valueOf(p.getString("osd.corner", null)!!) }.getOrDefault(Corner.TOP_LEFT)
        color.value = p.getInt("osd.color", 0).coerceIn(0, Colors.lastIndex)
        stats.value = p.getStringSet("osd.stats", null)?.mapNotNull { n -> Stat.entries.firstOrNull { it.name == n } }?.toSet() ?: setOf(Stat.FPS)
    }

    fun cycleMode() { mode.value = Mode.entries[(mode.value.ordinal + 1) % Mode.entries.size]; prefs?.edit()?.putString("osd.mode", mode.value.name)?.apply() }
    fun cycleCorner() { corner.value = Corner.entries[(corner.value.ordinal + 1) % Corner.entries.size]; prefs?.edit()?.putString("osd.corner", corner.value.name)?.apply() }
    fun cycleColor() = setColor((color.value + 1) % Colors.size)
    fun setColor(i: Int) { color.value = i; prefs?.edit()?.putInt("osd.color", i)?.apply() }
    fun toggle(s: Stat) {
        stats.value = if (s in stats.value) stats.value - s else stats.value + s
        prefs?.edit()?.putStringSet("osd.stats", stats.value.map { it.name }.toSet())?.apply()
    }
}

/** One reading of everything the overlay can show. */
private data class Reading(
    val fps: Double? = null,
    val cpu: Double? = null,
    val ramMb: Long? = null,
    val gpu: Int? = null,
    val gpuMhz: Int? = null,
    val maps: Int? = null,
    val battery: Int? = null,
    val tempC: Double? = null,
    val charging: Boolean = false,
)

/**
 * The performance overlay, top left: the game's own frame rate (frames the player actually
 * drew, not the screen's refresh), frame time, this app's CPU use across all cores, its memory,
 * battery and temperature, and the time. Sampled twice a second; costs nothing when hidden.
 *
 * [frames] returns the game's running frame count, or -1 when the player cannot say.
 */
@Composable
fun OsdOverlay(frames: () -> Long, modifier: Modifier = Modifier) {
    if (OsdPrefs.mode.value == OsdPrefs.Mode.OFF) return
    val ctx = LocalContext.current
    var r by remember { mutableStateOf(Reading()) }
    LaunchedEffect(Unit) {
        var lastFrames = -1L
        var lastT = SystemClock.elapsedRealtimeNanos()
        var lastCpu = withContext(Dispatchers.IO) { cpuTicks() }
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        while (true) {
            delay(500)
            val now = SystemClock.elapsedRealtimeNanos()
            val secs = (now - lastT) / 1e9
            lastT = now
            val f = frames()
            val fps = if (f >= 0 && lastFrames >= 0 && secs > 0) (f - lastFrames) / secs else null
            lastFrames = f
            val ticks = withContext(Dispatchers.IO) { cpuTicks() }
            val cpu = if (ticks >= 0 && lastCpu >= 0 && secs > 0) (ticks - lastCpu) / 100.0 / secs / cores * 100 else null
            lastCpu = ticks
            val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            r = Reading(
                fps = fps,
                cpu = cpu,
                ramMb = withContext(Dispatchers.IO) { rssMb() },
                gpu = if (OsdPrefs.Stat.GPU in OsdPrefs.shown) withContext(Dispatchers.IO) { Gpu.busy() } else null,
                gpuMhz = if (OsdPrefs.Stat.GPU in OsdPrefs.shown) withContext(Dispatchers.IO) { Gpu.mhz() } else null,
                maps = if (OsdPrefs.Stat.MAPS in OsdPrefs.shown) withContext(Dispatchers.IO) { MapCensus.count() } else null,
                battery = bat?.let { b -> val l = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100); if (l >= 0) l * 100 / s else null },
                tempC = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0),
                charging = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, 0) == BatteryManager.BATTERY_STATUS_CHARGING,
            )
        }
    }
    val stats = OsdPrefs.shown
    val lines = buildList {
        if (OsdPrefs.Stat.FPS in stats) add("FPS " + (r.fps?.let { "%.0f".format(it) } ?: "--"))
        if (OsdPrefs.Stat.FRAME in stats) add("Frame " + (r.fps?.takeIf { it > 0.5 }?.let { "%.1f ms".format(1000 / it) } ?: "--"))
        if (OsdPrefs.Stat.CPU in stats) add("CPU " + (r.cpu?.let { "%.0f%%".format(it) } ?: "--"))
        if (OsdPrefs.Stat.GPU in stats) add("GPU " + (r.gpu?.let { "$it%" } ?: "--") + (r.gpuMhz?.let { "  $it MHz" } ?: ""))
        if (OsdPrefs.Stat.RAM in stats) add("RAM " + (r.ramMb?.let { "$it MB" } ?: "--"))
        if (OsdPrefs.Stat.MAPS in stats) add("MAPS " + (r.maps?.let { "$it / 65530" } ?: "--"))
        if (OsdPrefs.Stat.BATTERY in stats) add(
            "BAT " + (r.battery?.let { "$it%" } ?: "--") + (r.tempC?.let { "  %.1f°C".format(it) } ?: "") + if (r.charging) "  +" else "",
        )
        if (OsdPrefs.Stat.CLOCK in stats) add(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date()))
    }
    if (lines.isEmpty()) return
    val color = OsdPrefs.Colors[OsdPrefs.color.value.coerceIn(0, OsdPrefs.Colors.lastIndex)].second
    Column(modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x8C000000)).padding(horizontal = 8.dp, vertical = 5.dp)) {
        lines.forEach { Text(it, color = color, fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
    }
}

/** This process's CPU time in clock ticks (user + system), from /proc/self/stat. */
private fun cpuTicks(): Long = runCatching {
    val s = java.io.File("/proc/self/stat").readText()
    val f = s.substring(s.lastIndexOf(')') + 2).split(' ')
    f[11].toLong() + f[12].toLong() // fields 14 and 15, counted after the command name
}.getOrDefault(-1)

/** Resident memory of this process in MB, from /proc/self/statm. */
private fun rssMb(): Long? = runCatching {
    java.io.File("/proc/self/statm").readText().trim().split(' ')[1].toLong() * 4096 / (1024 * 1024)
}.getOrNull()

/**
 * Memory mappings of this process. Android caps a process at 65,530; past that every new
 * allocation fails as "out of memory" with RAM still free (the GPU driver's included), which is
 * how native Flash crashed in Ninja Painter. Logged every 30 s while a game runs.
 */
object MapCensus {
    const val CAP = 65530

    fun count(): Int? = runCatching { java.io.File("/proc/self/maps").useLines { it.count() } }.getOrNull()

    /** "N of cap; most: name x, name y, ..." grouping anonymous and file mappings by name. */
    fun summary(): String = runCatching {
        val by = HashMap<String, Int>()
        var n = 0
        java.io.File("/proc/self/maps").forEachLine { line ->
            n++
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            val name = parts.getOrNull(5)?.let { p -> if (p.startsWith("/")) p.substringAfterLast('/') else p } ?: "anon"
            by[name] = (by[name] ?: 0) + 1
        }
        val top = by.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { "${it.key} ${it.value}" }
        "$n of $CAP; most: $top"
    }.getOrElse { "unreadable: ${it.message}" }
}

/**
 * GPU load and clock where the kernel lets apps read them: Adreno's kgsl files (the Odin's, world
 * readable), then Mali's. Null when the device keeps them private; the overlay shows "--".
 */
private object Gpu {
    private fun read(path: String): String? = runCatching { java.io.File(path).readText().trim() }.getOrNull()

    fun busy(): Int? {
        read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.removeSuffix("%")?.trim()?.toIntOrNull()?.let { return it }
        read("/sys/class/kgsl/kgsl-3d0/gpubusy")?.split(Regex("\\s+"))?.mapNotNull { it.toLongOrNull() }?.let { v ->
            if (v.size >= 2 && v[1] > 0) return (v[0] * 100 / v[1]).toInt()
        }
        read("/sys/class/misc/mali0/device/utilization")?.toIntOrNull()?.let { return it }
        return null
    }

    fun mhz(): Int? =
        (read("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq") ?: read("/sys/class/kgsl/kgsl-3d0/gpuclk")
            ?: read("/sys/class/misc/mali0/device/clock"))?.toLongOrNull()?.let { if (it > 100_000) (it / 1_000_000).toInt() else it.toInt() }
}
