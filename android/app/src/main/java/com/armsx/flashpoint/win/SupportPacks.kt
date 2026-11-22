package com.armsx.flashpoint.win

import com.armsx.flashpoint.core.FpCore
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Flashpoint's support packs: each platform's player (Flash Player, the Shockwave projector,
 * Unity, Java and about 170 more), published in Flashpoint's component index and unpacked into
 * <Flashpoint>/FPSoftware exactly as the desktop launcher lays them out. Downloaded the first
 * time a game needs one.
 */
object SupportPacks {
    private const val BASE = "https://nexus-dev.unstable.life/repository/stable/"

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

    /** Download and unpack pack [id] into [root]/FPSoftware; [progress] gets bytes so far and total. */
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
