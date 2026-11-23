package com.armsx.flashpoint.discord

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The quick menu's Discord buttons, carried out in the launcher's process.
 *
 * Games run in their own process. The sign-in (its tokens) and the Discord switches are kept in
 * the launcher's settings, and two processes writing the same settings file undo each other's
 * changes, so a game asks the launcher to do it: Connect, Disconnect, and the two switches.
 */
class DiscordControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        when (intent.action) {
            ACTION_CONNECT -> DiscordPresence.authorizeForGame()
            ACTION_DISCONNECT -> DiscordPresence.signOut()
            ACTION_NOTIFY -> DiscordPresence.notifyInGame = on
            ACTION_SHOW_GAME -> DiscordPresence.showGameName = on
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.armsx.flashpoint.discord.CONNECT"
        const val ACTION_DISCONNECT = "com.armsx.flashpoint.discord.DISCONNECT"
        const val ACTION_NOTIFY = "com.armsx.flashpoint.discord.NOTIFY"
        const val ACTION_SHOW_GAME = "com.armsx.flashpoint.discord.SHOW_GAME"
        const val EXTRA_ON = "on"

        fun send(ctx: Context, action: String, on: Boolean = true) {
            ctx.sendBroadcast(Intent(ctx, DiscordControlReceiver::class.java).setAction(action).putExtra(EXTRA_ON, on))
        }
    }
}
