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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
    val gameId: String
    /** Whether this screen can use a custom Vulkan driver (native Flash). */
    val usesDriver: Boolean
    /** A Flash game: the pause menu offers the player and graphics choice. */
    val isFlash: Boolean get() = false
    /** A Flash game that runs as a page (no native player; Flash Player means its own browser). */
    val isFlashPage: Boolean get() = false
    /** Whether this player runs RetroArch shaders (native Flash), and how to tell it they changed. */
    val hasShaders: Boolean get() = false
    /** Errors Ruffle reported running this game (0 when it never struggled, or not Ruffle). */
    fun flashTrouble(): Long = 0
    fun shaderChanged(full: Boolean) {}
    /** Whether this player has frame generation (native Flash), and how to tell it the settings changed. */
    val hasFrameGen: Boolean get() = false
    fun frameGenChanged() {}
    /** Frame generation's overlay line, "" while it is off. */
    fun frameGenStatus(): String = ""
    /** Why frame generation cannot run here (see FrameGenPrefs.Reason), 0 when it can. */
    fun frameGenReason(): Int = 1
    fun save(asDefault: Boolean)
    fun keyboard()
    fun restart()
    fun exit()
    /** The quick menu opened or closed: pause or resume the game. */
    fun menuOpen(open: Boolean)
    fun display(scale: String, quality: String)
    var menuLeft: Boolean
    /** Frames the game has drawn so far (for the FPS counter), or -1 if the player cannot say. */
    fun frames(): Long = -1
    /** Whether [setVolume] works for this player. */
    val hasVolume: Boolean get() = false
    fun setVolume(v: Float) {}
}

class OverlayState(layout: TouchLayout) {
    var layout by mutableStateOf(layout)
    var menu by mutableStateOf(false)
    var tab by mutableStateOf(MenuTab.SESSION)
    /** Controller button being remapped (its keycode), while the key picker is open for it. */
    var padPick by mutableStateOf<Int?>(null)
    /** Whose buttons the Controller card shows and edits, with local multiplayer on (1 to 4). */
    var padPlayer by mutableStateOf(1)
    var editing by mutableStateOf(false)
    var selected by mutableStateOf<Int?>(null)
    var picking by mutableStateOf(false)
    /** The key being changed while the picker is open for it (null: the picker adds a new key). */
    var swapping by mutableStateOf<Int?>(null)
    var cursor by mutableStateOf<Offset?>(null)
    /** Bumped on every touch so the auto-hide timer starts over. */
    var touchTick by mutableStateOf(0)
    /** Where the pad was in the quick menu, per tab, so reopening it lands there (as ARMSX2 does). */
    val menuMemory = MenuMemory()
    /**
     * The pad's moves and presses while the quick menu is open, delivered to the menu itself
     * rather than through Android's window focus, which a touch on the menu (touch mode) or
     * the game view behind it can take away, leaving the D-pad driving the game instead.
     */
    val menuInput = kotlinx.coroutines.channels.Channel<MenuInput>(16)
    /** A controller is attached (kept current by the game screen). */
    var padConnected by mutableStateOf(false)
    /** The gesture slot ("Swipe up", "Double-tap"...) whose key is being picked. */
    var gesturePick by mutableStateOf<String?>(null)
    /** The layout editor's profiles sheet is open. */
    var profiles by mutableStateOf(false)
    /** Android's own dialog (the camera permission) is up: pausing for it does not open the menu. */
    var holdMenu = false
    /** What gestures press keys through (set by the overlay). */
    var gestureInput: GameInput? = null
    /** The on-screen controls' touch areas in window pixels, which gestures leave alone. */
    var controlRects: List<android.graphics.RectF> = emptyList()
    var screenW = 0f
    var screenH = 0f
    fun controlAt(x: Float, y: Float) = controlRects.any { it.contains(x, y) }
}

sealed interface MenuInput {
    data class Move(val dir: androidx.compose.ui.focus.FocusDirection) : MenuInput
    data class Key(val event: android.view.KeyEvent) : MenuInput
}

/** Whether a real controller is attached: what "Auto" hides the touch keys and menu button for. */
fun gamepadAttached(): Boolean = android.view.InputDevice.getDeviceIds().any { id ->
    val d = android.view.InputDevice.getDevice(id) ?: return@any false
    !d.isVirtual && (d.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD) || d.supportsSource(android.view.InputDevice.SOURCE_JOYSTICK))
}

private const val PauseRevealMs = 3000L

private val Accent = Color(0xFF5CB531)

@Composable
fun GameOverlay(st: OverlayState, inj: GameInput, host: OverlayHost) {
    val onSave: (Boolean) -> Unit = host::save
    // The game also pauses while its touch layout is being edited, as in ARMSX2.
    androidx.compose.runtime.LaunchedEffect(st.menu || st.editing) { host.menuOpen(st.menu || st.editing) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val l = st.layout
        val density = LocalDensity.current
        // ARMSX2's multi-touch layer, fed by each key's touch area (see TouchSurface).
        val surface = remember(inj) { TouchSurface { code, down -> press(inj, code, down) } }
        androidx.compose.runtime.DisposableEffect(surface) { onDispose { surface.releaseAll() } }
        val multi = TouchPrefs.multiTouch.value && !st.editing
        androidx.compose.runtime.SideEffect { st.gestureInput = inj; st.screenW = w; st.screenH = h }

        // Mouse layer (under the buttons, so a finger on a button never reaches it).
        if (l.mode != "direct" && !st.editing) {
            Box(Modifier.fillMaxSize().then(mouseInput(l.mode, inj) { st.cursor = it }))
        }

        // The cursor, whenever something other than a direct touch is driving the mouse.
        st.cursor?.let { c ->
            Canvas(Modifier.fillMaxSize()) { with(Cursors) { drawCursor(c) } }
        }

        // ARMSX2's on-screen controls modes. Auto-hide: hidden once the screen has gone untouched
        // for the set time, back on the next touch (which the keys then also receive).
        var timedOut by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(st.touchTick, l.showMode, l.hideAfter) {
            timedOut = false
            if (l.showMode == "timed") { kotlinx.coroutines.delay(l.hideAfter * 1000L); timedOut = true }
        }
        val keysShown = st.editing || when {
            !l.visible -> false
            l.showMode == "off" -> false
            // Auto follows the last input, as ARMSX2 does on handhelds: a device with built-in
            // controls (Odin) always has a pad attached, so hiding for that alone hid the keys for
            // good. Touch brings them back, a button press hides them.
            l.showMode == "auto" -> !(st.padConnected && com.armsx.flashpoint.ui.PadInput.active.value)
            l.showMode == "timed" -> !timedOut
            else -> true
        }
        // Hidden keys, the menu or the editor: nothing stays held.
        LaunchedEffect(keysShown, st.menu, st.editing, multi) { surface.releaseAll() }
        if (keysShown && multi) {
            val reach = TouchPrefs.reach.floatValue
            val keyAreas = ArrayList<TouchSurface.Key>()
            val padAreas = ArrayList<TouchSurface.Pad>()
            l.buttons.forEachIndexed { i, b ->
                val hp = with(density) { b.size.dp.toPx() }
                when (b.kind) {
                    "arrows", "wasd" -> padAreas += TouchSurface.Pad(i, dirCodes(b.kind), b.x * w, b.y * h, hp / 2)
                    "key", "mouse" -> if (!b.tapToHold) {
                        val wp = if (b.kind == "key" && isWideKey(b.code)) hp * 1.9f else hp
                        keyAreas += TouchSurface.Key(i, b.code, b.turbo, b.x * w, b.y * h, hp * reach, (wp - hp) / 2)
                    }
                }
            }
            androidx.compose.runtime.SideEffect { surface.keys = keyAreas; surface.pads = padAreas }
        }
        // Where the controls are, for gestures (which take only touches on the game itself).
        val corner = with(density) { 64.dp.toPx() }
        androidx.compose.runtime.SideEffect {
            st.controlRects = buildList {
                add(android.graphics.RectF(origin.x + w - corner, origin.y, origin.x + w, origin.y + corner))
                if (!keysShown) return@buildList
                if (TouchPrefs.halfScreenStick.value) add(android.graphics.RectF(origin.x, origin.y, origin.x + w / 2, origin.y + h))
                l.buttons.forEach { b ->
                    val hp = with(density) { b.size.dp.toPx() }
                    val wp = if (b.kind == "key" && isWideKey(b.code)) hp * 1.9f else hp
                    val grow = if (multi && b.kind in setOf("key", "mouse")) hp * TouchPrefs.reach.floatValue - hp / 2 else 0f
                    val cx = origin.x + b.x * w
                    val cy = origin.y + b.y * h
                    add(android.graphics.RectF(cx - wp / 2 - grow, cy - hp / 2 - grow, cx + wp / 2 + grow, cy + hp / 2 + grow))
                }
            }
        }
        // ARMSX2's snap grid while editing: the faint squares the keys' centres snap to.
        if (st.editing && TouchPrefs.gridSnap.value) {
            val gpx = w / GRID_COLS
            Canvas(Modifier.fillMaxSize()) {
                val line = Color.White.copy(alpha = 0.13f)
                var gx = 0f
                while (gx <= size.width) { drawLine(line, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f); gx += gpx }
                var gy = 0f
                while (gy <= size.height) { drawLine(line, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f); gy += gpx }
            }
        }
        // ARMSX2's Half-Screen Sticks: the left half (outside the keys) is a stick for the arrow keys.
        // While editing, its area is marked; in play, the stick shows under the thumb holding it.
        if (st.editing && TouchPrefs.halfScreenStick.value) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(0.5f).background(Color(0x1A6CC63F)).border(1.dp, Color(0x666CC63F)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Half-screen stick\nThe left half steers the arrow keys",
                    color = Color(0xCCFFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        if (keysShown && !st.editing && TouchPrefs.halfScreenStick.value) {
            var at by remember { mutableStateOf<Offset?>(null) }
            var thumb by remember { mutableStateOf(Offset.Zero) }
            val baseDp = 140f
            val basePx = with(density) { baseDp.dp.toPx() }
            val thumbPx = basePx * 0.56f
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.5f).then(halfStickInput(dirCodes("arrows"), inj, basePx) { o, t -> at = o; thumb = t })) {
                at?.let { o ->
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.armsx.flashpoint.R.drawable.pad_stick_base), null,
                        Modifier.offset { IntOffset((o.x - basePx / 2).roundToInt(), (o.y - basePx / 2).roundToInt()) }.size(baseDp.dp).alpha(l.opacity),
                    )
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.armsx.flashpoint.R.drawable.pad_thumb), null,
                        Modifier.offset { IntOffset((o.x + thumb.x - thumbPx / 2).roundToInt(), (o.y + thumb.y - thumbPx / 2).roundToInt()) }
                            .size((baseDp * 0.56f).dp).alpha(l.opacity),
                    )
                }
            }
        }
        if (keysShown) {
            l.buttons.forEachIndexed { i, b ->
                ButtonView(b, w, h, l.opacity, st, i, inj, surface, multi)
            }
        }

        // Menu button, top right. Hidden with tap to reveal (the first tap in its corner shows it
        // for a few seconds, the next opens the menu) and while a controller is attached, whose
        // Back / Select button opens the menu instead. The corner always answers a tap, and a
        // long press opens the menu straight away, so it can never lock anyone out.
        if (!st.menu && !st.editing) {
            var revealed by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(revealed) { if (revealed) { kotlinx.coroutines.delay(PauseRevealMs); revealed = false } }
            val shown = (!l.pauseTapToReveal && !(st.padConnected && com.armsx.flashpoint.ui.PadInput.active.value)) || revealed
            val shownNow = androidx.compose.runtime.rememberUpdatedState(shown)
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(52.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { if (shownNow.value) { st.menu = true; st.editing = false } else revealed = true },
                            onLongPress = { st.menu = true; st.editing = false },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (shown) Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color(0x66000000)),
                    contentAlignment = Alignment.Center,
                ) { Text("☰", color = Color.White, fontSize = 20.sp) }
            }
        }

        val osdAt = when (OsdPrefs.corner.value) {
            OsdPrefs.Corner.TOP_LEFT -> Alignment.TopStart
            OsdPrefs.Corner.TOP_RIGHT -> Alignment.TopEnd
            OsdPrefs.Corner.BOTTOM_LEFT -> Alignment.BottomStart
            OsdPrefs.Corner.BOTTOM_RIGHT -> Alignment.BottomEnd
        }
        // Top right sits under the menu button.
        OsdOverlay(host::frames, Modifier.align(osdAt).padding(top = if (OsdPrefs.corner.value == OsdPrefs.Corner.TOP_RIGHT) 56.dp else 0.dp), host::frameGenStatus)
        if (st.menu) QuickMenu(st, host)
        WelcomeBannerOverlay(this)
        com.armsx.flashpoint.discord.FriendOnlineBanner(Modifier.align(Alignment.TopCenter))
        DataPrepBanner(host.title, Modifier.align(Alignment.BottomCenter))
        if (st.editing) {
            // ARMSX2's editor panel: moved off what is being edited by itself (a key in the top
            // half puts it at the bottom), or by its grip; resized; folded down to the grip.
            val landscape = w > h
            val dxS = EditorPanel.dx(landscape)
            val dyS = EditorPanel.dy(landscape)
            val dockBottom = st.selected?.let { st.layout.buttons.getOrNull(it) }?.let { it.y < 0.5f } == true
            Box(
                Modifier.align(if (dockBottom) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(top = if (dockBottom) 0.dp else 10.dp, bottom = if (dockBottom) 10.dp else 0.dp)
                    // The stored offset is away from the edge it docks to, so it flips with the dock.
                    .offset { IntOffset(dxS.floatValue.roundToInt(), (if (dockBottom) -dyS.floatValue else dyS.floatValue).roundToInt()) },
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalDensity provides androidx.compose.ui.unit.Density(density.density * EditorPanel.scale(landscape).floatValue, density.fontScale),
                ) { EditBar(st, onSave, Modifier, w, h) }
            }
        }
        if (st.picking) KeyPicker(st)
        if (st.profiles) ProfilesPanel(st)
        st.padPick?.let { PadKeyPicker(st, it, host) }
        st.gesturePick?.let { GestureKeyPicker(st, it) }
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
private fun ButtonView(b: TouchButton, w: Float, h: Float, opacity: Float, st: OverlayState, index: Int, inj: GameInput, surface: TouchSurface, multi: Boolean) {
    val density = LocalDensity.current
    val wide = b.kind == "key" && isWideKey(b.code)
    val heightPx = with(density) { b.size.dp.toPx() }
    val widthPx = if (wide) heightPx * 1.9f else heightPx
    // ARMSX2's grid: while editing, a key is drawn on its nearest grid cross as it is dragged; the
    // layout keeps the free position until the finger lifts, then takes the snapped one.
    val snap = st.editing && TouchPrefs.gridSnap.value
    val cx = (if (snap) snapFrac(b.x, w, w) else b.x) * w
    val cy = (if (snap) snapFrac(b.y, h, w) else b.y) * h
    val x = (cx - widthPx / 2).roundToInt()
    val y = (cy - heightPx / 2).roundToInt()
    val sel = st.editing && st.selected == index
    val sizeMod = if (wide) Modifier.size(width = (b.size * 1.9f).dp, height = b.size.dp) else Modifier.size(b.size.dp)
    val fade = if (st.editing) 0.95f else opacity
    // The fade goes on what is drawn, not on the control's box: a fade layer is cut off at the
    // box's edge, which sliced a floating D-pad or stick moved toward the thumb.
    val box = Modifier.offset { IntOffset(x, y) }.then(sizeMod)
    val base = box.alpha(fade)
    val edit = if (st.editing) Modifier.border(2.dp, if (sel) Accent else Color(0x88FFFFFF), RoundedCornerShape(12.dp)).pointerInput(index) {
        detectDragGestures(
            onDragStart = { st.selected = index },
            // ARMSX2's grid: a key let go of lands on the nearest grid cross.
            onDragEnd = { if (TouchPrefs.gridSnap.value) snapToGrid(st, w, h, only = index) },
        ) { change, drag ->
            change.consume()
            val cur = st.layout.buttons[index]
            st.layout = st.layout.copy(buttons = st.layout.buttons.toMutableList().also {
                it[index] = cur.copy(x = (cur.x + drag.x / w).coerceIn(0f, 1f), y = (cur.y + drag.y / h).coerceIn(0f, 1f))
            })
        }
    }.clickable { st.selected = index } else Modifier
    // Where this control's touch area starts, read fresh by its touch handler (Multi-Touch).
    val at by androidx.compose.runtime.rememberUpdatedState(Offset(x.toFloat(), y.toFloat()))

    when (b.kind) {
        "arrows", "wasd" -> {
            val codes = dirCodes(b.kind)
            var ownLit by remember { mutableStateOf(0) }
            var ownShift by remember { mutableStateOf(Offset.Zero) }
            val input = when {
                st.editing -> Modifier
                multi -> surfaceInput(surface) { at }
                else -> dpadInput(codes, widthPx, inj, { ownShift = it }) { ownLit = it }
            }
            val lit = if (multi) surface.lit[index] ?: 0 else ownLit
            val moved = if (multi) surface.shift[index] ?: Offset.Zero else ownShift
            Box(box.then(edit).then(input)) {
                // Floating: the D-pad drawn where the thumb holding it centred it.
                Box(Modifier.fillMaxSize().offset { IntOffset(moved.x.roundToInt(), moved.y.roundToInt()) }.alpha(fade)) { SpriteDpad(lit) }
                if (b.kind == "wasd") Text("WASD", color = KeyLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center).offset { IntOffset(moved.x.roundToInt(), moved.y.roundToInt()) }.alpha(fade))
            }
        }
        "stick", "stick_wasd" -> {
            val codes = dirCodes(if (b.kind == "stick_wasd") "wasd" else "arrows")
            var thumb by remember { mutableStateOf(Offset.Zero) }
            var shift by remember { mutableStateOf(Offset.Zero) }
            Box(box.then(edit).then(if (st.editing) Modifier else stickInput(codes, widthPx, inj, { thumb = it }, { shift = it }))) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.armsx.flashpoint.R.drawable.pad_stick_base), null,
                    Modifier.fillMaxSize().offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }.alpha(fade),
                )
                val t = b.size * 0.56f
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.armsx.flashpoint.R.drawable.pad_thumb), null,
                    Modifier.align(Alignment.Center).offset { IntOffset((shift.x + thumb.x).roundToInt(), (shift.y + thumb.y).roundToInt()) }.size(t.dp).alpha(fade),
                )
            }
        }
        else -> {
            val label = when (b.code) { Keys.MOUSE_LEFT -> "L-Click"; Keys.MOUSE_RIGHT -> "R-Click"; else -> Keys.label(b.code) }
            if (multi && !b.tapToHold) {
                // Multi-Touch: the key answers within its reach (ARMSX2's hit circle), so its touch
                // area is that much bigger than the key drawn in its middle.
                val r = heightPx * TouchPrefs.reach.floatValue
                val boxW = widthPx - heightPx + 2 * r
                val boxH = 2 * r
                val bx = cx - boxW / 2
                val by = cy - boxH / 2
                val areaAt by androidx.compose.runtime.rememberUpdatedState(Offset(bx, by))
                Box(
                    Modifier.offset { IntOffset(bx.roundToInt(), by.roundToInt()) }
                        .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                        .then(surfaceInput(surface) { areaAt }),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(sizeMod.alpha(opacity)) { Keycap(label, surface.held[index] == true, round = false, sizeDp = b.size) }
                }
            } else {
                var down by remember { mutableStateOf(false) }
                Box(base.then(edit).then(if (st.editing) Modifier else keyInput(inj, b) { down = it })) {
                    Keycap(label, down, round = false, sizeDp = b.size)
                }
            }
        }
    }
}

/**
 * One key handling its own touch (Multi-Touch off, or a Tap-Hold key): ARMSX2's press handling,
 * with its Turbo and Tap-Hold. A key let go of when the control goes away never stays down.
 */
private fun keyInput(inj: GameInput, b: TouchButton, shown: (Boolean) -> Unit): Modifier = Modifier.pointerInput(b.code, b.tapToHold, b.turbo) {
    var latched = false
    fun emit(on: Boolean) {
        if (b.turbo > 0) Turbo.press("touch", b.code, on, b.turbo) { c, d -> press(inj, c, d) } else press(inj, b.code, on)
    }
    try {
        awaitEachGesture {
            awaitFirstDown().consume()
            if (b.tapToHold) {
                latched = !latched
                shown(latched)
                emit(latched)
                if (latched) TouchHaptics.tick()
                do {
                    val e = awaitPointerEvent()
                    e.changes.forEach { it.consume() }
                } while (e.changes.any { it.pressed })
            } else {
                shown(true)
                emit(true)
                TouchHaptics.tick()
                try {
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { it.consume() }
                    } while (e.changes.any { it.pressed })
                } finally {
                    emit(false)
                    shown(false)
                }
            }
        }
    } finally {
        if (latched) { emit(false); shown(false) }
    }
}

/**
 * A Multi-Touch control's touch area: its fingers go to the shared [TouchSurface] in overlay
 * coordinates ([at] is where the area starts), which decides what they press.
 */
private fun surfaceInput(surface: TouchSurface, at: () -> Offset): Modifier = Modifier.pointerInput(surface) {
    val mine = HashSet<Long>()
    try {
        awaitPointerEventScope {
            while (true) {
                val ev = awaitPointerEvent()
                val o = at()
                for (ch in ev.changes) {
                    val id = ch.id.value
                    if (ch.pressed) {
                        mine += id
                        surface.touch(id, ch.position.x + o.x, ch.position.y + o.y)
                    } else if (mine.remove(id)) surface.lift(id)
                    ch.consume()
                }
                surface.commit()
            }
        }
    } finally {
        mine.forEach(surface::lift)
        surface.commit()
    }
}

/**
 * ARMSX2's Half-Screen Sticks, for the arrow keys: a thumb anywhere on the area steers from where
 * it landed. [show] gets where the stick is (null: let go) and where its thumb is, to draw them;
 * [sizePx] is the drawn stick's size.
 */
private fun halfStickInput(codes: IntArray, inj: GameInput, sizePx: Float, show: (Offset?, Offset) -> Unit): Modifier = Modifier.pointerInput(codes.toList(), sizePx) {
    val visual = sizePx * 0.30f
    awaitEachGesture {
        val d = awaitFirstDown()
        d.consume()
        show(d.position, Offset.Zero)
        var mask = 0
        fun apply(m: Int) {
            for (i in 0..3) {
                val bit = 1 shl i
                if ((m and bit) != (mask and bit)) inj.key(codes[i], m and bit != 0)
            }
            if (m and mask.inv() != 0) TouchHaptics.tick()
            mask = m
        }
        try {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == d.id } ?: break
                c.consume()
                if (!c.pressed) break
                val v = c.position - d.position
                val travel = visual * TouchPrefs.stickTravel.intValue / 100f
                val len = hypot(v.x, v.y)
                show(d.position, if (len > 0f) v * (minOf(len, travel) / len) * (visual / travel) else Offset.Zero)
                apply(if (len > travel * 0.35f) angleMask(v.x, v.y) else 0)
            }
        } finally {
            apply(0)
            show(null, Offset.Zero)
        }
    }
}

/** ARMSX2's grid: the overlay's width in this many columns, rows at the same pitch. */
private const val GRID_COLS = 24f

/** [f], a fraction of [dim], on the nearest grid line (the grid's pitch comes from the width [w]). */
private fun snapFrac(f: Float, dim: Float, w: Float): Float {
    val grid = w / GRID_COLS
    if (grid <= 0f || dim <= 0f) return f
    return (((f * dim / grid).roundToInt() * grid) / dim).coerceIn(0.02f, 0.98f)
}

/** Put keys on the nearest grid cross: one ([only]) or all of them. */
private fun snapToGrid(st: OverlayState, w: Float, h: Float, only: Int? = null) {
    if (w <= 0f || h <= 0f) return
    st.layout = st.layout.copy(buttons = st.layout.buttons.mapIndexed { i, b ->
        if (only != null && i != only) b else b.copy(x = snapFrac(b.x, w, w), y = snapFrac(b.y, h, w))
    })
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
    // ARMSX2's D-pad spacing pulls each arm back toward its edge, opening a gap in the middle.
    val armFill = (0.46f - TouchPrefs.dpadSpacing.floatValue).coerceAtLeast(0.2f)
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

/**
 * The left stick as a direction pad: 8 directions, same keys as the D-pad. ARMSX2's Stick travel
 * sets how far the thumb goes before a direction presses, and its Floating stick centres the stick
 * where the thumb lands ([shift]: how far the stick moved for that).
 */
private fun stickInput(codes: IntArray, sizePx: Float, inj: GameInput, thumb: (Offset) -> Unit, shift: (Offset) -> Unit): Modifier = Modifier.pointerInput(codes.toList(), sizePx) {
    val visual = sizePx * 0.30f
    awaitEachGesture {
        var mask = 0
        val travel = visual * TouchPrefs.stickTravel.intValue / 100f
        fun apply(v: Offset?) {
            var m = 0
            if (v != null) {
                val len = hypot(v.x, v.y)
                // The thumb is drawn within the stick's ring, however far the travel is.
                thumb(if (len > 0f) v * (minOf(len, travel) / len) * (visual / travel) else Offset.Zero)
                if (len > travel * 0.35f) m = angleMask(v.x, v.y)
            } else {
                thumb(Offset.Zero)
                shift(Offset.Zero)
            }
            for (i in 0..3) {
                val bit = 1 shl i
                if ((m and bit) != (mask and bit)) inj.key(codes[i], m and bit != 0)
            }
            if (m and mask.inv() != 0) TouchHaptics.tick()
            mask = m
        }
        val d = awaitFirstDown()
        d.consume()
        var center = Offset(sizePx / 2, sizePx / 2)
        if (TouchPrefs.floatingStick.value) {
            // Kept far enough in that full travel stays reachable every way.
            center = Offset(d.position.x.coerceIn(visual, sizePx - visual), d.position.y.coerceIn(visual, sizePx - visual))
            shift(center - Offset(sizePx / 2, sizePx / 2))
        }
        // finally: a gesture cut short (the menu opening, the keys hiding) still lets go.
        try {
            apply(d.position - center)
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull { it.id == d.id } ?: break
                c.consume()
                if (!c.pressed) break
                apply(c.position - center)
            }
        } finally {
            apply(null)
        }
    }
}

private fun press(inj: GameInput, code: Int, down: Boolean) = when (code) {
    Keys.MOUSE_LEFT -> if (down) inj.mouseDown(MotionEvent.BUTTON_PRIMARY) else inj.mouseUp(MotionEvent.BUTTON_PRIMARY)
    Keys.MOUSE_RIGHT -> if (down) inj.mouseDown(MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(MotionEvent.BUTTON_SECONDARY)
    else -> inj.key(code, down)
}

/** Arrow cluster: slide between directions, diagonals included. Bits: 1 up, 2 down, 4 left, 8 right. */
private fun dpadInput(codes: IntArray, sizePx: Float, inj: GameInput, shift: (Offset) -> Unit, lit: (Int) -> Unit): Modifier = Modifier.pointerInput(codes.toList(), sizePx) {
    awaitEachGesture {
        var mask = 0
        // Floating: the D-pad centres where the thumb lands (kept inside its square).
        var center = Offset(sizePx / 2, sizePx / 2)
        fun apply(p: Offset?) {
            val m = if (p != null) dpadMask(p.x - center.x, p.y - center.y, sizePx / 2) else 0
            for (i in 0..3) {
                val bit = 1 shl i
                if ((m and bit) != (mask and bit)) inj.key(codes[i], m and bit != 0)
            }
            if (m and mask.inv() != 0) TouchHaptics.tick()
            mask = m
            lit(m)
        }
        val d = awaitFirstDown()
        d.consume()
        if (TouchPrefs.floatingStick.value) {
            center = d.position
            shift(center - Offset(sizePx / 2, sizePx / 2))
        }
        try {
            apply(d.position)
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Main)
                val c = e.changes.firstOrNull { it.id == d.id } ?: break
                c.consume()
                if (!c.pressed) break
                apply(c.position)
            }
        } finally {
            apply(null)
            shift(Offset.Zero)
        }
    }
}

@Composable
internal fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    // ARMSX2's sheet (24 dp corners, outline, shadow) in Flashpoint's colours.
    androidx.compose.material3.Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF2A2A2D),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF55555B).copy(alpha = 0.5f)),
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
        color = if (on) Color(0xFF6CC63F).copy(alpha = 0.17f) else Color(0xFF2A2A2D),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (on) Color(0xFF6CC63F) else Color(0xFF55555B).copy(alpha = 0.38f)),
    ) {
        Text(
            label, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (on) Color(0xFF6CC63F) else Color(0xFFA0A0A6), fontSize = 15.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditBar(st: OverlayState, onSave: (Boolean) -> Unit, modifier: Modifier, w: Float, h: Float) {
    fun update(i: Int, f: (TouchButton) -> TouchButton) {
        st.layout = st.layout.copy(buttons = st.layout.buttons.toMutableList().also { it[i] = f(it[i]) })
    }
    val landscape = w > h
    Panel(modifier) {
        // ARMSX2's grip row: fold the panel down, make it smaller or bigger, drag the grip to move
        // it off the keys (double-tap the grip to put it back).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PanelSizeButton(if (EditorPanel.collapsed.value) "\u25BC" else "\u25B2") { EditorPanel.collapsed.value = !EditorPanel.collapsed.value }
            PanelSizeButton("\uFF0D") { EditorPanel.resize(landscape, -0.1f) }
            Box(
                Modifier.weight(1f).height(30.dp)
                    .pointerInput(landscape) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            EditorPanel.dx(landscape).floatValue = (EditorPanel.dx(landscape).floatValue + drag.x).coerceIn(-w * 0.42f, w * 0.42f)
                            EditorPanel.dy(landscape).floatValue = (EditorPanel.dy(landscape).floatValue + drag.y).coerceIn(0f, h * 0.78f)
                        }
                    }
                    .pointerInput(landscape) { detectTapGestures(onDoubleTap = { EditorPanel.reset(landscape) }) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x99FFFFFF)))
            }
            PanelSizeButton("\uFF0B") { EditorPanel.resize(landscape, 0.1f) }
        }
        // Folded: the grip row only, so everything under the panel can be reached.
        if (EditorPanel.collapsed.value) return@Panel
        Text("Drag keys to move them. Tap one to select it, then change, resize or remove it.", color = Color.White, fontSize = 12.sp)
        FlowRow {
            Pill("Add key") { st.swapping = null; st.picking = true }
            st.selected?.let { i -> st.layout.buttons.getOrNull(i)?.let { b ->
                Pill("Change key", on = true) { st.swapping = i; st.picking = true }
                Pill("Bigger") { resize(st, i, 1.15f) }
                Pill("Smaller") { resize(st, i, 1 / 1.15f) }
                if (b.kind in setOf("key", "mouse")) {
                    // ARMSX2's per-button Turbo (Off, Fast, Med, Slow) and Tap-Hold.
                    Pill(Turbo.label(b.turbo), on = b.turbo > 0) {
                        update(i) { it.copy(turbo = Turbo.steps[(Turbo.steps.indexOf(it.turbo) + 1).mod(Turbo.steps.size)]) }
                    }
                    Pill(if (b.tapToHold) "Tap-Hold On" else "Tap-Hold Off", on = b.tapToHold) { update(i) { it.copy(tapToHold = !it.tapToHold) } }
                }
                Pill("Remove") {
                    st.layout = st.layout.copy(buttons = st.layout.buttons.filterIndexed { j, _ -> j != i }); st.selected = null
                }
            } }
        }
        st.selected?.let { st.layout.buttons.getOrNull(it) }?.takeIf { it.kind == "arrows" || it.kind == "wasd" }?.let {
            // ARMSX2's D-Pad spacing, for every D-pad.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("D-Pad spacing", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(96.dp))
                Slider(
                    TouchPrefs.dpadSpacing.floatValue, { TouchPrefs.dpadSpacing.floatValue = it }, valueRange = 0f..0.35f,
                    onValueChangeFinished = { TouchPrefs.save() }, modifier = Modifier.width(200.dp),
                )
                Text("${(TouchPrefs.dpadSpacing.floatValue * 100).roundToInt()}%", color = Color(0xFFA0A0A6), fontSize = 12.sp)
            }
        }
        // ARMSX2's editor switches. They are for every game.
        FlowRow {
            val multi = TouchPrefs.multiTouch.value
            Pill(if (multi) "Multi-Touch On" else "Multi-Touch Off", on = multi) { TouchPrefs.multiTouch.value = !multi; TouchPrefs.save() }
            Pill(if (multi) "Glide: ${TouchPrefs.glide.value.label}" else "Glide (needs Multi-touch)", on = multi) {
                if (multi) {
                    val modes = TouchPrefs.Glide.entries
                    TouchPrefs.glide.value = modes[(TouchPrefs.glide.value.ordinal + 1) % modes.size]
                    TouchPrefs.save()
                }
            }
            Pill(if (TouchPrefs.gridSnap.value) "Grid On" else "Grid Off", on = TouchPrefs.gridSnap.value) {
                TouchPrefs.gridSnap.value = !TouchPrefs.gridSnap.value
                TouchPrefs.save()
                if (TouchPrefs.gridSnap.value) snapToGrid(st, w, h)
            }
            Pill(if (TouchPrefs.floatingStick.value) "Floating D-Pad/Stick On" else "Floating D-Pad/Stick Off", on = TouchPrefs.floatingStick.value) {
                TouchPrefs.floatingStick.value = !TouchPrefs.floatingStick.value; TouchPrefs.save()
            }
            Pill(if (TouchPrefs.halfScreenStick.value) "Half-Screen Stick On" else "Half-Screen Stick Off", on = TouchPrefs.halfScreenStick.value) {
                TouchPrefs.halfScreenStick.value = !TouchPrefs.halfScreenStick.value; TouchPrefs.save()
            }
            Pill("Profiles") { st.profiles = true }
        }
        FlowRow {
            Pill("Reset") { st.layout = st.layout.copy(buttons = TouchLayout.DEFAULT_BUTTONS); st.selected = null }
            Pill("Save for this game", on = true) { st.editing = false; onSave(false) }
            Pill("Save as default") { st.editing = false; onSave(true) }
        }
    }
}

/** ARMSX2's grip row buttons (fold, smaller, bigger). */
@Composable
private fun PanelSizeButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF1F1F2C)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * ARMSX2's Touch Control Profiles: save the keys being edited under a name, or load a saved set
 * onto this layout, for any game.
 */
@Composable
private fun ProfilesPanel(st: OverlayState) {
    var all by remember { mutableStateOf(TouchProfiles.all()) }
    var name by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { st.profiles = false }, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 560.dp).clickable(enabled = false) {}) {
            Text("Touch Control Profiles", color = Color.White, fontWeight = FontWeight.Black)
            Text(
                "A profile keeps a set of keys for any game. Loading one puts its keys on the layout you are editing: save the layout to keep them.",
                color = Color(0xFFBDBDC2), fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                if (all.isEmpty()) Text("No profiles yet.", color = Color(0xFFA0A0A6), fontSize = 13.sp)
                all.keys.sortedBy { it.lowercase() }.forEach { n ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Pill("Load", on = true) {
                            all[n]?.let { st.layout = st.layout.copy(buttons = it) }
                            st.selected = null
                            st.profiles = false
                        }
                        Pill("Delete") { TouchProfiles.delete(n); all = TouchProfiles.all() }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Save current layout as new profile:", color = Color.White, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    name, { name = it.take(40) }, singleLine = true,
                    placeholder = { Text("Profile name") },
                    modifier = Modifier.weight(1f),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF6CC63F), unfocusedBorderColor = Color(0xFF55555B),
                        cursorColor = Color(0xFF6CC63F),
                    ),
                )
                Pill("Save As", on = name.isNotBlank()) {
                    if (name.isNotBlank()) { TouchProfiles.save(name.trim(), st.layout.buttons); all = TouchProfiles.all(); name = "" }
                }
            }
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
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable { st.picking = false; st.swapping = null }, contentAlignment = Alignment.Center) {
        Panel(Modifier.widthIn(max = 560.dp)) {
            Text(if (st.swapping != null) "Change this key to" else "Add a key", color = Color.White, fontWeight = FontWeight.Black)
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

/** Add the picked key, or (when changing one) put it in place of the selected key, keeping its spot and size. */
/**
 * [b] moved to the free spot nearest where it asks to go, so keys added one after another (the
 * number row, say) each get a place of their own instead of all landing in the middle on top of
 * each other. Spirals out a key's width at a time; where the screen is full, it stays put.
 */
private fun freeSpot(st: OverlayState, b: TouchButton): TouchButton {
    val w = st.screenW
    val h = st.screenH
    if (w <= 0f || h <= 0f) return b
    val dens = android.content.res.Resources.getSystem().displayMetrics.density
    val gap = 6f * dens
    fun halfW(t: TouchButton) = t.size * dens / 2f * (if (t.kind == "key" && isWideKey(t.code)) 1.9f else 1f)
    fun halfH(t: TouchButton) = t.size * dens / 2f
    fun clear(x: Float, y: Float) = st.layout.buttons.none { o ->
        kotlin.math.abs(o.x * w - x * w) < halfW(o) + halfW(b) + gap && kotlin.math.abs(o.y * h - y * h) < halfH(o) + halfH(b) + gap
    }
    fun inside(x: Float, y: Float) = x * w >= halfW(b) && x * w <= w - halfW(b) && y * h >= halfH(b) && y * h <= h - halfH(b)
    if (clear(b.x, b.y)) return b
    val stepX = (halfW(b) * 2 + gap) / w
    val stepY = (halfH(b) * 2 + gap) / h
    for (ring in 1..14) {
        for (dy in -ring..ring) for (dx in -ring..ring) {
            if (kotlin.math.abs(dx) != ring && kotlin.math.abs(dy) != ring) continue
            val x = b.x + dx * stepX
            val y = b.y + dy * stepY
            if (inside(x, y) && clear(x, y)) return b.copy(x = x, y = y)
        }
    }
    return b
}

private fun add(st: OverlayState, b: TouchButton) {
    val i = st.swapping
    if (i != null && i in st.layout.buttons.indices) {
        val old = st.layout.buttons[i]
        // A cluster swapped for a single key (or back) takes that kind's usual size.
        val sameShape = (old.kind in setOf("key", "mouse")) == (b.kind in setOf("key", "mouse"))
        val repl = b.copy(x = old.x, y = old.y, size = if (sameShape) old.size else b.size)
        st.layout = st.layout.copy(buttons = st.layout.buttons.toMutableList().also { it[i] = repl })
        st.selected = i
    } else {
        st.layout = st.layout.copy(buttons = st.layout.buttons + freeSpot(st, b))
        st.selected = st.layout.buttons.lastIndex
    }
    st.swapping = null
    st.picking = false
}

/**
 * "Getting <game> ready for its first run": shown while the game server indexes a game's big data
 * (Happy Wheels' 9 GB level pack takes a minute or two, once), with how far it has got.
 */
@Composable
private fun DataPrepBanner(title: String, modifier: Modifier = Modifier) {
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            progress = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.armsx.flashpoint.core.FpCore.preparing() }
            kotlinx.coroutines.delay(1000)
        }
    }
    val p = progress ?: return
    val pct = if (p.second > 0) (p.first * 100 / p.second).toInt() else 0
    androidx.compose.material3.Surface(
        modifier = modifier.padding(bottom = 18.dp, start = 16.dp, end = 16.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = Color(0xE6202024),
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp).widthIn(max = 520.dp)) {
            Text(
                "Getting ${title.ifBlank { "this game" }} ready for its first run: $pct%",
                color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                "This takes a minute or two, once. Its levels and other online parts load when it is done.",
                color = Color(0xFFBDBDC2), fontSize = 12.sp,
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                color = Color(0xFF6CC63F),
                trackColor = Color(0xFF48484D),
            )
        }
    }
}
