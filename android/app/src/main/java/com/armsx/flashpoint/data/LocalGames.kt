package com.armsx.flashpoint.data

import android.content.Context
import android.net.Uri
import com.armsx.flashpoint.core.Game
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * My Games: Flash games players add themselves, from their own .swf files, outside Flashpoint's
 * database. Each file is copied to `Legacy/htdocs/armflash.local/<id>.swf`, where the game
 * server serves it as `http://armflash.local/<id>.swf`, so it plays like any Flash game in the
 * library (Ruffle or Flash Player, the pause menu, per-game settings). The list lives next to
 * the database in `Data/MyGames.json`, so both move with the Flashpoint folder.
 */
class LocalGames(private val library: Library) {
    @Serializable
    data class Entry(
        val id: String,
        val title: String,
        /** The name the file had when it was added. */
        val fileName: String = "",
        val added: Long = 0,
        val lastPlayed: Long = 0,
        val playtime: Long = 0,
        val plays: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val dir get() = File(library.htdocs, HOST)
    private val listFile get() = File(library.root, "Data/MyGames.json")

    @Synchronized
    fun all(): List<Entry> = runCatching {
        json.decodeFromString(ListSerializer(Entry.serializer()), listFile.readText())
    }.getOrDefault(emptyList()).filter { file(it.id).isFile }

    @Synchronized
    private fun save(list: List<Entry>) {
        listFile.parentFile?.mkdirs()
        val tmp = File(listFile.path + ".tmp")
        tmp.writeText(json.encodeToString(ListSerializer(Entry.serializer()), list))
        tmp.renameTo(listFile)
    }

    fun file(id: String) = File(dir, "$id.swf")

    fun entry(id: String) = all().firstOrNull { it.id == id }

    /**
     * Add the .swf at [uri], named [displayName] (its file name). The same file added twice is
     * one game. Throws [NotASwf] when the file is not a Flash movie.
     */
    @Synchronized
    fun import(ctx: Context, uri: Uri, displayName: String?): Entry {
        dir.mkdirs()
        val tmp = File(dir, ".import-${System.nanoTime()}.part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw java.io.IOException("cannot open the file")
            input.use { ins ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var first = true
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        if (first) {
                            if (!isSwf(buf, n)) throw NotASwf()
                            first = false
                        }
                        md.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                    if (first) throw NotASwf()
                }
            }
            val id = PREFIX + md.digest().take(8).joinToString("") { "%02x".format(it) }
            val list = all()
            list.firstOrNull { it.id == id }?.let { tmp.delete(); return it }
            val name = displayName?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "$id.swf"
            if (!tmp.renameTo(file(id))) throw java.io.IOException("cannot save the file")
            val e = Entry(id, titleFrom(name), name, added = System.currentTimeMillis())
            save(list + e)
            return e
        } finally {
            tmp.delete()
        }
    }

    @Synchronized
    fun remove(id: String) {
        save(all().filterNot { it.id == id })
        file(id).delete()
    }

    @Synchronized
    fun rename(id: String, title: String) {
        val t = title.trim().takeIf { it.isNotEmpty() } ?: return
        save(all().map { if (it.id == id) it.copy(title = t) else it })
    }

    /** A game started now. */
    @Synchronized
    fun started(id: String) = save(all().map { if (it.id == id) it.copy(lastPlayed = System.currentTimeMillis(), plays = it.plays + 1) else it })

    @Synchronized
    fun played(id: String, seconds: Long) = save(all().map { if (it.id == id) it.copy(playtime = it.playtime + seconds) else it })

    /** The game as the library shows and launches it: a Flash game at its local address. */
    fun game(e: Entry) = Game(
        id = e.id,
        title = e.title,
        library = "arcade",
        primaryPlatform = "Flash",
        platforms = "Flash",
        source = "My Games",
        notes = if (e.fileName.isNotBlank()) "Added from ${e.fileName}" else "",
        legacyLaunchCommand = "http://$HOST/${e.id}.swf",
        lastPlayed = e.lastPlayed.takeIf { it > 0 }?.let { java.time.Instant.ofEpochMilli(it).toString() },
        playtime = e.playtime,
        playCounter = e.plays,
    )

    fun game(id: String) = entry(id)?.let(::game)

    class NotASwf : Exception("That is not a Flash game (.swf) file.")

    companion object {
        /** The address imported games are served under. */
        const val HOST = "armflash.local"
        const val PREFIX = "local-"

        fun isLocal(id: String) = id.startsWith(PREFIX)

        /** A Flash movie: FWS (plain), CWS (zlib) or ZWS (LZMA). */
        fun isSwf(head: ByteArray, n: Int = head.size) =
            n >= 3 && head[1] == 'W'.code.toByte() && head[2] == 'S'.code.toByte() &&
                (head[0] == 'F'.code.toByte() || head[0] == 'C'.code.toByte() || head[0] == 'Z'.code.toByte())

        /** "my_cool-game.swf" -> "my cool-game". */
        fun titleFrom(fileName: String) =
            fileName.substringAfterLast('/').removeSuffix(".swf").removeSuffix(".SWF").replace('_', ' ').trim().ifEmpty { fileName }
    }
}
