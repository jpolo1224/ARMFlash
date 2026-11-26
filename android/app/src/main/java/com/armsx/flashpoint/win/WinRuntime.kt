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
    /**
     * Download [url] (the runtime or the 3D file on ARMFlash's GitHub release) and install it when
     * its checksum is [sha256], as for a file picked by hand.
     */
    fun download(url: String, sha256: String, progress: (String) -> Unit): Boolean {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("User-Agent", "ARMFlash")
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        return try {
            if (conn.responseCode != 200) { progress("Download failed (HTTP ${conn.responseCode})."); false }
            else install(conn.inputStream, setOf(sha256), conn.contentLengthLong, progress)
        } catch (e: java.io.IOException) {
            progress("Download failed: ${e.message}"); false
        } finally {
            conn.disconnect()
        }
    }

    /** Install the runtime or the 3D pack from [src] when its checksum is one of [accepted]. */
    fun install(src: java.io.InputStream, accepted: Set<String>?, size: Long = -1, progress: (String) -> Unit): Boolean {
        val tmp = File(ctx.cacheDir, "winrt.tar.zst")
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val verb = if (size > 0) "Downloading" else "Copying"
        progress("$verb…")
        src.use { ins ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n); md.update(buf, 0, n); total += n
                    if (total % (8 shl 20) < n) progress("$verb… ${total shr 20}${if (size > 0) " of ${size shr 20}" else ""} MB")
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
        // Where the base system's glibc keeps POSIX shared memory (Winlator's patch, relocated):
        // Wine's esync maps its shared object table there.
        File(root, "tmp/shm").mkdirs()
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

    /** There while a Windows setup is under way: still there at the next start, that setup was cut short. */
    private val settingUp = File(root, "home/.armflash-setting-up")

    /**
     * Make the prefix on first use (several minutes on a phone; only once), and finish one whose
     * setup stopped partway.
     *
     * Wine stamps a prefix as set up (its .update-timestamp) before it copies Windows' system
     * files into it, and from then on looks for them only there. A first setup cut short (the
     * game screen closed, Android stopping the app, or Wine giving up its 5-minute wait on a slow
     * phone while the copying went on, until the game closed) left a prefix that looked set up,
     * and every Windows program then failed at once with "could not load kernel32.dll, status
     * c0000135" (reported on a Mali phone; reproduced on the emulator by stopping a setup 2 s in).
     * Without the stamp, Wine's next start copies everything again: that is how one is finished.
     */
    fun ensurePrefix(log: (String) -> Unit): Boolean {
        val registry = { File(prefix, "system.reg").isFile && File(prefix, "user.reg").isFile }
        val missing = missingSystemFiles()
        if (missing.isEmpty() && registry() && !settingUp.exists()) return true
        val again = File(prefix, "dosdevices").isDirectory
        if (again) {
            val why = if (missing.isEmpty()) "cut short" else "missing " + missing.take(4).joinToString() + if (missing.size > 4) " and ${missing.size - 4} more" else ""
            Log.w(TAG, "prefix: the last Windows setup did not finish ($why); finishing it")
            File(prefix, ".update-timestamp").delete()
        }
        val what = if (again) "Finishing Windows setup" else "Setting up Windows support (first time only)"
        log("$what. Keep this screen open…")
        settingUp.parentFile?.mkdirs()
        settingUp.writeText("")
        val started = android.os.SystemClock.elapsedRealtime()
        val tick = {
            val mins = (android.os.SystemClock.elapsedRealtime() - started) / 60_000
            if (mins > 0) log("$what ($mins min so far). Keep this screen open…")
        }
        val boot = run(listOf("wineboot", "-i"), null)
        val booted = waitTicking(boot, SETUP_LIMIT_MS, tick)
        Log.i(TAG, "wineboot -i ${if (booted) "exited with ${boot.exitValue()}" else "still running"}")
        // Wine waits only 5 minutes for its own setup and then goes on without it, while a slow
        // phone is still copying. Its server ends once every Wine program has, the setup's too,
        // and saves the registry as it ends.
        val ended = booted && waitForServer(SETUP_LIMIT_MS, tick)
        val deadline = System.currentTimeMillis() + 75_000
        while (ended && !registry() && System.currentTimeMillis() < deadline) Thread.sleep(500)
        val left = missingSystemFiles()
        val done = ended && registry() && left.isEmpty()
        if (done) {
            settingUp.delete()
            // The setup's own commands may have run before the files were there.
            cannotStart = false
            Log.i(TAG, "prefix: set up in ${(android.os.SystemClock.elapsedRealtime() - started) / 1000} s")
        } else {
            Log.w(TAG, "prefix: setup did not finish (server ${if (ended) "ended" else "still running"}, registry ${registry()}, missing ${left.take(4)})")
        }
        return done
    }

    /**
     * Windows files every program needs, in the prefix's system folders (each copied there from
     * the runtime by Wine's setup, at the runtime's size): the ones of [SYSTEM_FILES] the runtime
     * has that are missing or empty.
     */
    private fun missingSystemFiles(): List<String> {
        val win = File(prefix, "drive_c/windows")
        val pe = File(root, "opt/wine/lib/wine")
        return listOf("system32" to "x86_64-windows", "syswow64" to "i386-windows").flatMap { (dir, arch) ->
            SYSTEM_FILES.filter { File(pe, "$arch/$it").isFile && File(win, "$dir/$it").length() == 0L }.map { "$dir/$it" }
        }
    }

    /** Wait for [p] to end, up to [limitMs], calling [tick] every 30 s. False if it is still running then. */
    private fun waitTicking(p: Process, limitMs: Long, tick: () -> Unit): Boolean {
        val until = android.os.SystemClock.elapsedRealtime() + limitMs
        while (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            if (android.os.SystemClock.elapsedRealtime() >= until) return false
            tick()
        }
        return true
    }

    /** Wait until Wine's server has ended (every Wine program with it), up to [limitMs]. */
    private fun waitForServer(limitMs: Long, tick: () -> Unit): Boolean {
        val pb = ProcessBuilder(listOf("$lib/libbox64.so", "${root.path}/opt/wine/bin/wineserver", "-w")).redirectErrorStream(true)
        pb.environment().putAll(env)
        pb.environment().putAll(extra)
        val p = pb.start().also { drain(it, "wineserver") }
        return waitTicking(p, limitMs, tick).also { if (!it) p.destroy() }
    }

    /**
     * Point Windows' own proxy setting (WinINet, which Flash Player and most plugins use) at the
     * game server, as desktop Flashpoint does, so every file a game asks for comes from its data.
     */
    fun ensureProxy(port: Int) = ensureReg(
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings",
        listOf(RegValue("ProxyEnable", "REG_DWORD", "1"), RegValue("ProxyServer", "REG_SZ", "127.0.0.1:$port")),
    )

    /** A registry value as reg.exe adds it, and the line Wine saves for it in user.reg. */
    private class RegValue(val name: String, val type: String, val data: String) {
        val line: String get() = "\"$name\"=" + if (type == "REG_DWORD") "dword:%08x".format(data.toLong()) else "\"$data\""
    }

    /**
     * Settings ARMFlash puts in Windows' registry, added with reg.exe whenever Wine's saved user
     * registry (user.reg) does not have them. Wine saves the registry only every 30 seconds or
     * when its server stops, and closing a game stops it hard: a setting added in a game's first
     * seconds could be lost while a marker file said it was done, so it never came back (the
     * Shockwave projector's window then kept its first size, cropping the game). Checked against
     * the saved registry itself, a lost setting is back before the game starts.
     */
    private fun ensureReg(key: String, values: List<RegValue>) {
        val saved = savedUserValues(key)
        for (v in values) if (v.line.lowercase() !in saved) {
            Log.i(TAG, "registry: adding ${v.name} under $key (not in user.reg)")
            run(listOf("reg", "add", key, "/v", v.name, "/t", v.type, "/d", v.data, "/f"), null).waitFor()
        }
    }

    /** The value lines (lowercase) Wine last saved under the HKCU key [key]. */
    private fun savedUserValues(key: String): Set<String> {
        val text = runCatching { File(prefix, "user.reg").readText() }.getOrNull() ?: return emptySet()
        // user.reg names keys relative to HKCU, with each backslash written twice.
        val header = "[" + key.removePrefix("HKCU\\").replace("\\", "\\\\").lowercase() + "]"
        val out = HashSet<String>()
        var inKey = false
        for (line in text.lineSequence()) {
            if (line.startsWith("[")) inKey = line.lowercase().startsWith(header)
            else if (inKey) out += line.trim().lowercase()
        }
        return out
    }

    /**
     * Let Wine place its windows itself. There is no window manager on the virtual screen, and
     * Wine hands "managed" windows' moves and resizes to one and waits: a Shockwave projector
     * resized its window to the stage while the X window stayed at its first size, so only a
     * quarter of the game showed.
     */
    /**
     * Fixes from later Wine versions, put over the runtime's own files (made by
     * tools/winrt/winefix). Each replaces only the exact file it was made from, so a later
     * runtime keeps its own; reinstalling the runtime brings the fix back at the next start.
     */
    fun ensureWineFixes() {
        for (fix in WINE_FIXES) runCatching {
            val f = File(root, fix.path)
            if (!f.isFile) return@runCatching
            when (sha256(f)) {
                fix.fixed -> return@runCatching
                fix.original -> {
                    val tmp = File(f.path + ".armflash-new")
                    ctx.assets.open(fix.asset).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                    check(sha256(tmp) == fix.fixed) { "asset checksum" }
                    check(tmp.renameTo(f)) { "rename" }
                    Log.i(TAG, "wine fix: ${fix.path} (${fix.what})")
                }
                else -> Log.i(TAG, "wine fix: ${fix.path} is another build, left as it is")
            }
        }.onFailure { Log.w(TAG, "wine fix: ${fix.path}: ${it.message}") }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * GLX on the virtual screen, for OpenGL and Direct3D in Windows games. Termux's Xvfb has GLX
     * built in but loads its software renderer from x/lib/dri, which the runtime never had, so it
     * offered no GL visuals and Wine got no OpenGL (Shockwave 3D games froze where 3D starts).
     * Mesa's libdril (tools/winrt/xglx.py) is the stub Mesa makes for such X servers: it gives
     * GLX a list of framebuffer configs and renders nothing. Wine renders with the 3D file's
     * llvmpipe in its own process, so only then is it put in.
     */
    fun ensureGlx() {
        if (!has3d) return
        for ((asset, rel) in GLX_FILES) runCatching {
            val f = File(xdir, rel)
            val bytes = ctx.assets.open("winrt/xglx/$asset").use { it.readBytes() }
            if (f.isFile && f.length() == bytes.size.toLong() && f.readBytes().contentEquals(bytes)) return@runCatching
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".armflash-new")
            tmp.writeBytes(bytes)
            check(tmp.renameTo(f)) { "rename" }
            Log.i(TAG, "glx: x/$rel put in")
        }.onFailure { Log.w(TAG, "glx: x/$rel: ${it.message}") }
    }

    private class WineFix(val path: String, val asset: String, val original: String, val fixed: String, val what: String)

    fun ensureUnmanaged() {
        // Where it was seen: the Shockwave projector (every PJ version is SPR.exe) and
        // Flashpoint Navigator (FPNavigator.exe starts flashpointnavigator.exe). Wine then draws
        // their title bars itself, which the game screen crops off.
        for (exe in UNMANAGED_EXES) {
            ensureReg("HKCU\\Software\\Wine\\AppDefaults\\$exe\\X11 Driver", listOf(RegValue("Managed", "REG_SZ", "N")))
        }
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
     * Every Internet Explorer zone (local, intranet, trusted, internet, restricted): ActiveX
     * controls and plugins run and scripts may use them, with no prompt.
     */
    private fun ensureZones() {
        for (zone in 0..4) {
            ensureReg(
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings\\Zones\\$zone",
                listOf("1001", "1004", "1200", "1201", "1208", "1209", "120A", "120B", "1405", "2000", "2201").map { RegValue(it, "REG_DWORD", "0") },
            )
        }
    }

    /**
     * Internet Explorer's engine for games that run in its browser control (Secure Player's
     * WEB_BROWSER templates: ActiveX games, WildTangent, Hypercosm, Viscape and others): Wine
     * Gecko, the version this Wine uses, installed once with Wine's msiexec. Until it is there,
     * mshtml stays off (WINEDLLOVERRIDES), or Wine would stop every program to offer it. Also
     * lets pages run their ActiveX controls without asking, as Secure Player's browser does.
     */
    fun ensureGecko(log: (String) -> Unit): Boolean {
        if (hasGecko) { ensureZones(); return true }
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
            ensureZones()
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
        runCatching {
            p.inputStream.bufferedReader().forEachLine {
                Log.i(TAG, "$tag: $it")
                if (tag == "wine" && "could not load kernel32.dll" in it) cannotStart = true
            }
        }
    }.apply { isDaemon = true }.start()

    /**
     * Wine could not load its own kernel32.dll: no Windows program can run, the setup commands
     * included. The game screen says so instead of closing like a crash. (On the Mali phone where
     * it was first seen, the prefix's setup had been cut short; [ensurePrefix] now finishes such
     * a prefix, so this is left for causes not seen yet.)
     */
    @Volatile var cannotStart = false
        private set

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

    /**
     * Stop the game, Wine's services and the X server. With [wait], also wait (up to 3 s) until
     * they are gone, so a new X server can take the display.
     */
    fun stopAll(wait: Boolean = false) {
        val pids = children()
        pids.forEach { android.os.Process.sendSignal(it, SIGCONT); android.os.Process.killProcess(it) }
        wine?.destroy(); xServer?.destroy()
        if (!wait) return
        val until = android.os.SystemClock.uptimeMillis() + 3000
        while (pids.any(::alive) && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(20)
    }

    /** Still running: there, and not a zombie waiting to be reaped. */
    private fun alive(pid: Int) = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(')').trim().firstOrNull().let { it != 'Z' && it != 'X' }
    }.getOrDefault(false)

    companion object {
        /** Programs Wine places windows for itself (see [ensureUnmanaged]). */
        const val UNMANAGED_EXE = "SPR.exe"
        val UNMANAGED_EXES = listOf(UNMANAGED_EXE, "FPNavigator.exe", "flashpointnavigator.exe")
        const val TAG = "ARMFlashWin"
        /** Where the runtime and the 3D file download from: ARMFlash's first GitHub release. */
        private const val RELEASE_FILES = "https://github.com/jpolo1224/ARMFlash/releases/download/1.0/"
        const val RUNTIME_URL = RELEASE_FILES + "ARMFlash-windows-1.tar.zst"
        const val GRAPHICS_URL = RELEASE_FILES + "ARMFlash-3d-1.tar.zst"
        const val RUNTIME_SHA256 = "cf540f0bbf70e717389dbd871d3af2f1f4683fd46831be07ad922d528d9ec2e5"
        /** Microsoft's Visual C++ 6 runtime (winetricks' source for MFC 4.2). */
        const val VC6_URL = "https://download.microsoft.com/download/vc60pro/Update/2/W9XNT4/EN-US/VC6RedistSetup_deu.exe"
        const val VC6_SHA256 = "c2eb91d9c4448d50e46a32fecbcc3b418706d002beab9b5f4981de552098cee7"
        /** Wine Gecko, the version Wine 10 uses (32-bit: the ActiveX controls are). */
        const val GECKO_URL = "https://dl.winehq.org/wine/wine-gecko/2.47.4/wine-gecko-2.47.4-x86.msi"
        const val GECKO_SHA256 = "26cecc47706b091908f7f814bddb074c61beb8063318e9efc5a7f789857793d6"
        /** ARMFlash-3d-1.tar.zst (tools/winrt/pack3d.py). */
        const val GRAPHICS_SHA256 = "2ac35e8bd4ab011f83c1041608806603273f236d7d83f88111b72b3212ee0260"
        /** See [ensureGlx]: asset name -> path under x/. */
        private val GLX_FILES = listOf(
            "swrast_dri.so" to "lib/dri/swrast_dri.so",
            "libgbm.so" to "lib/libgbm.so",
            "libdrm.so" to "lib/libdrm.so",
        )
        /** See [ensureWineFixes]. */
        private val WINE_FIXES = listOf(
            // Wine commit 4781a712 (iphlpapi: Avoid out-of-bounds read if no network interfaces
            // are present), in the runtime's Wine 10.10. Android hides the network interfaces from
            // apps, so Wine finds none, and a 32-bit program asking for its network adapters read
            // past an empty list and crashed: Merlin's Revenge's Shockwave projector, as it loaded.
            WineFix(
                path = "opt/wine/lib/wine/i386-windows/iphlpapi.dll",
                asset = "winrt/iphlpapi-i386.dll",
                original = "0923068b93de990b0c811f2cfee628289035809c0fd00d5f725ccfe204a04320",
                fixed = "c56e50b660e4033fd8d08a28e3acd7c1666ae05182c88c9cbde7d55100e811f2",
                what = "no crash without network adapters",
            ),
        )
        /** See [missingSystemFiles]: what loads first in every Windows program, and Wine's own setup commands. */
        private val SYSTEM_FILES = listOf(
            "ntdll.dll", "kernel32.dll", "kernelbase.dll", "user32.dll", "win32u.dll", "gdi32.dll",
            "advapi32.dll", "sechost.dll", "msvcrt.dll", "ucrtbase.dll", "rpcrt4.dll", "combase.dll",
            "ole32.dll", "shell32.dll", "ws2_32.dll", "wininet.dll", "winex11.drv", "cmd.exe", "reg.exe",
            "wineboot.exe", "services.exe", "wow64.dll", "wow64win.dll", "wow64cpu.dll",
        )
        /** How long a Windows setup may take (a slow phone takes minutes; the emulator 20 s). */
        private const val SETUP_LIMIT_MS = 30 * 60_000L
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
