package com.armsx.flashpoint.data

import android.content.Context
import android.net.Uri
import com.armsx.flashpoint.core.FpCore
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

/** A Flashpoint playlist file, as the launcher reads and writes them (one JSON file each in Data/Playlists). */
@Serializable
data class Playlist(
    val id: String,
    val title: String = "",
    val description: String = "",
    val author: String = "",
    val icon: String = "",
    val library: String = "arcade",
    val extreme: Boolean = false,
    val games: List<PlaylistGame> = emptyList(),
    @Transient val file: String = "",
) {
    val gameIds: List<String> get() = games.sortedBy { it.order ?: Int.MAX_VALUE }.map { it.gameId }

    /** The playlist icon (base64, sometimes with a data: prefix) as bytes for the image loader. */
    val iconBytes: ByteArray? by lazy {
        icon.takeIf { it.isNotBlank() }?.let { runCatching { android.util.Base64.decode(it.substringAfter(","), android.util.Base64.DEFAULT) }.getOrNull() }
    }
}

@Serializable
data class PlaylistGame(val gameId: String, val notes: String = "", val order: Int? = null)

class Playlists(private val dir: File) {
    init {
        dir.mkdirs()
    }

    fun all(): List<Playlist> = dir.listFiles { f -> f.extension.equals("json", true) }.orEmpty().mapNotNull { f ->
        runCatching { FpCore.json.decodeFromString(Playlist.serializer(), f.readText()).copy(file = f.path) }.getOrNull()
    }.sortedBy { it.title.lowercase() }

    /** Copy picked playlist files in; a playlist with the same id is replaced. Returns how many were added. */
    fun import(ctx: Context, uris: List<Uri>): Int {
        var n = 0
        uris.forEach { uri ->
            val text = ctx.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } ?: return@forEach
            val pl = runCatching { FpCore.json.decodeFromString(Playlist.serializer(), text) }.getOrNull() ?: return@forEach
            val safe = pl.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
            File(dir, "$safe.json").writeText(text)
            n++
        }
        return n
    }

    /** Bundled playlists (assets/playlists) are copied in on first run, once. */
    fun seed(ctx: Context) {
        val marker = File(dir, ".seeded")
        if (marker.exists()) return
        runCatching {
            ctx.assets.list("playlists")?.forEach { name ->
                val out = File(dir, name)
                if (!out.exists()) ctx.assets.open("playlists/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        marker.writeText("1")
    }

    fun delete(p: Playlist) {
        if (p.file.isNotBlank()) File(p.file).delete()
    }

    /** A new, empty playlist of the player's own in [library] ("arcade" or "theatre"). */
    fun create(title: String, library: String): Playlist =
        write(Playlist(id = java.util.UUID.randomUUID().toString(), title = title, library = library))

    /** [p] with [gameId] at its end (unchanged when it is already in it), saved. */
    fun add(p: Playlist, gameId: String): Playlist {
        if (p.games.any { it.gameId == gameId }) return p
        val next = (p.games.mapNotNull { it.order }.maxOrNull() ?: (p.games.size - 1)) + 1
        return write(p.copy(games = p.games + PlaylistGame(gameId, order = next)))
    }

    /** [p] without [gameId], saved. */
    fun remove(p: Playlist, gameId: String): Playlist = write(p.copy(games = p.games.filter { it.gameId != gameId }))

    /** Save [p] to its file (a new one named after its id when it has none), in Flashpoint's playlist format. */
    private fun write(p: Playlist): Playlist {
        val f = if (p.file.isNotBlank()) File(p.file) else File(dir, p.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
        val tmp = File(f.path + ".part")
        tmp.writeText(FpCore.json.encodeToString(Playlist.serializer(), p))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        return p.copy(file = f.path)
    }
}
