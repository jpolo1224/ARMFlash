package com.armsx.flashpoint.game

import android.content.Context
import android.os.Build

/**
 * One line about the phone, for logs testers send: what a problem seen on one phone and not
 * another usually comes down to (the Android version and kernel, the memory, the chip and its
 * ARM cores, which decide the code Box64 makes). Nothing that identifies the person.
 */
object DeviceInfo {
    fun line(ctx: Context): String = buildString {
        append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != Build.UNKNOWN) {
            append(", ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL)
        }
        append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(')')
        append(", kernel ").append(System.getProperty("os.version").orEmpty())
        runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            append(", RAM %.1f GB".format(java.util.Locale.ROOT, mi.totalMem / 1073741824.0))
        }
        runCatching { append(", page ").append(android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE) / 1024).append(" KB") }
        cores()?.let { append(", cores ").append(it) }
        runCatching {
            val p = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            append(", ARMFlash ").append(p.versionName).append(" (").append(p.longVersionCode).append(')')
        }
    }

    /** The ARM cores, from /proc/cpuinfo: "4x Cortex-A55 + 4x Cortex-A75", or the raw part numbers. */
    private fun cores(): String? = runCatching {
        val parts = java.io.File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("CPU part") }
            .map { it.substringAfter(':').trim().lowercase() }
        if (parts.isEmpty()) return null
        parts.groupingBy { it }.eachCount().entries.joinToString(" + ") { (part, n) -> "${n}x ${CORE_NAMES[part] ?: part}" }
    }.getOrNull()

    /** ARM's own core part numbers (implementer 0x41). Anything else is shown as its number. */
    private val CORE_NAMES = mapOf(
        "0xd03" to "Cortex-A53", "0xd04" to "Cortex-A35", "0xd05" to "Cortex-A55", "0xd07" to "Cortex-A57",
        "0xd08" to "Cortex-A72", "0xd09" to "Cortex-A73", "0xd0a" to "Cortex-A75", "0xd0b" to "Cortex-A76",
        "0xd0d" to "Cortex-A77", "0xd41" to "Cortex-A78", "0xd44" to "Cortex-X1", "0xd46" to "Cortex-A510",
        "0xd47" to "Cortex-A710", "0xd48" to "Cortex-X2", "0xd4d" to "Cortex-A715", "0xd4e" to "Cortex-X3",
        "0xd80" to "Cortex-A520", "0xd81" to "Cortex-A720", "0xd82" to "Cortex-X4", "0xd85" to "Cortex-X925",
        "0xd87" to "Cortex-A725",
    )
}
