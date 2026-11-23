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

    /** The pack a program path needs: FPSoftware\<Folder>\... -> its component id. */
    fun idFor(applicationPath: String): String? {
        val parts = applicationPath.replace('/', '\\').split('\\').filter { it.isNotEmpty() }
        if (parts.size < 3 || !parts[0].equals("FPSoftware", ignoreCase = true)) return null
        val folder = parts[1].lowercase()
        return when (folder) {
            "fpnavigator-portable" -> "fpnavigator"
            else -> folder.filter { it.isLetterOrDigit() }
        }
    }

    /** A Windows program from a support pack (what desktop Flashpoint starts for the game). */
    fun isWindowsProgram(applicationPath: String) =
        idFor(applicationPath) != null && applicationPath.endsWith(".exe", ignoreCase = true)

    private fun marker(root: File, id: String) = File(root, "FPSoftware/.armflash/$id")

    fun installed(root: File, id: String) = marker(root, id).isFile

    /** Download and unpack pack [id] into [root]/FPSoftware ([root] is [drive]); [progress] gets bytes so far and total. */
    fun install(root: File, id: String, progress: (Long, Long) -> Unit) {
        if (installed(root, id)) return
        val soft = File(root, "FPSoftware").apply { mkdirs() }
        val tmp = File(soft, ".armflash/$id.zip.part").apply { parentFile?.mkdirs() }
        val conn = URL("${BASE}supportpack-$id.zip").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "ARMFlash")
        conn.connectTimeout = 20_000; conn.readTimeout = 60_000
        try {
            if (conn.responseCode != 200) throw IOException("support pack $id: HTTP ${conn.responseCode}")
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
