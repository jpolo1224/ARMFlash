package com.armsx.flashpoint.lab

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Debug only: fixed probes for running Box64 and Wine inside FlashARM, where Android's real
 * execution rules apply. Logs under "FlashARMLab". Takes a step name, never a command:
 *
 *   adb shell am start -n com.armsx.flasharm/com.armsx.flashpoint.lab.LabActivity --es step box64
 *
 * The base system is expected in <data>/r (glibc, Box64 at usr/local/bin/box64, Wine at opt/wine).
 */
class LabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val step = intent.getStringExtra("step") ?: return finish()
        val lib = applicationInfo.nativeLibraryDir
        val root = File(dataDir, "r").path
        val loader = "$lib/libldlinux.so"
        val env = mapOf(
            "HOME" to "$root/home",
            "TMPDIR" to "$root/tmp",
            "LD_LIBRARY_PATH" to "$root/usr/lib",
            "BOX64_LD_LIBRARY_PATH" to "$root/usr/lib/x86_64-linux-gnu",
            "GCONV_PATH" to "$root/usr/lib/gconv",
            "BOX64_LOG" to "1",
            "WINEPREFIX" to "$root/home/.wine",
            "WINEDEBUG" to "err+all,warn+module",
            "DISPLAY" to ":1",
        )
        // The X server: Termux's Android-native Xvfb, relocated to <data>/x, started through the
        // system linker (allowed). It runs xkbcomp through x/bin/sh (a link to the system shell)
        // and x/bin/xkbcomp (a link to the copy in the APK).
        val x = File(dataDir, "x").path
        val xenv = mapOf("LD_LIBRARY_PATH" to "$x/lib", "TMPDIR" to "$x/tmp", "HOME" to "$x/home")
        // Box64 ships in the APK as libbox64.so with its interpreter set to <data>/r/ld, a link to
        // the glibc loader (also in the APK), so it can be started, and can re-start itself for
        // every Wine process, without ever running a file the app wrote.
        val bx = "$lib/libbox64.so"
        val wine = "$root/opt/wine/bin/wine"
        val argv = when (step) {
            "loader" -> listOf(loader, "--version")
            "box64" -> listOf(bx, "--version")
            "wine" -> listOf(bx, wine, "--version")
            "boot" -> listOf(bx, wine, "wineboot", "-i")
            "cmd" -> listOf(bx, wine, "cmd", "/c", "ver")
            "xvfb" -> listOf("/system/bin/linker64", "$x/bin/Xvfb", ":1", "-screen", "0", "1024x768x24", "-fbdir", "$x/tmp", "-nolisten", "tcp", "-ac")
            "notepad" -> listOf(bx, wine, "notepad")
            else -> return finish()
        }
        runCatching {
            fun link(target: String, at: String) {
                val f = File(at); f.parentFile?.mkdirs()
                if (f.exists() || java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete()
                android.system.Os.symlink(target, f.path)
            }
            link("/system/bin/sh", "$x/bin/sh")
            link("$lib/libxkbcomp.so", "$x/bin/xkbcomp")
            File("$x/tmp/.X11-unix").mkdirs(); File("$x/home").mkdirs()
            // Wine's X11 library looks for the socket under its own tmp.
            link("$x/tmp/.X11-unix", "$root/tmp/.X11-unix")
        }.onFailure { Log.e(TAG, "x links", it) }
        runCatching {
            val link = File(root, "ld")
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            android.system.Os.symlink(loader, link.path)
        }.onFailure { Log.e(TAG, "loader link", it) }
        Thread {
            Log.i(TAG, "step $step: ${argv.joinToString(" ")}")
            runCatching {
                File("$root/tmp").mkdirs(); File("$root/home").mkdirs()
                val pb = ProcessBuilder(argv).redirectErrorStream(true)
                pb.environment().putAll(if (step == "xvfb") xenv else env)
                val p = pb.start()
                p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
                Log.i(TAG, "exit ${p.waitFor()}")
            }.onFailure { Log.e(TAG, "failed", it) }
        }.start()
        finish()
    }

    companion object { const val TAG = "FlashARMLab" }
}
