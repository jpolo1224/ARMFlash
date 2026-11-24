package com.armsx.flashpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.armsx.flashpoint.core.Game
import kotlinx.coroutines.launch

/**
 * The launcher's Games tab. Wide screens (tablets, landscape handhelds) get the desktop
 * three-pane layout; phones get the grid with the playlists in a drawer and the details
 * as a full-screen page.
 */
@Composable
fun BrowseScreen(vm: BrowseVm, onPlay: (Game) -> Unit, onConfig: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Fp.Browse)) {
        // Tablets and desktops-in-a-pocket get all three panes; landscape phones and
        // handhelds keep the details panel beside the grid and move playlists to a drawer.
        val sidebar = maxWidth >= 1100.dp
        val panel = maxWidth >= 720.dp
        if (panel) {
            val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                gesturesEnabled = !sidebar,
                drawerContent = {
                    if (!sidebar) ModalDrawerSheet(drawerContainerColor = Fp.Sidebar) {
                        Sidebar(vm, Modifier.fillMaxHeight().width(260.dp)) { scope.launch { drawer.close() } }
                    }
                },
            ) {
                Row(Modifier.fillMaxSize()) {
                    if (sidebar) Sidebar(vm, Modifier.width(230.dp).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        TopBar(vm, onMenu = if (sidebar) null else ({ scope.launch { drawer.open() } }), onConfig = onConfig)
                        Grid(vm, Modifier.weight(1f))
                    }
                    val sel = vm.selected
                    Box(Modifier.width(if (sidebar) 340.dp else 300.dp).fillMaxHeight().background(Fp.Panel)) {
                        if (sel != null) Details(vm, sel, onPlay, onClose = null)
                        else Text("Select a game", color = Fp.TextDim, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        } else {
            val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawer,
                drawerContent = {
                    ModalDrawerSheet(drawerContainerColor = Fp.Sidebar) {
                        Sidebar(vm, Modifier.fillMaxHeight().width(280.dp)) { scope.launch { drawer.close() } }
                    }
                },
            ) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(vm, onMenu = { scope.launch { drawer.open() } }, onConfig = onConfig)
                    Grid(vm, Modifier.weight(1f))
                }
                val sel = vm.selected
                if (sel != null) {
                    Box(Modifier.fillMaxSize().background(Fp.Panel)) {
                        Details(vm, sel, onPlay, onClose = { vm.clearSelection() })
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(vm: BrowseVm, modifier: Modifier, onPicked: () -> Unit = {}) {
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) vm.importPlaylists(uris) }
    val pls = vm.playlists.filter { it.library == vm.lib && (!it.extreme || vm.prefs2.extremeGames.value) }
    LazyColumn(modifier.background(Fp.Sidebar).padding(vertical = 8.dp)) {
        items(Shelf.entries.filter { vm.lib != "theatre" || it != Shelf.MY_GAMES }) { s ->
            SidebarRow(
                label = if (s == Shelf.ALL) (if (vm.lib == "theatre") "All Animations" else "All Games") else s.label,
                on = vm.playlist == null && vm.shelf == s,
            ) { vm.pickShelf(s); onPicked() }
        }
        if (pls.isNotEmpty()) item {
            Text("Playlists", color = Fp.TextDim, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp))
        }
        items(pls, key = { it.id }) { p ->
            SidebarRow(p.title, on = vm.playlist?.id == p.id, sub = p.author.takeIf { it.isNotBlank() }?.let { "by $it" }, icon = p.iconBytes) {
                vm.pickPlaylist(p); onPicked()
            }
        }
        item {
            SidebarRow("Import Playlist", on = false) { importer.launch(arrayOf("application/json", "*/*")) }
            if (vm.dbGames > 0) Text(
                "${"%,d".format(vm.dbGames)} games", color = Fp.TextDim, fontSize = 12.sp,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun SidebarRow(label: String, on: Boolean, sub: String? = null, icon: ByteArray? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padFocus(RoundedCornerShape(0.dp)).clickable(onClick = onClick)
            .background(if (on) Color(0x33FFFFFF) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            AsyncImage(icon, null, Modifier.size(22.dp).clip(RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(8.dp))
        }
        Column {
            Text(label, color = if (on) Fp.Strong else Fp.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
            if (sub != null) Text(sub, color = Fp.TextDim, fontSize = 11.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
        }
    }
}

@Composable
private fun TopBar(vm: BrowseVm, onMenu: (() -> Unit)?, onConfig: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Fp.Header).padding(horizontal = 8.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) IconButton(onClick = onMenu, modifier = Modifier.padFocus(RoundedCornerShape(20.dp))) { Icon(Icons.Filled.Menu, "Playlists", tint = Fp.Text) }
            // With a pad the search bar is a stop like any button: A starts typing, and moving
            // off it ends typing. Otherwise focus landing on the text field opens the keyboard
            // and swallows the D-pad. Touch taps straight into the field as before.
            var typing by remember { mutableStateOf(false) }
            var fieldFocused by remember { mutableStateOf(false) }
            val field = remember { androidx.compose.ui.focus.FocusRequester() }
            val pad = PadInput.active.value
            LaunchedEffect(typing) { if (typing) runCatching { field.requestFocus() } }
            Row(
                Modifier.weight(1f).height(40.dp).padFocus()
                    .then(if (pad && !typing) Modifier.clickable { typing = true } else Modifier)
                    .clip(RoundedCornerShape(6.dp)).background(Fp.Field).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Fp.TextDim, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (vm.query.isEmpty()) Text("Search...", color = Fp.TextDim, fontSize = 15.sp)
                    BasicTextField(
                        value = vm.query, onValueChange = vm::updateQuery, singleLine = true,
                        textStyle = TextStyle(color = Fp.Text, fontSize = 15.sp), cursorBrush = SolidColor(Fp.Text),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { typing = false }),
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(field)
                            .focusProperties { canFocus = !pad || typing }
                            .onFocusChanged {
                                if (it.isFocused) fieldFocused = true
                                else if (fieldFocused) { fieldFocused = false; typing = false }
                            },
                    )
                }
                if (vm.query.isNotEmpty()) {
                    Icon(Icons.Filled.Close, "Clear", tint = Fp.TextDim, modifier = Modifier.size(20.dp).padFocus(RoundedCornerShape(10.dp)).clickable { vm.updateQuery("") })
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // My Games: the player's own .swf files (any file type is offered: few file managers
        // know Flash's type; ARMFlash checks the file itself).
        val swfPicker = androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris -> if (uris.isNotEmpty()) vm.importSwfs(uris) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (vm.shelf == Shelf.MY_GAMES) item { Chip("Import .swf", true) { swfPicker.launch(arrayOf("*/*")) } }
            item { Chip(if (vm.shelf == Shelf.INSTALLED) "Installed ✓" else "Installed", vm.shelf == Shelf.INSTALLED) {
                vm.pickShelf(if (vm.shelf == Shelf.INSTALLED) Shelf.ALL else Shelf.INSTALLED)
            } }
            item {
                Picker(
                    label = vm.platform ?: "Platform",
                    on = vm.platform != null,
                    options = listOf<Pair<String?, String>>(null to "All platforms") + vm.platforms.map { it to it },
                ) { vm.pickPlatform(it) }
            }
            item {
                Picker(
                    label = BrowseVm.ORDERS.firstOrNull { it.first == vm.order }?.second ?: "Title",
                    on = false,
                    options = BrowseVm.ORDERS,
                ) { vm.pickOrder(it) }
            }
            item { Chip(if (vm.descending) "Descending" else "Ascending", false) { vm.toggleDirection() } }
            item {
                val total = vm.index?.total
                if (total != null) Text("Results: $total", color = Fp.TextDim, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}

@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padFocus(RoundedCornerShape(5.dp)).clip(RoundedCornerShape(5.dp))
            .background(if (on) Fp.Play else Color(0xFF3A3A3E))
            .border(1.dp, Color(0xFF555559), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
private fun <T> Picker(label: String, on: Boolean, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("$label ▾", on) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(v) })
            }
        }
    }
}

@Composable
private fun Grid(vm: BrowseVm, modifier: Modifier) {
    val total = (vm.index?.total ?: 0L).toInt()
    val state = rememberLazyGridState()
    LaunchedEffect(vm.index) { state.scrollToItem(0) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = state,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Keys include the search, so the cards already on screen are rebuilt for new results
        // instead of keeping the previous search's games until scrolled away.
        val searchKey = System.identityHashCode(vm.index)
        if (vm.shelf == Shelf.MY_GAMES && vm.playlist == null && total == 0 && vm.query.isEmpty()) {
            item(key = "mine-empty", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    "Your own Flash games go here. Press Import .swf above, or open a .swf file with ARMFlash from your file manager.",
                    color = Fp.TextDim, fontSize = 15.sp, modifier = Modifier.padding(8.dp),
                )
            }
        }
        if (vm.showFeatured) {
            item(key = "popular", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeader("Popular") }
            items(vm.featured.size, key = { "popular:${vm.featured[it].id}" }) { i ->
                val g = vm.featured[i]
                GameCard(vm, g, selected = g.id == vm.selected?.id) { vm.select(g) }
            }
            item(key = "all", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { GridHeader("All Games, A to Z") }
        }
        items(total, key = { "$searchKey:$it" }) { i ->
            val g = vm.gameAt(i)
            GameCard(vm, g, selected = g != null && g.id == vm.selected?.id) { if (g != null) vm.select(g) }
        }
    }
}

@Composable
private fun GridHeader(text: String) {
    Text(text, color = Fp.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun GameCard(vm: BrowseVm, g: Game?, selected: Boolean, onClick: () -> Unit) {
    var peek by remember(g?.id) { mutableStateOf(false) }
    Column(
        Modifier.padFocus().clip(RoundedCornerShape(6.dp))
            .background(if (selected) Fp.CardSelected.copy(alpha = 0.25f) else Color.Transparent)
            .combinedClickable(enabled = g != null, onClick = onClick, onLongClick = peekOnLongPress(vm, g) { peek = !peek })
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Fp.Card)) {
            if (g != null) {
                GameArt(vm, g, peek, Modifier.fillMaxSize(), localPadding = 28.dp)
                PlatformBadge(g.primaryPlatform, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            g?.title ?: "", color = Fp.Text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
            lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().height(36.dp),
        )
    }
}

/**
 * A game's picture on a card. Config, Screenshot Preview: Always shows the game's screenshot in
 * place of its logo, Long Press swaps a card between the two ([peek]). A game with no screenshot
 * keeps its logo, an Extreme game's screenshot stays hidden while Hide Extreme Screenshots is on,
 * and an imported game (My Games) shows its platform's picture.
 */
@Composable
fun GameArt(vm: BrowseVm, g: Game, peek: Boolean, modifier: Modifier, localPadding: androidx.compose.ui.unit.Dp) {
    val mode = vm.prefs2.screenshotPreviewMode.value
    var noShot by remember(g.id) { mutableStateOf(false) }
    val local = com.armsx.flashpoint.data.LocalGames.isLocal(g.id)
    val shot = !local && !noShot && (mode == "2" || mode == "1" && peek) &&
        !(vm.prefs2.hideExtremeScreenshots.value && com.armsx.flashpoint.core.Extreme.isExtreme(g))
    AsyncImage(
        model = when {
            local -> LogoSets.image(g.primaryPlatform)
            shot -> vm.library.screenshotUrl(g.id)
            else -> vm.library.logoUrl(g.id)
        },
        contentDescription = null,
        onError = { if (shot) noShot = true },
        contentScale = ContentScale.Fit,
        modifier = modifier.then(if (local) Modifier.padding(localPadding) else Modifier),
    )
}

/** What a long press on a card does: with Screenshot Preview on Long Press, [toggle] the screenshot. */
fun peekOnLongPress(vm: BrowseVm, g: Game?, toggle: () -> Unit): (() -> Unit)? =
    if (vm.prefs2.screenshotPreviewMode.value == "1" && g != null && !com.armsx.flashpoint.data.LocalGames.isLocal(g.id)) toggle else null

@Composable
fun PlatformBadge(platform: String, modifier: Modifier = Modifier) {
    if (platform.isBlank()) return
    LogoSets.image(platform)?.let { f ->
        AsyncImage(f, platform, modifier.size(26.dp), contentScale = ContentScale.Fit)
        return
    }
    Box(
        modifier.size(26.dp).clip(RoundedCornerShape(4.dp)).background(Fp.platformColor(platform)),
        contentAlignment = Alignment.Center,
    ) {
        Text(Fp.platformGlyph(platform), color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp)
    }
}
