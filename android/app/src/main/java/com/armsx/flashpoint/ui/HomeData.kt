package com.armsx.flashpoint.ui

import com.armsx.flashpoint.core.FpCore
import com.armsx.flashpoint.core.Game
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Game of the Day, from the same feed the launcher reads (config.json `gotdUrl`). */
@Serializable
data class Gotd(
    val id: String,
    val author: String = "",
    val description: String = "",
    val date: String = "",
    @Transient val game: Game? = null,
) {
    @Serializable
    private data class Feed(val games: List<Gotd>)

    companion object {
        const val URL_ = "https://download.unstable.life/gotd.json"

        /** How long the kept feed is used before asking whether it changed. */
        private const val FRESH_MS = 6 * 60 * 60 * 1000L

        /** Today's pick: the latest entry dated today or earlier (later ones stay hidden, as on desktop). */
        fun today(): Pair<Gotd, List<Gotd>>? = try {
            val text = feedText() ?: throw java.io.IOException("no feed")
            val feed = FpCore.json.decodeFromString(Feed.serializer(), text)
            val now = LocalDate.now().toString()
            val past = feed.games.filter { it.date <= now }.sortedBy { it.date }
            past.lastOrNull()?.let { it to past }
        } catch (_: Exception) {
            null
        }

        /**
         * The feed, kept on the device: used as it is for a few hours, then asked for again only
         * if it changed (ETag / Last-Modified), so Flashpoint's server answers "not modified"
         * instead of sending the whole file on every start. Offline, the kept copy is used.
         */
        private fun feedText(): String? {
            val dir = com.armsx.flashpoint.FpApp.app?.filesDir
            val file = dir?.let { java.io.File(it, "gotd.json") }
            val meta = dir?.let { java.io.File(it, "gotd.meta") }
            val kept = file?.takeIf { it.isFile }
            if (kept != null && System.currentTimeMillis() - kept.lastModified() < FRESH_MS) return kept.readText()
            val (etag, modified) = meta?.takeIf { it.isFile }?.readLines().orEmpty().let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
            val conn = (URL(URL_).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "ARMFlash")
                if (kept != null) {
                    if (etag.isNotEmpty()) setRequestProperty("If-None-Match", etag)
                    if (modified.isNotEmpty()) setRequestProperty("If-Modified-Since", modified)
                }
            }
            return try {
                if (kept != null && conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    kept.setLastModified(System.currentTimeMillis())
                    return kept.readText()
                }
                val text = conn.inputStream.bufferedReader().readText()
                if (file != null && meta != null) runCatching {
                    val tmp = java.io.File(file.path + ".part")
                    tmp.writeText(text)
                    if (tmp.renameTo(file)) meta.writeText(conn.getHeaderField("ETag").orEmpty() + "\n" + conn.getHeaderField("Last-Modified").orEmpty())
                }
                text
            } catch (_: Exception) {
                kept?.readText()
            } finally {
                conn.disconnect()
            }
        }
    }
}
