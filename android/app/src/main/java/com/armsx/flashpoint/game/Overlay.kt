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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
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
/** What the overlay asks of the game screen it sits on. */
interface OverlayHost {
    val title: String
    val runner: String
    val logoUrl: String
    fun save(asDefault: Boolean)
    fun keyboard()
    fun restart()
    fun exit()
    /** The quick menu opened or closed: pause or resume the game. */
    fun menuOpen(open: Boolean)
    fun display(scale: String, quality: String)
    var menuLeft: Boolean
}

class OverlayState(layout: TouchLayout) {
    var layout by mutableStateOf(layout)
    var menu by mutableStateOf(false)
    var tab by mutableStateOf(MenuTab.SESSION)
    /** Controller button being remapped (its keycode), while the key picker is open for it. */
    var padPick by mutableStateOf<Int?>(null)
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<Int?>(null)
    var picking by mutableStateOf(false)
    var cursor by mutableStateOf<Offset?>(null)
}

private val Accent = Color(0xFF5CB531)

@Composable
fun GameOverlay(st: OverlayState, inj: GameInput, host: OverlayHost) {
    val onSave: (Boolean) -> Unit = host::save
    androidx.compose.runtime.LaunchedEffect(st.menu) { host.menuOpen(st.menu) }
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
        if (!st.menu) Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp).size(40.dp).clip(CircleShape)
                .background(Color(0x66000000)).clickable { st.menu = !st.menu; st.editing = false },
            contentAlignment = Alignment.Center,
        ) { Text("☰", color = Color.White, fontSize = 20.sp) }

        if (st.menu) QuickMenu(st, host)
        if (st.editing) EditBar(st, onSave, Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
        if (st.picking) KeyPicker(st)
        st.padPick?.let { PadKeyPicker(st, it, host) }
    }
}

/** Touchpad and point-and-click: turn finger gestures into mouse movement and clicks. */
private fun mouseInput(mode: String, inj: GameInput, show: (Offset) -> Unit): Modifier = Modifier.pointerInput(mode) {
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
private fun ButtonView(b: TouchButton, w: Float, h: Float, opacity: Float, st: OverlayState, index: Int, inj: GameInput) {
    val density = LocalDensity.current
    val wide = b.kind == "key" && isWideKey(b.code)
    val heightPx = with(density) { b.size.dp.toPx() }
    val widthPx = if (wide) heightPx * 1.9f else heightPx
    val x = (b.x * w - widthPx / 2).roundToInt()
    val y = (b.y * h - heightPx / 2).roundToInt()
    val sel = st.editing && st.selected == index
    val sizeMod = if (wide) Modifier.size(width = (b.size * 1.9f).dp, height = b.size.dp) else Modifier.size(b.size.dp)
    val base = Modifier.offset { IntOffset(x, y) }.then(sizeMod).alpha(if (st.editing) 0.95f else opacity)
    val edit = if (st.editing) Modifier.border(2.dp, if (sel) Accent else Color(0x88FFFFFF), RoundedCornerShape(12.dp)).pointerInput(index) {
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
            val codes = dirCodes(b.kind)
            var lit by remember { mutableStateOf(0) }
            Box(base.then(edit).then(if (st.editing) Modifier else dpadInput(codes, widthPx, inj) { lit = it })) {
                SpriteDpad(lit)
                if (b.kind == "wasd") Text("WASD", color = KeyLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
            }
        }
        "stick", "stick_wasd" -> {
            val codes = dirCodes(if (b.kind == "stick_wasd") "wasd" else "arrows")
            var thumb by remember { mutableStateOf(Offset.Zero) }
            Box(base.then(edit).then(if (st.editing) Modifier else stickInput(codes, widthPx, inj) { thumb = it })) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.armsx.flashpoint.R.drawable.pad_stick_base), null, Modifier.fillMaxSize(),
                )
                val t = b.size * 0.56f
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.armsx.flashpoint.R.drawable.pad_thumb), null,
                    Modifier.align(Alignment.Center).offset { IntOffset(thumb.x.roundToInt(), thumb.y.roundToInt()) }.size(t.dp),
                )
            }
        }
        else -> {
            var down by remember { mutableStateOf(false) }
            val label = when (b.code) { Keys.MOUSE_LEFT -> "L-Click"; Keys.MOUSE_RIGHT -> "R-Click"; else -> Keys.label(b.code) }
            Box(
                base.then(edit).then(if (st.editing) Modifier else Modifier.pointerInput(b.code) {
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
            ) {
                Keycap(label, down, round = false, sizeDp = b.size)
            }
        }
    }
}

private val KeyLabel = Color(0xFFC4C4C8)

private fun isWideKey(code: Int) = code in setOf(
    KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT,
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL,
)

private fun dirCodes(kind: String) = if (kind == "wasd") intArrayOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D)
else intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

/**
 * The D-pad from ARMSX2's touch controls (sprites by the ARMSX2 team): four arms, each pointing
 * at the centre, down being the up arm turned round. Bits: 1 up, 2 down, 4 left, 8 right.
 */
@Composable
private fun SpriteDpad(lit: Int) {
    val armFill = 0.46f
    Box(Modifier.fillMaxSize()) {
        @Composable
        fun arm(res: Int, pressedRes: Int, on: Boolean, m: Modifier) = androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(if (on) pressedRes else res), null,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = m,
        )
        arm(com.armsx.flashpoint.R.drawable.pad_dpad_up, com.armsx.flashpoint.R.drawable.pad_dpad_up_pressed, lit and 1 != 0, Modifier.align(Alignment.TopCenter).androidx_fillH(armFill).aspectRatio(45f / 52f))
        arm(com.armsx.flashpoint.R.drawable.pad_dpad_up, com.armsx.flashpoint.R.drawable.pad_dpad_up_pressed, lit and 2 != 0, Modifier.align(Alignment.BottomCenter).androidx_fillH(armFill).aspectRatio(45f / 52f).rotate(180f))
        arm(com.armsx.flashpoint.R.drawable.pad_dpad_left, com.armsx.flashpoint.R.drawable.pad_dpad_left_pressed, lit and 4 != 0, Modifier.align(Alignment.CenterStart).androidx_fillW(armFill).aspectRatio(53f / 44f))
        arm(com.armsx.flashpoint.R.drawable.pad_dpad_right, com.armsx.flashpoint.R.drawable.pad_dpad_right_pressed, lit and 8 != 0, Modifier.align(Alignment.CenterEnd).androidx_fillW(armFill).aspectRatio(53f / 44f))
    }
}

private fun Modifier.androidx_fillH(f: Float) = this.then(Modifier.fillMaxHeight(f))
private fun Modifier.androidx_fillW(f: Float) = this.then(Modifier.fillMaxWidth(f))

/**
 * A key built the way the D-pad sprites are drawn, at the same pixel scale (one sprite pixel
 * = 1.3 dp): a black outline, a grained face (the arm sprite's own grain), one lighter row
 * along the top and a two-pixel dark lip along the bottom. Pressed, the face drops into the
 * lip and darkens, as the pressed sprites do. Letters get a square key, words a wide one.
 */
@Composable
private fun Keycap(label: String, pressed: Boolean, @Suppress("UNUSED_PARAMETER") round: Boolean, sizeDp: Float) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current
    val u = with(density) { 1.3.dp.toPx() }
    val grain = remember(u) {
        val src = android.graphics.BitmapFactory.decodeResource(ctx.resources, com.armsx.flashpoint.R.drawable.pad_dpad_left)
        val face = android.graphics.Bitmap.createBitmap(src, 10, 8, 25, 22)
        val scaled = android.graphics.Bitmap.createScaledBitmap(face, (25 * u).roundToInt(), (22 * u).roundToInt(), true)
        androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(scaled.asImageBitmap(), androidx.compose.ui.graphics.TileMode.Mirror, androidx.compose.ui.graphics.TileMode.Mirror),
        )
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = minOf(6.5f * u, size.height / 2)
            fun rr(l: Float, t: Float, rgt: Float, b: Float, rad: Float) =
                Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, rgt, b, androidx.compose.ui.geometry.CornerRadius(rad, rad))) }
            // Outline.
            drawPath(rr(0f, 0f, size.width, size.height, r), Color.Black)
            val drop = if (pressed) 2f * u else 0f
            val faceBottom = size.height - u - (if (pressed) 0f else 2f * u)
            // Lip: the key's lower edge, seen below the face.
            if (!pressed) drawPath(rr(u, u + 2f * u, size.width - u, size.height - u, r - u), Color(0xFF141414))
            val face = rr(u, u + drop, size.width - u, faceBottom + drop.coerceAtMost(0f), r - u)
            drawPath(face, grain)
            if (pressed) drawPath(face, Color(0x38000000))
            else clipPath(face) {
                drawRect(Color(0xFF444444), topLeft = Offset(0f, u), size = androidx.compose.ui.geometry.Size(size.width, u))
            }
        }
        Text(
            label,
            color = if (pressed) Color(0xFF8C8C90) else Color(0xFFB4B4B8),
            fontWeight = FontWeight.SemiBold,
            fontSize = (sizeDp * if (label.length > 2) 0.24f else 0.36f).coerceIn(11f, 24f).sp,
            modifier = Modifier.offset(y = if (pressed) 1.3.dp else (-1.3).dp),
        )
    }
}

/** The left stick as a direction pad: 8 directions, same keys as the D-pad. */
private fun stickInput(codes: IntArray, sizePx: Float, inj: GameInput, thumb: (Offset) -> Unit): Modifier = Modifier.pointerInput(codes.toList()) {
    val radius = sizePx * 0.30f
    awaitEachGesture {
        var mask = 0
        fun apply(v: Offset?) {
            var m = 0
            if (v != null) {
                val len = hypot(v.x, v.y)
                val clamped = if (len > radius) v * (radius / len) else v
                thumb(clamped)
                if (len > radius * 0.35f) {
                    val a = Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble()))
                    if (a in -157.5..-22.5) m = m or 1
                    if (a in 22.5..157.5) m = m or 2
                    if (abs(a) >= 112.5) m = m or 4
                    if (abs(a) <= 67.5) m = m or 8
                }
            } else thumb(Offset.Zero)
            for (i in 0..3) {
                val bit = 1 shl i
                if ((m and bit) != (mask and bit)) inj.key(codes[i], m and bit != 0)
            }
            mask = m
        }
        val d = awaitFirstDown()
        d.consume()
        val center = Offset(sizePx / 2, sizePx / 2)
        apply(d.position - center)
        while (true) {
            val e = awaitPointerEvent()
            val c = e.changes.firstOrNull { it.id == d.id } ?: break
            c.consume()
            if (!c.pressed) break
            apply(c.position - center)
        }
        apply(null)
    }
}

private fun press(inj: GameInput, code: Int, down: Boolean) = when (code) {
    Keys.MOUSE_LEFT -> if (down) inj.mouseDown(MotionEvent.BUTTON_PRIMARY) else inj.mouseUp(MotionEvent.BUTTON_PRIMARY)
    Keys.MOUSE_RIGHT -> if (down) inj.mouseDown(MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(MotionEvent.BUTTON_SECONDARY)
    else -> inj.key(code, down)
}

/** Arrow cluster: slide between directions, diagonals included. Bits: 1 up, 2 down, 4 left, 8 right. */
private fun dpadInput(codes: IntArray, sizePx: Float, inj: GameInput, lit: (Int) -> Unit): Modifier = Modifier.pointerInput(codes.toList()) {
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
internal fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    // ARMSX2's sheet: Night surface, 24 dp corners, outline, shadow.
    androidx.compose.material3.Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF13284A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2F4E76).copy(alpha = 0.5f)),
        shadowElevation = 22.dp,
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
internal fun Pill(label: String, on: Boolean = false, onClick: () -> Unit) {
    // ARMSX2's option chip.
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = Modifier.padding(3.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (on) Color(0xFF73A8FF).copy(alpha = 0.17f) else Color(0xFF13284A),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Color(0xFF73A8FF) else Color(0xFF2F4E76).copy(alpha = 0.38f)),
    ) {
        Text(
            label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (on) Color(0xFF73A8FF) else Color(0xFF9BAAC0), fontSize = 15.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        )
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
                    Pill("Stick (arrows)") { add(st, TouchButton("stick", x = 0.15f, y = 0.7f, size = 140f)) }
                    Pill("Stick (WASD)") { add(st, TouchButton("stick_wasd", x = 0.15f, y = 0.7f, size = 140f)) }
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
