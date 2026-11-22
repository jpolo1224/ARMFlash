package com.armsx.flashpoint.win

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Runs Windows programs (Flashpoint's Shockwave projector, Unity player and the rest) inside the
 * app: Box64 translating x86-64 Wine (WoW64 for 32-bit programs), drawing to a virtual X screen.
 *
 * Layout in the app's data folder:
 *   r/  the Linux base: glibc, Wine (opt/wine) and the Wine prefix (home/.wine)
 *   x/  the X server (an Android build of Xvfb) and its libraries
 * Everything Android must start comes from the APK's native library folder (libbox64.so,
 * libldlinux.so, libxkbcomp.so): Android does not let an app run files it wrote itself.
 */
class WinRuntime(private val ctx: Context) {
    private val lib = ctx.applicationInfo.nativeLibraryDir
    val root = File(ctx.dataDir, "r")
    val xdir = File(ctx.dataDir, "x")
    val prefix = File(root, "home/.wine")
    val screenFile = File(xdir, "tmp/Xvfb_screen0")
    val socket = File(xdir, "tmp/.X11-unix/X$DISPLAY_NO")
    private var xServer: Process? = null
    private var wine: Process? = null

    /** The base system and X server are in place. */
    val installed: Boolean
        get() = File(root, "opt/wine/bin/wine").isFile && File(xdir, "bin/Xvfb").isFile && File(lib, "libbox64.so").isFile

    private val env: Map<String, String>
        get() = mapOf(
            "HOME" to "${root.path}/home",
            "TMPDIR" to "${root.path}/tmp",
            "LD_LIBRARY_PATH" to "${root.path}/usr/lib",
            "BOX64_LD_LIBRARY_PATH" to "${root.path}/usr/lib/x86_64-linux-gnu",
            "GCONV_PATH" to "${root.path}/usr/lib/gconv",
            "BOX64_LOG" to "0",
            "BOX64_NOBANNER" to "1",
            "WINEPREFIX" to prefix.path,
            "WINEDEBUG" to "-all,err+all",
            "DISPLAY" to ":$DISPLAY_NO",
            // No Wine Mono or Gecko: their first-run download prompt blocks every later process.
            "WINEDLLOVERRIDES" to "mscoree,mshtml=",
            // This glibc's System V shared memory asks a helper socket named here; with nothing
            // listening, shmget fails cleanly and Wine uses plain X images (unset, it crashes).
            "ANDROID_SYSVSHM_SERVER" to "${root.path}/tmp/.sysvshm",
        )

    private fun link(target: String, at: File) {
        at.parentFile?.mkdirs()
        if (at.exists() || java.nio.file.Files.isSymbolicLink(at.toPath())) at.delete()
        Os.symlink(target, at.path)
    }

    /** Links the base system needs (re-made every start: the APK's folder changes on update). */
    private fun prepareLinks() {
        // Box64's interpreter is set to <data>/r/ld, the glibc loader shipped in the APK.
        link("$lib/libldlinux.so", File(root, "ld"))
        link("/system/bin/sh", File(xdir, "bin/sh"))
        link("$lib/libxkbcomp.so", File(xdir, "bin/xkbcomp"))
        File(xdir, "tmp/.X11-unix").mkdirs(); File(xdir, "home").mkdirs()
        File(root, "tmp").mkdirs(); File(root, "home").mkdirs()
        link("${xdir.path}/tmp/.X11-unix", File(root, "tmp/.X11-unix"))
    }

    /** Start the X server at [w]x[h] and wait for its socket. */
    fun startX(w: Int, h: Int): Boolean {
        prepareLinks()
        File(xdir, "tmp/.X$DISPLAY_NO-lock").delete(); socket.delete()
        val pb = ProcessBuilder(
            "/system/bin/linker64", "${xdir.path}/bin/Xvfb", ":$DISPLAY_NO", "-screen", "0", "${w}x${h}x24",
            "-fbdir", "${xdir.path}/tmp", "-nolisten", "tcp", "-ac", "-extension", "MIT-SHM",
        ).redirectErrorStream(true)
        pb.environment().putAll(mapOf("LD_LIBRARY_PATH" to "${xdir.path}/lib", "TMPDIR" to "${xdir.path}/tmp", "HOME" to "${xdir.path}/home"))
        val p = pb.start().also { xServer = it }
        drain(p, "Xvfb")
        repeat(100) { if (!socket.exists()) Thread.sleep(100) }
        return socket.exists()
    }

    /** Make the prefix on first use (several minutes on a phone; only once). */
    fun ensurePrefix(log: (String) -> Unit): Boolean {
        if (File(prefix, "system.reg").isFile && File(prefix, "drive_c/windows/syswow64").isDirectory) return true
        log("Setting up Windows support (first time only)…")
        val code = run(listOf("wineboot", "-i"), null).waitFor()
        return code == 0 && File(prefix, "system.reg").isFile
    }

    /** Map Windows drive F: to [dir] (the Flashpoint folder), as desktop Flashpoint's paths expect. */
    fun mapDrive(letter: Char, dir: File) {
        link(dir.path, File(prefix, "dosdevices/${letter.lowercaseChar()}:"))
    }

    /** Run `wine <args>` with [cwd] as the working folder. */
    fun run(args: List<String>, cwd: File?): Process {
        prepareLinks()
        val pb = ProcessBuilder(listOf("$lib/libbox64.so", "${root.path}/opt/wine/bin/wine") + args).redirectErrorStream(true)
        if (cwd != null) pb.directory(cwd)
        pb.environment().putAll(env)
        return pb.start().also { if (wine == null) wine = it; drain(it, "wine") }
    }

    private fun drain(p: Process, tag: String) = Thread {
        runCatching { p.inputStream.bufferedReader().forEachLine { Log.i(TAG, "$tag: $it") } }
    }.apply { isDaemon = true }.start()

    /** Every process this app started under Wine or X (they outlive their parents). */
    private fun children(): List<Int> {
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        return File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }.filter { pid ->
            pid != me && runCatching {
                val status = File("/proc/$pid/status").readText()
                val u = Regex("""Uid:\s+(\d+)""").find(status)?.groupValues?.get(1)?.toInt()
                val cmd = File("/proc/$pid/cmdline").readText()
                u == uid && !cmd.startsWith(ctx.packageName)
            }.getOrDefault(false)
        }
    }

    /** Pause or resume the game (everything but the X server, which only draws). */
    fun setPaused(paused: Boolean) {
        val x = runCatching { xServer?.let { p -> p.javaClass.getDeclaredField("pid").apply { isAccessible = true }.getInt(p) } }.getOrNull()
        children().filter { it != x }.forEach { android.os.Process.sendSignal(it, if (paused) SIGSTOP else SIGCONT) }
    }

    /** Stop the game, Wine's services and the X server. */
    fun stopAll() {
        children().forEach { android.os.Process.sendSignal(it, SIGCONT); android.os.Process.killProcess(it) }
        wine?.destroy(); xServer?.destroy()
    }

    companion object {
        const val TAG = "FlashARMWin"
        const val DISPLAY_NO = 1
        private const val SIGSTOP = 19
        private const val SIGCONT = 18

        /** Split a desktop launch command into arguments: spaces separate, double quotes group. */
        fun splitArgs(cmd: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var quoted = false
            var any = false
            for (c in cmd) {
                when {
                    c == '"' -> { quoted = !quoted; any = true }
                    c.isWhitespace() && !quoted -> { if (any || cur.isNotEmpty()) out += cur.toString(); cur.clear(); any = false }
                    else -> cur.append(c)
                }
            }
            if (any || cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}
