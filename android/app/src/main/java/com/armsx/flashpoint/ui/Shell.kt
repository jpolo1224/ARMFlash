package com.armsx.flashpoint.ui

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.fillMaxHeight
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.armsx.flashpoint.R
import com.armsx.flashpoint.core.Game

/** The desktop launcher's top tabs. Help opens the online manual, as on desktop. */
/** The launcher's tabs. Animations lists Flashpoint's Flash animations (its Theatre library). */
enum class Tab(val label: String) { HOME("Home"), NEWS("News"), GAMES("Games"), ANIMATIONS("Animations"), LOGS("Logs"), CONFIG("Config"), HELP("Help"), ABOUT("About") }

/** LB / RB on a controller: step through the launcher's tabs (set by MainActivity). */
object ShellNav {
    /** Bumped with each press; [dir] is the direction of the latest one. */
    val tick = androidx.compose.runtime.mutableIntStateOf(0)
    var dir = 1
    fun step(d: Int) { dir = d; tick.intValue++ }
}

const val MANUAL_URL = "https://flashpointproject.github.io/manual/"

@Composable
fun Shell(vm: BrowseVm, onPlay: (Game) -> Unit, configPage: @Composable () -> Unit, aboutPage: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(if (vm.prefs2.defaultOpeningPage.value == "games") Tab.GAMES else Tab.HOME) }
    fun go(t: Tab) {
        when (t) {
            Tab.HELP -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MANUAL_URL)))
            Tab.GAMES -> { vm.showLibrary("arcade"); tab = t }
            Tab.ANIMATIONS -> { vm.showLibrary("theatre"); tab = t }
            else -> tab = t
        }
    }
    androidx.activity.compose.BackHandler(enabled = tab != Tab.HOME && vm.selected == null) { tab = Tab.HOME }
    // Bumped by LB / RB, so the highlight follows to the newly opened tab.
    var padTabSwitch by remember { mutableStateOf(0) }
    // LB / RB: previous / next tab, wrapping round; Help (which opens the manual) is skipped.
    LaunchedEffect(ShellNav.tick.intValue) {
        if (ShellNav.tick.intValue == 0) return@LaunchedEffect
        val tabs = Tab.entries.filter { it != Tab.HELP }
        val i = tabs.indexOf(tab).coerceAtLeast(0)
        vm.clearSelection()
        padTabSwitch++
        go(tabs[(i + ShellNav.dir + tabs.size) % tabs.size])
    }

    Column(Modifier.fillMaxSize().background(Fp.Header)) {
        // The pad enters the tab row on the tab that is open, not whichever is nearest.
        val openTab = remember { androidx.compose.ui.focus.FocusRequester() }
        var rowFocused by remember { mutableStateOf(false) }
        // The controller highlight moves with the open tab (LB / RB, or a tab picked while the
        // highlight is in the tab row), instead of staying on the tab that was open before.
        LaunchedEffect(padTabSwitch) {
            if (padTabSwitch == 0) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
            runCatching { openTab.requestFocus() }
        }
        LaunchedEffect(tab) {
            if (!rowFocused) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
            runCatching { openTab.requestFocus() }
        }
        Row(
            Modifier.fillMaxWidth().background(Fp.Header)
                .onFocusChanged { rowFocused = it.hasFocus }
                .focusProperties { onEnter = { if (!openTab.requestFocus()) Unit } }
                .focusGroup()
                .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier.then(if (on) Modifier.focusRequester(openTab) else Modifier).padFocus().clickable { go(t) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t.label, color = if (on) Fp.Strong else Fp.TextDim, fontWeight = if (on) FontWeight.Black else FontWeight.Bold, fontSize = 15.sp)
                    Box(Modifier.padding(top = 3.dp).height(2.dp).width(if (on) 24.dp else 0.dp).background(Fp.Play))
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.HOME -> HomePage(vm, onPlay, go = ::go)
                Tab.GAMES, Tab.ANIMATIONS -> BrowseScreen(vm, onPlay, onConfig = { tab = Tab.CONFIG })
                Tab.NEWS -> NewsPage()
                Tab.LOGS -> LogsPage()
                Tab.CONFIG -> configPage()
                Tab.ABOUT -> aboutPage()
                Tab.HELP -> {}
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePage(vm: BrowseVm, onPlay: (Game) -> Unit, go: (Tab) -> Unit) {
    // A game picked on Home opens its details over Home: you stay on Home, and Back or the
    // arrow closes them.
    var picked by remember { mutableStateOf<Game?>(null) }
    fun open(g: Game) { vm.select(g); picked = g }
    picked?.let { g ->
        val close = { picked = null; vm.clearSelection() }
        androidx.compose.ui.window.Dialog(
            onDismissRequest = close,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                Modifier.fillMaxWidth(0.62f).widthIn(min = 340.dp).fillMaxHeight(0.94f)
                    .clip(RoundedCornerShape(8.dp)).background(Fp.Panel),
            ) {
                Details(vm, vm.selected ?: g, onPlay = { picked = null; onPlay(it) }, onClose = close)
            }
        }
    }
    LaunchedEffect(Unit) { if (vm.randomPicks.isEmpty()) vm.rerollPicks() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Fp.Browse)) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padVerticalScroll().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.fp_logo), null, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ARMFlash", color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 24.sp)
                    if (vm.dbGames > 0) Text("${"%,d".format(vm.dbGames)} games", color = Fp.TextDim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            var allRecent by remember { mutableStateOf(false) }
            var allInstalled by remember { mutableStateOf(false) }
            val left: @Composable () -> Unit = {
                HomeBox("Quick Start") {
                    QuickLink("Looking for something to play? View ", "All Games") { go(Tab.GAMES) }
                    QuickLink("Just want something to watch? View ", "All Animations") { go(Tab.ANIMATIONS) }
                    QuickLink("Want to change something? Go to ", "Config") { go(Tab.CONFIG) }
                    QuickLink("Need help? ", "Read the manual") { go(Tab.HELP) }
                }
                HomeBox("Extras") {
                    QuickLink("", "Favorites Playlist") { vm.pickShelf(Shelf.FAVORITES); go(Tab.GAMES) }
                    QuickLink("", "Play History") { allRecent = true; vm.loadHomeAll() }
                    Text("Filter by platform", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        vm.gamePlatforms.take(24).forEach { p ->
                            Row(
                                Modifier.padFocus(RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp)).background(Fp.Field).clickable { vm.pickShelf(Shelf.ALL); vm.pickPlatform(p); go(Tab.GAMES) }.padding(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlatformBadge(p, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(p, color = Fp.Text, fontSize = 12.sp)
                            }
                        }
                    }
                }
                UpdatesBox(vm)
                HomeBox("Notes") {
                    Text("Don't forget to read the help page or manual if you're having issues.", color = Fp.Text, fontSize = 14.sp)
                }
            }
            LaunchedEffect(vm.recentTick) { vm.loadHomeRows(); if (allRecent || allInstalled) vm.loadHomeAll() }
            // "View All" opens a row in full here on Home, rather
            // than in a tab whose search then only looks inside that list.
            val rows: @Composable () -> Unit = {
                if (vm.recent.isNotEmpty()) GameRow(vm, "Recently Played", if (allRecent) vm.recentAll.ifEmpty { vm.recent } else vm.recent, allRecent,
                    onAll = { allRecent = !allRecent; if (allRecent) vm.loadHomeAll() }) { g -> open(g) }
                if (vm.installedGames.isNotEmpty()) GameRow(vm, "Installed", if (allInstalled) vm.installedAll.ifEmpty { vm.installedGames } else vm.installedGames, allInstalled,
                    onAll = { allInstalled = !allInstalled; if (allInstalled) vm.loadHomeAll() }) { g -> open(g) }
            }
            val right: @Composable () -> Unit = {
                vm.gotd?.let { g -> GotdBox(vm, g, onPlay) }
                HomeBox("Random Picks", action = "Re-Roll Picks" to { vm.rerollPicks() }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        vm.randomPicks.forEach { g -> MiniCard(vm, g) { open(g) } }
                    }
                }
            }
            rows()
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f)) { left() }
                    Column(Modifier.weight(1f)) { right() }
                }
            } else {
                right()
                left()
            }
        }
    }
}

@Composable
private fun UpdatesBox(vm: BrowseVm) {
    val s = vm.sync
    HomeBox("Updates") {
        when {
            s?.running == true -> {
                Text("${s.phase}: ${s.done} / ${s.total}", color = Fp.Text)
                if (vm.syncEta.isNotEmpty()) Text(vm.syncEta, color = Fp.TextDim, fontSize = 13.sp)
                if (s.total > 0) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp).background(Fp.Field)) {
                        Box(Modifier.fillMaxWidth((s.done.toFloat() / s.total).coerceIn(0f, 1f)).height(6.dp).background(Fp.Play))
                    }
                }
            }
            else -> {
                Text("Game info last checked: ${vm.lastChecked.ifBlank { "never" }}", color = Fp.Text, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Chip("Check for game info updates", on = true) { vm.syncDatabase() }
            }
        }
    }
}

@Composable
private fun GotdBox(vm: BrowseVm, g: Gotd, onPlay: (Game) -> Unit) {
    val game = g.game ?: return
    HomeBox("Game of the Day") {
        Row {
            AsyncImage(vm.library.logoUrl(game.id), null, Modifier.size(110.dp).clip(RoundedCornerShape(4.dp)).background(Fp.Card), contentScale = ContentScale.Fit)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(game.title, color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("${g.date}  |  picked by ${g.author}", color = Fp.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(g.description, color = Fp.Text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Chip("Play", on = true) { vm.select(game); onPlay(game) }
            }
        }
    }
}

/** A Home row of games that scrolls sideways, with View All opening the matching shelf. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GameRow(vm: BrowseVm, title: String, games: List<Game>, expanded: Boolean, onAll: () -> Unit, onPick: (Game) -> Unit) {
    HomeBox(title, action = (if (expanded) "Show Less" else "View All") to onAll) {
        if (expanded) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                games.forEach { g -> MiniCard(vm, g) { onPick(g) } }
            }
        } else {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(games.size) { i -> MiniCard(vm, games[i]) { onPick(games[i]) } }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun MiniCard(vm: BrowseVm, g: Game, onClick: () -> Unit) {
    var peek by remember(g.id) { mutableStateOf(false) }
    Column(
        Modifier.width(120.dp).padFocus()
            .combinedClickable(onClick = onClick, onLongClick = peekOnLongPress(vm, g) { peek = !peek })
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Fp.Card)) {
            GameArt(vm, g, peek, Modifier.fillMaxSize(), localPadding = 22.dp)
            PlatformBadge(g.primaryPlatform, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp))
        }
        Text(g.title, color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun HomeBox(title: String, action: Pair<String, () -> Unit>? = null, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(6.dp)).background(Fp.Panel).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
            action?.let { (label, f) -> Chip(label, on = false, onClick = f) }
        }
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun QuickLink(prefix: String, link: String, onClick: () -> Unit) {
    Row(Modifier.padding(vertical = 3.dp)) {
        if (prefix.isNotEmpty()) Text(prefix, color = Fp.Text, fontSize = 14.sp)
        Text(link, color = Fp.Accent, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padFocus(RoundedCornerShape(3.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp))
    }
}

/** Logs tab: this app's own log lines (launcher, game server, game pages). */
@Composable
private fun LogsPage() {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) {
        lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", "2000").redirectErrorStream(true).start()
                p.inputStream.bufferedReader().readLines().filter { l ->
                    listOf("fpcore", "fpgame", "fpruffle", "ARMFlash", "AndroidRuntime", "chromium").any { it in l }
                }.takeLast(1000)
            }.getOrDefault(listOf("Could not read logs"))
        }
    }
    Column(Modifier.fillMaxSize().background(Fp.Browse).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Chip("Refresh", on = false) { tick++ }
            Spacer(Modifier.width(6.dp))
            val ctx = LocalContext.current
            Chip("Copy", on = false) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ARMFlash logs", lines.joinToString("\n")))
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Fp.Field).verticalScroll(rememberScrollState()).padding(8.dp)) {
            lines.forEach { Text(it, color = Fp.Text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) }
        }
    }
}

/** A labelled setting row in the desktop Config style: title, description, control on the right. */
@Composable
fun ConfigRow(title: String, desc: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = Fp.Strong, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (desc.isNotEmpty()) Text(desc, color = Fp.TextDim, fontSize = 13.sp)
        }
        Box(Modifier.widthIn(max = 260.dp).padFocus()) { control() }
    }
}

/** News tab: ARMFlash's GitHub releases with their pictures, as in ARMSX2/3. */
@Composable
private fun NewsPage() {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<com.armsx.flashpoint.data.News.Result?>(null) }
    var loading by remember { mutableStateOf(true) }
    var force by remember { mutableStateOf(0) }
    LaunchedEffect(force) {
        loading = true
        result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.armsx.flashpoint.data.News.fetch(ctx, forceRefresh = force > 0)
        }
        loading = false
    }
    Column(Modifier.fillMaxSize().background(Fp.Browse).padVerticalScroll().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("News", color = Fp.Strong, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Chip("Refresh", on = false) { force++ }
        }
        Spacer(Modifier.height(10.dp))
        val r = result
        when {
            loading && r == null -> Text("Loading release notes", color = Fp.TextDim)
            r == null || r.items.isEmpty() -> Text("No release notes yet. They appear here once ARMFlash has a GitHub release.", color = Fp.TextDim)
            else -> {
                if (r.fromCache) Text("Offline: showing saved notes.", color = Fp.TextDim, fontSize = 12.sp)
                r.items.forEach { item -> ReleaseCard(item) }
            }
        }
    }
}

@Composable
private fun ReleaseCard(item: com.armsx.flashpoint.data.News.Item) {
    var open by remember { mutableStateOf(false) }
    HomeBox(item.title.ifBlank { item.tag }) {
        Text(item.published.take(10) + if (item.prerelease) "  |  pre-release" else "", color = Fp.TextDim, fontSize = 12.sp)
        if (item.credited.isNotEmpty()) {
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                item.credited.take(12).forEach { c ->
                    AsyncImage(c.avatar, c.login, Modifier.padding(end = 4.dp).size(24.dp).clip(RoundedCornerShape(12.dp)))
                }
            }
        }
        if (!open) {
            Text(item.notes.take(400), color = Fp.Text, fontSize = 14.sp)
        } else {
            item.blocks.forEach { b ->
                when (b) {
                    is com.armsx.flashpoint.data.News.Block.Text -> Text(b.text, color = Fp.Text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))
                    is com.armsx.flashpoint.data.News.Block.Image -> AsyncImage(
                        b.url, null,
                        Modifier.fillMaxWidth().aspectRatio(b.aspectRatio.coerceIn(0.3f, 4f)).padding(vertical = 4.dp).clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Chip(if (open) "Show less" else "Show more", on = false) { open = !open }
    }
}
