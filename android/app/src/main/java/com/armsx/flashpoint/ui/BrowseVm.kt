package com.armsx.flashpoint.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.armsx.flashpoint.FpApp
import com.armsx.flashpoint.core.FpCore
import com.armsx.flashpoint.core.Game
import com.armsx.flashpoint.core.SearchIndex
import com.armsx.flashpoint.core.SearchOpts
import com.armsx.flashpoint.game.Launch
import com.armsx.flashpoint.game.LaunchPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Sidebar entries. Real Flashpoint playlists come later; these are the built-in ones. */
enum class Shelf(val label: String) { ALL("All"), INSTALLED("Installed"), FAVORITES("Favorites"), HISTORY("Play History") }

data class Download(val done: Long, val total: Long)

/** [winApp]/[winArgs]: the Windows program and arguments when the game runs under Wine. */
data class Ready(val game: Game, val plan: LaunchPlan, val zips: List<String>, val winApp: String? = null, val winArgs: String = "")

/** Flashpoint's usual Flash Player (most Flash games name it as their program). */
/** The most a Home row shows when opened in full. */
const val HOME_ALL = 500

/** Game data this big or bigger is asked about before downloading. */
const val BIG_DOWNLOAD = 300L shl 20

/** "8.8 GB", "640 MB", "12 KB". */
fun sizeText(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(maxOf(1, bytes shr 10))
}

const val DEFAULT_FLASH_PLAYER = "FPSoftware\\Flash\\flashplayer_32_sa.exe"

class BrowseVm(app: Application) : AndroidViewModel(app) {
    val library = (app as FpApp).library
    val prefs2 = AppPrefs(app)

    /** The library the launcher browses: Flashpoint's games ("arcade"). Its animations ("theatre") are left out. */
    var lib by mutableStateOf("arcade")
        private set
    var playlists by mutableStateOf<List<com.armsx.flashpoint.data.Playlist>>(emptyList())
        private set
    var playlist by mutableStateOf<com.armsx.flashpoint.data.Playlist?>(null)
        private set
    var randomPicks by mutableStateOf<List<Game>>(emptyList())
        private set
    var gotd by mutableStateOf<Gotd?>(null)
        private set
    private val prefs = app.getSharedPreferences("browse", Context.MODE_PRIVATE)

    private val statusState = mutableStateOf("")
    /** The launcher's status line; problems also go to the log, to find them afterwards. */
    var status: String
        get() = statusState.value
        set(v) { if (v.isNotEmpty()) android.util.Log.i("ARMFlash", "status: $v"); statusState.value = v }
    var dbReady by mutableStateOf(false)
    var dbGames by mutableStateOf(0)
    var importing by mutableStateOf<Long?>(null)
    var sync by mutableStateOf<com.armsx.flashpoint.core.SyncStatus?>(null)
        private set
    val lastSync: String get() = prefs.getString("lastSync", "")!!
    /** Time left in a database download, as shown under its progress bar ("" while unknown). */
    var syncEta by mutableStateOf("")
        private set

    var query by mutableStateOf("")
        private set
    var shelf by mutableStateOf(Shelf.ALL)
        private set
    var platform by mutableStateOf<String?>(null)
        private set
    var order by mutableStateOf(prefs.getString("order", "TITLE")!!)
        private set
    var descending by mutableStateOf(prefs.getBoolean("desc", false))
        private set

    /** The platform filter's choices: the platforms with entries in the current tab. */
    var platforms by mutableStateOf<List<String>>(emptyList())
        private set
    /** Home's "Filter by platform" (it opens the Games tab): the platforms with games. */
    var gamePlatforms by mutableStateOf<List<String>>(emptyList())
        private set
    /** Per library ("arcade"), under the current Extreme setting. */
    private var platformsFor = mapOf<String, List<String>>()
    var index by mutableStateOf<SearchIndex?>(null)
    private val pages = mutableStateMapOf<Int, List<Game>>()
    private val loading = HashSet<Int>()
    private var generation = 0
    private var searchJob: Job? = null

    /** A game a frontend or shortcut asked to play (its Flashpoint ID). */
    var pendingGame by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Game?>(null)
        private set
    var details by mutableStateOf<Game?>(null)
        private set
    val downloads = mutableStateMapOf<String, Download>()
    var favorites by mutableStateOf(prefs.getStringSet("favorites", emptySet())!!.toSet())
        private set

    init {
        if (library.hasDatabase()) openDatabase()
    }

    fun openDatabase() = viewModelScope.launch {
        status = "Opening database"
        try {
            dbGames = withContext(Dispatchers.IO) { FpCore.open(library.database.path) }
            loadPlatforms()
            loadFeatured()
            dbReady = true
            status = ""
            refresh(immediate = true)
            rerollPicks()
            loadHomeRows()
            loadGotd()
            loadPlaylists()
        } catch (e: Exception) {
            status = "Could not open the database: ${e.message}"
        }
    }

    fun importDatabase(uri: Uri) = viewModelScope.launch {
        importing = 0
        try {
            withContext(Dispatchers.IO) { library.importDatabase(uri) { importing = it } }
            importing = null
            openDatabase()
        } catch (e: Exception) {
            importing = null
            status = "Import failed: ${e.message}"
        }
    }

    /**
     * Download (or update) the game database from Flashpoint's metadata server, the same way
     * the desktop launcher syncs. A first run builds the whole database on the phone.
     */
    fun syncDatabase(fresh: Boolean = false) = viewModelScope.launch {
        if (sync?.running == true) return@launch
        try {
            withContext(Dispatchers.IO) {
                if (fresh || !library.hasDatabase()) {
                    library.database.parentFile!!.mkdirs()
                    listOf("", "-wal", "-shm").forEach { java.io.File(library.database.path + it).delete() }
                    prefs.edit().remove("lastSync").apply()
                }
                FpCore.open(library.database.path)
                FpCore.startSync(library.metadataBase, lastSync)
            }
            // The game list is nearly all of the work, so time left comes from its rate,
            // measured from when that phase began (earlier phases would skew it).
            var gamesStart = 0L
            var gamesDone0 = 0L
            syncEta = ""
            while (true) {
                val st = withContext(Dispatchers.IO) { FpCore.sync() }
                sync = st
                if (!st.running) break
                val now = android.os.SystemClock.elapsedRealtime()
                syncBytes = library.database.length() + java.io.File(library.database.path + "-wal").length()
                syncEta = when (st.phase) {
                    "Starting", "Counting updates", "Tags", "Platforms" -> "Working out the time left"
                    "Games" -> {
                        if (gamesStart == 0L) { gamesStart = now; gamesDone0 = st.done }
                        val did = st.done - gamesDone0
                        val secs = (now - gamesStart) / 1000.0
                        if (did <= 0 || secs < 3 || st.total <= 0) "Working out the time left"
                        else timeLeft(((st.total - st.done) * secs / did).toLong() + 20)
                    }
                    else -> "Almost done"
                }
                delay(500)
            }
            val st = sync
            if (st?.failure != null) {
                status = "Database download failed: ${st.failure}"
            } else {
                st?.lastUpdate?.let { prefs.edit().putString("lastSync", it).apply() }
                openDatabase()
            }
        } catch (e: Exception) {
            status = "Database download failed: ${e.message}"
        } finally {
            sync = sync?.copy(running = false)
        }
    }

    private fun timeLeft(secs: Long): String = when {
        secs < 60 -> "Less than a minute left"
        secs < 90 -> "About 1 minute left"
        secs < 3600 -> "About ${(secs + 30) / 60} minutes left"
        else -> "About ${secs / 3600} h ${(secs % 3600) / 60} min left"
    }

    /** Where the Flashpoint folder (database, games, Legacy files) can go, for first-run setup. */
    var locations by mutableStateOf<List<com.armsx.flashpoint.data.Library.Location>>(emptyList())
        private set
    var location by mutableStateOf(library.root)
        private set

    fun loadLocations() = viewModelScope.launch {
        locations = withContext(Dispatchers.IO) { library.locations() }
    }

    /** Database bytes written so far during a first download (it grows as the sync writes). */
    var syncBytes by mutableStateOf(0L)
        private set

    fun pickLocation(dir: java.io.File) {
        if (library.hasDatabase()) return
        library.useLocation(dir)
        location = library.root
        // The new folder gets Flashpoint's credits, logos and themes too.
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            com.armsx.flashpoint.data.Defaults.install(ctx, library.root)
            FpThemes.loadDesktop(library.root)
            withContext(Dispatchers.Main) { Fp.load(ctx); LogoSets.load(ctx) }
        }
    }

    fun cancelSync() = viewModelScope.launch(Dispatchers.IO) { runCatching { FpCore.cancelSync() } }

    fun updateQuery(q: String) { query = q; refresh() }
    fun pickShelf(s: Shelf) { shelf = s; playlist = null; refresh(immediate = true) }
    fun pickPlatform(p: String?) { platform = p; refresh(immediate = true) }
    fun pickOrder(o: String) { order = o; prefs.edit().putString("order", o).apply(); refresh(immediate = true) }
    fun toggleDirection() { descending = !descending; prefs.edit().putBoolean("desc", descending).apply(); refresh(immediate = true) }

    fun loadPlaylists() = viewModelScope.launch {
        playlists = withContext(Dispatchers.IO) {
            library.playlists.seed(getApplication())
            library.playlists.all()
        }
    }

    fun importPlaylists(uris: List<android.net.Uri>) = viewModelScope.launch {
        val n = withContext(Dispatchers.IO) { library.playlists.import(getApplication(), uris) }
        status = "Imported $n playlist${if (n == 1) "" else "s"}"
        loadPlaylists()
    }

    fun pickPlaylist(p: com.armsx.flashpoint.data.Playlist?) {
        playlist = p
        if (p != null) shelf = Shelf.ALL
        clearSelection()
        refresh(immediate = true)
    }

    fun refreshAll() {
        refresh(immediate = true)
        rerollPicks()
        loadPlatforms()
        loadFeatured()
    }

    fun showLibrary(l: String) {
        if (lib == l) return
        lib = l
        clearSelection()
        applyPlatforms()
        refresh(immediate = true)
    }

    /**
     * Each tab's platforms, counted with the search the grid runs (its tab and the Extreme
     * filter): a platform shows up only where it has entries. Flashpoint's platform list has
     * many that are only animations (Flick, Sizzler) or only games (MapGuide, ChemDraw), which
     * otherwise led to an empty grid in the other tab.
     */
    private fun loadPlatforms() = viewModelScope.launch {
        val bl = tagBlacklist()
        platformsFor = withContext(Dispatchers.IO) {
            listOf("arcade").associateWith { l ->
                runCatching { FpCore.platformsWithGames(SearchOpts(library = l, tagBlacklist = bl)) }
                    .getOrElse { FpCore.platformNames() }.filter { it.isNotBlank() }.sorted()
            }
        }
        applyPlatforms()
    }

    private fun applyPlatforms() {
        platforms = platformsFor[lib].orEmpty()
        gamePlatforms = platformsFor["arcade"].orEmpty()
        // A platform picked in the other tab that has nothing here: show the whole tab.
        if (platform != null && platforms.isNotEmpty() && platform !in platforms) {
            platform = null
            refresh(immediate = true)
        }
    }

    /** Tags hidden while "Show Extreme Filters" is off (the launcher's Extreme tag filter group). */
    private fun tagBlacklist(): List<String>? = if (prefs2.extremeGames.value) null else ExtremeTags.list.ifEmpty { null }

    fun rerollPicks() = viewModelScope.launch {
        val libs = setOf("arcade")
        randomPicks = withContext(Dispatchers.IO) {
            runCatching {
                libs.flatMap { FpCore.random(SearchOpts(library = it, tagBlacklist = tagBlacklist()), 6 / libs.size + 1) }.shuffled().take(6)
            }.getOrDefault(emptyList())
        }
    }

    /** Home's Recently Played and Installed rows: the newest few of each, games and animations together. */
    var recent by mutableStateOf<List<Game>>(emptyList())
        private set
    var installedGames by mutableStateOf<List<Game>>(emptyList())
        private set

    fun loadHomeRows() = viewModelScope.launch {
        val bl = tagBlacklist()
        recent = withContext(Dispatchers.IO) {
            runCatching { FpCore.page("", SearchOpts(played = true, order = "LASTPLAYED", descending = true, tagBlacklist = bl, library = "arcade"), 12) }.getOrDefault(emptyList())
        }
        installedGames = withContext(Dispatchers.IO) {
            runCatching { FpCore.page("", SearchOpts(installed = true, order = "LASTPLAYED", descending = true, tagBlacklist = bl, library = "arcade"), 12) }.getOrDefault(emptyList())
        }
    }

    /** Home's rows in full ("View All" opens them on Home itself, games and animations together). */
    var recentAll by mutableStateOf<List<Game>>(emptyList())
        private set
    var installedAll by mutableStateOf<List<Game>>(emptyList())
        private set

    fun loadHomeAll() = viewModelScope.launch {
        val bl = tagBlacklist()
        recentAll = withContext(Dispatchers.IO) {
            runCatching { FpCore.page("", SearchOpts(played = true, order = "LASTPLAYED", descending = true, tagBlacklist = bl, library = "arcade"), HOME_ALL) }.getOrDefault(emptyList())
        }
        installedAll = withContext(Dispatchers.IO) {
            runCatching { FpCore.page("", SearchOpts(installed = true, order = "LASTPLAYED", descending = true, tagBlacklist = bl, library = "arcade"), HOME_ALL) }.getOrDefault(emptyList())
        }
    }

    fun loadGotd() = viewModelScope.launch {
        // The newest Game of the Day that is a game (Flashpoint sometimes picks an animation).
        gotd = withContext(Dispatchers.IO) {
            Gotd.today()?.let { (_, past) ->
                past.asReversed().take(30).firstNotNullOfOrNull { entry ->
                    runCatching { FpCore.game(entry.id) }.getOrNull()?.takeIf { it.library == "arcade" }?.let { entry.copy(game = it) }
                }
            }
        }
    }

    private fun opts(): SearchOpts {
        val pl = playlist
        if (pl != null) {
            return SearchOpts(ids = pl.gameIds.ifEmpty { listOf("-") }, order = "CUSTOM", tagBlacklist = tagBlacklist(), platforms = platform?.let { listOf(it) })
        }
        return baseOpts()
    }

    private fun baseOpts() = SearchOpts(
        // Every shelf stays inside the games library: Installed lists installed games only.
        library = lib,
        tagBlacklist = tagBlacklist(),
        installed = if (shelf == Shelf.INSTALLED) true else null,
        order = if (shelf == Shelf.HISTORY) "LASTPLAYED" else order,
        descending = if (shelf == Shelf.HISTORY) true else descending,
        played = shelf == Shelf.HISTORY,
        platforms = platform?.let { listOf(it) },
        // An empty id list would mean "no filter"; an impossible id means "nothing".
        ids = if (shelf == Shelf.FAVORITES) favorites.toList().ifEmpty { listOf("-") } else null,
    )

    private fun refresh(immediate: Boolean = false) {
        if (!dbReady) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (!immediate) delay(220)
            val gen = ++generation
            val q = query
            val o = opts()
            // A newer keystroke cancels this search: that is not a failure, so it must not be caught.
            val idx = try {
                withContext(Dispatchers.IO) { FpCore.index(q, o, PAGE) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Exception) {
                status = "Search failed: ${e.message}"; return@launch
            }
            if (gen != generation) return@launch
            pages.clear()
            loading.clear()
            index = idx
            loadPage(0)
        }
    }

    /** The game at grid position [i], loading its page if needed. */
    fun gameAt(i: Int): Game? {
        val p = i / PAGE
        val page = pages[p]
        if (page == null) loadPage(p)
        return page?.getOrNull(i % PAGE)
    }

    private fun loadPage(p: Int) {
        val idx = index ?: return
        if (p in loading) return
        if (p > 0 && p - 1 >= idx.marks.size) return
        loading += p
        val gen = generation
        val o = opts().copy(after = if (p == 0) null else idx.marks[p - 1])
        val q = query
        viewModelScope.launch {
            val games = runCatching { withContext(Dispatchers.IO) { FpCore.page(q, o, PAGE) } }.getOrNull()
            if (gen != generation) return@launch
            if (games != null) pages[p] = games else loading -= p
        }
    }

    fun select(g: Game) {
        selected = g
        details = null
        viewModelScope.launch {
            val d = withContext(Dispatchers.IO) { runCatching { FpCore.game(g.id) }.getOrNull() }
            if (selected?.id == g.id) details = d
        }
    }

    fun clearSelection() { selected = null; details = null }

    fun toggleFavorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        prefs.edit().putStringSet("favorites", favorites).apply()
        if (shelf == Shelf.FAVORITES) refresh(immediate = true)
    }

    fun isInstalled(g: Game) = library.isInstalled(g)

    /**
     * Write a .armflash file for each game in [which] ("installed", "favorites" or "played") into
     * the folder [tree], for frontends to list and launch.
     */
    fun exportForFrontends(tree: android.net.Uri, which: String) = viewModelScope.launch {
        status = "Exporting…"
        val ctx = getApplication<Application>()
        val n = withContext(Dispatchers.IO) {
            runCatching {
                val games = when (which) {
                    "favorites" -> favorites.mapNotNull { runCatching { FpCore.game(it) }.getOrNull() }
                    "played" -> FpCore.page("", SearchOpts(played = true, order = "LASTPLAYED", descending = true), 5000)
                    else -> FpCore.page("", SearchOpts(installed = true), 20000)
                }
                val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, tree) ?: return@runCatching 0
                val existing = dir.listFiles().mapNotNull { it.name }.toSet()
                var written = 0
                games.forEach { g ->
                    val name = com.armsx.flashpoint.Frontend.fileName(g.title, g.primaryPlatform)
                    if (name in existing) return@forEach
                    val f = dir.createFile("application/octet-stream", name) ?: return@forEach
                    ctx.contentResolver.openOutputStream(f.uri)?.use {
                        it.write(com.armsx.flashpoint.Frontend.fileText(g.id, g.title, g.primaryPlatform).toByteArray())
                    }
                    written++
                }
                written
            }.getOrDefault(-1)
        }
        status = if (n < 0) "Export failed." else "Exported $n game${if (n == 1) "" else "s"} for frontends."
    }

    /** Remove an installed game or animation's data pack; it can be downloaded again any time. */
    fun uninstall(game: Game) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { library.uninstall(game) }
        status = if (ok) "Uninstalled ${game.title}" else "Could not remove ${game.title}"
        if (selected?.id == game.id) select(game)
        recentTick++
        if (shelf == Shelf.INSTALLED) refresh(immediate = true)
    }

    /** Download the active data pack if needed, then hand back everything the game needs. */
    /** A Flash game desktop Flashpoint runs in a page in its browser (Flashpoint Navigator, Basilisk). */
    fun isFlashPage(game: Game, plan: com.armsx.flashpoint.game.LaunchPlan) =
        plan.runner == com.armsx.flashpoint.game.Runner.WEB && game.primaryPlatform.equals("Flash", ignoreCase = true) &&
            com.armsx.flashpoint.win.SupportPacks.isWindowsProgram(game.applicationPath)

    /** The Games tab's popular titles ([FEATURED_GAMES]), as the database has them. */
    var featured by mutableStateOf<List<Game>>(emptyList())
        private set

    /** Popular titles lead the list: Games tab, All Games, no search, filter or custom sort. */
    val showFeatured: Boolean
        get() = lib == "arcade" && shelf == Shelf.ALL && playlist == null && platform == null &&
            query.isBlank() && order == "TITLE" && !descending && featured.isNotEmpty()

    private fun loadFeatured() = viewModelScope.launch {
        val bl = tagBlacklist()
        featured = withContext(Dispatchers.IO) {
            runCatching { FpCore.page("", SearchOpts(ids = FEATURED_GAMES, order = "CUSTOM", tagBlacklist = bl), FEATURED_GAMES.size) }.getOrDefault(emptyList())
        }
    }

    /** A game whose data is big enough to ask about before it downloads (see [prepare]). */
    data class BigDownload(val title: String, val bytes: Long, val free: Long, val go: () -> Unit)
    var bigDownload by mutableStateOf<BigDownload?>(null)
    private val bigOk = HashSet<String>()

    fun prepare(game: Game, onReady: (Ready) -> Unit): kotlinx.coroutines.Job = viewModelScope.launch {
        val plan = Launch.plan(game)
        if (!plan.runner.ready) {
            status = "${plan.runner.label} games are not playable on Android yet."
            return@launch
        }
        val gd = game.activeData
        val zips = mutableListOf<String>()
        if (gd != null) {
            var zip: File = library.zipFor(gd)
            if (!zip.isFile) {
                // Some games carry gigabytes (Happy Wheels with its archived levels is 8.8 GB):
                // say how big before starting. A download that was started and cut off resumes.
                if (gd.size >= BIG_DOWNLOAD && game.id !in bigOk && !library.hasPartial(gd)) {
                    val free = withContext(Dispatchers.IO) { library.games.apply { mkdirs() }.usableSpace }
                    bigDownload = BigDownload(game.title, gd.size, free) { bigOk += game.id; bigDownload = null; prepare(game, onReady) }
                    return@launch
                }
                downloads[game.id] = Download(0, gd.size)
                try {
                    zip = withContext(Dispatchers.IO) {
                        library.download(gd) { d, t -> downloads[game.id] = Download(d, t) }
                    }
                } catch (e: Exception) {
                    status = "Download failed: ${e.message}"
                    return@launch
                } finally {
                    downloads.remove(game.id)
                }
                if (selected?.id == game.id) select(game)
            }
            zips += zip.path
        }
        // Games whose online parts live in another game's pack (Happy Wheels HTML5's levels are
        // served by the Flash release's level server): mounted too when that pack is downloaded.
        COMPANION_PACKS[game.id]?.let { other ->
            withContext(Dispatchers.IO) { FpCore.game(other)?.activeData?.let { library.zipFor(it) } }
                ?.takeIf { it.isFile }?.let { zips += it.path }
        }
        // Windows players (and Flash Player for a Flash game set to it): the runtime, then the
        // game's support pack, downloaded the first time.
        val ctx = getApplication<Application>()
        val flashPage = isFlashPage(game, plan)
        val flashPlayer = (plan.runner == com.armsx.flashpoint.game.Runner.FLASH || flashPage) &&
            com.armsx.flashpoint.game.FlashPrefs.player(ctx, game.id, flashPage) == "flashplayer"
        val winApp = when {
            plan.runner == com.armsx.flashpoint.game.Runner.WINDOWS -> game.applicationPath
            // Its own browser with the Flash plugin, as on the desktop.
            flashPlayer && flashPage -> game.applicationPath
            flashPlayer -> game.applicationPath.takeIf { "flashplayer" in it.lowercase() } ?: DEFAULT_FLASH_PLAYER
            else -> null
        }
        if (winApp != null) {
            if (!com.armsx.flashpoint.win.WinRuntime(ctx).installed) {
                status = "This game needs Windows support: install it in Config, under Windows Players."
                return@launch
            }
            val packs = com.armsx.flashpoint.win.SupportPacks
            val drive = withContext(Dispatchers.IO) { packs.drive(ctx, library.root) }
            val missing = withContext(Dispatchers.IO) { packs.needed(ctx, winApp, game.primaryPlatform).filterNot { packs.installed(drive, it) } }
            for (id in missing) {
                downloads[game.id] = Download(0, 0)
                try {
                    withContext(Dispatchers.IO) { packs.install(drive, id) { d, t -> downloads[game.id] = Download(d, t) } }
                } catch (e: Exception) {
                    status = "Could not get ${id.removePrefix("supportpack-")}: ${e.message}"
                    return@launch
                } finally {
                    downloads.remove(game.id)
                }
            }
        }
        // PHP for the game server's PHP pages: unpacked here, off the main thread, the first time.
        withContext(Dispatchers.IO) { com.armsx.flashpoint.core.Php.ensure(ctx) }
        val args = if (flashPlayer && "flashplayer" !in game.applicationPath.lowercase()) plan.url else game.launchCommand
        onReady(Ready(game, plan, zips, winApp, args))
    }

    /** A game started: count the play and its date now, so it is in Play History even if it never returns cleanly. */
    fun recordPlay(game: Game) = viewModelScope.launch(Dispatchers.IO) {
        if (!tracks(game)) return@launch
        runCatching { FpCore.playtime(game.id, 0) }
    }

    fun addPlaytime(id: String, seconds: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (prefs2.enablePlaytimeTracking.value) runCatching { FpCore.playSeconds(id, seconds) }
        recentTick++
    }

    private fun tracks(game: Game) = prefs2.enablePlaytimeTracking.value &&
        (prefs2.enablePlaytimeTrackingExtreme.value || game.tags.split(';').none { it.trim() in ExtremeTags.list })

    /** Bumped when a game returns, so the Home rows reload. */
    var recentTick by mutableStateOf(0)
        private set

    companion object {
        /** Game id -> the game whose pack serves its online parts. */
        private val COMPANION_PACKS = mapOf(
            "b448cbec-8a0e-4abf-96cf-4a12b41b6818" to "2686de77-6b3c-4718-b57b-9ba01a8bf255", // Happy Wheels HTML5 -> Flash
        )
        const val PAGE = 120
        val ORDERS = listOf(
            "TITLE" to "Title", "DEVELOPER" to "Developer", "PUBLISHER" to "Publisher", "SERIES" to "Series",
            "PLATFORM" to "Platform", "DATEADDED" to "Date Added", "DATEMODIFIED" to "Date Modified",
            "RELEASEDATE" to "Release Date", "LASTPLAYED" to "Last Played", "PLAYTIME" to "Playtime",
        )
    }
}
