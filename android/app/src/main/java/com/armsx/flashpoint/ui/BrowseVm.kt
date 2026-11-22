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

data class Ready(val game: Game, val plan: LaunchPlan, val zips: List<String>)

class BrowseVm(app: Application) : AndroidViewModel(app) {
    val library = (app as FpApp).library
    val prefs2 = AppPrefs(app)

    /** Which browse tab: "arcade" (Games) or "theatre" (Animations). */
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

    var status by mutableStateOf("")
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

    var platforms by mutableStateOf<List<String>>(emptyList())
    var index by mutableStateOf<SearchIndex?>(null)
    private val pages = mutableStateMapOf<Int, List<Game>>()
    private val loading = HashSet<Int>()
    private var generation = 0
    private var searchJob: Job? = null

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
            platforms = withContext(Dispatchers.IO) { FpCore.platformNames().filter { it.isNotBlank() }.sorted() }
            dbReady = true
            status = ""
            refresh(immediate = true)
            rerollPicks()
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

    fun pickLocation(dir: java.io.File) {
        if (library.hasDatabase()) return
        library.useLocation(dir)
        location = library.root
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
    }

    fun showLibrary(l: String) {
        if (lib == l) return
        lib = l
        clearSelection()
        refresh(immediate = true)
    }

    /** Tags hidden while "Show Extreme Filters" is off (the launcher's Extreme tag filter group). */
    private fun tagBlacklist(): List<String>? = if (prefs2.extremeGames.value) null else ExtremeTags.list.ifEmpty { null }

    fun rerollPicks() = viewModelScope.launch {
        val libs = prefs2.randomLibraries.value.ifEmpty { setOf("arcade") }
        randomPicks = withContext(Dispatchers.IO) {
            runCatching {
                libs.flatMap { FpCore.random(SearchOpts(library = it, tagBlacklist = tagBlacklist()), 6 / libs.size + 1) }.shuffled().take(6)
            }.getOrDefault(emptyList())
        }
    }

    fun loadGotd() = viewModelScope.launch {
        gotd = withContext(Dispatchers.IO) { Gotd.today()?.let { (entry, _) -> runCatching { FpCore.game(entry.id) }.getOrNull()?.let { entry.copy(game = it) } } }
    }

    private fun opts(): SearchOpts {
        val pl = playlist
        if (pl != null) {
            return SearchOpts(ids = pl.gameIds.ifEmpty { listOf("-") }, order = "CUSTOM", tagBlacklist = tagBlacklist(), platforms = platform?.let { listOf(it) })
        }
        return baseOpts()
    }

    private fun baseOpts() = SearchOpts(
        library = if (shelf == Shelf.ALL) lib else null,
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
            val idx = runCatching { withContext(Dispatchers.IO) { FpCore.index(q, o, PAGE) } }
                .getOrElse { status = "Search failed: ${it.message}"; return@launch }
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

    /** Download the active data pack if needed, then hand back everything the game needs. */
    fun prepare(game: Game, onReady: (Ready) -> Unit) = viewModelScope.launch {
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
        onReady(Ready(game, plan, zips))
    }

    fun addPlaytime(id: String, seconds: Long) = viewModelScope.launch(Dispatchers.IO) {
        if (prefs2.enablePlaytimeTracking.value) runCatching { FpCore.playtime(id, seconds) }
    }

    companion object {
        const val PAGE = 120
        val ORDERS = listOf(
            "TITLE" to "Title", "DEVELOPER" to "Developer", "PUBLISHER" to "Publisher", "SERIES" to "Series",
            "PLATFORM" to "Platform", "DATEADDED" to "Date Added", "DATEMODIFIED" to "Date Modified",
            "RELEASEDATE" to "Release Date", "LASTPLAYED" to "Last Played", "PLAYTIME" to "Playtime",
        )
    }
}
