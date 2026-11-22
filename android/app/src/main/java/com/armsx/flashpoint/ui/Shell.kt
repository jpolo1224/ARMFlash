package com.armsx.flashpoint.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
enum class Tab(val label: String) { HOME("Home"), NEWS("News"), GAMES("Games"), ANIMATIONS("Animations"), LOGS("Logs"), CONFIG("Config"), HELP("Help"), ABOUT("About") }

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

    Column(Modifier.fillMaxSize().background(Fp.Header)) {
        // The pad enters the tab row on the tab that is open, not whichever is nearest.
        val openTab = remember { androidx.compose.ui.focus.FocusRequester() }
        Row(
            Modifier.fillMaxWidth().background(Fp.Header)
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
                    Text(t.label, color = if (on) Color.White else Fp.TextDim, fontWeight = if (on) FontWeight.Black else FontWeight.Bold, fontSize = 15.sp)
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
    LaunchedEffect(Unit) { if (vm.randomPicks.isEmpty()) vm.rerollPicks() }
    BoxWithConstraints(Modifier.fillMaxSize().background(Fp.Browse)) {
        val wide = maxWidth >= 840.dp
        Column(Modifier.fillMaxSize().padVerticalScroll().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.fp_logo), null, Modifier.size(56.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("FlashARM", color = Color.White, fontWeight = FontWeight.Black, fontSize = 24.sp)
                    Text("${vm.dbGames} games and animations", color = Fp.TextDim, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            val left: @Composable () -> Unit = {
                HomeBox("Quick Start") {
                    QuickLink("Looking for something to play? View ", "All Games") { go(Tab.GAMES) }
                    QuickLink("Just want something to watch? View ", "All Animations") { go(Tab.ANIMATIONS) }
                    QuickLink("Want to change something? Go to ", "Config") { go(Tab.CONFIG) }
                    QuickLink("Need help? ", "Read the manual") { go(Tab.HELP) }
                }
                HomeBox("Extras") {
                    QuickLink("", "Favorites Playlist") { vm.pickShelf(Shelf.FAVORITES); go(Tab.GAMES) }
                    QuickLink("", "Play History") { vm.pickShelf(Shelf.HISTORY); go(Tab.GAMES) }
                    Text("Filter by platform", color = Fp.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        vm.platforms.take(24).forEach { p ->
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
            val right: @Composable () -> Unit = {
                vm.gotd?.let { g -> GotdBox(vm, g, onPlay) }
                HomeBox("Random Picks", action = "Re-Roll Picks" to { vm.rerollPicks() }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        vm.randomPicks.forEach { g -> MiniCard(vm, g) { vm.select(g); go(if (g.library == "theatre") Tab.ANIMATIONS else Tab.GAMES) } }
                    }
                }
            }
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
                Text("Game info last updated: ${vm.lastSync.take(10).ifBlank { "never" }}", color = Fp.Text, fontSize = 14.sp)
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
                Text(game.title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                Text("${g.date}  |  picked by ${g.author}", color = Fp.TextDim, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text(g.description, color = Fp.Text, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                Chip("Play", on = true) { vm.select(game); onPlay(game) }
            }
        }
    }
}

@Composable
private fun MiniCard(vm: BrowseVm, g: Game, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).padFocus().clickable(onClick = onClick).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Fp.Card)) {
            AsyncImage(vm.library.logoUrl(g.id), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
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
            Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
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
                    listOf("fpcore", "fpgame", "FlashARM", "AndroidRuntime", "chromium").any { it in l }
                }.takeLast(1000)
            }.getOrDefault(listOf("Could not read logs"))
        }
    }
    Column(Modifier.fillMaxSize().background(Fp.Browse).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Logs", color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Chip("Refresh", on = false) { tick++ }
            Spacer(Modifier.width(6.dp))
            val ctx = LocalContext.current
            Chip("Copy", on = false) {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("FlashARM logs", lines.joinToString("\n")))
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
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (desc.isNotEmpty()) Text(desc, color = Fp.TextDim, fontSize = 13.sp)
        }
        Box(Modifier.widthIn(max = 260.dp).padFocus()) { control() }
    }
}

/** News tab: FlashARM's GitHub releases with their pictures, as in ARMSX2/3. */
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
            Text("News", color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp, modifier = Modifier.weight(1f))
            Chip("Refresh", on = false) { force++ }
        }
        Spacer(Modifier.height(10.dp))
        val r = result
        when {
            loading && r == null -> Text("Loading release notes", color = Fp.TextDim)
            r == null || r.items.isEmpty() -> Text("No release notes yet. They appear here once FlashARM has a GitHub release.", color = Fp.TextDim)
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
