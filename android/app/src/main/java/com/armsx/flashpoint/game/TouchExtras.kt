package com.armsx.flashpoint.game

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.armsx.flashpoint.core.FpCore
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * ARMSX2's on-screen control options (its Settings > Controls > On-Screen Controls and the layout
 * editor's switches), for keyboard keys. They are for every game, as in ARMSX2; the layout itself
 * (which keys, where) stays per game.
 */
object TouchPrefs {
    /**
     * What a finger sliding across keys presses (ARMSX2's glide modes). Needs Multi-Touch: without
     * it each key handles its own touch and nothing can be glided across.
     */
    enum class Glide(val label: String, val help: String) {
        FOLLOW("Follow", "Only the keys under the finger now, let go as it leaves them."),
        HOLD_FIRST("Hold first", "The first key the finger lands on stays down until it lifts, plus the one under it now."),
        HOLD_ALL("Hold all", "Every key the finger crosses stays down until it lifts."),
    }

    /** ARMSX2's multi-touch layer: a key answers a little outside itself, and fingers glide. On by default. */
    val multiTouch = mutableStateOf(true)
    /** How far a key reaches, as a part of its size from its centre (ARMSX2's 0.62 by default). */
    val reach = mutableFloatStateOf(0.62f)
    val glide = mutableStateOf(Glide.FOLLOW)
    /** A short vibration on every key press (ARMSX2's Touch Haptics). On by default. */
    val haptics = mutableStateOf(true)
    /** Vibration strength in percent (ARMSX2's Vibration Strength). */
    val hapticStrength = mutableIntStateOf(100)
    /** How far a thumb moves from a touch stick's centre before it presses a direction, in percent. */
    val stickTravel = mutableIntStateOf(100)
    /** A touch stick centres itself where the thumb lands. */
    val floatingStick = mutableStateOf(false)
    /** The left half of the screen is an invisible stick for the arrow keys (ARMSX2's Half-Screen Sticks). */
    val halfScreenStick = mutableStateOf(false)
    /** The gap between the D-pad's arms, as a part of its half size (ARMSX2's D-Pad spacing). */
    val dpadSpacing = mutableFloatStateOf(0f)
    /** The layout editor snaps keys to a grid. */
    val gridSnap = mutableStateOf(false)

    /** ARMSX2's Gesture Control: swipes and a double-tap on the game press keys. Off by default. */
    val gestures = mutableStateOf(false)
    val swipeUp = mutableIntStateOf(0)
    val swipeDown = mutableIntStateOf(0)
    val swipeLeft = mutableIntStateOf(0)
    val swipeRight = mutableIntStateOf(0)
    val doubleTap = mutableIntStateOf(0)
    /** Double-tap holds its key down until the next double-tap, instead of a quick press. */
    val doubleTapHold = mutableStateOf(false)
    /** How far a swipe travels, as a part of the screen's shorter edge. */
    val swipeDistance = mutableFloatStateOf(0.17f)

    private var prefs: SharedPreferences? = null
    internal var app: Context? = null

    fun load(ctx: Context) {
        app = ctx.applicationContext
        if (prefs != null) return
        val p = ctx.getSharedPreferences("touch", Context.MODE_PRIVATE)
        prefs = p
        multiTouch.value = p.getBoolean("opt.multiTouch", true)
        reach.floatValue = p.getFloat("opt.reach", 0.62f).coerceIn(0.5f, 0.95f)
        glide.value = runCatching { Glide.valueOf(p.getString("opt.glide", null)!!) }.getOrDefault(Glide.FOLLOW)
        haptics.value = p.getBoolean("opt.haptics", true)
        hapticStrength.intValue = p.getInt("opt.hapticStrength", 100).coerceIn(0, 200)
        stickTravel.intValue = p.getInt("opt.stickTravel", 100).coerceIn(50, 200)
        floatingStick.value = p.getBoolean("opt.floatingStick", false)
        halfScreenStick.value = p.getBoolean("opt.halfScreenStick", false)
        dpadSpacing.floatValue = p.getFloat("opt.dpadSpacing", 0f).coerceIn(0f, 0.35f)
        gridSnap.value = p.getBoolean("opt.gridSnap", false)
        gestures.value = p.getBoolean("opt.gestures", false)
        swipeUp.intValue = p.getInt("opt.swipeUp", 0)
        swipeDown.intValue = p.getInt("opt.swipeDown", 0)
        swipeLeft.intValue = p.getInt("opt.swipeLeft", 0)
        swipeRight.intValue = p.getInt("opt.swipeRight", 0)
        doubleTap.intValue = p.getInt("opt.doubleTap", 0)
        doubleTapHold.value = p.getBoolean("opt.doubleTapHold", false)
        swipeDistance.floatValue = p.getFloat("opt.swipeDistance", 0.17f).coerceIn(0.05f, 0.4f)
    }

    /** Write every option (they are few, and one call keeps the setters simple). */
    fun save() {
        prefs?.edit()?.apply {
            putBoolean("opt.multiTouch", multiTouch.value)
            putFloat("opt.reach", reach.floatValue)
            putString("opt.glide", glide.value.name)
            putBoolean("opt.haptics", haptics.value)
            putInt("opt.hapticStrength", hapticStrength.intValue)
            putInt("opt.stickTravel", stickTravel.intValue)
            putBoolean("opt.floatingStick", floatingStick.value)
            putBoolean("opt.halfScreenStick", halfScreenStick.value)
            putFloat("opt.dpadSpacing", dpadSpacing.floatValue)
            putBoolean("opt.gridSnap", gridSnap.value)
            putBoolean("opt.gestures", gestures.value)
            putInt("opt.swipeUp", swipeUp.intValue)
            putInt("opt.swipeDown", swipeDown.intValue)
            putInt("opt.swipeLeft", swipeLeft.intValue)
            putInt("opt.swipeRight", swipeRight.intValue)
            putInt("opt.doubleTap", doubleTap.intValue)
            putBoolean("opt.doubleTapHold", doubleTapHold.value)
            putFloat("opt.swipeDistance", swipeDistance.floatValue)
        }?.apply()
    }

    /** The gesture slots, for the menu: name and the state holding its key (0 = none). */
    val gestureSlots get() = listOf(
        "Swipe up" to swipeUp, "Swipe down" to swipeDown, "Swipe left" to swipeLeft,
        "Swipe right" to swipeRight, "Double-tap" to doubleTap,
    )
}

/**
 * ARMSX2's touch haptic: a short, crisp tick on an on-screen key press, from the device's own
 * vibrator. Presses landing together (D-pad and a key in the same frame) make one tick, and fast
 * mashing is rate limited, so the vibrator's queue can't fill up on a slow device.
 */
object TouchHaptics {
    private var last = 0L
    private var vibrator: Vibrator? = null

    fun tick() {
        if (!TouchPrefs.haptics.value) return
        val strength = TouchPrefs.hapticStrength.intValue
        if (strength <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - last < 24L) return
        last = now
        val v = vibrator ?: run {
            val ctx = TouchPrefs.app ?: return
            (if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java))?.also { vibrator = it }
        } ?: return
        if (!v.hasVibrator()) return
        val amp = (0.6f * strength / 100f * 255f).roundToInt().coerceIn(1, 255)
        runCatching { v.vibrate(VibrationEffect.createOneShot(12, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}

/**
 * ARMSX2's per-button Turbo: while held, the key goes down and up on its own. [frames] (at 60 a
 * second) between the two: 2 fast, 4 medium, 8 slow, 0 off.
 */
object Turbo {
    /** The speeds, in the order the editor steps through them (ARMSX2's: Off, Fast, Medium, Slow). */
    val steps = listOf(0, 2, 4, 8)

    fun label(frames: Int) = when (frames) {
        0 -> "Turbo Off"
        in 1..2 -> "Turbo Fast"
        in 3..5 -> "Turbo Med"
        else -> "Turbo Slow"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val running = HashMap<String, Runnable>()

    fun press(tag: String, code: Int, down: Boolean, frames: Int, emit: (Int, Boolean) -> Unit) {
        val key = "$code:$tag"
        if (!down) {
            running.remove(key)?.let(handler::removeCallbacks)
            // Always let go, even halfway through a cycle, or the key stays down in the game.
            emit(code, false)
            return
        }
        if (frames <= 0) { emit(code, true); return }
        if (key in running) return
        val period = (frames * 1000.0 / 60.0).toLong().coerceAtLeast(24L)
        var on = false
        val r = object : Runnable {
            override fun run() {
                on = !on
                emit(code, on)
                handler.postDelayed(this, period)
            }
        }
        running[key] = r
        handler.post(r)
    }
}

/** Arrow-cluster directions under a point [dx], [dy] from the pad's centre. Bits: 1 up, 2 down, 4 left, 8 right. */
fun dpadMask(dx: Float, dy: Float, half: Float): Int {
    // The middle answers nothing; D-pad spacing (ARMSX2's) widens it along with the gap.
    if (hypot(dx, dy) <= half * (0.24f + TouchPrefs.dpadSpacing.floatValue)) return 0
    return angleMask(dx, dy)
}

/** Eight directions by angle. Bits: 1 up, 2 down, 4 left, 8 right. */
fun angleMask(dx: Float, dy: Float): Int {
    val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
    var m = 0
    if (a in -157.5..-22.5) m = m or 1
    if (a in 22.5..157.5) m = m or 2
    if (abs(a) >= 112.5) m = m or 4
    if (abs(a) <= 67.5) m = m or 8
    return m
}

/**
 * ARMSX2's multi-touch layer (its UnifiedTouchLayer), for keys and the arrow clusters: every
 * finger on them is followed here, so one finger presses every key whose reach it is in, and a
 * finger sliding from key to key (or off the D-pad onto a key and back) presses what the glide
 * mode says. ARMSX2 lays this over the whole screen; here each key's touch area feeds it instead,
 * because a touch on the game itself between the keys has to reach the game.
 *
 * Per finger, as in ARMSX2: the keys under it now, the first it landed on, and every key it has
 * crossed; the glide mode only picks which of them it holds.
 */
class TouchSurface(private val press: (Int, Boolean) -> Unit) {
    /** A key's reach: a circle, or for a wide key a capsule ([half] = how far the circle's centre slides each way). */
    class Key(val index: Int, val code: Int, val turbo: Int, val cx: Float, val cy: Float, val r: Float, val half: Float) {
        fun contains(x: Float, y: Float): Boolean {
            val dx = (abs(x - cx) - half).coerceAtLeast(0f)
            val dy = y - cy
            return dx * dx + dy * dy <= r * r
        }
    }

    /** An arrow cluster: its square, centred at [cx], [cy], [half] each way, and its four keys. */
    class Pad(val index: Int, val codes: IntArray, val cx: Float, val cy: Float, val half: Float)

    var keys: List<Key> = emptyList()
    var pads: List<Pad> = emptyList()

    /** Keys down now, by layout index, for drawing them pressed. */
    val held = mutableStateMapOf<Int, Boolean>()
    /** Directions lit per arrow cluster (layout index). */
    val lit = mutableStateMapOf<Int, Int>()

    private class Finger {
        var current: Set<Int> = emptySet()
        var first: Set<Int>? = null
        val latched = HashSet<Int>()
        /** The arrow cluster this finger drives (-1: none) and its directions now. */
        var pad = -1
        var dir = 0
        /** Its first direction, when a direction came before any key (Hold first). */
        var dirFirst: Pair<Int, Int>? = null
        /** Every direction crossed, per cluster (Hold all). */
        val dirLatched = HashMap<Int, Int>()
    }

    private val fingers = HashMap<Long, Finger>()
    private var pressedKeys = emptySet<Int>()
    private val pressedDirs = HashMap<Int, Int>()

    /** Finger [id] is down at [x], [y] (overlay pixels). */
    fun touch(id: Long, x: Float, y: Float) {
        val f = fingers.getOrPut(id) { Finger() }
        val pad = pads.firstOrNull { abs(x - it.cx) <= it.half && abs(y - it.cy) <= it.half }
        if (pad != null) f.pad = pad.index
        // Inside a cluster only the cluster answers, as when it handled its own touch.
        val h = if (pad != null) emptySet() else keys.filter { it.contains(x, y) }.mapTo(HashSet()) { it.index }
        if (pad == null && h.isNotEmpty()) f.pad = -1
        // Past the cluster's edge the finger keeps steering from its centre until it reaches a key.
        val d = pads.firstOrNull { it.index == f.pad }?.let { dpadMask(x - it.cx, y - it.cy, it.half) } ?: 0
        f.dir = d
        if (f.pad >= 0) f.dirLatched[f.pad] = (f.dirLatched[f.pad] ?: 0) or d
        f.current = h
        f.latched.addAll(h)
        if (f.first == null && f.dirFirst == null) {
            if (h.isNotEmpty()) f.first = h else if (d != 0) f.dirFirst = f.pad to d
        }
    }

    /** Finger [id] lifted (or was cancelled). */
    fun lift(id: Long) { fingers.remove(id) }

    private fun keysOf(f: Finger): Set<Int> = when (TouchPrefs.glide.value) {
        TouchPrefs.Glide.FOLLOW -> f.current
        TouchPrefs.Glide.HOLD_FIRST -> f.first.orEmpty() + f.current
        TouchPrefs.Glide.HOLD_ALL -> f.latched
    }

    private fun dirsOf(f: Finger): Map<Int, Int> = when (TouchPrefs.glide.value) {
        TouchPrefs.Glide.FOLLOW -> if (f.pad >= 0) mapOf(f.pad to f.dir) else emptyMap()
        TouchPrefs.Glide.HOLD_FIRST -> buildMap {
            f.dirFirst?.let { (p, m) -> put(p, m) }
            if (f.pad >= 0) put(f.pad, (get(f.pad) ?: 0) or f.dir)
        }
        TouchPrefs.Glide.HOLD_ALL -> f.dirLatched
    }

    /** Press and let go of whatever changed since the last call. */
    fun commit() {
        val agg = fingers.values.flatMapTo(HashSet()) { keysOf(it) }
        val aggDirs = HashMap<Int, Int>()
        fingers.values.forEach { f -> dirsOf(f).forEach { (p, m) -> aggDirs[p] = (aggDirs[p] ?: 0) or m } }
        val byIndex = keys.associateBy { it.index }
        var newPress = false
        // What lets go goes first, then what presses.
        (pressedKeys - agg).forEach { i -> byIndex[i]?.let { k -> key(k, false) } }
        val padsByIndex = pads.associateBy { it.index }
        (pressedDirs.keys + aggDirs.keys).toSet().forEach { p ->
            val pad = padsByIndex[p]
            val was = pressedDirs[p] ?: 0
            val now = aggDirs[p] ?: 0
            for (b in 0..3) {
                val bit = 1 shl b
                if (was and bit != 0 && now and bit == 0) pad?.let { press(it.codes[b], false) }
            }
            for (b in 0..3) {
                val bit = 1 shl b
                if (was and bit == 0 && now and bit != 0) { pad?.let { press(it.codes[b], true) }; newPress = true }
            }
        }
        (agg - pressedKeys).forEach { i -> byIndex[i]?.let { k -> key(k, true); newPress = true } }
        if (newPress) TouchHaptics.tick()
        pressedKeys = agg
        pressedDirs.clear()
        pressedDirs.putAll(aggDirs.filterValues { it != 0 })
        // Drawn state.
        held.keys.toList().forEach { if (it !in agg) held.remove(it) }
        agg.forEach { held[it] = true }
        lit.keys.toList().forEach { if (it !in pressedDirs) lit.remove(it) }
        pressedDirs.forEach { (p, m) -> lit[p] = m }
    }

    private fun key(k: Key, down: Boolean) {
        if (k.turbo > 0) Turbo.press("multitouch", k.code, down, k.turbo, press) else press(k.code, down)
    }

    /** Let go of everything (the keys hiding, the menu opening, the layout changing). */
    fun releaseAll() {
        fingers.clear()
        commit()
    }
}

/**
 * ARMSX2's Gesture Control: a swipe or a double-tap on the game (not on an on-screen key) presses a
 * key, for keys that don't fit on screen. It watches the touches on their way to the game without
 * taking them, so the game still gets them (Direct touch). One finger at a time, so a thumb resting
 * on the screen can't turn a key press elsewhere into a swipe.
 */
class Gestures(private val overlay: OverlayState) {
    private val handler = Handler(Looper.getMainLooper())
    private var tracking = -1
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var fired = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    /** The key a Hold double-tap is holding down (0: none). */
    private var latched = 0

    private fun active(): Boolean = TouchPrefs.gestures.value && overlay.layout.mode == "direct" &&
        !overlay.menu && !overlay.editing && overlay.gestureInput != null &&
        TouchPrefs.gestureSlots.any { it.second.intValue != 0 }

    fun onTouch(ev: MotionEvent, density: Float) {
        if (!active()) { reset(); return }
        val slop = 18f * density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (tracking != -1) return
                val i = ev.actionIndex
                val x = ev.getX(i)
                val y = ev.getY(i)
                // A finger on an on-screen control is that control's.
                if (overlay.controlAt(x, y)) return
                tracking = ev.getPointerId(i)
                startX = x; startY = y; startTime = ev.eventTime; fired = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (tracking == -1 || fired) return
                val i = ev.findPointerIndex(tracking).takeIf { it >= 0 } ?: return
                val dx = ev.getX(i) - startX
                val dy = ev.getY(i) - startY
                val threshold = minOf(overlay.screenW, overlay.screenH) * TouchPrefs.swipeDistance.floatValue
                if (threshold > 0f && (abs(dx) >= threshold || abs(dy) >= threshold)) {
                    // The longer axis wins, so a diagonal fires one direction rather than two.
                    pulse(
                        if (abs(dx) > abs(dy)) (if (dx > 0) TouchPrefs.swipeRight else TouchPrefs.swipeLeft).intValue
                        else (if (dy > 0) TouchPrefs.swipeDown else TouchPrefs.swipeUp).intValue,
                    )
                    fired = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = ev.actionIndex
                if (ev.getPointerId(i) != tracking) return
                val x = ev.getX(i)
                val y = ev.getY(i)
                val now = ev.eventTime
                // A short press that stayed put is a tap; two close together within 300 ms a double-tap.
                if (!fired && hypot(x - startX, y - startY) <= slop && now - startTime <= 250) {
                    if (now - lastTapTime <= 300 && hypot(x - lastTapX, y - lastTapY) <= slop * 3f) {
                        val code = TouchPrefs.doubleTap.intValue
                        if (TouchPrefs.doubleTapHold.value) toggle(code) else pulse(code)
                        lastTapTime = 0L
                    } else {
                        lastTapTime = now; lastTapX = x; lastTapY = y
                    }
                }
                tracking = -1
            }
            MotionEvent.ACTION_CANCEL -> tracking = -1
        }
    }

    /** Let go of a held double-tap key: nothing else could once the gestures stop. */
    fun reset() {
        tracking = -1
        if (latched != 0) { send(latched, false); latched = 0 }
    }

    private fun send(code: Int, down: Boolean) {
        val inj = overlay.gestureInput ?: return
        when (code) {
            Keys.MOUSE_LEFT -> if (down) inj.mouseDown(MotionEvent.BUTTON_PRIMARY) else inj.mouseUp(MotionEvent.BUTTON_PRIMARY)
            Keys.MOUSE_RIGHT -> if (down) inj.mouseDown(MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(MotionEvent.BUTTON_SECONDARY)
            else -> inj.key(code, down)
        }
    }

    private fun pulse(code: Int) {
        if (code == 0) return
        // Held long enough for a Flash game reading keys once a frame (24 to 30 a second) to see it.
        send(code, true)
        TouchHaptics.tick()
        handler.postDelayed({ send(code, false) }, 90)
    }

    private fun toggle(code: Int) {
        if (code == 0) return
        if (latched == code) { send(code, false); latched = 0; return }
        if (latched != 0) send(latched, false)
        send(code, true)
        TouchHaptics.tick()
        latched = code
    }
}

/**
 * ARMSX2's touch control profiles: named sets of keys a layout can be saved as and loaded from,
 * for any game. Kept with the layouts.
 */
object TouchProfiles {
    private val serializer = MapSerializer(String.serializer(), ListSerializer(TouchButton.serializer()))

    private fun prefs() = TouchPrefs.app?.getSharedPreferences("touch", Context.MODE_PRIVATE)

    fun all(): Map<String, List<TouchButton>> = prefs()?.getString("profiles", null)
        ?.let { runCatching { FpCore.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    private fun write(m: Map<String, List<TouchButton>>) {
        prefs()?.edit()?.putString("profiles", FpCore.json.encodeToString(serializer, m))?.apply()
    }

    fun save(name: String, buttons: List<TouchButton>) = write(all() + (name to buttons))
    fun delete(name: String) = write(all() - name)
}
