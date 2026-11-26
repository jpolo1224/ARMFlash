package com.armsx.flashpoint.game

import kotlin.math.roundToInt
import android.view.KeyEvent
import com.armsx.flashpoint.ui.sliderPadKeys
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The in-game quick menu: ARMSX2's pause menu (EmulationMenuScreen, ARMSX2 team), ported.
 *
 * Same layout and behaviour as ARMSX2's, in Flashpoint's colours: its type scale, the content
 * sheet and the separate tab rail as two floating 28 dp panels sliding in from the docked
 * edge over a 62% scrim, the compact single-sheet layout under 700 dp, and its header, action
 * grid, section cards, option chips, switch rows and cycle rows. What changed is what is in
 * it: Flashpoint's session actions, display modes, touch keys and controller mapping.
 */

enum class MenuTab(val label: String, val glyph: String) {
    SESSION("Session", "☰"),
    DISPLAY("Display", "🖥️"),
    CONTROLS("Controls", "🎮"),
    OPTIONS("Options", "⚙"),
    FRIENDS("Friends", "👥"),
}

private fun menuTabs(): List<MenuTab> = MenuTab.entries

// ---- colours: Flashpoint's (ARMFlash's launcher theme) in ARMSX2's menu ------------------

private val Success = Color(0xFF5CB531) // Flashpoint's Play green
private val Danger = Color(0xFFE0262B) // Flashpoint's red

private val NightScheme = darkColorScheme(
    primary = Color(0xFF6CC63F),
    onPrimary = Color(0xFF0E1A08),
    primaryContainer = Color(0xFF2F4A22),
    onPrimaryContainer = Color(0xFFE2F5D6),
    secondary = Color(0xFFF0A030),
    onSecondary = Color(0xFF1C1206),
    secondaryContainer = Color(0xFF48484D), // slider track, switch off-track
    onSecondaryContainer = Color(0xFFE6E6E8),
    tertiary = Color(0xFF7FCB4E),
    background = Color(0xFF1C1C1E),
    onBackground = Color(0xFFE6E6E8),
    surface = Color(0xFF2A2A2D),
    onSurface = Color(0xFFE6E6E8),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFFA0A0A6),
    outline = Color(0xFF55555B),
    outlineVariant = Color(0xFF55555B),
    error = Danger,
    scrim = Color.Black,
    surfaceTint = Color.Transparent,
)

private val ArmsTypography = Typography(
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
)

// ---- the menu ------------------------------------------------------------------------------

@Composable
fun QuickMenu(st: OverlayState, host: OverlayHost) {
    MaterialTheme(colorScheme = NightScheme, typography = ArmsTypography) {
        val scope = rememberCoroutineScope()
        var shown by remember { mutableStateOf(false) }
        var left by remember { mutableStateOf(host.menuLeft) }
        LaunchedEffect(Unit) { shown = true }
        val close: () -> Unit = {
            shown = false
            scope.launch { delay(220); st.menu = false }
        }
        androidx.activity.compose.BackHandler(onBack = close)

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 700.dp
            AnimatedVisibility(shown, enter = fadeIn(tween(190, easing = EaseOut)), exit = fadeOut(tween(190, easing = EaseIn))) {
                // A tap outside closes the menu; the scrim is never a focus stop for the pad.
                Box(
                    Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f))
                        .focusProperties { canFocus = false }
                        .clickable(onClick = close),
                )
            }
            val slideFrom: (Int) -> Int = if (left) { w -> -w } else { w -> w }
            AnimatedVisibility(
                shown,
                enter = slideInHorizontally(tween(320, easing = EaseOut), slideFrom),
                exit = slideOutHorizontally(tween(220, easing = EaseIn), slideFrom),
                modifier = Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd),
            ) {
                if (compact) {
                    Surface(
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.96f)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)),
                        shape = if (left) RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp) else RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                        shadowElevation = 22.dp,
                    ) { MenuPage(st, host, compact = true, left = left, onLeft = { left = it; host.menuLeft = it }, close = close) }
                } else {
                    Row(
                        Modifier.fillMaxHeight().fillMaxWidth(0.64f).widthIn(max = 900.dp)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical))
                            .padding(top = 14.dp, start = if (left) 12.dp else 0.dp, end = if (left) 0.dp else 12.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (left) Rail(st)
                        Surface(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                            shadowElevation = 22.dp,
                        ) { MenuPage(st, host, compact = false, left = left, onLeft = { left = it; host.menuLeft = it }, close = close) }
                        if (!left) Rail(st)
                    }
                }
            }
        }
    }
}

@Composable
private fun Rail(st: OverlayState) {
    Surface(
        modifier = Modifier.width(76.dp).fillMaxHeight(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        shadowElevation = 18.dp,
    ) { MenuRail(st.tab) { st.tab = it } }
}

@Composable
private fun MenuPage(st: OverlayState, host: OverlayHost, compact: Boolean, left: Boolean, onLeft: (Boolean) -> Unit, close: () -> Unit) {
    // With a pad, focus lands on the pane's first control when the menu opens or the tab changes.
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val view = androidx.compose.ui.platform.LocalView.current
    st.menuMemory.tab = st.tab
    // The pad's menu input (OverlayState.menuInput): move Compose focus directly, and if nothing
    // in the menu has focus (after a touch), land on the control last used instead.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    LaunchedEffect(Unit) {
        for (input in st.menuInput) {
            when (input) {
                is MenuInput.Move -> if (!focusManager.moveFocus(input.dir)) {
                    view.requestFocus()
                    runCatching { (st.menuMemory.restore(st.tab) ?: first).requestFocus() }
                }
                is MenuInput.Key -> view.dispatchKeyEvent(input.event)
            }
        }
    }
    // After a touch, Android uses the pad's first press to leave touch mode and never passes it
    // on; when that happens, put focus back on the control last used so the ring shows there.
    androidx.compose.runtime.DisposableEffect(view) {
        val l = android.view.ViewTreeObserver.OnTouchModeChangeListener { inTouch ->
            if (!inTouch) view.post { runCatching { (st.menuMemory.restore(st.tab) ?: first).requestFocus() } }
        }
        view.viewTreeObserver.addOnTouchModeChangeListener(l)
        onDispose { view.viewTreeObserver.removeOnTouchModeChangeListener(l) }
    }
    LaunchedEffect(st.tab) {
        if (!com.armsx.flashpoint.ui.PadInput.active.value) return@LaunchedEffect
        // The game view (a WebView after Resume) may hold Android focus; the menu's own view
        // must take it back, or the D-pad keeps going to the game behind the menu.
        view.requestFocus()
        repeat(3) {
            delay(60)
            // Back to the control last used on this tab, else the tab's first one.
            val target = st.menuMemory.restore(st.tab) ?: first
            if (runCatching { target.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFirstFocus provides first, LocalMenuMemory provides st.menuMemory) {
        MenuPageBody(st, host, compact, left, onLeft, close)
    }
}

@Composable
private fun MenuPageBody(st: OverlayState, host: OverlayHost, compact: Boolean, left: Boolean, onLeft: (Boolean) -> Unit, close: () -> Unit) {
    val scroll = st.menuMemory.scroll.getOrPut(st.tab) { androidx.compose.foundation.ScrollState(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = 18.dp)) {
        if (compact) CompactMenuTabs(st.tab) { st.tab = it }
        MenuHeader(host, compact)
        HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.34f))
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (st.tab) {
                MenuTab.SESSION -> SessionPane(st, host, close)
                MenuTab.DISPLAY -> DisplayPane(st, host)
                MenuTab.CONTROLS -> ControlsPane(st, host)
                MenuTab.OPTIONS -> OptionsPane(host, left, onLeft)
                MenuTab.FRIENDS -> FriendsPane()
            }
        }
    }
}

@Composable
private fun CompactMenuTabs(selected: MenuTab, onSelect: (MenuTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { menuTabs().forEach { MenuTabChip(it, it == selected, onSelect) } }
}

@Composable
private fun MenuRail(selected: MenuTab, onSelect: (MenuTab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        menuTabs().forEach { tab ->
            val active = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                modifier = Modifier.size(56.dp).menuFocus(RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                border = BorderStroke(
                    if (active) 2.dp else 1.dp,
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.34f),
                ),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        tab.glyph, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuTabChip(tab: MenuTab, active: Boolean, onSelect: (MenuTab) -> Unit) {
    Surface(
        onClick = { onSelect(tab) },
        modifier = Modifier.widthIn(min = 132.dp, max = 210.dp).padding(vertical = 3.dp).menuFocus(RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.56f),
        border = BorderStroke(
            if (active) 1.5.dp else 1.dp,
            if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.72f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
        ),
    ) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(9.dp),
                color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(tab.glyph, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun MenuHeader(host: OverlayHost, compact: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var logoOk by remember(host.logoUrl) { mutableStateOf(host.logoUrl.isNotBlank()) }
        if (logoOk) {
            AsyncImage(
                host.logoUrl, null, contentScale = ContentScale.Fit,
                onError = { logoOk = false },
                modifier = Modifier.size(if (compact) 46.dp else 54.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                host.title.ifBlank { "Flashpoint" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(host.runner, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusClock()
    }
}

/** The time and battery, top right of the menu, as ARMSX2's pause menu shows them. */
@Composable
private fun StatusClock() {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
            delay(15_000)
        }
    }
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 10.dp)) {
        Text(time, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        BatteryIndicator()
    }
}

// ---- panes ---------------------------------------------------------------------------------

private data class MenuAction(val title: String, val detail: String, val glyph: String, val accent: Color?, val action: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SessionPane(st: OverlayState, host: OverlayHost, close: () -> Unit) {
    ActionGrid(
        listOf(
            MenuAction("Resume", "Back to the game", "▶", Success, close),
            MenuAction("Restart", "Start the game over", "↻", null) { st.menu = false; host.restart() },
            MenuAction("Keyboard", "Type into the game", "⌨", null) { st.menu = false; host.keyboard() },
            MenuAction("Edit touch layout", "Move, resize, add or remove keys", "✎", null) {
                st.menu = false; st.editing = true; st.selected = null
            },
            MenuAction("Close", host.title, "■", Danger) { host.exit() },
        ),
    )
    MenuSwitchRow("Menu button: tap to reveal", st.layout.pauseTapToReveal) {
        st.layout = st.layout.copy(pauseTapToReveal = it); host.save(false)
    }
    SectionCard("Performance overlay") {
        // ARMSX2's overlay rows: the mode (Full / Minimal / Custom / Off), its colour and its corner.
        MenuCycleRow("Overlay", OsdPrefs.mode.value.label) { OsdPrefs.cycleMode() }
        if (OsdPrefs.mode.value == OsdPrefs.Mode.CUSTOM) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OsdPrefs.Stat.entries.forEach { s -> OptionChip(s.label, s in OsdPrefs.stats.value) { OsdPrefs.toggle(s) } }
            }
        }
        if (OsdPrefs.mode.value != OsdPrefs.Mode.OFF) {
            Spacer(Modifier.height(6.dp))
            MenuCycleRow("Overlay colour", OsdPrefs.Colors[OsdPrefs.color.value].first) { OsdPrefs.cycleColor() }
            Spacer(Modifier.height(6.dp))
            MenuCycleRow("Overlay position", OsdPrefs.corner.value.label) { OsdPrefs.cycleCorner() }
        }
    }
}

@Composable
private fun DisplayPane(st: OverlayState, host: OverlayHost) {
    fun set(scale: String = st.layout.scale, quality: String = st.layout.quality) {
        st.layout = st.layout.copy(scale = scale, quality = quality)
        host.save(false)
        host.display(scale, quality)
    }
    // The GPU driver first, as in Config.
    if (host.usesDriver) DriverCard(host)
    if (host.isFlash) RendererCard(host)
    if (host.hasShaders) ShaderCard(host)
    // Frame generation (the GitHub build's FrameGenUi; the Play build's draws nothing).
    if (host.hasFrameGen) FrameGenCard(host)
    if (!host.usesDriver) SectionCard("GPU driver") {
        Text(
            "Browser games draw through Android's built-in browser, which always uses the device's own driver. " +
                "Flash games and the other players can use a custom driver from here.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalOptions(
        "Scale",
        listOf("default" to "Game default", "fit" to "Fit", "stretch" to "Stretch", "fill" to "Fill", "original" to "Original size"),
        st.layout.scale,
        description = "How the game fills the screen. Fit keeps its shape with bars, Stretch fills the screen and " +
            "changes its shape, Fill keeps its shape and crops the edges. On a browser game, Original size shows the whole web page.",
        first = !host.usesDriver,
    ) { set(scale = it) }
    HorizontalOptions(
        "Edge smoothing",
        listOf("low" to "Off", "medium" to "Some", "high" to "Smooth", "best" to "Smoothest"),
        st.layout.quality,
        description = "Flash's quality setting: how much the edges of shapes and lines are smoothed. Off is the " +
            "fastest and looks jagged. Lower it if a game runs slowly.",
    ) { set(quality = it) }
}

/**
 * The GPU driver for this game: ARMSX2's in-game driver manager (DriverManagerSection, ARMSX2
 * team) ported into this menu. Shows the GPU and the recommended source, the driver in use,
 * the installed ones (pick or delete), downloads grouped by source, import from a zip, and
 * Apply & Restart, since a driver only loads when the renderer starts. Picks are per game;
 * "Same as Config" follows the global pick from Config > Graphics Driver.
 */
/** Flash's player and graphics API for this game, applied with a restart, like the driver. */
@Composable
private fun RendererCard(host: OverlayHost) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var player by remember { mutableStateOf(FlashPrefs.gamePlayer(ctx, host.gameId)) }
    var backend by remember { mutableStateOf(FlashPrefs.gameBackend(ctx, host.gameId)) }
    val startedWith = remember { player to backend }
    SectionCard("Renderer") {
        // Ruffle reported errors running this game: suggest the desktop's own player.
        val trouble = remember { host.flashTrouble() }
        if (trouble > 0 && FlashPrefs.player(ctx, host.gameId, host.isFlashPage) != "flashplayer") {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Ruffle ran into trouble with this game ($trouble ${if (trouble == 1L) "error" else "errors"}). " +
                        "If it misbehaves, set Player to Flash Player, the player desktop Flashpoint uses, then Apply & Restart.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(
            "For this game. Native runs Ruffle straight on the GPU; Browser runs its web version. " +
                "Graphics is what native Flash draws with.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // A page game has no native player, and its default is Flash Player (in its browser).
        val players = if (host.isFlashPage) FlashPrefs.players.filter { it.first != "native" }
            .map { if (it.first.isEmpty()) "" to "Default (Flash Player)" else it } else FlashPrefs.players
        HorizontalOptions("Player", players, player, onSelect = { player = it; FlashPrefs.setGamePlayer(ctx, host.gameId, it) })
        if (!host.isFlashPage && FlashPrefs.player(ctx, host.gameId) == "native") {
            Spacer(Modifier.height(6.dp))
            HorizontalOptions("Graphics", FlashPrefs.backends, backend, onSelect = { backend = it; FlashPrefs.setGameBackend(ctx, host.gameId, it) })
        }
        Spacer(Modifier.height(8.dp))
        ApplyRestart(changed = (player to backend) != startedWith) { host.restart() }
    }
}

@Composable
private fun DriverCard(host: OverlayHost) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val cd = com.armsx.flashpoint.data.CustomDriver
    val installed = remember { androidx.compose.runtime.mutableStateListOf<com.armsx.flashpoint.data.CustomDriver.InstalledDriver>().apply { addAll(cd.listInstalled(ctx)) } }
    var pick by remember { mutableStateOf(cd.gameId(host.gameId)) }
    var remote by remember { mutableStateOf<List<com.armsx.flashpoint.data.CustomDriver.RemoteDriver>?>(null) }
    var loadingRemote by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val expandedSources = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // What the game started with, so Apply & Restart only stands out once something changed.
    val startedWith = remember { pick }

    fun refreshInstalled() { installed.clear(); installed.addAll(cd.listInstalled(ctx)) }
    fun select(id: String) { pick = id; cd.selectForGame(host.gameId, id) }

    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            busyId = "import"; message = null
            val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { cd.installFromUri(ctx, uri) }
            busyId = null
            if (d != null) { refreshInstalled(); select(d.id); message = "Installed ${d.name}" } else message = "That file is not a driver pack"
        }
    }

    SectionCard("GPU driver") {
        Text("For this game. A driver loads when the game starts, so apply with a restart.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        val gpuModel = remember { com.armsx.flashpoint.data.GpuInfo.rendererName() }
        val gpuRec = remember(gpuModel) { com.armsx.flashpoint.data.GpuInfo.recommendation(gpuModel) }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("GPU", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(gpuModel ?: "Unknown", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recommended", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(gpuRec?.sourceLabel ?: "System driver", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                gpuRec?.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val globalName = installed.firstOrNull { it.id == cd.globalId() }?.name ?: "System driver"
            DriverRow("Same as Config", globalName, first = true, selected = pick.isEmpty() || (pick != cd.SYSTEM_FOR_GAME && installed.none { it.id == pick })) { select("") }
            DriverRow("System driver", "The device's own Vulkan driver", selected = pick == cd.SYSTEM_FOR_GAME) { select(cd.SYSTEM_FOR_GAME) }
            installed.forEach { d ->
                DriverRow(
                    d.name,
                    listOf(d.vendor, d.version).filter(String::isNotBlank).joinToString(" \u00B7 ").ifBlank { "Installed" },
                    selected = pick == d.id,
                    onDelete = { cd.delete(d); refreshInstalled(); if (pick == d.id) select("") },
                ) { select(d.id) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactButton(if (showRemote) "Hide downloads" else "Download") {
                    showRemote = !showRemote
                    if (showRemote && remote == null && !loadingRemote) {
                        loadingRemote = true
                        scope.launch {
                            remote = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { cd.fetchRemote(gpuModel) }.getOrDefault(emptyList()) }
                            loadingRemote = false
                        }
                    }
                }
                CompactButton("Import") { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
            }
            if (showRemote) {
                when {
                    loadingRemote -> Text("Fetching driver list", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    remote.isNullOrEmpty() -> Text("No drivers found for this GPU", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    else -> remote!!.groupBy { it.source }.forEach { (source, drivers) ->
                        val expanded = expandedSources[source] ?: false
                        MenuCycleRow(source, "${drivers.size} ${if (expanded) "\u25BE" else "\u25B8"}") { expandedSources[source] = !expanded }
                        if (expanded) drivers.forEach { rd ->
                            MenuCycleRow(
                                rd.title.ifBlank { rd.assetName },
                                if (busyId == rd.id) "Installing" else "Get",
                            ) {
                                if (busyId == null) scope.launch {
                                    busyId = rd.id; message = null
                                    val d = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { cd.download(ctx, rd) }.getOrNull() }
                                    busyId = null
                                    if (d != null) { refreshInstalled(); select(d.id); message = "Installed ${d.name}" } else message = "Download failed"
                                }
                            }
                        }
                    }
                }
            }
            if (busyId == "import") Text("Installing", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            ApplyRestart(changed = pick != startedWith) { host.restart() }
        }
    }
}

/** ARMSX2's driver row: name and detail, Active when it is the pick, Delete for installed ones. */
@Composable
private fun DriverRow(title: String, subtitle: String, selected: Boolean, first: Boolean = false, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val firstReq = if (first) LocalFirstFocus.current else null
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().then(if (firstReq != null) Modifier.focusRequester(firstReq) else Modifier).menuFocus(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected) Text("Active", style = MaterialTheme.typography.labelLarge, color = Success, fontWeight = FontWeight.Bold)
            if (onDelete != null) Surface(
                onClick = onDelete,
                modifier = Modifier.menuFocus(RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
            ) { Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
        }
    }
}

@Composable
private fun CompactButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.menuFocus(RoundedCornerShape(13.dp)),
        shape = RoundedCornerShape(13.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) }
}

/** ARMSX2's "Apply & Restart" action, full width; filled once there is something to apply. */
@Composable
private fun ApplyRestart(changed: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().menuFocus(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = if (changed) Success else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, if (changed) Success else MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("\u21BB", color = if (changed) Color.White else MaterialTheme.colorScheme.primary, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
            Text("Apply & Restart", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = if (changed) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ControlsPane(st: OverlayState, host: OverlayHost) {
    val cctx = androidx.compose.ui.platform.LocalContext.current
    HorizontalOptions("Cursor", Cursors.styles, Cursors.style.value, description = "The pointer shown when a controller, the touchpad or point & click moves the mouse.") {
        Cursors.setStyle(cctx, it)
    }
    HorizontalOptions("Cursor size", Cursors.sizes, Cursors.scale.floatValue) { Cursors.setSize(cctx, it) }
    HorizontalOptions("Pointer", listOf("direct" to "Direct touch", "touchpad" to "Touchpad", "point" to "Point & click"), st.layout.mode, first = true) {
        st.layout = st.layout.copy(mode = it)
        if (it == "direct") st.cursor = null
        host.save(false)
    }
    SectionCard("Touch keys") {
        Text("On-screen controls", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(6.dp))
        val mode = if (!st.layout.visible) "off" else st.layout.showMode
        Row(
            Modifier.fillMaxWidth().bleedHorizontal(13.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("auto" to "Auto", "always" to "Always", "off" to "Off", "timed" to "Auto-hide").forEach { (v, label) ->
                OptionChip(label, mode == v) { st.layout = st.layout.copy(showMode = v, visible = true); host.save(false) }
            }
        }
        val status = when (mode) {
            "auto" -> if (st.padConnected) "Hidden while you play with the controller, back when you touch the screen" else "Shown: no controller attached"
            "timed" -> "Hide ${st.layout.hideAfter} s after the last touch, back on the next touch"
            else -> null
        }
        if (status != null) {
            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (mode == "timed") {
            Spacer(Modifier.height(6.dp))
            MenuCycleRow("Hide after", "${st.layout.hideAfter} s") {
                st.layout = st.layout.copy(hideAfter = if (st.layout.hideAfter >= 10) 1 else st.layout.hideAfter + 1); host.save(false)
            }
        }
        Spacer(Modifier.height(6.dp))
        var op by remember { mutableFloatStateOf(st.layout.opacity) }
        Text("Opacity  ${(op * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Slider(
            op, { op = it; st.layout = st.layout.copy(opacity = it) }, valueRange = 0.15f..1f,
            onValueChangeFinished = { host.save(false) },
            modifier = Modifier.sliderPadKeys().menuFocus(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.height(2.dp))
        MenuCycleRow("Edit layout", "Open") { st.menu = false; st.editing = true; st.selected = null }
    }
    // ARMSX2's on-screen control options.
    SectionCard("Touch options") {
        Hint("For every game.")
        MenuSwitchRow("Multi-Touch", TouchPrefs.multiTouch.value) { TouchPrefs.multiTouch.value = it; TouchPrefs.save() }
        Hint("A key answers a little outside itself too, so one thumb can press two keys side by side, and a finger can slide from key to key.")
        if (TouchPrefs.multiTouch.value) {
            Spacer(Modifier.height(6.dp))
            PercentSlider("Multi-touch reach", (TouchPrefs.reach.floatValue * 100).roundToInt(), 50..95) { TouchPrefs.reach.floatValue = it / 100f }
            Hint("How far from a key's centre a touch still presses it. Higher reaches further, so keys side by side are easier to press together.")
            Spacer(Modifier.height(8.dp))
            Text("Glide", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.fillMaxWidth().bleedHorizontal(13.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TouchPrefs.Glide.entries.forEach { g ->
                    OptionChip(g.label, TouchPrefs.glide.value == g) { TouchPrefs.glide.value = g; TouchPrefs.save() }
                }
            }
            Hint(TouchPrefs.glide.value.help)
        }
        Spacer(Modifier.height(8.dp))
        MenuSwitchRow("Touch haptics", TouchPrefs.haptics.value) { TouchPrefs.haptics.value = it; TouchPrefs.save() }
        Hint("Vibrate briefly when you press an on-screen key.")
        if (TouchPrefs.haptics.value) {
            Spacer(Modifier.height(6.dp))
            PercentSlider("Vibration strength", TouchPrefs.hapticStrength.intValue, 0..200) { TouchPrefs.hapticStrength.intValue = it }
        }
        Spacer(Modifier.height(8.dp))
        PercentSlider("Stick travel", TouchPrefs.stickTravel.intValue, 50..200) { TouchPrefs.stickTravel.intValue = it }
        Hint("How far your thumb moves from the centre of a touch stick before it presses a direction, for every touch stick.")
        Spacer(Modifier.height(8.dp))
        MenuSwitchRow("Floating D-pad and stick", TouchPrefs.floatingStick.value) { TouchPrefs.floatingStick.value = it; TouchPrefs.save() }
        Hint("The D-pad and touch sticks centre themselves where your thumb lands, so you can grab them without looking.")
        Spacer(Modifier.height(8.dp))
        MenuSwitchRow("Half-screen stick", TouchPrefs.halfScreenStick.value) { TouchPrefs.halfScreenStick.value = it; TouchPrefs.save() }
        Hint("The left half of the screen, outside the keys, is a stick for the arrow keys: it appears wherever your thumb lands and steers from there. Touches there no longer reach the game itself.")
    }
    SectionCard("Gesture control") {
        Hint("Swipes and a double-tap on the game itself (not on a key) press a key, for keys that don't fit on screen. The game still gets the touch. With Pointer set to Touchpad or Point & click the screen moves the mouse, so gestures work with Direct touch.")
        MenuSwitchRow("Gesture control", TouchPrefs.gestures.value) { TouchPrefs.gestures.value = it; TouchPrefs.save() }
        if (TouchPrefs.gestures.value) {
            Spacer(Modifier.height(6.dp))
            TouchPrefs.gestureSlots.forEach { (name, state) ->
                MenuCycleRow(name, keyName(state.intValue.takeIf { it != 0 })) { st.gesturePick = name }
                Spacer(Modifier.height(6.dp))
            }
            Text("Double-tap mode", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OptionChip("Tap", !TouchPrefs.doubleTapHold.value) { TouchPrefs.doubleTapHold.value = false; TouchPrefs.save() }
                OptionChip("Hold", TouchPrefs.doubleTapHold.value) { TouchPrefs.doubleTapHold.value = true; TouchPrefs.save() }
            }
            Hint(if (TouchPrefs.doubleTapHold.value) "The key stays down until you double-tap again." else "A quick press, for something one-shot.")
            Spacer(Modifier.height(8.dp))
            PercentSlider("Swipe distance", (TouchPrefs.swipeDistance.floatValue * 100).roundToInt(), 5..40) { TouchPrefs.swipeDistance.floatValue = it / 100f }
            Hint("How far a finger travels to count as a swipe, as a part of the screen's shorter edge. Lower is more sensitive.")
        }
    }
    SectionCard("Controller") {
        // Local multiplayer (GitHub issue #1): a player per controller, each with its own keys.
        MenuSwitchRow("Local multiplayer", st.layout.multiplayer) {
            st.layout = st.layout.copy(multiplayer = it); host.save(false)
            if (!it) st.padPlayer = 1
        }
        Text(
            if (st.layout.multiplayer) "Each controller is its own player, in the order they connected, with buttons of its own. " +
                "Player 2 starts on WASD, player 3 on IJKL and player 4 on the number keys."
            else "Every controller is player 1. Turn this on for games two or more people play at once.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        val who = if (st.layout.multiplayer) st.padPlayer else 1
        if (st.layout.multiplayer) {
            MenuCycleRow("Buttons for", "Player $who") { st.padPlayer = st.padPlayer % 4 + 1 }
            Spacer(Modifier.height(6.dp))
        }
        if (who == 1) {
            MenuCycleRow("Mouse cursor", if (st.layout.mouseStick == "left") "Left stick" else "Right stick") {
                st.layout = st.layout.copy(mouseStick = if (st.layout.mouseStick == "left") "right" else "left"); host.save(false)
            }
        }
        Text(
            if (who == 1) "The other stick works like the D-pad (arrow keys)."
            else "Player $who moves with the D-pad and both sticks, which press the D-pad's keys below.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        Spacer(Modifier.height(6.dp))
        val map = st.layout.padFor(who)
        PAD_BUTTONS.forEach { (code, name) ->
            MenuCycleRow(name, keyName(map[code])) { st.padPick = code }
            Spacer(Modifier.height(6.dp))
        }
        MenuCycleRow("Reset buttons", "Defaults") { st.layout = st.layout.withPad(who, TouchLayout.defaultPad(who)); host.save(false) }
    }
}

@Composable
private fun OptionsPane(host: OverlayHost, left: Boolean, onLeft: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val act = ctx as? android.app.Activity
    HorizontalOptions(
        "Screen orientation", GamePrefs.orientations, GamePrefs.orientation.value,
        description = "For every game. Landscape turns with the device either way up.", first = true,
    ) { v -> act?.let { GamePrefs.setOrientation(it, v) } }
    if (host.hasVolume) SectionCard("Game volume") {
        var v by remember { mutableFloatStateOf(GamePrefs.volume.floatValue) }
        Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Slider(
            v, { v = it; host.setVolume(it) }, valueRange = 0f..1f,
            onValueChangeFinished = { GamePrefs.setVolume(v) },
            modifier = Modifier.sliderPadKeys().menuFocus(RoundedCornerShape(12.dp)),
        )
    }
    HorizontalOptions("Menu side", listOf(false to "Right", true to "Left"), left, onSelect = onLeft)
    SectionCard("Sound") {
        MenuSwitchRow("Menu sounds", MenuSfx.enabled.value) { MenuSfx.set(ctx, it) }
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow("Pause menu music", PauseMusic.enabled.value) { PauseMusic.set(ctx, it, menuOpen = true) }
    }
}

/**
 * ARMSX2's in-game Friends panel: connect Discord here or in the launcher, who is signed in, the
 * two switches, and which Discord friends are in ARMFlash right now with what they are playing
 * (title, platform and the game's logo).
 */
@Composable
private fun FriendsPane() {
    val presence = com.armsx.flashpoint.discord.DiscordPresence
    val status by presence.status
    val friends by presence.friends
    val notify by presence.notifyState
    val showGame by presence.showGameState
    val first = LocalFirstFocus.current
    if (!com.armsx.flashpoint.BuildConfig.DISCORD || !presence.available()) {
        // Built without the Discord SDK: say so plainly instead of a button that cannot work.
        SectionCard("Discord") {
            Text("This build was made without the Discord SDK.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SectionCard("Discord") {
        Text(
            "Link Discord to show what you're playing and see which friends are in ARMFlash. Uses your existing Discord friends. ARMFlash keeps no account and runs no server.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        when (status) {
            presence.CONNECTED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    com.armsx.flashpoint.discord.SelfChip()
                    Text("Discord connected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(first?.let { Modifier.focusRequester(it) } ?: Modifier) {
                    CompactButton("Disconnect") { presence.disconnectFromGame() }
                }
            }
            presence.AUTHORIZING, presence.CONNECTING -> Text("Connecting to Discord…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            else -> Box(first?.let { Modifier.focusRequester(it) } ?: Modifier) {
                CompactButton("Connect Discord") { presence.connectFromGame() }
            }
        }
        presence.error.value?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (status != presence.CONNECTED) return
    SectionCard("Notifications") {
        MenuSwitchRow("Notify me in game", notify) { presence.setNotifyFromGame(it) }
        Spacer(Modifier.height(6.dp))
        MenuSwitchRow("Show the game in my status", showGame) { presence.setShowGameFromGame(it) }
    }
    SectionCard("In ARMFlash now") {
        if (friends.isEmpty()) {
            // Empty is a real answer, not a failure: say which one it is.
            Text("None of your Discord friends are in ARMFlash right now.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            friends.forEach { f ->
                com.armsx.flashpoint.discord.FriendRow(
                    f, Modifier.menuFocus(RoundedCornerShape(12.dp)).focusable().padding(horizontal = 4.dp),
                )
            }
        }
    }
}

// ---- ARMSX2's building blocks ----------------------------------------------------------------

/** The launcher's controller ring (ARMSX2's), on a menu control. */
@Composable
internal fun Modifier.menuFocus(shape: androidx.compose.ui.graphics.Shape): Modifier {
    // Each control registers where it sits, so the menu can come back to the one last used.
    val mem = LocalMenuMemory.current
    val req = remember { androidx.compose.ui.focus.FocusRequester() }
    val spot = remember { floatArrayOf(0f, 0f) }
    if (mem != null) androidx.compose.runtime.DisposableEffect(req) {
        mem.live[req] = spot
        onDispose { mem.live.remove(req) }
    }
    return this
        .focusRequester(req)
        .onGloballyPositioned { val p = it.positionInRoot(); spot[0] = p.x; spot[1] = p.y + (mem?.scroll?.get(mem.tab)?.value ?: 0) }
        .onFocusChanged {
            if (!it.isFocused) return@onFocusChanged
            mem?.last?.set(mem.tab, spot[0] to spot[1])
            // ARMSX2's soft tick as the pad's highlight moves.
            if (com.armsx.flashpoint.ui.PadInput.active.value) MenuSfx.play(MenuSfx.Event.NAV)
        }
        .then(com.armsx.flashpoint.ui.padFocusModifier(shape))
}

/**
 * Where the pad was in the quick menu: the control last focused on each tab (by its place on the
 * page, which survives the menu closing) and each tab's scroll. Kept in the game's OverlayState.
 */
class MenuMemory {
    var tab: MenuTab = MenuTab.SESSION
    val last = HashMap<MenuTab, Pair<Float, Float>>()
    val scroll = HashMap<MenuTab, androidx.compose.foundation.ScrollState>()
    internal val live = HashMap<androidx.compose.ui.focus.FocusRequester, FloatArray>()

    /** The control nearest the one last used on [tab], if any. */
    fun restore(tab: MenuTab): androidx.compose.ui.focus.FocusRequester? {
        val (x, y) = last[tab] ?: return null
        return live.minByOrNull { (_, p) -> kotlin.math.abs(p[1] - y) * 4 + kotlin.math.abs(p[0] - x) }?.key
    }
}

internal val LocalMenuMemory = androidx.compose.runtime.staticCompositionLocalOf<MenuMemory?> { null }

/** First control of the open pane: where the pad lands when the menu opens or the tab changes. */
internal val LocalFirstFocus = androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.focus.FocusRequester?> { null }

@Composable
private fun ActionGrid(actions: List<MenuAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val first = LocalFirstFocus.current
        actions.forEachIndexed { i, item ->
            Surface(
                onClick = { MenuSfx.play(MenuSfx.Event.SELECT); item.action() },
                modifier = Modifier.fillMaxWidth()
                    .then(if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
                    .menuFocus(RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
            ) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.glyph, color = item.accent ?: MaterialTheme.colorScheme.primary, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        if (item.detail.isNotBlank()) {
                            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A line of explanation under a setting. */
@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 3.dp, bottom = 2.dp))
}

/** A percent setting: its name and value over a slider; saved when the slider is let go of. */
@Composable
private fun PercentSlider(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Text("$title  $value%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
    Slider(
        value.toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChangeFinished = { TouchPrefs.save() },
        modifier = Modifier.sliderPadKeys().menuFocus(RoundedCornerShape(12.dp)),
    )
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
internal fun <T> HorizontalOptions(title: String, options: List<Pair<T, String>>, selected: T, description: String = "", first: Boolean = false, onSelect: (T) -> Unit) {
    SectionCard(title) {
        if (description.isNotEmpty()) {
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
        }
        val req = if (first) LocalFirstFocus.current else null
        Row(
            Modifier.fillMaxWidth().bleedHorizontal(13.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEachIndexed { i, (value, label) ->
                Box(if (i == 0 && req != null) Modifier.focusRequester(req) else Modifier) { OptionChip(label, selected == value) { onSelect(value) } }
            }
        }
    }
}

@Composable
internal fun OptionChip(label: String, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    Surface(
        onClick = { MenuSfx.play(MenuSfx.Event.SELECT); onClick() },
        modifier = Modifier.menuFocus(RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = if (dot != null) 11.dp else 0.dp)) {
        if (dot != null) Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(dot))
        Text(
            label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 2,
        )
        }
    }
}

@Composable
internal fun MenuSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        onClick = {
            MenuSfx.play(if (checked) MenuSfx.Event.TOGGLE_OFF else MenuSfx.Event.TOGGLE_ON)
            onCheckedChange(!checked)
        },
        modifier = Modifier.fillMaxWidth().menuFocus(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Spacer(Modifier.width(10.dp))
            // The row toggles it; the switch itself is not a second stop for the pad.
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
internal fun MenuCycleRow(title: String, valueLabel: String, onClick: () -> Unit) {
    Surface(
        onClick = { MenuSfx.play(MenuSfx.Event.SELECT); onClick() },
        modifier = Modifier.fillMaxWidth().menuFocus(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Spacer(Modifier.width(10.dp))
            Text(valueLabel, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun Modifier.bleedHorizontal(edge: Dp): Modifier = layout { measurable, constraints ->
    val edgePx = edge.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth + edgePx * 2).coerceAtMost(constraints.maxWidth + edgePx * 2),
            maxWidth = constraints.maxWidth + edgePx * 2,
        ),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-edgePx, 0) }
}

// ---- controller button picker --------------------------------------------------------------

private val PAD_BUTTONS = listOf(
    KeyEvent.KEYCODE_BUTTON_A to "A", KeyEvent.KEYCODE_BUTTON_B to "B",
    KeyEvent.KEYCODE_BUTTON_X to "X", KeyEvent.KEYCODE_BUTTON_Y to "Y",
    KeyEvent.KEYCODE_BUTTON_L1 to "L1", KeyEvent.KEYCODE_BUTTON_R1 to "R1",
    KeyEvent.KEYCODE_BUTTON_L2 to "L2", KeyEvent.KEYCODE_BUTTON_R2 to "R2",
    KeyEvent.KEYCODE_BUTTON_START to "Start", KeyEvent.KEYCODE_BUTTON_SELECT to "Select",
    KeyEvent.KEYCODE_DPAD_UP to "D-pad Up", KeyEvent.KEYCODE_DPAD_DOWN to "D-pad Down",
    KeyEvent.KEYCODE_DPAD_LEFT to "D-pad Left", KeyEvent.KEYCODE_DPAD_RIGHT to "D-pad Right",
)

private fun keyName(code: Int?): String = when (code) {
    null -> "None"
    Keys.MOUSE_LEFT -> "Left click"
    Keys.MOUSE_RIGHT -> "Right click"
    else -> Keys.label(code)
}

/** Pick what a controller button sends, in the same sheet style. */
@Composable
fun PadKeyPicker(st: OverlayState, button: Int, host: OverlayHost) {
    val who = if (st.layout.multiplayer) st.padPlayer else 1
    val map = st.layout.padFor(who)
    val name = PAD_BUTTONS.firstOrNull { it.first == button }?.second ?: "Button"
    KeySheet(if (st.layout.multiplayer) "Player $who: $name sends" else "$name sends", map[button], { st.padPick = null }) { code ->
        st.layout = st.layout.withPad(who, map.toMutableMap().apply { if (code == null) remove(button) else put(button, code) })
        host.save(false)
        st.padPick = null
    }
}

/** Pick the key a gesture ([slot]: "Swipe up", "Double-tap"...) presses. */
@Composable
fun GestureKeyPicker(st: OverlayState, slot: String) {
    val state = TouchPrefs.gestureSlots.firstOrNull { it.first == slot }?.second ?: return
    KeySheet("$slot presses", state.intValue.takeIf { it != 0 }, { st.gesturePick = null }) { code ->
        state.intValue = code ?: 0
        TouchPrefs.save()
        st.gesturePick = null
    }
}

/** The key picker sheet: None, the mouse buttons and every key; [current] is marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeySheet(title: String, current: Int?, dismiss: () -> Unit, pick: (Int?) -> Unit) {
    MaterialTheme(colorScheme = NightScheme, typography = ArmsTypography) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable { dismiss() }, contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.8f).widthIn(max = 620.dp).fillMaxHeight(0.86f).clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                shadowElevation = 24.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        Modifier.verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        OptionChip("None", current == null) { pick(null) }
                        OptionChip("Left click", current == Keys.MOUSE_LEFT) { pick(Keys.MOUSE_LEFT) }
                        OptionChip("Right click", current == Keys.MOUSE_RIGHT) { pick(Keys.MOUSE_RIGHT) }
                        Keys.ALL.forEach { k -> OptionChip(k.label, current == k.code) { pick(k.code) } }
                    }
                }
            }
        }
    }
}


/**
 * RetroArch shaders for native Flash (librashader): get RetroArch's shader pack, pick a preset
 * for this game by folder, and change its parameters while the game runs. The pack, index and
 * parameter reading are ARMSX2's (ShaderRepo, ShaderIndex, ShaderParams).
 */
@Composable
private fun ShaderCard(host: OverlayHost) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val sp = com.armsx.flashpoint.shaders.ShaderPrefs
    val repo = com.armsx.flashpoint.shaders.ShaderRepo
    val root = remember { repo.shadersRoot(ctx) }
    var installed by remember { mutableStateOf<Boolean?>(null) }
    var presets by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf<String?>(null) }
    var choice by remember { mutableStateOf(sp.gamePreset(ctx, host.gameId)) }
    var browsing by remember { mutableStateOf<String?>(null) } // folder being browsed, "" = top
    var params by remember { mutableStateOf<List<com.armsx.flashpoint.shaders.ShaderParam>>(emptyList()) }
    var values by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val current = sp.presetFile(ctx, host.gameId)

    LaunchedEffect(Unit) {
        val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.armsx.flashpoint.shaders.ShaderIndex.load(root).map { it.relPath }.sorted()
        }
        presets = list; installed = list.isNotEmpty()
    }
    LaunchedEffect(current?.path) {
        val f = current
        params = if (f == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.armsx.flashpoint.shaders.ShaderParams.read(f.path)
        }
        values = if (f == null) emptyMap() else sp.overrides(ctx, f.path)
    }
    fun pick(rel: String) {
        choice = rel; sp.setGamePreset(ctx, host.gameId, rel); browsing = null
        host.shaderChanged(true)
    }

    SectionCard("Shader") {
        Text(
            "RetroArch shaders (CRT, scanlines, smoothing and more) on top of the game, for this game.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when {
            installed == null -> Text("Looking for shaders…", style = MaterialTheme.typography.bodySmall)
            installed == false -> {
                val src = repo.baseSources().first()
                MenuCycleRow("Get RetroArch shaders", busy ?: src.name) {
                    if (busy != null) return@MenuCycleRow
                    busy = "Starting…"
                    scope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            repo.download(ctx, src,
                                onDownload = { d, t -> busy = if (t > 0) "Downloading ${d * 100 / t}%" else "Downloading ${d shr 20} MB" },
                                onExtract = { d, t -> busy = "Unpacking ${if (t > 0) d * 100 / t else 0}%" },
                                onIndex = { busy = "Indexing…" },
                            )
                        } != null
                        busy = if (ok) null else "Download failed, try again"
                        if (ok) {
                            presets = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                com.armsx.flashpoint.shaders.ShaderIndex.load(root).map { it.relPath }.sorted()
                            }
                            installed = presets.isNotEmpty()
                        }
                    }
                }
            }
            else -> {
                val label = when (choice) {
                    "" -> "Same as Config" + (current?.let { " (${it.nameWithoutExtension})" } ?: " (off)")
                    sp.OFF -> "Off"
                    else -> java.io.File(choice).nameWithoutExtension
                }
                MenuCycleRow("Preset", label) { browsing = if (browsing == null) "" else null }
                browsing?.let { dir ->
                    Spacer(Modifier.height(6.dp))
                    if (dir.isEmpty()) {
                        MenuCycleRow("Same as Config", "") { pick("") }
                        MenuCycleRow("Off", "") { pick(sp.OFF) }
                    } else {
                        MenuCycleRow("Back", dir.substringBeforeLast('/', "").ifEmpty { "Top" }) { browsing = dir.substringBeforeLast('/', "") }
                    }
                    val prefix = if (dir.isEmpty()) "" else "$dir/"
                    val here = presets.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                    here.mapNotNull { it.substringBefore('/', "").takeIf { f -> f.isNotEmpty() } }.distinct().forEach { folder ->
                        MenuCycleRow("$folder/", "") { browsing = prefix + folder }
                    }
                    here.filter { '/' !in it }.forEach { file ->
                        MenuCycleRow(file.removeSuffix(".slangp"), "") { pick(prefix + file) }
                    }
                }
                if (params.isNotEmpty() && current != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Parameters", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    params.forEach { p ->
                        if (!p.isAdjustable) {
                            Text(p.description, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 6.dp))
                            return@forEach
                        }
                        val v = values[p.name] ?: p.initial
                        Text("${p.description}: ${"%.2f".format(v)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Slider(
                            v, { nv ->
                                val stepped = if (p.step > 0f) (Math.round((nv - p.minimum) / p.step) * p.step + p.minimum) else nv
                                values = values + (p.name to stepped)
                            },
                            valueRange = p.minimum..p.maximum,
                            onValueChangeFinished = {
                                sp.setOverrides(ctx, current.path, values)
                                host.shaderChanged(false)
                            },
                            modifier = Modifier.sliderPadKeys().menuFocus(RoundedCornerShape(12.dp)),
                        )
                    }
                    MenuCycleRow("Reset parameters", "") {
                        // Push the author's values explicitly: a live chain keeps what it has.
                        values = params.associate { it.name to it.initial }
                        sp.setOverrides(ctx, current.path, values)
                        host.shaderChanged(false)
                        values = emptyMap(); sp.setOverrides(ctx, current.path, emptyMap())
                    }
                }
            }
        }
    }
}
