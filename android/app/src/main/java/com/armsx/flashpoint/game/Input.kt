package com.armsx.flashpoint.game

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlinx.serialization.Serializable
import com.armsx.flashpoint.core.FpCore

/**
 * Feeds keyboard and mouse input into the game's view as if real devices were attached.
 * Web content (Ruffle, HTML5) sees ordinary keydown/keyup and mouse events, including hover,
 * which is what point-and-click games need.
 */
/** What the overlay and controller mapping drive: keys, a cursor, mouse buttons. */
interface GameInput {
    val cursorX: Float
    val cursorY: Float
    fun key(code: Int, down: Boolean)
    fun moveCursor(x: Float, y: Float)
    fun mouseDown(button: Int = MotionEvent.BUTTON_PRIMARY)
    fun mouseUp(button: Int = MotionEvent.BUTTON_PRIMARY)
    fun click(button: Int = MotionEvent.BUTTON_PRIMARY) {
        mouseDown(button)
        mouseUp(button)
    }
    fun releaseAll()
}

/** GameInput for the WebView: real Android key and mouse events dispatched to the view. */
class Injector(private val target: View) : GameInput {
    private val held = HashSet<Int>()
    private var buttons = 0
    private var downAt = 0L
    override var cursorX = 0f
        private set
    override var cursorY = 0f
        private set

    override fun key(code: Int, down: Boolean) {
        if (down && !held.add(code)) return
        if (!down && !held.remove(code)) return
        val now = SystemClock.uptimeMillis()
        target.dispatchKeyEvent(
            KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD),
        )
    }

    override fun releaseAll() {
        held.toList().forEach { key(it, false) }
        if (buttons != 0) mouseUp(buttons)
    }

    override fun moveCursor(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, target.width.toFloat().coerceAtLeast(1f) - 1)
        cursorY = y.coerceIn(0f, target.height.toFloat().coerceAtLeast(1f) - 1)
        if (buttons != 0) mouse(MotionEvent.ACTION_MOVE, buttons) else hover()
    }

    override fun mouseDown(button: Int) {
        if (buttons and button != 0) return
        if (buttons == 0) downAt = SystemClock.uptimeMillis()
        buttons = buttons or button
        if (!canSetActionButton) return finger(MotionEvent.ACTION_DOWN)
        // What a real mouse sends: DOWN (touch stream) then BUTTON_PRESS (generic stream).
        send(MotionEvent.ACTION_DOWN, buttons, 0, touch = true)
        send(MotionEvent.ACTION_BUTTON_PRESS, buttons, button, touch = false)
    }

    override fun mouseUp(button: Int) {
        if (buttons and button == 0) return
        buttons = buttons and button.inv()
        if (!canSetActionButton) return finger(MotionEvent.ACTION_UP)
        send(MotionEvent.ACTION_BUTTON_RELEASE, buttons, button, touch = false)
        send(MotionEvent.ACTION_UP, buttons, 0, touch = true)
    }

    private fun hover() {
        val now = SystemClock.uptimeMillis()
        val ev = obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, 0, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE)
        target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun mouse(action: Int, state: Int) {
        if (!canSetActionButton) return finger(action)
        send(action, state, 0, touch = true)
    }

    private fun send(action: Int, state: Int, actionButton: Int, touch: Boolean) {
        val now = SystemClock.uptimeMillis()
        val ev = obtain(if (downAt == 0L) now else downAt, now, action, state, MotionEvent.TOOL_TYPE_MOUSE, InputDevice.SOURCE_MOUSE)
        if (actionButton != 0) setActionButton?.invoke(ev, actionButton)
        if (touch) target.dispatchTouchEvent(ev) else target.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    /** Fallback where mouse buttons can't be expressed: a finger at the cursor (pages see a tap/drag). */
    private fun finger(action: Int) {
        val now = SystemClock.uptimeMillis()
        val ev = obtain(if (downAt == 0L) now else downAt, now, action, 0, MotionEvent.TOOL_TYPE_FINGER, InputDevice.SOURCE_TOUCHSCREEN)
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private companion object {
        /** MotionEvent.setActionButton is not in the public SDK; without it, buttons fall back to taps. */
        val setActionButton: java.lang.reflect.Method? = runCatching {
            MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
        }.getOrNull()
        val canSetActionButton get() = setActionButton != null
    }

    private fun obtain(down: Long, now: Long, action: Int, state: Int, tool: Int, source: Int): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = cursorX; y = cursorY; pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f; size = 1f })
        return MotionEvent.obtain(down, now, action, 1, props, coords, 0, state, 1f, 1f, 0, 0, source, 0)
    }
}

/** A keyboard key the overlay and controller mapping can send. */
data class KeyDef(val code: Int, val label: String)

object Keys {
    val ARROWS = listOf(
        KeyDef(KeyEvent.KEYCODE_DPAD_UP, "↑"), KeyDef(KeyEvent.KEYCODE_DPAD_DOWN, "↓"),
        KeyDef(KeyEvent.KEYCODE_DPAD_LEFT, "←"), KeyDef(KeyEvent.KEYCODE_DPAD_RIGHT, "→"),
    )
    val ALL: List<KeyDef> = buildList {
        addAll(ARROWS)
        add(KeyDef(KeyEvent.KEYCODE_SPACE, "Space"))
        add(KeyDef(KeyEvent.KEYCODE_ENTER, "Enter"))
        add(KeyDef(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift"))
        add(KeyDef(KeyEvent.KEYCODE_CTRL_LEFT, "Ctrl"))
        add(KeyDef(KeyEvent.KEYCODE_ALT_LEFT, "Alt"))
        add(KeyDef(KeyEvent.KEYCODE_TAB, "Tab"))
        add(KeyDef(KeyEvent.KEYCODE_ESCAPE, "Esc"))
        add(KeyDef(KeyEvent.KEYCODE_DEL, "Bksp"))
        ('A'..'Z').forEachIndexed { i, c -> add(KeyDef(KeyEvent.KEYCODE_A + i, c.toString())) }
        ('0'..'9').forEachIndexed { i, c -> add(KeyDef(KeyEvent.KEYCODE_0 + i, c.toString())) }
        (1..12).forEach { add(KeyDef(KeyEvent.KEYCODE_F1 + it - 1, "F$it")) }
    }
    fun label(code: Int) = ALL.firstOrNull { it.code == code }?.label ?: KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")

    /** Mouse "keys" for buttons and controller mapping. */
    const val MOUSE_LEFT = -1
    const val MOUSE_RIGHT = -2
}

/** On-screen button: a key, the arrow cluster, or a mouse button. Positions are fractions of the screen. */
@Serializable
data class TouchButton(
    val kind: String, // "key", "arrows", "wasd", "mouse"
    val code: Int = 0,
    val x: Float,
    val y: Float,
    val size: Float = 64f, // dp
)

@Serializable
data class TouchLayout(
    val mode: String = "direct", // "direct" | "touchpad" | "point"
    val visible: Boolean = true,
    val opacity: Float = 0.7f,
    val buttons: List<TouchButton> = DEFAULT_BUTTONS,
    /** Controller button keycode -> keyboard keycode (or Keys.MOUSE_*). */
    val pad: Map<Int, Int> = DEFAULT_PAD,
    val stickMovesCursor: Boolean = false,
) {
    companion object {
        val DEFAULT_BUTTONS = listOf(
            TouchButton("arrows", x = 0.12f, y = 0.70f, size = 150f),
            TouchButton("key", KeyEvent.KEYCODE_SPACE, x = 0.83f, y = 0.88f, size = 56f),
            TouchButton("key", KeyEvent.KEYCODE_Z, x = 0.74f, y = 0.66f, size = 62f),
            TouchButton("key", KeyEvent.KEYCODE_X, x = 0.87f, y = 0.62f, size = 62f),
            TouchButton("key", KeyEvent.KEYCODE_ENTER, x = 0.92f, y = 0.40f, size = 46f),
        )
        val DEFAULT_PAD = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_Z,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_X,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_C,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_CTRL_LEFT,
            KeyEvent.KEYCODE_BUTTON_R2 to Keys.MOUSE_LEFT,
            KeyEvent.KEYCODE_BUTTON_L2 to Keys.MOUSE_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}

/** Layouts saved per game, falling back to the user's default, then the built-in one. */
class LayoutStore(ctx: Context) {
    private val p = ctx.getSharedPreferences("touch", Context.MODE_PRIVATE)

    fun load(gameId: String): TouchLayout =
        (p.getString("game.$gameId", null) ?: p.getString("default", null))
            ?.let { runCatching { FpCore.json.decodeFromString(TouchLayout.serializer(), it) }.getOrNull() }
            ?: TouchLayout()

    fun save(gameId: String, l: TouchLayout, asDefault: Boolean) {
        val s = FpCore.json.encodeToString(TouchLayout.serializer(), l)
        p.edit().putString("game.$gameId", s).apply { if (asDefault) putString("default", s) }.apply()
    }
}
