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
                KeyCharacterMap.VIRTUAL_KEYBOARD, ScanCodes.of(code), KeyEvent.FLAG_KEEP_TOUCH_MODE, InputDevice.SOURCE_KEYBOARD),
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
    /**
     * When the touch keys show (ARMSX2's on-screen controls setting): "auto" hides them while a
     * controller is attached, "always", "off", or "timed", which hides them [hideAfter] seconds
     * after the last touch until the screen is touched again.
     */
    val showMode: String = "auto",
    val hideAfter: Int = 5,
    /** ARMSX2's tap to reveal: the menu button stays hidden until its corner is tapped. */
    val pauseTapToReveal: Boolean = false,
    /** Display: "default" (the game decides), "fit", "stretch", "fill", "original". */
    val scale: String = "default",
    /** Flash quality: "low", "medium", "high", "best". */
    val quality: String = "high",
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

    /** Which edge the quick menu docks to (follows the person, not the game). */
    var menuLeft: Boolean
        get() = p.getBoolean("menuLeft", false)
        set(v) = p.edit().putBoolean("menuLeft", v).apply()

    fun load(gameId: String): TouchLayout =
        (p.getString("game.$gameId", null) ?: p.getString("default", null))
            ?.let { runCatching { FpCore.json.decodeFromString(TouchLayout.serializer(), it) }.getOrNull() }
            ?: TouchLayout()

    fun save(gameId: String, l: TouchLayout, asDefault: Boolean) {
        val s = FpCore.json.encodeToString(TouchLayout.serializer(), l)
        p.edit().putString("game.$gameId", s).apply { if (asDefault) putString("default", s) }.apply()
    }
}

/**
 * Linux scan codes for the keys the overlay and controller send. The browser takes a key's
 * physical code (KeyboardEvent.code) from it, and Ruffle tracks held keys by that code: with
 * none, every key looked alike, so holding one key while pressing another lost the second
 * one's release and it stayed held in the game.
 */
object ScanCodes {
    private val map = mapOf(
        KeyEvent.KEYCODE_ESCAPE to 1,
        KeyEvent.KEYCODE_MINUS to 12,
        KeyEvent.KEYCODE_EQUALS to 13,
        KeyEvent.KEYCODE_DEL to 14,
        KeyEvent.KEYCODE_TAB to 15,
        KeyEvent.KEYCODE_LEFT_BRACKET to 26,
        KeyEvent.KEYCODE_RIGHT_BRACKET to 27,
        KeyEvent.KEYCODE_ENTER to 28,
        KeyEvent.KEYCODE_CTRL_LEFT to 29,
        KeyEvent.KEYCODE_SEMICOLON to 39,
        KeyEvent.KEYCODE_APOSTROPHE to 40,
        KeyEvent.KEYCODE_GRAVE to 41,
        KeyEvent.KEYCODE_SHIFT_LEFT to 42,
        KeyEvent.KEYCODE_BACKSLASH to 43,
        KeyEvent.KEYCODE_COMMA to 51,
        KeyEvent.KEYCODE_PERIOD to 52,
        KeyEvent.KEYCODE_SLASH to 53,
        KeyEvent.KEYCODE_SHIFT_RIGHT to 54,
        KeyEvent.KEYCODE_ALT_LEFT to 56,
        KeyEvent.KEYCODE_SPACE to 57,
        KeyEvent.KEYCODE_CAPS_LOCK to 58,
        KeyEvent.KEYCODE_F11 to 87,
        KeyEvent.KEYCODE_F12 to 88,
        KeyEvent.KEYCODE_CTRL_RIGHT to 97,
        KeyEvent.KEYCODE_ALT_RIGHT to 100,
        KeyEvent.KEYCODE_MOVE_HOME to 102,
        KeyEvent.KEYCODE_DPAD_UP to 103,
        KeyEvent.KEYCODE_PAGE_UP to 104,
        KeyEvent.KEYCODE_DPAD_LEFT to 105,
        KeyEvent.KEYCODE_DPAD_RIGHT to 106,
        KeyEvent.KEYCODE_MOVE_END to 107,
        KeyEvent.KEYCODE_DPAD_DOWN to 108,
        KeyEvent.KEYCODE_PAGE_DOWN to 109,
        KeyEvent.KEYCODE_INSERT to 110,
        KeyEvent.KEYCODE_FORWARD_DEL to 111,
        KeyEvent.KEYCODE_1 to 2,
        KeyEvent.KEYCODE_2 to 3,
        KeyEvent.KEYCODE_3 to 4,
        KeyEvent.KEYCODE_4 to 5,
        KeyEvent.KEYCODE_5 to 6,
        KeyEvent.KEYCODE_6 to 7,
        KeyEvent.KEYCODE_7 to 8,
        KeyEvent.KEYCODE_8 to 9,
        KeyEvent.KEYCODE_9 to 10,
        KeyEvent.KEYCODE_0 to 11,
        KeyEvent.KEYCODE_Q to 16,
        KeyEvent.KEYCODE_W to 17,
        KeyEvent.KEYCODE_E to 18,
        KeyEvent.KEYCODE_R to 19,
        KeyEvent.KEYCODE_T to 20,
        KeyEvent.KEYCODE_Y to 21,
        KeyEvent.KEYCODE_U to 22,
        KeyEvent.KEYCODE_I to 23,
        KeyEvent.KEYCODE_O to 24,
        KeyEvent.KEYCODE_P to 25,
        KeyEvent.KEYCODE_A to 30,
        KeyEvent.KEYCODE_S to 31,
        KeyEvent.KEYCODE_D to 32,
        KeyEvent.KEYCODE_F to 33,
        KeyEvent.KEYCODE_G to 34,
        KeyEvent.KEYCODE_H to 35,
        KeyEvent.KEYCODE_J to 36,
        KeyEvent.KEYCODE_K to 37,
        KeyEvent.KEYCODE_L to 38,
        KeyEvent.KEYCODE_Z to 44,
        KeyEvent.KEYCODE_X to 45,
        KeyEvent.KEYCODE_C to 46,
        KeyEvent.KEYCODE_V to 47,
        KeyEvent.KEYCODE_B to 48,
        KeyEvent.KEYCODE_N to 49,
        KeyEvent.KEYCODE_M to 50,
        KeyEvent.KEYCODE_F1 to 59,
        KeyEvent.KEYCODE_F2 to 60,
        KeyEvent.KEYCODE_F3 to 61,
        KeyEvent.KEYCODE_F4 to 62,
        KeyEvent.KEYCODE_F5 to 63,
        KeyEvent.KEYCODE_F6 to 64,
        KeyEvent.KEYCODE_F7 to 65,
        KeyEvent.KEYCODE_F8 to 66,
        KeyEvent.KEYCODE_F9 to 67,
        KeyEvent.KEYCODE_F10 to 68,
    )

    fun of(keyCode: Int): Int = map[keyCode] ?: 0
}
