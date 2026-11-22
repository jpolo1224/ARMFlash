package com.armsx.flashpoint

import android.app.Application
import com.armsx.flashpoint.data.Library

class FpApp : Application() {
    lateinit var library: Library
        private set

    /** True in the launcher process; false in the `:game` process. */
    val isMainProcess: Boolean get() = getProcessName() == packageName

    override fun onCreate() {
        super.onCreate()
        library = Library(this)
        com.armsx.flashpoint.data.CustomDriver.prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // Discord presence lives in the launcher process (the SDK itself in :discord).
        com.armsx.flashpoint.discord.DiscordPresence.appContext = this
    }
}
