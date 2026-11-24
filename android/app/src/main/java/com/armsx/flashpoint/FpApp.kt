package com.armsx.flashpoint

import android.app.Application
import com.armsx.flashpoint.data.Library

class FpApp : Application(), coil.ImageLoaderFactory {
    companion object {
        /** This process's application, for code that has no Context of its own (FpCore's settings). */
        @Volatile var app: FpApp? = null
            private set
    }

    /**
     * Logos and screenshots, kept like the desktop launcher keeps them: each is downloaded from
     * Flashpoint's image server once. A game's images never change, and the server's
     * "max-age=0" would otherwise have every one asked for again whenever it is shown fresh.
     * The folder is app storage, which Android does not clear on its own (its cache folder it
     * does); each process has its own, as the cache cannot be shared between two processes.
     */
    override fun newImageLoader(): coil.ImageLoader {
        val dir = java.io.File(filesDir, if (isMainProcess) "images" else "images-game")
        // Images cached before this went to Coil's default folder: keep them.
        val old = java.io.File(cacheDir, "image_cache")
        if (isMainProcess && !dir.exists() && old.isDirectory) old.renameTo(dir)
        return coil.ImageLoader.Builder(this)
            .respectCacheHeaders(false)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(dir)
                    .maxSizePercent(0.1)
                    .minimumMaxSizeBytes(64L shl 20)
                    .maximumMaxSizeBytes(1L shl 30)
                    .build()
            }
            .build()
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
