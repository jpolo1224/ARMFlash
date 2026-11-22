package com.armsx.flashpoint.game

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Everything the overlay shows and edits. */
class OverlayState(layout: TouchLayout) {
    var layout by mutableStateOf(layout)
    var menu by mutableStateOf(false)
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<Int?>(null)
    var picking by mutableStateOf(false)
    var cursor by mutableStateOf<Offset?>(null)
}

private val Accent = Color(0xFF5CB531)

@Composable
fun GameOverlay(st: OverlayState, inj: Injector, onSave: (asDefault: Boolean) -> Unit, onKeyboard: () -> Unit, onExit: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val l = st.layout

        // Mouse layer (under the buttons, so a finger on a button never reaches it).
        if (l.mode != "direct" && !st.editing) {
            Box(Modifier.fillMaxSize().then(mouseInput(l.mode, inj) { st.cursor = it }))
        }

        // The cursor, whenever something other than a direct touch is driving the mouse.
        st.cursor?.let { c ->
            Canvas(Modifier.fillMaxSize()) {
                val p = Path().apply {
                    moveTo(c.x, c.y); lineTo(c.x, c.y + 26f); lineTo(c.x + 7f, c.y + 20f)
                    lineTo(c.x + 12f, c.y + 31f); lineTo(c.x + 17f, c.y + 29f); lineTo(c.x + 12f, c.y + 18f)
                    lineTo(c.x + 20f, c.y + 18f); close()
                }
                drawPath(p, Color.White)
                drawPath(p, Color.Black, style = Stroke(width = 2.5f))
            }
        }

        if (l.visible || st.editing) {
            l.buttons.forEachIndexed { i, b ->
                ButtonView(b, w, h, l.opacity, st, i, inj)
            }
        }

        // Menu button, top right.
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(40.dp).clip(CircleShape)
                .background(Color(0x66000000)).clickable { st.menu = !st.menu; st.editing = false },
            contentAlignment = Alignment.Center,
        ) { Text("☰", color = Color.White, fontSize = 20.sp) }

        if (st.menu) Menu(st, onSave, onKeyboard, onExit, Modifier.align(Alignment.TopEnd).padding(top = 56.dp, end = 10.dp))
        if (st.editing) EditBar(st, onSave, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        if (st.picking) KeyPicker(st)
    }
}

/** Touchpad and point-and-click: turn finger gestures into mouse movement and clicks. */
private fun mouseInput(mode: String, inj: Injector, show: (Offset) -> Unit): Modifier = Modifier.pointerInput(mode) {
    var lastTapUp = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val t0 = down.uptimeMillis
        var moved = 0f
        var maxPointers = 1
        var dragging = false
        if (mode == "point") {
            inj.moveCursor(start.x, start.y - 60f)
            show(Offset(inj.cursorX, inj.cursorY))
        } else if (t0 - lastTapUp < 280) {
            // Double tap and hold: drag with the button down.
            dragging = true
            inj.mouseDown()
        }
        var prev = start
        while (true) {
            val ev = awaitPointerEvent()
            maxPointers = maxOf(maxPointers, ev.changes.count { it.pressed })
            val c = ev.changes.firstOrNull { it.id == down.id } ?: ev.changes.first()
            if (!c.pressed) {
                val dt = c.uptimeMillis - t0
                when {
                    maxPointers >= 2 && moved < 30f -> inj.click(MotionEvent.BUTTON_SECONDARY)
                    dragging -> inj.mouseUp()
                    mode == "point" -> inj.click()
                    moved < 18f && dt < 250 -> { inj.click(); lastTapUp = c.uptimeMillis }
                }
                break
            }
            val d = c.position - prev
            prev = c.position
            moved += hypot(d.x, d.y)
            if (mode == "point") {
                if (!dragging && moved < 12f && c.uptimeMillis - t0 > 450) {
                    dragging = true
                    inj.mouseDown()
                }
                inj.moveCursor(c.position.x, c.position.y - 60f)
            } else if (maxPointers < 2) {
                inj.moveCursor(inj.cursorX + d.x * 1.6f, inj.cursorY + d.y * 1.6f)
            }
            show(Offset(inj.cursorX, inj.cursorY))
            ev.changes.forEach { it.consume() }
        }
    }
}

@Composable
private fun ButtonView(b: TouchButton, w: Float, h: Float, opacity: Float, st: OverlayState, index: Int, inj: Injector) {
    val density = LocalDensity.current
    val sizePx = with(density) { b.size.dp.toPx() }
    val x = (b.x * w - sizePx / 2).roundToInt()
    val y = (b.y * h - sizePx / 2).roundToInt()
    val sel = st.editing && st.selected == index
    val base = Modifier.offset { IntOffset(x, y) }.size(b.size.dp).alpha(if (st.editing) 0.9f else opacity)
    val edit = if (st.editing) Modifier.border(2.dp, if (sel) Accent else Color.White, RoundedCornerShape(10.dp)).pointerInput(index) {
        detectDragGestures(onDragStart = { st.selected = index }) { change, drag ->
            change.consume()
            val cur = st.layout.buttons[index]
            st.layout = st.layout.copy(buttons = st.layout.buttons.toMutableList().also {
                it[index] = cur.copy(x = (cur.x + drag.x / w).coerceIn(0f, 1f), y = (cur.y + drag.y / h).coerceIn(0f, 1f))
            })
        }
    }.clickable { st.selected = index } else Modifier

    when (b.kind) {
        "arrows", "wasd" -> {
            val codes = if (b.kind == "wasd") intArrayOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
            else intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
            var lit by remember { mutableStateOf(0) }
            Box(base.then(edit).then(if (st.editing) Modifier else dpadInput(codes, sizePx, inj) { lit = it })) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = size.width
                    val arm = s / 3
                    fun part(mask: Int, l: Float, t: Float) = drawRoundRect(
                        if (lit and mask != 0) Color(0xEEFFFFFF) else Color(0x99202020),
                        topLeft = Offset(l, t), size = androidx.compose.ui.geometry.Size(arm, arm),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    )
                    part(1, arm, 0f); part(2, arm, 2 * arm); part(4, 0f, arm); part(8, 2 * arm, arm)
                    val outline = Path().apply {
                        moveTo(arm, 0f); lineTo(2 * arm, 0f); lineTo(2 * arm, arm); lineTo(s, arm); lineTo(s, 2 * arm)
                        lineTo(2 * arm, 2 * arm); lineTo(2 * arm, s); lineTo(arm, s); lineTo(arm, 2 * arm); lineTo(0f, 2 * arm)
                        lineTo(0f, arm); lineTo(arm, arm); close()
                    }
                    drawPath(outline, Color(0x99FFFFFF), style = Stroke(width = 3f))
                    drawRoundRect(Color(0x99202020), topLeft = Offset(arm, arm), size = androidx.compose.ui.geometry.Size(arm, arm))
                }
                if (b.kind == "wasd") Text("WASD", color = Color.White, fontSize = 10.sp, modifier = Modifier.align(Alignment.Center))
            }
        }
        else -> {
            var down by remember { mutableStateOf(false) }
            val label = when (b.code) { Keys.MOUSE_LEFT -> "L click"; Keys.MOUSE_RIGHT -> "R click"; else -> Keys.label(b.code) }
            Box(
                base.clip(RoundedCornerShape(if (b.kind == "mouse") 10.dp else 50.dp))
                    .background(if (down) Color(0xEEFFFFFF) else Color(0x99202020))
                    .border(1.5.dp, Color(0x99FFFFFF), RoundedCornerShape(if (b.kind == "mouse") 10.dp else 50.dp))
                    .then(edit)
                    .then(if (st.editing) Modifier else Modifier.pointerInput(b.code) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            down = true
                            press(inj, b.code, true)
                            do {
                                val e = awaitPointerEvent()
                                e.changes.forEach { it.consume() }
                            } while (e.changes.any { it.pressed })
                            press(inj, b.code, false)
                            down = false
                        }
                    }),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (down) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = (b.size / 4.2f).coerceIn(11f, 22f).sp)
            }
        }
    }
}

private fun press(inj: Injector, code: Int, down: Boolean) = when (code) {
    Keys.MOUSE_LEFT -> if (down) inj.mouseDown(MotionEvent.BUTTON_PRIMARY) else inj.mouseUp(MotionEvent.BUTTON_PRIMARY)
    Keys.MOUSE_RIGHT -> if (down) inj.mouseDown(MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(MotionEvent.BUTTON_SECONDARY)
    else -> inj.key(code, down)
}

/** Arrow cluster: slide between directions, diagonals included. Bits: 1 up, 2 down, 4 left, 8 right. */
private fun dpadInput(codes: IntArray, sizePx: Float, inj: Injector, lit: (Int) -> Unit): Modifier = Modifier.pointerInput(codes.toList()) {
    awaitEachGesture {
        var mask = 0
        fun apply(p: Offset?) {
            var m = 0
            if (p != null) {
                val dx = p.x - sizePx / 2
                val dy = p.y - sizePx / 2
                if (hypot(dx, dy) > sizePx * 0.12f) {
                    val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
                    if (a in -157.5..-22.5) m = m or 1
                    if (a in 22.5..157.5) m = m or 2
                    if (abs(a) >= 112.5) m = m or 4
                    if (abs(a) <= 67.5) m = m or 8
                }
            }
            for (i in 0..3) {
                val bit = 1 shl i
                if ((m and bit) != (mask and bit)) inj.key(codes[i], m and bit != 0)
            }
            mask = m
            lit(m)
        }
        val d = awaitFirstDown()
        d.consume()
        apply(d.position)
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Main)
            val c = e.changes.firstOrNull { it.id == d.id } ?: break
            c.consume()
            if (!c.pressed) break
            apply(c.position)
        }
        apply(null)
    }
}

@Composable
private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xEE1C1C1E)).padding(12.dp)) { content() }
}

@Composable
private fun Pill(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.padding(3.dp).clip(RoundedCornerShape(6.dp)).background(if (on) Accent else Color(0xFF3A3A3E))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Menu(st: OverlayState, onSave: (Boolean) -> Unit, onKeyboard: () -> Unit, onExit: () -> Unit, modifier: Modifier) {
    Panel(modifier) {
        Text("Pointer", color = Color.White, fontWeight = FontWeight.Black)
        FlowRow {
            listOf("direct" to "Direct touch", "touchpad" to "Touchpad", "point" to "Point & click").forEach { (m, label) ->
                Pill(label, st.layout.mode == m) {
                    st.layout = st.layout.copy(mode = m)
                    if (m == "direct") st.cursor = null
                    onSave(false)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        FlowRow {
            Pill(if (st.layout.visible) "Hide keys" else "Show keys") { st.layout = st.layout.copy(visible = !st.layout.visible); onSave(false) }
            Pill("Edit layout") { st.editing = true; st.menu = false; st.selected = null }
            Pill("Keyboard") { st.menu = false; onKeyboard() }
            Pill(if (st.layout.stickMovesCursor) "Left stick: cursor" else "Left stick: arrows") {
                st.layout = st.layout.copy(stickMovesCursor = !st.layout.stickMovesCursor); onSave(false)
            }
        }
        Text("Key opacity", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        var op by remember { mutableFloatStateOf(st.layout.opacity) }
        Slider(op, { op = it; st.layout = st.layout.copy(opacity = it) }, valueRange = 0.15f..1f, onValueChangeFinished = { onSave(false) })
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            Pill("Close menu") { st.menu = false }
            Pill("Exit game", on = false, onClick = onExit)
        }
    }
}

@Composable
private fun EditBar(st: OverlayState, onSave: (Boolean) -> Unit, modifier: Modifier) {
    Panel(modifier) {
        Text("Drag keys to move them. Tap one to select it.", color = Color.White, fontSize = 12.sp)
        Row {
            Pill("Add key") { st.picking = true }
            st.selected?.let { i ->
                Pill("Bigger") { resize(st, i, 1.15f) }
                Pill("Smaller") { resize(st, i, 1 / 1.15f) }
                Pill("Remove") {
                    st.layout = st.layout.copy(buttons = st.layout.buttons.filterIndexed { j, _ -> j != i }); st.selected = null
                }
            }
        }
        Row {
            Pill("Reset") { st.layout = st.layout.copy(buttons = TouchLayout.DEFAULT_BUTTONS); st.selected = null }
            Pill("Save for this game", on = true) { st.editing = false; onSave(false) }
            Pill("Save as default") { st.editing = false; onSave(true) }
        }
    }
}

private fun resize(st: OverlayState, i: Int, f: Float) {
    val b = st.layout.buttons[i]
    st.layout = st.layout.copy(buttons = st.layout.buttons.toMutableList().also { it[i] = b.copy(size = (b.size * f).coerceIn(36f, 260f)) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyPicker(st: OverlayState) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { st.picking = false }, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 560.dp)) {
            Text("Add a key", color = Color.White, fontWeight = FontWeight.Black)
            Column(Modifier.height(300.dp).verticalScroll(rememberScrollState())) {
                FlowRow {
                    Pill("Arrow keys") { add(st, TouchButton("arrows", x = 0.15f, y = 0.7f, size = 150f)) }
                    Pill("WASD") { add(st, TouchButton("wasd", x = 0.15f, y = 0.7f, size = 150f)) }
                    Pill("Left click") { add(st, TouchButton("mouse", Keys.MOUSE_LEFT, 0.5f, 0.5f, 64f)) }
                    Pill("Right click") { add(st, TouchButton("mouse", Keys.MOUSE_RIGHT, 0.5f, 0.5f, 64f)) }
                    Keys.ALL.forEach { k -> Pill(k.label) { add(st, TouchButton("key", k.code, 0.5f, 0.5f, 60f)) } }
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

private fun add(st: OverlayState, b: TouchButton) {
    st.layout = st.layout.copy(buttons = st.layout.buttons + b)
    st.selected = st.layout.buttons.lastIndex
    st.picking = false
}
