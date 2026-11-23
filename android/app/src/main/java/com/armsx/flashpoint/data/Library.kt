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
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * The Flashpoint folder. It lives in one of the app's own storage folders (internal or an
     * SD card), which need no storage permission, so this works the same in a Play build.
     * Picked on first run; see [locations].
     */
    var root: File = prefs.getString("storageRoot", null)?.let(::File)?.takeIf { it.parentFile?.isDirectory == true }
        ?: File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "Flashpoint")
        private set
    val database get() = File(root, "Data/flashpoint.sqlite")
    val games get() = File(root, "Data/Games")
    val htdocs get() = File(root, "Legacy/htdocs")
    /** My Games: the player's own .swf files. */
    val local by lazy { LocalGames(this) }
    val playlists get() = Playlists(File(root, "Data/Playlists"))

    /** A place the Flashpoint folder can go, with its free space. */
    data class Location(val dir: File, val label: String, val freeBytes: Long, val removable: Boolean)

    /** Internal storage and every mounted SD card or USB drive, as app folders (no permission needed). */
    fun locations(): List<Location> {
        val sm = ctx.getSystemService(android.os.storage.StorageManager::class.java)
        return ctx.getExternalFilesDirs(null).filterNotNull().mapNotNull { base ->
            val vol = runCatching { sm.getStorageVolume(base) }.getOrNull() ?: return@mapNotNull null
            if (vol.state != android.os.Environment.MEDIA_MOUNTED) return@mapNotNull null
            val free = runCatching { android.os.StatFs(base.path).availableBytes }.getOrDefault(0L)
            Location(File(base, "Flashpoint"), vol.getDescription(ctx), free, vol.isRemovable)
        }
    }

    /**
     * A folder picked with the system's folder picker, as a path (ARMSX2's resolveTreeUriToPosix),
     * if it is writable. GitHub build only: needs all-files access.
     */
    fun customLocation(tree: Uri): File? {
        val docId = runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return null
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val dir = File(if (parts[0] == "primary") "/storage/emulated/0/${parts[1]}" else "/storage/${parts[0]}/${parts[1]}")
        // Write-probe it now, while the user can still pick another (as ARMSX2 does).
        return dir.takeIf {
            runCatching { (it.isDirectory || it.mkdirs()) && File.createTempFile(".armflash-probe-", ".tmp", it).delete() }.getOrDefault(false)
        }
    }

    /** Use [dir] for the Flashpoint folder from now on. Only for a library that has nothing in it yet. */
    fun useLocation(dir: File) {
        root = dir
        prefs.edit().putString("storageRoot", dir.path).apply()
        games.mkdirs()
        htdocs.mkdirs()
    }

    /** Base URL for game data packs (`<base><gameId>-<ms>.zip`). User-configurable, as in the launcher. */
    var gameDataSource: String
        get() = prefs.getString("gameDataSource", DEFAULT_GAME_DATA_SOURCE)!!
        set(v) = prefs.edit().putString("gameDataSource", v).apply()

    var imagesBase: String
        get() = prefs.getString("imagesBase", DEFAULT_IMAGES)!!
        set(v) = prefs.edit().putString("imagesBase", v).apply()

    var metadataBase: String
        get() = prefs.getString("metadataBase", METADATA_BASE)!!
        set(v) = prefs.edit().putString("metadataBase", v).apply()

    /** Fetch Legacy (non-zip) game files from Flashpoint's server on demand. */
    var legacyOnline: Boolean
        get() = prefs.getBoolean("legacyOnline", true)
        set(v) = prefs.edit().putBoolean("legacyOnline", v).apply()

    /** Last resort for files in neither the game's data nor Legacy: the original site (see fp-proxy). */
    var liveWeb: Boolean
        get() = prefs.getBoolean("liveWeb", true)
        set(v) = prefs.edit().putBoolean("liveWeb", v).apply()

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
        if (id.length < 4 || LocalGames.isLocal(id) || !prefs.getBoolean("onDemandImages", true)) return ""
        val base = imagesBase.let { if (it.endsWith("/")) it else "$it/" }
        val url = "$base$folder/${id.substring(0, 2)}/${id.substring(2, 4)}/$id.png"
        // The launcher's "Compressed Images": same path, ?type=jpg.
        return if (prefs.getBoolean("onDemandImagesCompressed", true)) "$url?type=jpg" else url
    }

    /** Local zip for a data pack, whether or not it exists yet. */
    fun zipFor(gd: GameData): File {
        gd.path?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            return if (f.isAbsolute) f else File(games, p)
        }
        return File(games, fileName(gd))
    }

    /** Delete [game]'s data pack and record it as gone. True when nothing is left on disk. */
    fun uninstall(game: Game): Boolean {
        val gd = game.activeData ?: return false
        val zip = zipFor(gd)
        if (zip.exists() && !zip.delete()) return false
        runCatching { FpCore.markOffDisk(gd.id) }
        return true
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
        var last: IOException? = null
        for (base in listOf(gameDataSource, MIRROR_GAME_DATA_SOURCE).distinct()) {
            try {
                return downloadFrom(base, gd, progress)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no game data source")
    }

    /** A download of this data was started and not finished (it resumes). */
    fun hasPartial(gd: GameData): Boolean = File(games, fileName(gd) + ".temp").isFile

    private fun downloadFrom(base: String, gd: GameData, progress: (done: Long, total: Long) -> Unit): File {
        val dest = File(games, fileName(gd))
        if (dest.isFile) return dest
        val tmp = File(games, dest.name + ".temp")
        val url = URL(base.trimEnd('/') + "/" + dest.name)
        // The archive's server throttles each connection (64 MB took two minutes on one and
        // nine seconds on four), so anything big comes down in ranges over several at once,
        // resuming where it stopped. A one-byte range asks for the size and whether ranges work.
        val (total, ranges) = probe(url) ?: (gd.size to false)
        if (ranges && total >= SEGMENTED_FROM) {
            segmented(url, tmp, total, progress)
        } else {
            single(url, tmp, total.takeIf { it > 0 } ?: gd.size, progress)
        }
        progress(total, total)
        if (gd.sha256.isNotBlank()) {
            val got = sha256(tmp)
            if (!got.equals(gd.sha256, ignoreCase = true)) {
                tmp.delete(); partsFile(tmp).delete()
                throw IOException("checksum mismatch for ${dest.name}")
            }
        }
        if (!tmp.renameTo(dest)) throw IOException("could not move ${dest.name} into place")
        partsFile(tmp).delete()
        runCatching { FpCore.markOnDisk(gd.id, dest.name) }
        return dest
    }

    private fun connect(url: URL, range: String?): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "ARMFlash/0.1")
        if (range != null) setRequestProperty("Range", "bytes=$range")
    }

    /** The file's size and whether the server serves ranges of it, or null if it cannot say. */
    private fun probe(url: URL): Pair<Long, Boolean>? {
        val c = connect(url, "0-0")
        return try {
            when (c.responseCode) {
                206 -> c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()?.let { it to true }
                200 -> c.contentLengthLong.takeIf { it > 0 }?.let { it to false }
                else -> throw IOException("HTTP ${c.responseCode} for $url")
            }
        } finally {
            c.disconnect()
        }
    }

    /** One connection, start to end (small files, or a server without ranges). */
    private fun single(url: URL, tmp: File, total: Long, progress: (Long, Long) -> Unit) {
        val c = connect(url, null)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode} for $url")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 18)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > (1 shl 20)) { progress(done, total); last = done }
                    }
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun partsFile(tmp: File) = File(tmp.path + ".parts")

    /**
     * [total] bytes in up to [CONNECTIONS] ranges at once, written into place in [tmp]. Each
     * range's progress is saved next to it, so a failed or interrupted download (the app closed,
     * the network dropped) picks up where it was the next time.
     */
    private fun segmented(url: URL, tmp: File, total: Long, progress: (Long, Long) -> Unit) {
        val n = (total / SEGMENT_MIN).coerceIn(1, CONNECTIONS.toLong()).toInt()
        val size = (total + n - 1) / n
        val starts = LongArray(n) { it * size }
        val ends = LongArray(n) { minOf(total, (it + 1) * size) }
        val parts = partsFile(tmp)
        // Resume only a download of the same file split the same way.
        val saved = runCatching { parts.readText().trim().split(' ').map { it.toLong() } }.getOrNull()
        val done = java.util.concurrent.atomic.AtomicLongArray(n)
        if (tmp.isFile && tmp.length() == total && saved != null && saved.size == n + 1 && saved[0] == total) {
            for (i in 0 until n) done.set(i, saved[i + 1].coerceIn(0, ends[i] - starts[i]))
        } else {
            tmp.delete()
            java.io.RandomAccessFile(tmp, "rw").use { it.setLength(total) }
        }
        fun save() = runCatching { parts.writeText("$total " + (0 until n).joinToString(" ") { done.get(it).toString() }) }
        val failure = java.util.concurrent.atomic.AtomicReference<IOException?>(null)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(n)
        for (i in 0 until n) pool.execute {
            var tries = 0
            java.io.RandomAccessFile(tmp, "rw").use { raf ->
                while (failure.get() == null) {
                    val pos = starts[i] + done.get(i)
                    if (pos >= ends[i]) break
                    val c = connect(url, "$pos-${ends[i] - 1}")
                    try {
                        if (c.responseCode != 206) throw IOException("HTTP ${c.responseCode} for a range of $url")
                        c.inputStream.use { input ->
                            val buf = ByteArray(1 shl 18)
                            var at = pos
                            while (at < ends[i]) {
                                val r = input.read(buf, 0, minOf(buf.size.toLong(), ends[i] - at).toInt())
                                if (r < 0) break
                                raf.seek(at); raf.write(buf, 0, r)
                                at += r
                                done.addAndGet(i, r.toLong())
                            }
                        }
                        tries = 0
                    } catch (e: IOException) {
                        // A dropped connection: try this range again from where it stopped.
                        if (++tries > 8) { failure.compareAndSet(null, e); break }
                        Thread.sleep(minOf(30_000L, 1_000L shl tries))
                    } finally {
                        c.disconnect()
                    }
                }
            }
        }
        pool.shutdown()
        var lastSave = 0L
        while (!pool.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            var sum = 0L
            for (i in 0 until n) sum += done.get(i)
            progress(sum, total)
            val now = System.currentTimeMillis()
            if (now - lastSave > 2_000) { save(); lastSave = now }
        }
        save()
        failure.get()?.let { throw it }
        var sum = 0L
        for (i in 0 until n) sum += done.get(i)
        if (sum != total) throw IOException("download stopped short (${sum shr 20} of ${total shr 20} MB); it continues next time")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val r = input.read(buf); if (r < 0) break; md.update(buf, 0, r) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Files this big or bigger download in ranges over several connections. */
        private const val SEGMENTED_FROM = 16L shl 20
        private const val SEGMENT_MIN = 8L shl 20
        private const val CONNECTIONS = 8
        /** Checked 2026-11-22: serves <gameId>-<ms>.zip (download.unstable.life mirrors it). */
        const val DEFAULT_GAME_DATA_SOURCE = "https://download.flashpointarchive.org/gib-roms/Games/"
        const val MIRROR_GAME_DATA_SOURCE = "https://download.unstable.life/gib-roms/Games/"
        /** Flashpoint's metadata server (FPFSS), what the launcher syncs from. */
        const val METADATA_BASE = "https://fpfss.flashpointarchive.org"
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
