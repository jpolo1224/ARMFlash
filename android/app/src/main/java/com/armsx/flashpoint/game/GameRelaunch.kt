package com.armsx.flashpoint.game

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

/**
 * Starts a game again in a new game process, for changes that only take hold in one: a GPU driver
 * or graphics API. A Vulkan driver, once loaded, stays in its process, so starting the player again
 * in the same one after picking another driver hung with no window (the system driver after a
 * custom one). The game ends its process; this, in the launcher's process and showing nothing,
 * waits until that process is gone and then starts the game, which Android puts in a new one. The
 * launcher's wait for the game's result is passed along (FLAG_ACTIVITY_FORWARD_RESULT).
 */
class GameRelaunch : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val target = intent.getParcelableExtra<Intent>(EXTRA_TARGET)
        if (target == null) { finish(); return }
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        val until = SystemClock.uptimeMillis() + 5_000
        val launch = object : Runnable {
            override fun run() {
                if (pid > 0 && File("/proc/$pid").exists() && SystemClock.uptimeMillis() < until) {
                    handler.postDelayed(this, 50)
                    return
                }
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
        handler.post(launch)
    }

    companion object {
        const val EXTRA_TARGET = "relaunchTarget"
        const val EXTRA_PID = "relaunchPid"
    }
}
