package com.armsx.flashpoint.game

/**
 * Controllers for every player: buttons and D-pad to keyboard keys and mouse buttons (per the
 * game's layout), left stick as arrows or cursor, right stick moves the cursor, analog
 * triggers as clicks, Back / Home opens the in-game menu.
 */
class PadMapper(private val inj: GameInput, private val overlay: OverlayState, private val viewWidth: () -> Int) {

    private fun isPad(e: android.view.InputEvent): Boolean {
        val src = e.source
        return src and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            src and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            (src and android.view.InputDevice.SOURCE_DPAD == android.view.InputDevice.SOURCE_DPAD &&
                e.device?.let { !it.isVirtual && it.keyboardType != android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true)
    }

    fun key(event: android.view.KeyEvent): Boolean {
        if (!isPad(event)) return false
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_BACK || code == android.view.KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == android.view.KeyEvent.ACTION_UP) overlay.menu = !overlay.menu
            return true
        }
        val target = overlay.layout.pad[code] ?: return false
        if (event.repeatCount > 0) return true
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        when (target) {
            Keys.MOUSE_LEFT -> { showCursor(); if (down) inj.mouseDown() else inj.mouseUp() }
            Keys.MOUSE_RIGHT -> { showCursor(); if (down) inj.mouseDown(android.view.MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(android.view.MotionEvent.BUTTON_SECONDARY) }
            else -> inj.key(target, down)
        }
        return true
    }

    private var stickMask = 0
    private var hatMask = 0
    private var aimX = 0f
    private var aimY = 0f
    private var trig = 0

    fun motion(ev: android.view.MotionEvent): Boolean {
        if (!isPad(ev) || ev.action != android.view.MotionEvent.ACTION_MOVE) return false
        val arrows = intArrayOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        fun mask(x: Float, y: Float, t: Float) = (if (y < -t) 1 else 0) or (if (y > t) 2 else 0) or (if (x < -t) 4 else 0) or (if (x > t) 8 else 0)
        fun apply(old: Int, new: Int) {
            for (i in 0..3) { val b = 1 shl i; if ((old and b) != (new and b)) inj.key(arrows[i], new and b != 0) }
        }
        // D-pad as a hat.
        val hat = mask(ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y), 0.5f)
        apply(hatMask, hat); hatMask = hat
        val lx = ev.getAxisValue(android.view.MotionEvent.AXIS_X)
        val ly = ev.getAxisValue(android.view.MotionEvent.AXIS_Y)
        val rx = ev.getAxisValue(android.view.MotionEvent.AXIS_Z)
        val ry = ev.getAxisValue(android.view.MotionEvent.AXIS_RZ)
        if (overlay.layout.stickMovesCursor) {
            aimX = rx + lx; aimY = ry + ly
            apply(stickMask, 0); stickMask = 0
        } else {
            val m = mask(lx, ly, 0.5f)
            apply(stickMask, m); stickMask = m
            aimX = rx; aimY = ry
        }
        // Analog triggers on pads that report them as axes instead of L2/R2 keys.
        val t = (if (ev.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER) > 0.5f || ev.getAxisValue(android.view.MotionEvent.AXIS_GAS) > 0.5f) 1 else 0) or
            (if (ev.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER) > 0.5f || ev.getAxisValue(android.view.MotionEvent.AXIS_BRAKE) > 0.5f) 2 else 0)
        if (t != trig) {
            if ((t xor trig) and 1 != 0) { showCursor(); if (t and 1 != 0) inj.mouseDown() else inj.mouseUp() }
            if ((t xor trig) and 2 != 0) { showCursor(); if (t and 2 != 0) inj.mouseDown(android.view.MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(android.view.MotionEvent.BUTTON_SECONDARY) }
            trig = t
        }
        if (hypot2(aimX, aimY) > 0.04f) startAim()
        return true
    }

    private fun hypot2(x: Float, y: Float) = x * x + y * y

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
                val mag = kotlin.math.sqrt(hypot2(aimX, aimY))
                if (mag < 0.15f) { aiming = false; return }
                val speed = viewWidth() * 0.9f * ((mag - 0.15f) / 0.85f).let { it * it + 0.15f }
                inj.moveCursor(inj.cursorX + aimX / mag * speed * dt, inj.cursorY + aimY / mag * speed * dt)
                showCursor()
                choreo.postFrameCallback(this)
            }
        })
    }

}
