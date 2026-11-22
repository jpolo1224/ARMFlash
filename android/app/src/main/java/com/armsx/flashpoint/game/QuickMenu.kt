package com.armsx.flashpoint.game

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The in-game quick menu, laid out like ARMSX2's: a panel docked to one edge, a rail of tabs,
 * a header naming the game, and section cards. Tailored to Flashpoint: no save states or
 * achievements; display (scale mode, quality), touch and controller setup instead.
 */
enum class MenuTab(val label: String, val glyph: String) {
    SESSION("Session", "▶"),
    DISPLAY("Display", "▣"),
    CONTROLS("Controls", "✚"),
    OPTIONS("Options", "⚙"),
}

private val Green = Color(0xFF5CB531)
private val PanelBg = Color(0xF2161618)
private val CardBg = Color(0xFF222225)
private val Outline = Color(0x33FFFFFF)
private val TextDim = Color(0xFFA0A0A6)

@Composable
fun QuickMenu(st: OverlayState, host: OverlayHost) {
    var left by remember { mutableStateOf(host.menuLeft) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(indication = null, interactionSource = null) { st.menu = false }) {
        val width = if (maxWidth < 600.dp) maxWidth * 0.9f else 420.dp
        Row(
            Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().width(width)
                .background(PanelBg).border(BorderStroke(1.dp, Outline))
                .clickable(indication = null, interactionSource = null) {},
        ) {
            if (!left) Rail(st)
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp)) {
                Header(host, onClose = { st.menu = false })
                Spacer(Modifier.height(10.dp))
                when (st.tab) {
                    MenuTab.SESSION -> SessionPane(st, host)
                    MenuTab.DISPLAY -> DisplayPane(st, host)
                    MenuTab.CONTROLS -> ControlsPane(st, host)
                    MenuTab.OPTIONS -> OptionsPane(left) { left = it; host.menuLeft = it }
                }
            }
            if (left) Rail(st)
        }
    }
}

@Composable
private fun Rail(st: OverlayState) {
    Column(
        Modifier.fillMaxHeight().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MenuTab.entries.forEach { t ->
            val on = st.tab == t
            Surface(
                onClick = { st.tab = t },
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(18.dp),
                color = if (on) Green.copy(alpha = 0.16f) else Color(0x80222225),
                border = BorderStroke(if (on) 2.dp else 1.dp, if (on) Green else Outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(t.glyph, color = if (on) Green else TextDim, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun Header(host: OverlayHost, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(host.title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(host.runner, color = TextDim, fontSize = 12.sp)
        }
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(CardBg).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", color = Color.White, fontSize = 16.sp) }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(RoundedCornerShape(16.dp)).background(CardBg)
            .border(1.dp, Outline, RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) Green.copy(alpha = 0.18f) else Color(0xFF2E2E32))
                    .border(1.dp, if (on) Green else Outline, RoundedCornerShape(10.dp))
                    .clickable { onPick(v) }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(label, color = if (on) Color.White else Color(0xFFD0D0D4), fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun Tile(glyph: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2A2E)).border(1.dp, Outline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(glyph, color = Green, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionPane(st: OverlayState, host: OverlayHost) {
    Card("Game") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("▶", "Resume", Modifier.weight(1f)) { st.menu = false }
            Tile("↻", "Restart", Modifier.weight(1f)) { st.menu = false; host.restart() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("Aa", "Keyboard", Modifier.weight(1f)) { st.menu = false; host.keyboard() }
            Tile("✕", "Exit game", Modifier.weight(1f)) { host.exit() }
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
    Card("Scale") {
        Text("How the game fits the screen.", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Choices(
            listOf("default" to "Game default", "fit" to "Fit", "stretch" to "Stretch", "fill" to "Fill (crop)", "original" to "Original size"),
            st.layout.scale,
        ) { set(scale = it) }
    }
    Card("Quality") {
        Text("Lower quality draws faster on slower phones.", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Choices(listOf("low" to "Low", "medium" to "Medium", "high" to "High", "best" to "Best"), st.layout.quality) { set(quality = it) }
    }
}

@Composable
private fun ControlsPane(st: OverlayState, host: OverlayHost) {
    Card("Pointer") {
        Choices(listOf("direct" to "Direct touch", "touchpad" to "Touchpad", "point" to "Point & click"), st.layout.mode) {
            st.layout = st.layout.copy(mode = it)
            if (it == "direct") st.cursor = null
            host.save(false)
        }
    }
    Card("Touch keys") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show keys", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(st.layout.visible, { st.layout = st.layout.copy(visible = it); host.save(false) })
        }
        Text("Opacity", color = TextDim, fontSize = 12.sp)
        var op by remember { mutableFloatStateOf(st.layout.opacity) }
        Slider(op, { op = it; st.layout = st.layout.copy(opacity = it) }, valueRange = 0.15f..1f, onValueChangeFinished = { host.save(false) })
        Tile("✎", "Edit layout", Modifier.fillMaxWidth()) {
            st.menu = false
            st.editing = true
            st.selected = null
        }
    }
    Card("Controller") {
        Text("Left stick", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Choices(listOf("arrows" to "Arrow keys", "cursor" to "Mouse cursor"), if (st.layout.stickMovesCursor) "cursor" else "arrows") {
            st.layout = st.layout.copy(stickMovesCursor = it == "cursor")
            host.save(false)
        }
        Spacer(Modifier.height(10.dp))
        Text("Buttons", color = TextDim, fontSize = 12.sp)
        PAD_BUTTONS.forEach { (code, name) ->
            val mapped = st.layout.pad[code]
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp)).clickable { st.padPick = code }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(keyName(mapped), color = Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(6.dp))
        Choices(listOf("reset" to "Reset buttons"), "") {
            st.layout = st.layout.copy(pad = TouchLayout.DEFAULT_PAD)
            host.save(false)
        }
    }
}

@Composable
private fun OptionsPane(left: Boolean, onSide: (Boolean) -> Unit) {
    Card("Menu side") {
        Text("Which edge this menu opens on.", color = TextDim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Choices(listOf("left" to "Left", "right" to "Right"), if (left) "left" else "right") { onSide(it == "left") }
    }
}

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

/** Pick what a controller button sends. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PadKeyPicker(st: OverlayState, button: Int, host: OverlayHost) {
    fun pick(code: Int?) {
        st.layout = st.layout.copy(pad = st.layout.pad.toMutableMap().apply { if (code == null) remove(button) else put(button, code) })
        host.save(false)
        st.padPick = null
    }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { st.padPick = null }, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 600.dp)) {
            Text("${PAD_BUTTONS.firstOrNull { it.first == button }?.second ?: "Button"} sends", color = Color.White, fontWeight = FontWeight.Black)
            Column(Modifier.height(280.dp).verticalScroll(rememberScrollState())) {
                FlowRow {
                    Pill("None") { pick(null) }
                    Pill("Left click") { pick(Keys.MOUSE_LEFT) }
                    Pill("Right click") { pick(Keys.MOUSE_RIGHT) }
                    Keys.ALL.forEach { k -> Pill(k.label) { pick(k.code) } }
                }
            }
        }
    }
}
