package com.armsx.flashpoint.lab

import android.app.Activity
import android.content.Intent
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
        run(intent)
    }

    // Another step while the lab is open (Xvfb keeps running in the first one).
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        run(intent)
    }

    private fun run(intent: android.content.Intent) {
        val step = intent.getStringExtra("step") ?: return finish()
        // The real game screen on the test game (Lava Lamp, the support pack in drive_c).
        if (step == "game") {
            val r = File(dataDir, "r").path
            val base = Intent().putExtra(com.armsx.flashpoint.game.GameActivity.EXTRA_GAME_ID, "lab")
                .putExtra(com.armsx.flashpoint.game.GameActivity.EXTRA_TITLE, "Lab")
                .putExtra(com.armsx.flashpoint.game.GameActivity.EXTRA_ZIPS, arrayOf("$r/home/games/test.zip"))
            startActivity(com.armsx.flashpoint.win.WinActivity.intent(this, base, "FPSoftware\\Shockwave\\PJ101\\SPR.exe",
                intent.getStringExtra("url") ?: "http://www.clevermedia.com/arcade/lava.dcr", "$r/home/.wine/drive_c"))
            return finish()
        }
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
            "WINEDEBUG" to "err+all,warn+module,warn+x11drv,warn+seh",
            "BOX64_SHOWSEGV" to "1",
            "BOX64_SHOWBT" to "1",
            "DISPLAY" to ":1",
            // No Wine Mono or Gecko: their first-run download prompt blocks every later process.
            "WINEDLLOVERRIDES" to "mscoree,mshtml=",
            // This glibc's System V shared memory asks a helper socket named here; with nothing
            // listening it fails cleanly and Wine uses plain X images (unset, it crashes).
            "ANDROID_SYSVSHM_SERVER" to "$root/tmp/.sysvshm",
        )
        // The X server: Termux's Android-native Xvfb, relocated to <data>/x, started through the
        // system linker (allowed). It runs xkbcomp through x/bin/sh (a link to the system shell)
        // and x/bin/xkbcomp (a link to the copy in the APK).
        // MIT-SHM is off: Android has no System V shared memory, and Wine crashes in XShmCreateImage
        // when the server offers it.
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
            "xvfb" -> listOf("/system/bin/linker64", "$x/bin/Xvfb", ":1", "-screen", "0", "1024x768x24", "-fbdir", "$x/tmp", "-nolisten", "tcp", "-ac", "-extension", "MIT-SHM")
            "notepad" -> listOf(bx, wine, "notepad")
            // Flashpoint's Shockwave projector on a test game, through FlashARM's game server on
            // the port the projector expects (its port.txt). Expects the support pack's PJ101 in
            // drive_c/FPSoftware/Shockwave and the game's data pack at home/games/test.zip.
            "spr" -> listOf(bx, wine, "C:\\FPSoftware\\Shockwave\\PJ101\\SPR.exe", intent.getStringExtra("url") ?: "http://www.clevermedia.com/arcade/lava.dcr")
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
                // Wine steps need the X server: start it first unless it is already up.
                // Track the server this process started: a socket file left by a killed one says nothing.
                if (step == "spr") {
                    val port = com.armsx.flashpoint.core.FpCore.startServer("", "", false, emptyList(), 22500)
                    com.armsx.flashpoint.core.FpCore.mount("$root/home/games/test.zip")
                    Log.i(TAG, "game server on $port")
                }
                if (step in setOf("boot", "notepad", "cmd", "spr") && xServer?.isAlive != true) {
                    File("$x/tmp/.X1-lock").delete(); File("$x/tmp/.X11-unix/X1").delete()
                    val xp = ProcessBuilder(listOf("/system/bin/linker64", "$x/bin/Xvfb", ":1", "-screen", "0", "1024x768x24", "-fbdir", "$x/tmp", "-nolisten", "tcp", "-ac", "-extension", "MIT-SHM"))
                        .redirectErrorStream(true)
                    xp.environment().putAll(xenv)
                    val proc = xp.start(); xServer = proc
                    Thread { proc.inputStream.bufferedReader().forEachLine { Log.i(TAG, "Xvfb: $it") } }.start()
                    repeat(50) { if (!File("$x/tmp/.X11-unix/X1").exists()) Thread.sleep(200) }
                    Log.i(TAG, "X server up: ${File("$x/tmp/.X11-unix/X1").exists()}")
                }
                val pb = ProcessBuilder(argv).redirectErrorStream(true)
                if (step == "spr") pb.directory(File("$root/home/.wine/drive_c/FPSoftware/Shockwave/PJ101"))
                pb.environment().putAll(if (step == "xvfb") xenv else env)
                val p = pb.start()
                p.inputStream.bufferedReader().forEachLine { Log.i(TAG, it) }
                Log.i(TAG, "exit ${p.waitFor()}")
            }.onFailure { Log.e(TAG, "failed", it) }
        }.start()
        // Stay on screen: in the background Android's app freezer would freeze Wine's processes.
        setContentView(android.widget.TextView(this).apply { text = "FlashARM lab: $step"; setPadding(40, 80, 40, 40) })
    }

    companion object {
        const val TAG = "FlashARMLab"
        @Volatile var xServer: Process? = null
    }
}
