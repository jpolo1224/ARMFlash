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
enum class Shelf(val label: String) { ALL("All Games"), INSTALLED("Installed"), FAVORITES("Favorites") }

data class Download(val done: Long, val total: Long)

data class Ready(val game: Game, val plan: LaunchPlan, val zips: List<String>)

class BrowseVm(app: Application) : AndroidViewModel(app) {
    val library = (app as FpApp).library
    private val prefs = app.getSharedPreferences("browse", Context.MODE_PRIVATE)

    var status by mutableStateOf("")
    var dbReady by mutableStateOf(false)
    var dbGames by mutableStateOf(0)
    var importing by mutableStateOf<Long?>(null)

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

    fun updateQuery(q: String) { query = q; refresh() }
    fun pickShelf(s: Shelf) { shelf = s; refresh(immediate = true) }
    fun pickPlatform(p: String?) { platform = p; refresh(immediate = true) }
    fun pickOrder(o: String) { order = o; prefs.edit().putString("order", o).apply(); refresh(immediate = true) }
    fun toggleDirection() { descending = !descending; prefs.edit().putBoolean("desc", descending).apply(); refresh(immediate = true) }

    private fun opts() = SearchOpts(
        installed = if (shelf == Shelf.INSTALLED) true else null,
        order = order,
        descending = descending,
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
        runCatching { FpCore.playtime(id, seconds) }
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
