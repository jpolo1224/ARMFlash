package com.armsx.flashpoint.game

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Controllers for every player: buttons and D-pad to keyboard keys and mouse buttons (per the
 * game's layout), left stick as arrows or cursor, right stick moves the cursor, analog
 * triggers as clicks, Back / Home opens the in-game menu.
 *
 * With the layout's local multiplayer on (GitHub issue #1), each controller is a player of its
 * own, numbered as Android numbers controllers (else in the order they were first used), and
 * presses its own player's keys ([TouchLayout.padFor]): players 2 to 4 move with their D-pad and
 * both sticks, and their triggers are their L2 / R2 buttons. Player 1 is as described above.
 *
 * Every key the game sees has one owner here. A press is recorded per source (a button, the
 * D-pad hat, the stick), the game gets a key down when the first source presses it and a key up
 * when the last one lets go, so a D-pad that reports as both keys and a hat, or a stick and a
 * D-pad on the same arrow, can never leave a key held. Opening the menu or leaving the game
 * lets go of everything.
 *
 * While the menu is open the pad drives the menu instead: D-pad and stick move focus, A selects,
 * B closes, L1 / R1 switch tabs. [ui] hands a key to the menu (the activity's own dispatch).
 */
class PadMapper(
    private val inj: GameInput,
    private val overlay: OverlayState,
    private val viewWidth: () -> Int,
    private val ui: (KeyEvent) -> Boolean,
) {

    private fun isPad(e: android.view.InputEvent): Boolean {
        val src = e.source
        return src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD &&
                e.device?.let { !it.isVirtual && it.keyboardType != InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true)
    }

    // ---- who holds what ------------------------------------------------------------------

    /** Game key (or Keys.MOUSE_*) -> the sources holding it down. */
    private val holders = HashMap<Int, MutableSet<String>>()

    private fun press(source: String, target: Int) {
        val set = holders.getOrPut(target) { HashSet() }
        if (!set.add(source) || set.size > 1) return
        when (target) {
            Keys.MOUSE_LEFT -> { showCursor(); inj.mouseDown() }
            Keys.MOUSE_RIGHT -> { showCursor(); inj.mouseDown(MotionEvent.BUTTON_SECONDARY) }
            else -> inj.key(target, true)
        }
    }

    private fun release(source: String, target: Int) {
        val set = holders[target] ?: return
        if (!set.remove(source) || set.isNotEmpty()) return
        holders.remove(target)
        when (target) {
            Keys.MOUSE_LEFT -> { showCursor(); inj.mouseUp() }
            Keys.MOUSE_RIGHT -> { showCursor(); inj.mouseUp(MotionEvent.BUTTON_SECONDARY) }
            else -> inj.key(target, false)
        }
    }

    /** What each button pressed down, so its release lets go of the same key even if the mapping changed meanwhile. */
    private val buttonTarget = HashMap<String, Int>()

    /** Let go of everything the pad holds (menu opened, game paused or left). */
    fun reset() {
        holders.clear()
        buttonTarget.clear()
        hatMask = 0; stickMask = 0; trig = 0
        others.clear()
        aimX = 0f; aimY = 0f
        inj.releaseAll()
    }

    // ---- players -------------------------------------------------------------------------

    /** Controllers Android gave no number, in the order they were first used. */
    private val firstUsed = ArrayList<Int>()

    /** The player [e] comes from, 1 to 4: always 1 while the layout's local multiplayer is off. */
    private fun player(e: android.view.InputEvent): Int {
        if (!overlay.layout.multiplayer) return 1
        val n = e.device?.controllerNumber ?: 0
        if (n > 0) return n.coerceAtMost(4)
        if (e.deviceId !in firstUsed) firstUsed += e.deviceId
        return (firstUsed.indexOf(e.deviceId) + 1).coerceIn(1, 4)
    }

    /** Players 2 to 4's D-pad hat, sticks and triggers. */
    private class Other { var hat = 0; var left = 0; var right = 0; var trig = 0 }
    private val others = HashMap<Int, Other>()

    private val menuOpen get() = overlay.menu || overlay.padPick != null || overlay.picking

    // ---- keys ----------------------------------------------------------------------------

    fun key(event: KeyEvent): Boolean {
        com.armsx.flashpoint.ui.PadInput.onKey(event)
        if (!isPad(event)) return false
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) toggleMenu()
            return true
        }
        if (menuOpen) return menuKey(event, code, down)

        if (event.repeatCount > 0) return true
        val who = player(event)
        val map = overlay.layout.padFor(who)
        val source = "k$who:$code"
        if (down) {
            val target = map[code] ?: return false
            buttonTarget[source] = target
            press(source, target)
        } else {
            val target = buttonTarget.remove(source) ?: return map.containsKey(code)
            release(source, target)
        }
        return true
    }

    private fun toggleMenu() {
        if (overlay.padPick != null) { overlay.padPick = null; return }
        if (overlay.picking) { overlay.picking = false; return }
        overlay.editing = false
        if (!overlay.menu) reset()
        overlay.menu = !overlay.menu
    }

    private fun menuKey(event: KeyEvent, code: Int, down: Boolean): Boolean {
        if (holders.isNotEmpty()) reset()
        val first = down && event.repeatCount == 0
        return when (code) {
            // Focus moves go straight to the menu (see OverlayState.menuInput); held keys repeat.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) {
                    val dir = when (code) {
                        KeyEvent.KEYCODE_DPAD_UP -> androidx.compose.ui.focus.FocusDirection.Up
                        KeyEvent.KEYCODE_DPAD_DOWN -> androidx.compose.ui.focus.FocusDirection.Down
                        KeyEvent.KEYCODE_DPAD_LEFT -> androidx.compose.ui.focus.FocusDirection.Left
                        else -> androidx.compose.ui.focus.FocusDirection.Right
                    }
                    overlay.menuInput.trySend(MenuInput.Move(dir))
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                overlay.menuInput.trySend(MenuInput.Key(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount, 0, event.deviceId, 0, 0, InputDevice.SOURCE_DPAD)))
                true
            }
            KeyEvent.KEYCODE_BUTTON_B -> { if (event.action == KeyEvent.ACTION_UP) closeMenuLayer(); true }
            KeyEvent.KEYCODE_BUTTON_START -> { if (event.action == KeyEvent.ACTION_UP) toggleMenu(); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { if (first) stepTab(-1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { if (first) stepTab(1); true }
            // Nothing else may leak past the menu into the game behind it.
            else -> true
        }
    }

    private fun closeMenuLayer() {
        when {
            overlay.padPick != null -> overlay.padPick = null
            overlay.picking -> overlay.picking = false
            else -> { overlay.menu = false; return } // the menu's own close plays its sound
        }
        MenuSfx.play(MenuSfx.Event.BACK)
    }

    private fun stepTab(d: Int) {
        if (overlay.padPick != null || overlay.picking) return
        val tabs = MenuTab.entries
        overlay.tab = tabs[(overlay.tab.ordinal + d + tabs.size) % tabs.size]
        MenuSfx.play(MenuSfx.Event.NAV)
    }

    // ---- sticks, hat, triggers -----------------------------------------------------------

    private var stickMask = 0
    private var hatMask = 0
    private var aimX = 0f
    private var aimY = 0f
    private var trig = 0
    private val arrows = intArrayOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)

    /** Press or let go of [keys] (up, down, left, right; 0 = none) as the direction bits change. */
    private fun applyMask(source: String, old: Int, new: Int, keys: IntArray = arrows) {
        for (i in 0..3) {
            val b = 1 shl i
            if ((old and b) != (new and b) && keys[i] != 0) {
                if (new and b != 0) press(source, keys[i]) else release(source, keys[i])
            }
        }
    }

    fun motion(ev: MotionEvent): Boolean {
        if (!isPad(ev) || ev.action != MotionEvent.ACTION_MOVE) return false
        // In the menu, leave the hat and stick to Android, which turns them into D-pad keys for focus.
        if (menuOpen) { if (holders.isNotEmpty()) reset(); return false }
        val who = player(ev)
        if (who > 1) return otherMotion(who, ev)

        val hat = hatMask(ev.getAxisValue(MotionEvent.AXIS_HAT_X), ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        applyMask("hat", hatMask, hat); hatMask = hat

        val lx = ev.getAxisValue(MotionEvent.AXIS_X)
        val ly = ev.getAxisValue(MotionEvent.AXIS_Y)
        val rx = ev.getAxisValue(MotionEvent.AXIS_Z)
        val ry = ev.getAxisValue(MotionEvent.AXIS_RZ)
        // One stick moves the mouse, the other is a second D-pad (the arrow keys).
        val leftMouse = overlay.layout.mouseStick == "left"
        val m = if (leftMouse) stickDirections(rx, ry, stickMask) else stickDirections(lx, ly, stickMask)
        applyMask("stick", stickMask, m); stickMask = m
        if (leftMouse) { aimX = lx; aimY = ly } else { aimX = rx; aimY = ry }
        // Analog triggers on pads that report them as axes instead of L2/R2 keys.
        val t = (if (ev.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f || ev.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f) 1 else 0) or
            (if (ev.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f || ev.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f) 2 else 0)
        if (t != trig) {
            if ((t xor trig) and 1 != 0) { if (t and 1 != 0) press("rt", Keys.MOUSE_LEFT) else release("rt", Keys.MOUSE_LEFT) }
            if ((t xor trig) and 2 != 0) { if (t and 2 != 0) press("lt", Keys.MOUSE_RIGHT) else release("lt", Keys.MOUSE_RIGHT) }
            trig = t
        }
        // Only a real push counts as using the pad (resting sticks still send small moves).
        if (hat != 0 || stickMask != 0 || t != 0 || aimX * aimX + aimY * aimY > 0.09f) com.armsx.flashpoint.ui.PadInput.active.value = true
        if (aimX * aimX + aimY * aimY > 0.04f) startAim()
        return true
    }

    /** Players 2 to 4: D-pad hat and both sticks press their D-pad keys, triggers their L2 / R2. */
    private fun otherMotion(who: Int, ev: MotionEvent): Boolean {
        val s = others.getOrPut(who) { Other() }
        val map = overlay.layout.padFor(who)
        val dirs = intArrayOf(
            map[KeyEvent.KEYCODE_DPAD_UP] ?: 0, map[KeyEvent.KEYCODE_DPAD_DOWN] ?: 0,
            map[KeyEvent.KEYCODE_DPAD_LEFT] ?: 0, map[KeyEvent.KEYCODE_DPAD_RIGHT] ?: 0,
        )
        val hat = hatMask(ev.getAxisValue(MotionEvent.AXIS_HAT_X), ev.getAxisValue(MotionEvent.AXIS_HAT_Y))
        applyMask("hat$who", s.hat, hat, dirs); s.hat = hat
        val l = stickDirections(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y), s.left)
        applyMask("stick$who", s.left, l, dirs); s.left = l
        val r = stickDirections(ev.getAxisValue(MotionEvent.AXIS_Z), ev.getAxisValue(MotionEvent.AXIS_RZ), s.right)
        applyMask("rstick$who", s.right, r, dirs); s.right = r
        val t = (if (ev.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f || ev.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f) 1 else 0) or
            (if (ev.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f || ev.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f) 2 else 0)
        if (t != s.trig) {
            map[KeyEvent.KEYCODE_BUTTON_R2]?.let { k -> if ((t xor s.trig) and 1 != 0) { if (t and 1 != 0) press("rt$who", k) else release("rt$who", k) } }
            map[KeyEvent.KEYCODE_BUTTON_L2]?.let { k -> if ((t xor s.trig) and 2 != 0) { if (t and 2 != 0) press("lt$who", k) else release("lt$who", k) } }
            s.trig = t
        }
        if (hat != 0 || l != 0 || r != 0 || t != 0) com.armsx.flashpoint.ui.PadInput.active.value = true
        return true
    }

    private fun hatMask(x: Float, y: Float) =
        (if (y < -0.5f) 1 else 0) or (if (y > 0.5f) 2 else 0) or (if (x < -0.5f) 4 else 0) or (if (x > 0.5f) 8 else 0)

    /**
     * The stick as eight directions: a round dead zone with a gap between press (0.5) and
     * release (0.35), so resting near the edge never chatters, and diagonals only in the 45
     * degrees around them, so pushing right is right even when the thumb is a little off.
     */
    private fun stickDirections(x: Float, y: Float, prev: Int): Int {
        val mag = kotlin.math.hypot(x, y)
        if (mag < (if (prev != 0) 0.35f else 0.5f)) return 0
        val a = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble()))
        var m = 0
        if (a in -157.5..-22.5) m = m or 1
        if (a in 22.5..157.5) m = m or 2
        if (kotlin.math.abs(a) >= 112.5) m = m or 4
        if (kotlin.math.abs(a) <= 67.5) m = m or 8
        return m
    }

    private fun showCursor() {
        overlay.cursor = androidx.compose.ui.geometry.Offset(inj.cursorX, inj.cursorY)
    }

    private var aiming = false
    /** Right stick: move the cursor every frame while it's deflected, faster the further it goes. */
    private fun startAim() {
        if (aiming) return
        aiming = true
        val choreo = android.view.Choreographer.getInstance()
        var last = 0L
        choreo.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                val dt = if (last == 0L) 0.016f else (t - last) / 1e9f
                last = t
                val mag = kotlin.math.sqrt(aimX * aimX + aimY * aimY)
                if (mag < 0.15f || menuOpen) { aiming = false; return }
                val speed = viewWidth() * 0.9f * ((mag - 0.15f) / 0.85f).let { it * it + 0.15f }
                inj.moveCursor(inj.cursorX + aimX / mag * speed * dt, inj.cursorY + aimY / mag * speed * dt)
                showCursor()
                choreo.postFrameCallback(this)
            }
        })
    }
}
