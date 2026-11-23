package com.armsx.flashpoint.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement

/** A game as flashpoint-archive serializes it (snake_case fields). */
@Serializable
data class Game(
    val id: String,
    val title: String = "",
    val library: String = "",
    @SerialName("alternate_titles") val alternateTitles: String = "",
    val series: String = "",
    val developer: String = "",
    val publisher: String = "",
    @SerialName("primary_platform") val primaryPlatform: String = "",
    val platforms: String = "",
    @SerialName("date_added") val dateAdded: String = "",
    @SerialName("date_modified") val dateModified: String = "",
    @SerialName("play_mode") val playMode: String = "",
    val status: String = "",
    val notes: String = "",
    val tags: String = "",
    val source: String = "",
    @SerialName("legacy_application_path") val legacyApplicationPath: String = "",
    @SerialName("legacy_launch_command") val legacyLaunchCommand: String = "",
    @SerialName("release_date") val releaseDate: String = "",
    val version: String = "",
    @SerialName("original_description") val originalDescription: String = "",
    val language: String = "",
    @SerialName("active_data_id") val activeDataId: Long? = null,
    @SerialName("active_data_on_disk") val activeDataOnDisk: Boolean = false,
    @SerialName("last_played") val lastPlayed: String? = null,
    val playtime: Long = 0,
    @SerialName("play_counter") val playCounter: Long = 0,
    @SerialName("game_data") val gameData: List<GameData>? = null,
    @SerialName("ruffle_support") val ruffleSupport: String = "",
) {
    val tagList: List<String> get() = tags.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    val activeData: GameData? get() = gameData?.firstOrNull { it.id == activeDataId } ?: gameData?.maxByOrNull { it.dateAdded }

    /** What to run: the active data pack's launch info, else the legacy fields. */
    val applicationPath: String get() = activeData?.applicationPath?.takeIf { it.isNotBlank() } ?: legacyApplicationPath
    val launchCommand: String get() = activeData?.launchCommand?.takeIf { it.isNotBlank() } ?: legacyLaunchCommand
}

@Serializable
data class GameData(
    val id: Long,
    @SerialName("game_id") val gameId: String,
    val title: String = "",
    @SerialName("date_added") val dateAdded: String = "",
    val sha256: String = "",
    @SerialName("present_on_disk") val presentOnDisk: Boolean = false,
    val path: String? = null,
    val size: Long = 0,
    val parameters: String? = null,
    @SerialName("application_path") val applicationPath: String = "",
    @SerialName("launch_command") val launchCommand: String = "",
)

/** The platforms ARMFlash offers: games on any other platform stay out of every list. */
val OFFERED_PLATFORMS = listOf("Flash", "HTML5", "Java", "Shockwave", "PopCap Plugin")

@Serializable
data class SearchOpts(
    val installed: Boolean? = null,
    val order: String = "TITLE",
    val descending: Boolean = false,
    val platforms: List<String>? = null,
    val ids: List<String>? = null,
    val tagBlacklist: List<String>? = null,
    /** Games the tag filter also hides (set from [Extreme.ids] by [FpCore]). */
    val hideIds: List<String>? = null,
    val library: String? = null,
    val played: Boolean = false,
    val after: PageMark? = null,
)

@Serializable
data class PageMark(val value: JsonElement, val title: String, val id: String)

@Serializable
data class SearchIndex(val total: Long, val pageSize: Int, val marks: List<PageMark>)

internal val GameListSerializer: KSerializer<List<Game>> = ListSerializer(Game.serializer())
internal val StringListSerializer: KSerializer<List<String>> = ListSerializer(String.serializer())

@Serializable
data class SyncStatus(
    val running: Boolean = false,
    val phase: String = "",
    val done: Long = 0,
    val total: Long = 0,
    val failure: String? = null,
    val lastUpdate: String? = null,
)
