package com.armsx.flashpoint

import android.app.Application
import com.armsx.flashpoint.data.Library

class FpApp : Application() {
    companion object {
        /** This process's application, for code that has no Context of its own (FpCore's settings). */
        @Volatile var app: FpApp? = null
            private set
    }

    lateinit var library: Library
        private set

    /** True in the launcher process; false in the `:game` process. */
    val isMainProcess: Boolean get() = getProcessName() == packageName

    override fun onCreate() {
        super.onCreate()
        app = this
        library = Library(this)
        // Flashpoint's credits, platform logos, logo sets and themes, then the themes' colors.
        com.armsx.flashpoint.data.Defaults.install(this, library.root)
        com.armsx.flashpoint.ui.FpThemes.loadDesktop(library.root)
        com.armsx.flashpoint.ui.Fp.load(this)
        com.armsx.flashpoint.ui.LogoSets.load(this)
        com.armsx.flashpoint.data.CustomDriver.prefs = getSharedPreferences("settings", MODE_PRIVATE)
        // Discord presence lives in the launcher process (the SDK itself in :discord).
        com.armsx.flashpoint.discord.DiscordPresence.appContext = this
    }
}
