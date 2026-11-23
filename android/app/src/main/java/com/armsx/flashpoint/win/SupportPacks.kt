package com.armsx.flashpoint.win

import com.armsx.flashpoint.core.FpCore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Flashpoint's support packs: each platform's player (Flash Player, the Shockwave projector,
 * Unity, Java and about 170 more), published in Flashpoint's component index and unpacked into
 * FPSoftware exactly as the desktop launcher lays them out. Downloaded the first time a game
 * needs one.
 *
 * They live on Windows' drive F: in ARMFlash's private storage ([drive]), not in the Flashpoint
 * folder: Wine's folder scans on shared storage crash Android's own storage service (its FUSE
 * daemon aborts in opendir), which takes every app using shared storage down with it.
 */
object SupportPacks {
    private const val BASE = "https://nexus-dev.unstable.life/repository/stable/"

    /**
     * Windows' drive F:, laid out like desktop Flashpoint's folder: FPSoftware here, Legacy and
     * Data linked from the Flashpoint folder at [fpRoot]. Players an earlier version unpacked
     * into the Flashpoint folder move here once.
     */
    @Synchronized
    fun drive(ctx: android.content.Context, fpRoot: File): File {
        val d = File(ctx.dataDir, "r/f").apply { mkdirs() }
        val soft = File(d, "FPSoftware")
        val old = File(fpRoot, "FPSoftware")
        if (old.isDirectory) {
            if (!soft.exists()) {
                val moving = File(d, "FPSoftware.moving")
                moving.deleteRecursively()
                if (old.copyRecursively(moving, overwrite = true)) moving.renameTo(soft)
            }
            if (soft.isDirectory) old.deleteRecursively()
        }
        soft.mkdirs()
        for (name in listOf("Legacy", "Data")) {
            val at = File(d, name)
            if (at.exists() || java.nio.file.Files.isSymbolicLink(at.toPath())) at.delete()
            runCatching { android.system.Os.symlink(File(fpRoot, name).path, at.path) }
        }
        return d
    }

    /** One pack from Flashpoint's component index (assets/flashpoint/supportpacks.json). */
    private class Pack(val title: String, val path: String, val depends: List<String>, val top: List<String>)

    @Volatile private var index: Map<String, Pack>? = null

    private fun index(ctx: android.content.Context): Map<String, Pack> = index ?: synchronized(this) {
        index ?: run {
            val root = org.json.JSONObject(ctx.assets.open("flashpoint/supportpacks.json").bufferedReader().readText()).getJSONObject("packs")
            fun list(o: org.json.JSONObject, k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            root.keys().asSequence().associateWith { id ->
                val o = root.getJSONObject(id)
                Pack(o.optString("title"), o.optString("path"), list(o, "depends"), list(o, "top"))
            }.also { index = it }
        }
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * The packs a game needs, dependencies first: the one that ships its program or start
     * script (the first part of FPSoftware\...), the one named for its platform (plugins that
     * live in a shared browser's folder, Cult3D in Netscape's say), and what those depend on.
     */
    fun needed(ctx: android.content.Context, applicationPath: String, platform: String): List<String> {
        val idx = index(ctx)
        val parts = applicationPath.replace('/', '\\').split('\\').filter { it.isNotEmpty() }
        val wanted = mutableListOf<String>()
        if (parts.size >= 2 && parts[0].equals("FPSoftware", ignoreCase = true)) {
            val first = parts[1].lowercase()
            val owners = idx.filter { (_, p) -> p.top.any { it.equals(first, ignoreCase = true) } }.keys
            // A folder many packs fill (Netscape's, the browser plugins'): the platform picks.
            if (owners.size == 1) wanted += owners.first()
        }
        idx.entries.firstOrNull { norm(it.value.title) == norm(platform) }?.let { wanted += it.key }
        val out = LinkedHashSet<String>()
        fun add(id: String, depth: Int) {
            val p = idx[id] ?: return
            if (id in out || depth > 8) return
            p.depends.forEach { add(it, depth + 1) }
            out += id
        }
        wanted.forEach { add(it, 0) }
        return out.filter { idx[it]?.path == "FPSoftware" }
    }

    /**
     * Put ARMFlash's Flashpoint Secure Player (tools/winrt/fsp, a native stand-in that reads
     * the same configs) where start scripts call it, over the .NET one the Secure Player pack
     * ships: Wine here has no .NET.
     */
    /**
     * Java's security providers with Windows' own (SunMSCAPI) first. Java's built-in random
     * number source seeds itself from the network adapters list, and Wine's IP helper crashes
     * reading it here (Android keeps it from the netlink socket), which ended every applet as it
     * loaded its first jar; the Windows provider uses Windows' generator instead.
     */
    private val JAVA_SECURITY = listOf(
        "sun.security.mscapi.SunMSCAPI", "sun.security.provider.Sun", "sun.security.rsa.SunRsaSign",
        "sun.security.ec.SunEC", "com.sun.net.ssl.internal.ssl.Provider", "com.sun.crypto.provider.SunJCE",
        "sun.security.jgss.SunProvider", "com.sun.security.sasl.Provider", "org.jcp.xml.dsig.internal.dom.XMLDSigRI",
        "sun.security.smartcardio.SunPCSC",
    ).mapIndexed { i, p -> "security.provider.${i + 1}=$p" }.joinToString("\r\n", postfix = "\r\n")

    fun placeSecurePlayer(ctx: android.content.Context, drive: File) {
        File(drive, "FPSoftware/.armflash/java.security").apply { parentFile?.mkdirs() }.writeText(JAVA_SECURITY)
        val out = File(drive, "FPSoftware/FlashpointSecurePlayer.exe")
        val bytes = ctx.assets.open("winrt/FlashpointSecurePlayer.exe").use { it.readBytes() }
        if (out.isFile && out.length() == bytes.size.toLong() && out.readBytes().contentEquals(bytes)) return
        out.parentFile?.mkdirs()
        out.writeBytes(bytes)
    }

    /** A Windows program or start script from a support pack (what desktop Flashpoint starts). */
    fun isWindowsProgram(applicationPath: String): Boolean {
        val p = applicationPath.replace('/', '\\')
        return p.startsWith("FPSoftware\\", ignoreCase = true) &&
            (p.endsWith(".exe", ignoreCase = true) || p.endsWith(".bat", ignoreCase = true))
    }

    private fun marker(root: File, id: String) = File(root, "FPSoftware/.armflash/$id")

    /** Installed: by its index id, or by the short name earlier versions marked it with. */
    fun installed(root: File, id: String): Boolean {
        val short = id.removePrefix("supportpack-")
        return marker(root, id).isFile || marker(root, short).isFile || marker(root, short.substringAfterLast('-')).isFile
    }

    /** Download and unpack pack [id] (an index id) into [root]/FPSoftware ([root] is [drive]); [progress] gets bytes so far and total. */
    fun install(root: File, id: String, progress: (Long, Long) -> Unit) {
        if (installed(root, id)) return
        val soft = File(root, "FPSoftware").apply { mkdirs() }
        val tmp = File(soft, ".armflash/$id.zip.part").apply { parentFile?.mkdirs() }
        val conn = URL("$BASE$id.zip").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "ARMFlash")
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("$id: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        progress(done, total)
                    }
                }
            }
            FpCore.unzip(tmp.path, soft.path)
            marker(root, id).writeText("")
        } finally {
            conn.disconnect(); tmp.delete()
        }
    }
}
