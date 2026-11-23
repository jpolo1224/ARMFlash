package com.armsx.flashpoint.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * libfpcore.so: Flashpoint's database library (flashpoint-archive, the one the desktop
 * launcher runs on) and the zip-streaming game server, both in Rust.
 *
 * Every call returns JSON; failures come back as {"error": "..."} and are thrown here as
 * [FpException]. Calls block, so make them off the main thread.
 */
object FpCore {
    init {
        System.loadLibrary("fpcore")
        init()
    }

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @JvmStatic private external fun init()
    @JvmStatic private external fun openDatabase(path: String): String
    @JvmStatic private external fun searchIndex(input: String, opts: String, pageSize: Int): String
    @JvmStatic private external fun searchPage(input: String, opts: String, pageSize: Int): String
    @JvmStatic private external fun findGame(id: String): String
    @JvmStatic private external fun platforms(): String
    @JvmStatic private external fun platformsWithGames(opts: String): String
    @JvmStatic private external fun markDataOnDisk(path: String, gameDataId: Long): String
    @JvmStatic private external fun markDataOffDisk(gameDataId: Long): String
    @JvmStatic private external fun unpackTarZst(src: String, dest: String): String
    @JvmStatic private external fun unpackZip(src: String, dest: String): String
    @JvmStatic private external fun unpackProgress(): String
    @JvmStatic private external fun shaderPresetParams(path: String): String
    @JvmStatic private external fun addPlaytime(id: String, seconds: Long): String

    @JvmStatic private external fun addSeconds(id: String, seconds: Long): String
    @JvmStatic private external fun proxyStart(htdocs: String, legacy: String, live: String, port: Int, cache: Boolean): String
    @JvmStatic private external fun proxyMount(zip: String): String
    @JvmStatic private external fun proxyPhp(loader: String, home: String, work: String): String
    @JvmStatic private external fun proxyUnmountAll(): String
    @JvmStatic private external fun syncStart(base: String, after: String): String
    @JvmStatic private external fun syncStatus(): String
    @JvmStatic private external fun syncCancel(): String
    @JvmStatic private external fun randomGames(opts: String, count: Int): String
    @JvmStatic private external fun optimize(): String
    @JvmStatic private external fun clearPlaytime(): String

    private fun check(raw: String): JsonElement {
        val el = json.parseToJsonElement(raw)
        if (el is JsonObject && el.containsKey("error")) {
            throw FpException(el["error"]?.jsonPrimitive?.contentOrNull ?: "unknown error")
        }
        return el
    }

    /** Opens and migrates flashpoint.sqlite. Returns the number of games. */
    fun open(path: String): Int =
        (check(openDatabase(path)) as JsonObject)["games"]!!.jsonPrimitive.int

    fun index(query: String, opts: SearchOpts, pageSize: Int): SearchIndex =
        json.decodeFromJsonElement(SearchIndex.serializer(), check(searchIndex(query, json.encodeToString(SearchOpts.serializer(), opts), pageSize)))

    fun page(query: String, opts: SearchOpts, pageSize: Int): List<Game> =
        json.decodeFromJsonElement(GameListSerializer, check(searchPage(query, json.encodeToString(SearchOpts.serializer(), opts), pageSize)))

    fun game(id: String): Game? {
        val el = check(findGame(id))
        return if (el is JsonObject) json.decodeFromJsonElement(Game.serializer(), el) else null
    }

    fun platformNames(): List<String> =
        json.decodeFromJsonElement(StringListSerializer, check(platforms()))

    /** The platforms that have games under [opts] (a tab and its filters). */
    fun platformsWithGames(opts: SearchOpts): List<String> =
        json.decodeFromJsonElement(StringListSerializer, check(platformsWithGames(json.encodeToString(SearchOpts.serializer(), opts))))

    fun markOnDisk(gameDataId: Long, path: String) {
        check(markDataOnDisk(path, gameDataId))
    }

    /** Unpack [src] (.zip) into [dest]; blocks. */
    fun unzip(src: String, dest: String) { check(unpackZip(src, dest)) }

    /** Unpack [src] (.tar.zst) into [dest]; blocks. */
    fun untarZst(src: String, dest: String) { check(unpackTarZst(src, dest)) }

    /** Bytes of the archive being unpacked read so far, and its size. */
    fun unpackDone(): Pair<Long, Long> {
        val o = check(unpackProgress()) as JsonObject
        return o["done"]!!.jsonPrimitive.content.toLong() to o["total"]!!.jsonPrimitive.content.toLong()
    }

    /** A shader preset's parameters as a JSON array, or null when it can't be read. */
    fun shaderParamsJson(path: String): String? =
        shaderPresetParams(path).takeIf { it.trimStart().startsWith("[") }

    fun markOffDisk(gameDataId: Long) {
        check(markDataOffDisk(gameDataId))
    }

    fun playtime(id: String, seconds: Long) {
        check(addPlaytime(id, seconds))
    }

    /** Add time played without counting another play (the play is counted at launch). */
    fun playSeconds(id: String, seconds: Long) {
        check(addSeconds(id, seconds))
    }

    /** Starts the game server (once per process). Returns its port. */
    /** [liveDomains]: the game's own sites the live web may be asked for (empty: off). */
    fun startServer(htdocs: String, legacyBase: String, cacheRemote: Boolean, liveDomains: List<String>, port: Int = 0): Int =
        (check(proxyStart(htdocs, legacyBase, liveDomains.joinToString(","), port, cacheRemote)) as JsonObject)["port"]!!.jsonPrimitive.int

    /** Let the game server run PHP pages with the PHP at [home], started by [loader]. */
    fun setPhp(loader: String, home: String, work: String) {
        check(proxyPhp(loader, home, work))
    }

    fun mount(zip: String) {
        check(proxyMount(zip))
    }

    fun unmountAll() {
        check(proxyUnmountAll())
    }

    fun startSync(base: String, after: String) {
        check(syncStart(base, after))
    }

    fun sync(): SyncStatus = json.decodeFromJsonElement(SyncStatus.serializer(), check(syncStatus()))

    fun random(opts: SearchOpts, count: Int): List<Game> =
        json.decodeFromJsonElement(GameListSerializer, check(randomGames(json.encodeToString(SearchOpts.serializer(), opts), count)))

    fun optimizeDatabase() {
        check(optimize())
    }

    fun clearPlaytimeData() {
        check(clearPlaytime())
    }

    fun cancelSync() {
        check(syncCancel())
    }
}

class FpException(message: String) : Exception(message)
