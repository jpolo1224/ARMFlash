package com.armsx.flashpoint.game

import android.view.KeyEvent
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
}

// ---- colours: Flashpoint's (FlashARM's launcher theme) in ARMSX2's menu ------------------

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
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).clickable(onClick = close))
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 18.dp)) {
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
                MenuTab.OPTIONS -> OptionsPane(left, onLeft)
            }
        }
    }
}

@Composable
private fun CompactMenuTabs(selected: MenuTab, onSelect: (MenuTab) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) { MenuTab.entries.forEach { MenuTabChip(it, it == selected, onSelect) } }
}

@Composable
private fun MenuRail(selected: MenuTab, onSelect: (MenuTab) -> Unit) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        MenuTab.entries.forEach { tab ->
            val active = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                modifier = Modifier.size(56.dp),
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
        modifier = Modifier.widthIn(min = 132.dp, max = 210.dp).padding(vertical = 3.dp),
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
    }
}

// ---- panes ---------------------------------------------------------------------------------

private data class MenuAction(val title: String, val detail: String, val glyph: String, val accent: Color?, val action: () -> Unit)

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
}

@Composable
private fun DisplayPane(st: OverlayState, host: OverlayHost) {
    fun set(scale: String = st.layout.scale, quality: String = st.layout.quality) {
        st.layout = st.layout.copy(scale = scale, quality = quality)
        host.save(false)
        host.display(scale, quality)
    }
    HorizontalOptions(
        "Scale",
        listOf("default" to "Game default", "fit" to "Fit", "stretch" to "Stretch", "fill" to "Fill", "original" to "Original size"),
        st.layout.scale,
    ) { set(scale = it) }
    HorizontalOptions("Quality", listOf("low" to "Low", "medium" to "Medium", "high" to "High", "best" to "Best"), st.layout.quality) { set(quality = it) }
    if (host.usesDriver) DriverCard(host)
}

/** This game's GPU driver (ARMSX2's per-game pick): its own choice, or the global one. Applies on restart. */
@Composable
private fun DriverCard(host: OverlayHost) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val cd = com.armsx.flashpoint.data.CustomDriver
    val installed = remember { cd.listInstalled(ctx) }
    var pick by remember { mutableStateOf(cd.gameId(host.gameId)) }
    SectionCard("GPU driver") {
        Text("For this game. Takes effect when the game restarts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().bleedHorizontal(13.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OptionChip("Default", pick.isEmpty()) { pick = ""; cd.selectForGame(host.gameId, null) }
            installed.forEach { d -> OptionChip(d.name, pick == d.id) { pick = d.id; cd.selectForGame(host.gameId, d.id) } }
        }
        Spacer(Modifier.height(6.dp))
        MenuCycleRow("Restart with this driver", "Restart") { host.restart() }
    }
}

@Composable
private fun ControlsPane(st: OverlayState, host: OverlayHost) {
    HorizontalOptions("Pointer", listOf("direct" to "Direct touch", "touchpad" to "Touchpad", "point" to "Point & click"), st.layout.mode) {
        st.layout = st.layout.copy(mode = it)
        if (it == "direct") st.cursor = null
        host.save(false)
    }
    SectionCard("Touch keys") {
        MenuSwitchRow("Show touch keys", st.layout.visible) { st.layout = st.layout.copy(visible = it); host.save(false) }
        Spacer(Modifier.height(6.dp))
        var op by remember { mutableFloatStateOf(st.layout.opacity) }
        Text("Opacity  ${(op * 100).toInt()}%", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Slider(op, { op = it; st.layout = st.layout.copy(opacity = it) }, valueRange = 0.15f..1f, onValueChangeFinished = { host.save(false) })
        Spacer(Modifier.height(2.dp))
        MenuCycleRow("Edit layout", "Open") { st.menu = false; st.editing = true; st.selected = null }
    }
    SectionCard("Controller") {
        MenuCycleRow("Left stick", if (st.layout.stickMovesCursor) "Mouse cursor" else "Arrow keys") {
            st.layout = st.layout.copy(stickMovesCursor = !st.layout.stickMovesCursor); host.save(false)
        }
        Spacer(Modifier.height(6.dp))
        PAD_BUTTONS.forEach { (code, name) ->
            MenuCycleRow(name, keyName(st.layout.pad[code])) { st.padPick = code }
            Spacer(Modifier.height(6.dp))
        }
        MenuCycleRow("Reset buttons", "Defaults") { st.layout = st.layout.copy(pad = TouchLayout.DEFAULT_PAD); host.save(false) }
    }
}

@Composable
private fun OptionsPane(left: Boolean, onLeft: (Boolean) -> Unit) {
    HorizontalOptions("Menu side", listOf(false to "Right", true to "Left"), left, onLeft)
}

// ---- ARMSX2's building blocks ----------------------------------------------------------------

@Composable
private fun ActionGrid(actions: List<MenuAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { item ->
            Surface(
                onClick = item.action,
                modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
private fun <T> HorizontalOptions(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    SectionCard(title) {
        Row(
            Modifier.fillMaxWidth().bleedHorizontal(13.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) { options.forEach { (value, label) -> OptionChip(label, selected == value) { onSelect(value) } } }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.17f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f)),
    ) {
        Text(
            label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 2,
        )
    }
}

@Composable
private fun MenuSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        onClick = { onCheckedChange(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Spacer(Modifier.width(10.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun MenuCycleRow(title: String, valueLabel: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadKeyPicker(st: OverlayState, button: Int, host: OverlayHost) {
    fun pick(code: Int?) {
        st.layout = st.layout.copy(pad = st.layout.pad.toMutableMap().apply { if (code == null) remove(button) else put(button, code) })
        host.save(false)
        st.padPick = null
    }
    MaterialTheme(colorScheme = NightScheme, typography = ArmsTypography) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable { st.padPick = null }, contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.fillMaxWidth(0.8f).widthIn(max = 620.dp).fillMaxHeight(0.86f).clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                shadowElevation = 24.dp,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "${PAD_BUTTONS.firstOrNull { it.first == button }?.second ?: "Button"} sends",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        Modifier.verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val current = st.layout.pad[button]
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
