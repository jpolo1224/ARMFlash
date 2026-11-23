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

        /** Today's pick: the latest entry dated today or earlier (later ones stay hidden, as on desktop). */
        fun today(): Pair<Gotd, List<Gotd>>? {
            val conn = (URL(URL_).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "ARMFlash")
            }
            return try {
                val text = conn.inputStream.bufferedReader().readText()
                val feed = FpCore.json.decodeFromString(Feed.serializer(), text)
                val now = LocalDate.now().toString()
                val past = feed.games.filter { it.date <= now }.sortedBy { it.date }
                past.lastOrNull()?.let { it to past }
            } catch (_: Exception) {
                null
            } finally {
                conn.disconnect()
            }
        }
    }
}
