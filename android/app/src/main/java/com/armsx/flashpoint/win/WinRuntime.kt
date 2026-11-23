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
    /** The audio server's socket name (abstract namespace), one per game process. */
    val audioName = "armflash.audio.${android.os.Process.myPid()}"
    private var wine: Process? = null
    /** This game's settings, on top of the usual ones (see WinActivity.gameEnv). */
    var extra: Map<String, String> = emptyMap()

    /**
     * Install the runtime archive (tools/winrt/pack.py's winrt-N.tar.zst) from [src], checking
     * its SHA-256 when [sha256] is given. The Wine prefix (r/home) is kept.
     */
    /** Install the runtime or the 3D pack from [src] when its checksum is one of [accepted]. */
    fun install(src: java.io.InputStream, accepted: Set<String>?, progress: (String) -> Unit): Boolean {
        val tmp = File(ctx.cacheDir, "winrt.tar.zst")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        progress("Copying…")
        src.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); total += n
                    if (total % (8 shl 20) < n) progress("Copying… ${total shr 20} MB")
                }
            }
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (accepted != null && accepted.none { it.equals(got, ignoreCase = true) }) {
            tmp.delete(); progress("That is not ARMFlash's Windows support or 3D file, or it is damaged."); return false
        }
        progress("Unpacking…")
        val ok = runCatching { com.armsx.flashpoint.core.FpCore.untarZst(tmp.path, ctx.dataDir.path) }
            .onFailure { progress("Unpacking failed: ${it.message}") }.isSuccess
        tmp.delete()
        if (ok && got.equals(GRAPHICS_SHA256, ignoreCase = true)) { File(gl, ".armflash-3d").writeText(got); return true }
        if (ok) File(root, ".armflash-runtime").writeText(got)
        return ok && installed
    }

    /** Software 3D (tools/winrt/pack3d.py): Mesa's llvmpipe, for Wine's OpenGL and Direct3D. */
    private val gl = File(root, "usr/lib/3d")
    val has3d: Boolean get() = File(gl, ".armflash-3d").isFile

    /** The base system and X server are in place. */
    val installed: Boolean
        get() = File(root, "opt/wine/bin/wine").isFile && File(xdir, "bin/Xvfb").isFile && File(lib, "libbox64.so").isFile

    private val env: Map<String, String>
        get() = mapOf(
            "HOME" to "${root.path}/home",
            "TMPDIR" to "${root.path}/tmp",
            // The 3D pack's libraries after the base system's, so its own copies of shared ones win.
            "LD_LIBRARY_PATH" to "${root.path}/usr/lib" + if (has3d) ":${gl.path}" else "",
            "BOX64_LD_LIBRARY_PATH" to "${root.path}/usr/lib/x86_64-linux-gnu",
            "GCONV_PATH" to "${root.path}/usr/lib/gconv",
            "BOX64_LOG" to "0",
            "BOX64_NOBANNER" to "1",
            "WINEPREFIX" to prefix.path,
            // Debugging aid: r/tmp/.winedebug, when present, replaces the channels.
            "WINEDEBUG" to (runCatching { File(root, "tmp/.winedebug").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "-all,err+all"),
            "DISPLAY" to ":$DISPLAY_NO",
            // No Wine Mono, and no Gecko until ensureGecko put it in: their first-run download
            // prompt blocks every later process.
            "WINEDLLOVERRIDES" to if (hasGecko) "mscoree=" else "mscoree,mshtml=",
            // Every Java player (AppletViewer, Java in the browser, Web Start): a heap a 32-bit
            // process can reserve here (the default asked for 620 MB and failed), Java2D's
            // software pipeline (Wine has no Direct3D without OpenGL), and Windows' own random
            // numbers first (see SupportPacks.JAVA_SECURITY).
            "_JAVA_OPTIONS" to "-Xmx256m -Dsun.java2d.d3d=false -Dsun.java2d.noddraw=true " +
                "-Djava.security.properties=F:\\FPSoftware\\.armflash\\java.security",
            // This glibc's System V shared memory asks a helper socket named here; with nothing
            // listening, shmget fails cleanly and Wine uses plain X images (unset, it crashes).
            "ANDROID_SYSVSHM_SERVER" to "${root.path}/tmp/.sysvshm",
            // Sound: libasound loads ARMFlash's plugin (shipped in the APK) for the base
            // system's "android_aserver" device, which streams to [AudioServer].
            "ALSA_PLUGIN_DIR" to lib,
            "ARMFLASH_AUDIO" to audioName,
        ) + if (!has3d) emptyMap() else mapOf(
            // Mesa's software renderer for Wine's OpenGL (and Direct3D on top of it).
            "LIBGL_DRIVERS_PATH" to "${gl.path}/dri",
            "__EGL_VENDOR_LIBRARY_DIRS" to "${gl.path}/share/glvnd/egl_vendor.d",
            "__GLX_VENDOR_LIBRARY_NAME" to "mesa",
            "GALLIUM_DRIVER" to "llvmpipe",
            "LIBGL_ALWAYS_SOFTWARE" to "1",
            // Box64 opens the native libGL for Wine; by then the libraries Box64 already loaded
            // have used glibc's spare static TLS, which glvnd and Mesa need: reserve more.
            "GLIBC_TUNABLES" to "glibc.rtld.optional_static_tls=0x20000",
            "BOX64_LIBGL" to "${gl.path}/libGL.so.1",
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
        Log.i(TAG, "wineboot -i exited with $code")
        // Wine writes the registry files later than wineboot returns: when its server goes idle,
        // or on its periodic save (every 30 s) while Windows' own services keep it running.
        val done = { File(prefix, "system.reg").isFile && File(prefix, "user.reg").isFile }
        if (!done()) log("Finishing Windows setup…")
        val deadline = System.currentTimeMillis() + 75_000
        while (!done() && System.currentTimeMillis() < deadline) Thread.sleep(500)
        if (!done()) Log.w(TAG, "no registry after wineboot (exit $code)")
        return done()
    }

    /**
     * Point Windows' own proxy setting (WinINet, which Flash Player and most plugins use) at the
     * game server, as desktop Flashpoint does, so every file a game asks for comes from its data.
     */
    fun ensureProxy(port: Int) {
        val mark = File(prefix, ".armflash-proxy-$port")
        if (mark.isFile) return
        val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
        val ok = run(listOf("reg", "add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f"), null).waitFor() == 0 &&
            run(listOf("reg", "add", key, "/v", "ProxyServer", "/t", "REG_SZ", "/d", "127.0.0.1:$port", "/f"), null).waitFor() == 0
        if (ok) mark.writeText("")
    }

    /**
     * Let Wine place its windows itself. There is no window manager on the virtual screen, and
     * Wine hands "managed" windows' moves and resizes to one and waits: a Shockwave projector
     * resized its window to the stage while the X window stayed at its first size, so only a
     * quarter of the game showed.
     */
    fun ensureUnmanaged() {
        val mark = File(prefix, ".armflash-unmanaged-2")
        if (mark.isFile) return
        // Where it was seen: the Shockwave projector (every PJ version is SPR.exe) and
        // Flashpoint Navigator (FPNavigator.exe starts flashpointnavigator.exe). Wine then draws
        // their title bars itself, which the game screen crops off.
        val ok = UNMANAGED_EXES.all { exe ->
            val key = "HKCU\\Software\\Wine\\AppDefaults\\$exe\\X11 Driver"
            run(listOf("reg", "add", key, "/v", "Managed", "/t", "REG_SZ", "/d", "N", "/f"), null).waitFor() == 0
        }
        if (ok) mark.writeText("")
    }

    /**
     * A Verdana for Silverlight. Its default text font draws Latin letters with Verdana, Arial,
     * Arial Unicode MS, Lucida Sans Unicode or Lucida Grande, and with none of them installed (never
     * the case on Windows, always here) every Silverlight app stops with error 1001 before it
     * starts: the Xbox Avatar Editor stayed blank. Wine's own Tahoma (Verdana's narrower sibling,
     * by the same designer) goes into Windows' Fonts folder under the name Verdana, listed in the
     * registry, which is where Silverlight looks for font files. The value names are ARMFlash's
     * own, so Wine's start-up refresh of the fonts it found itself never removes them.
     */
    fun ensureVerdana() {
        val mark = File(prefix, ".armflash-verdana-1")
        if (mark.isFile) return
        val fonts = File(prefix, "drive_c/windows/Fonts").apply { mkdirs() }
        val key = "HKLM\\Software\\Microsoft\\Windows NT\\CurrentVersion\\Fonts"
        val ok = listOf("Regular" to "tahoma.ttf", "Bold" to "tahomabd.ttf").all { (style, from) ->
            val name = if (style == "Regular") "armflash-verdana.ttf" else "armflash-verdanab.ttf"
            runCatching {
                val src = File(root, "opt/wine/share/wine/fonts/$from").readBytes()
                File(fonts, name).writeBytes(FontRename.rename(src, "Verdana", style))
            }.onFailure { Log.w(TAG, "verdana: ${it.message}") }.isSuccess &&
                run(listOf("reg", "add", key, "/v", "ARMFlash Verdana${if (style == "Regular") "" else " Bold"} (TrueType)",
                    "/t", "REG_SZ", "/d", "C:\\windows\\Fonts\\$name", "/f"), null).waitFor() == 0
        }
        if (ok) mark.writeText("")
    }

    /**
     * MFC 4.2 (mfc42.dll), which Windows always had and Wine does not: Netscape 4 needs it, and
     * with it every plugin platform that runs in Netscape (Cult3D, Live Picture, MapGuide and the
     * like). From Microsoft's Visual C++ 6 runtime, as winetricks gets it: the installer and the
     * vcredist.exe inside it are both unpacked by Wine itself (/T:<folder> /C /Q), and only the
     * MFC libraries go into the 32-bit system folder. Done once; a failure only logs.
     */
    fun ensureMfc42(log: (String) -> Unit) {
        val sys = File(prefix, "drive_c/windows/syswow64")
        if (File(sys, "mfc42.dll").isFile || !sys.isDirectory) return
        runCatching {
            log("Getting Windows libraries (MFC)…")
            val tmp = File(prefix, "drive_c/windows/temp/armflash-vc6").apply { deleteRecursively(); mkdirs() }
            val setup = File(tmp, "vc6redist.exe")
            val conn = java.net.URL(VC6_URL).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "ARMFlash"); conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            val md = java.security.MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { ins -> setup.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); md.update(buf, 0, n) }
            } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            check(got == VC6_SHA256) { "checksum $got" }
            val win = "C:\\windows\\temp\\armflash-vc6"
            run(listOf("$win\\vc6redist.exe", "/T:$win\\a", "/C", "/Q"), null).waitFor()
            val inner = File(tmp, "a").listFiles().orEmpty().firstOrNull { it.name.equals("vcredist.exe", ignoreCase = true) }
                ?: error("no vcredist.exe")
            run(listOf("$win\\a\\${inner.name}", "/T:$win\\b", "/C", "/Q"), null).waitFor()
            val dlls = File(tmp, "b").listFiles().orEmpty().filter { it.name.lowercase().startsWith("mfc42") && it.name.lowercase().endsWith(".dll") }
            check(dlls.isNotEmpty()) { "no mfc42 in vcredist" }
            dlls.forEach { it.copyTo(File(sys, it.name.lowercase()), overwrite = true) }
            tmp.deleteRecursively()
            Log.i(TAG, "mfc42: installed ${dlls.map { it.name }}")
        }.onFailure { Log.w(TAG, "mfc42: ${it.message}") }
    }

    /** Wine Gecko is in the prefix: Internet Explorer's engine (mshtml) can run. */
    val hasGecko: Boolean get() = File(prefix, ".armflash-gecko").isFile

    /**
     * Internet Explorer's engine for games that run in its browser control (Secure Player's
     * WEB_BROWSER templates: ActiveX games, WildTangent, Hypercosm, Viscape and others): Wine
     * Gecko, the version this Wine uses, installed once with Wine's msiexec. Until it is there,
     * mshtml stays off (WINEDLLOVERRIDES), or Wine would stop every program to offer it. Also
     * lets pages run their ActiveX controls without asking, as Secure Player's browser does.
     */
    fun ensureGecko(log: (String) -> Unit): Boolean {
        if (hasGecko) return true
        return runCatching {
            log("Getting Internet Explorer support (once, 55 MB)…")
            val tmp = File(prefix, "drive_c/windows/temp/armflash-gecko").apply { deleteRecursively(); mkdirs() }
            val msi = File(tmp, "wine-gecko-x86.msi")
            val conn = java.net.URL(GECKO_URL).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "ARMFlash"); conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val total = conn.contentLengthLong
            conn.inputStream.use { ins -> msi.outputStream().use { out ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); done += n
                    if (total > 0 && done % (4 shl 20) < n) log("Getting Internet Explorer support… ${done * 100 / total}%")
                }
            } }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            check(got == GECKO_SHA256) { "checksum $got" }
            log("Installing Internet Explorer support…")
            val code = run(listOf("msiexec", "/i", "C:\\windows\\temp\\armflash-gecko\\wine-gecko-x86.msi", "/q"), null).waitFor()
            tmp.deleteRecursively()
            check(code == 0) { "msiexec exited with $code" }
            // Every zone (local, intranet, trusted, internet, restricted): ActiveX controls and
            // plugins run and scripts may use them, with no prompt.
            for (zone in 0..4) {
                val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Zones\\$zone"
                for (action in listOf("1001", "1004", "1200", "1201", "1208", "1209", "120A", "120B", "1405", "2000", "2201")) {
                    run(listOf("reg", "add", key, "/v", action, "/t", "REG_DWORD", "/d", "0", "/f"), null).waitFor()
                }
            }
            File(prefix, ".armflash-gecko").writeText(GECKO_SHA256)
            Log.i(TAG, "gecko: installed")
            true
        }.getOrElse { Log.w(TAG, "gecko: ${it.message}"); log("Could not set up Internet Explorer support: ${it.message}"); false }
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
        pb.environment().putAll(extra)
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
        /** Programs Wine places windows for itself (see [ensureUnmanaged]). */
        const val UNMANAGED_EXE = "SPR.exe"
        val UNMANAGED_EXES = listOf(UNMANAGED_EXE, "FPNavigator.exe", "flashpointnavigator.exe")
        const val TAG = "ARMFlashWin"
        /**
         * Box64's translation for a program that compiles its own code as it runs (a JIT): what
         * Box64 switches to by itself when it sees Mono or Java's libjvm load
         * (src/librarian/library.c). It only looks at Linux libraries, so a Windows runtime under
         * Wine never gets it: Silverlight's .NET runtime.
         */
        val JIT_SAFE = mapOf("BOX64_DYNAREC_BIGBLOCK" to "0", "BOX64_DYNAREC_STRONGMEM" to "1")
        /** Where the runtime downloads from (ARMFlash's GitHub releases), once it is published. */
        const val RUNTIME_URL = ""
        const val RUNTIME_SHA256 = "cf540f0bbf70e717389dbd871d3af2f1f4683fd46831be07ad922d528d9ec2e5"
        /** Microsoft's Visual C++ 6 runtime (winetricks' source for MFC 4.2). */
        const val VC6_URL = "https://download.microsoft.com/download/vc60pro/Update/2/W9XNT4/EN-US/VC6RedistSetup_deu.exe"
        const val VC6_SHA256 = "c2eb91d9c4448d50e46a32fecbcc3b418706d002beab9b5f4981de552098cee7"
        /** Wine Gecko, the version Wine 10 uses (32-bit: the ActiveX controls are). */
        const val GECKO_URL = "https://dl.winehq.org/wine/wine-gecko/2.47.4/wine-gecko-2.47.4-x86.msi"
        const val GECKO_SHA256 = "26cecc47706b091908f7f814bddb074c61beb8063318e9efc5a7f789857793d6"
        /** ARMFlash-3d-1.tar.zst (tools/winrt/pack3d.py). */
        const val GRAPHICS_SHA256 = "2ac35e8bd4ab011f83c1041608806603273f236d7d83f88111b72b3212ee0260"
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
