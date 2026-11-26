package com.armsx.flashpoint.game

import android.content.Context
import android.content.SharedPreferences
import android.view.InputDevice
import androidx.compose.runtime.mutableIntStateOf

/**
 * Which controller is which player, for local multiplayer (GitHub issue #1). Ported from
 * ARMSX2's PadRouter (input/PadRouter.kt, ARMSX2 team), for ARMFlash's four players.
 *
 * A controller takes the next free player the first time it presses something (Android's own
 * controller numbers have gaps: a DualSense docked next to an Xbox pad came up as number 3), unless
 * it is pinned to a player by hand. Pins are kept per controller, by its descriptor, which stays
 * the same across reconnects (ids do not), and several controllers pinned to one player all play
 * as it (a handheld's own controls and the pad it is docked with). A pinned player belongs to its
 * controller even while that controller is away: the next pad to press is not handed it. A
 * Joy-Con pair (two devices, one controller) plays as player 1, as in ARMSX2.
 *
 * Players are 0-based here (0 is player 1). Every game runs in a process of its own, so the
 * press-order claims start fresh with each game; pins are kept.
 */
object PadRouter {
    const val PLAYERS = 4
    private const val NINTENDO_VENDOR_ID = 0x057E
    private const val KEY_PINNED = "pinned"

    /** Player -> the device id that claimed it this game (-1 = nobody yet). */
    private val slots = IntArray(PLAYERS) { -1 }
    /** Controller descriptor -> the player it is pinned to. */
    private val pinned = LinkedHashMap<String, Int>()
    private var prefs: SharedPreferences? = null

    /** Bumped on every change, so a screen showing the assignments looks again. */
    val version = mutableIntStateOf(0)

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("pads", Context.MODE_PRIVATE).also { prefs = it }
        pinned.clear()
        runCatching {
            val obj = org.json.JSONObject(p.getString(KEY_PINNED, null) ?: return@runCatching)
            for (key in obj.keys()) obj.optInt(key, -1).takeIf { it in 0 until PLAYERS }?.let { pinned[key] = it }
        }
        version.intValue++
    }

    private fun save() {
        val obj = org.json.JSONObject()
        pinned.forEach { (d, player) -> obj.put(d, player) }
        prefs?.edit()?.putString(KEY_PINNED, obj.toString())?.apply()
    }

    fun pins(): Map<String, Int> = LinkedHashMap(pinned)

    /**
     * Pin a controller to [player], or pass null for automatic. The controllers already pinned
     * there stay (they all play as it); one that only claimed it by pressing first is moved off,
     * so the menu and where input goes agree straight away.
     */
    fun setPin(descriptor: String, player: Int?) {
        if (player == null) {
            pinned.remove(descriptor)
            for (i in slots.indices) if (slots[i] >= 0 && descriptorOf(slots[i]) == descriptor) slots[i] = -1
        } else {
            if (player !in 0 until PLAYERS) return
            pinned[descriptor] = player
            val claimed = slots[player]
            val claimedBy = if (claimed >= 0) descriptorOf(claimed) else null
            if (claimed >= 0 && claimedBy != descriptor && claimedBy?.let { pinned[it] } != player) slots[player] = -1
            for (i in slots.indices) if (i != player && slots[i] >= 0 && descriptorOf(slots[i]) == descriptor) slots[i] = -1
        }
        save()
        version.intValue++
    }

    private fun descriptorOf(deviceId: Int): String? = runCatching { InputDevice.getDevice(deviceId)?.descriptor }.getOrNull()

    private fun isPad(dev: InputDevice?): Boolean {
        val src = dev?.sources ?: 0
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    /** One physical controller, as the menu lists it. */
    data class Pad(val descriptor: String, val name: String, val deviceId: Int)

    /** Connected controllers, one entry per physical controller (a DualSense also has touchpad and motion nodes). */
    fun connectedPads(): List<Pad> {
        val out = LinkedHashMap<String, Pad>()
        for (id in runCatching { InputDevice.getDeviceIds() }.getOrDefault(IntArray(0))) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (dev.isVirtual || !isPad(dev)) continue
            val descriptor = dev.descriptor ?: continue
            out.getOrPut(descriptor) { Pad(descriptor, dev.name ?: "Controller", id) }
        }
        return out.values.toList()
    }

    /** The player a controller plays as now, pinned or claimed by pressing; null while it has not pressed yet. */
    fun currentPlayer(descriptor: String): Int? =
        pinned[descriptor] ?: slots.indices.firstOrNull { slots[it] >= 0 && descriptorOf(slots[it]) == descriptor }

    /**
     * The player [deviceId]'s input belongs to, claiming the next free one on its first press.
     * Anything that is not a gamepad node (a touchpad, a keyboard), and every pad past the
     * fourth, plays as player 1.
     */
    fun playerFor(deviceId: Int): Int {
        if (deviceId < 0) return 0
        for (i in slots.indices) if (slots[i] == deviceId) return i
        val dev = InputDevice.getDevice(deviceId)
        if (dev?.vendorId == NINTENDO_VENDOR_ID) return 0
        if (!isPad(dev)) return 0
        dev?.descriptor?.let { d -> pinned[d]?.let { p -> slots[p] = deviceId; version.intValue++; return p } }
        for (i in slots.indices) {
            if (pinned.containsValue(i)) continue
            if (slots[i] == -1) { slots[i] = deviceId; version.intValue++; return i }
        }
        return 0
    }

    /** A controller went away: its player is free again (a reconnect comes back with a new id). */
    fun forgetDevice(deviceId: Int) {
        for (i in slots.indices) if (slots[i] == deviceId) slots[i] = -1
        version.intValue++
    }

    /** A controller came or went, for the menu's list. */
    fun devicesChanged() { version.intValue++ }
}
