package com.armsx.flashpoint.data

import android.content.Context
import android.net.Uri
import com.armsx.flashpoint.core.FpCore
import com.armsx.flashpoint.core.Game
import com.armsx.flashpoint.core.GameData
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The on-device Flashpoint folder, laid out like the desktop one so data can be copied
 * across as-is:
 *
 *   Flashpoint/Data/flashpoint.sqlite
 *   Flashpoint/Data/Games/<gameId>-<dateAdded ms>.zip
 *   Flashpoint/Legacy/htdocs/<host>/<path>
 */
class Library(private val ctx: Context) {
    val root: File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Flashpoint")
    val database = File(root, "Data/flashpoint.sqlite")
    val games = File(root, "Data/Games")
    val htdocs = File(root, "Legacy/htdocs")

    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Base URL for game data packs (`<base><gameId>-<ms>.zip`). User-configurable, as in the launcher. */
    var gameDataSource: String
        get() = prefs.getString("gameDataSource", DEFAULT_GAME_DATA_SOURCE)!!
        set(v) = prefs.edit().putString("gameDataSource", v).apply()

    var imagesBase: String
        get() = prefs.getString("imagesBase", DEFAULT_IMAGES)!!
        set(v) = prefs.edit().putString("imagesBase", v).apply()

    /** Fetch Legacy (non-zip) game files from Flashpoint's server on demand. */
    var legacyOnline: Boolean
        get() = prefs.getBoolean("legacyOnline", true)
        set(v) = prefs.edit().putBoolean("legacyOnline", v).apply()

    val legacyBase: String get() = if (legacyOnline) DEFAULT_LEGACY else ""

    init {
        games.mkdirs()
        htdocs.mkdirs()
    }

    fun hasDatabase() = database.isFile && database.length() > 0

    /** Copy a flashpoint.sqlite picked with the system file picker into place. */
    fun importDatabase(uri: Uri, progress: (Long) -> Unit = {}) {
        database.parentFile!!.mkdirs()
        val tmp = File(database.path + ".import")
        ctx.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "cannot open the picked file" }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    progress(total)
                }
            }
        }
        // SQLite side files from a previous database would corrupt the new one.
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
        if (!tmp.renameTo(database)) throw IOException("could not move the database into place")
    }

    fun logoUrl(id: String) = imageUrl("Logos", id)
    fun screenshotUrl(id: String) = imageUrl("Screenshots", id)

    private fun imageUrl(folder: String, id: String): String {
        if (id.length < 4) return ""
        return "$imagesBase$folder/${id.substring(0, 2)}/${id.substring(2, 4)}/$id.png"
    }

    /** Local zip for a data pack, whether or not it exists yet. */
    fun zipFor(gd: GameData): File {
        gd.path?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            return if (f.isAbsolute) f else File(games, p)
        }
        return File(games, fileName(gd))
    }

    fun isInstalled(game: Game): Boolean {
        val gd = game.activeData ?: return false
        return zipFor(gd).isFile
    }

    /**
     * Download a data pack the way the launcher does: to `<name>.temp`, check SHA-256,
     * move into Data/Games, then record it in the database.
     */
    fun download(gd: GameData, progress: (done: Long, total: Long) -> Unit): File {
        val dest = File(games, fileName(gd))
        if (dest.isFile) return dest
        val tmp = File(games, dest.name + ".temp")
        val url = URL(gameDataSource.trimEnd('/') + "/" + dest.name)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Flashpoint-Android/0.1")
        }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: gd.size
            val sha = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 18)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        if (done - last > (1 shl 20) || done == total) {
                            progress(done, total)
                            last = done
                        }
                    }
                }
            }
            val got = sha.digest().joinToString("") { "%02x".format(it) }
            if (gd.sha256.isNotBlank() && !got.equals(gd.sha256, ignoreCase = true)) {
                tmp.delete()
                throw IOException("checksum mismatch for ${dest.name}")
            }
            if (!tmp.renameTo(dest)) throw IOException("could not move ${dest.name} into place")
        } finally {
            conn.disconnect()
            if (tmp.exists() && !dest.exists()) tmp.delete()
        }
        runCatching { FpCore.markOnDisk(gd.id, dest.name) }
        return dest
    }

    companion object {
        /** Unverified default; the launcher ships with no built-in source. Change in Config. */
        const val DEFAULT_GAME_DATA_SOURCE = "https://download.unstable.life/gib-roms/Games/"
        const val DEFAULT_IMAGES = "https://infinity.unstable.life/images/"
        const val DEFAULT_LEGACY = "https://infinity.flashpointarchive.org/Flashpoint/Legacy/htdocs/"

        /** `<gameId>-<epoch ms of dateAdded>.zip`, matching the launcher's getGameDataFilename. */
        fun fileName(gd: GameData): String = "${gd.gameId}-${epochMillis(gd.dateAdded)}.zip"

        fun epochMillis(date: String): Long {
            val s = date.trim()
            runCatching { return Instant.parse(s).toEpochMilli() }
            runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            // "2018-01-11 18:05:03.123" (SQLite style): the launcher treats it as UTC.
            val iso = s.replace(' ', 'T')
            runCatching { return LocalDateTime.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE_TIME).toInstant(ZoneOffset.UTC).toEpochMilli() }
            return 0
        }
    }
}
