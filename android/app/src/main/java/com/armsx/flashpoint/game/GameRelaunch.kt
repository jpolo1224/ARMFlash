package com.armsx.flashpoint.game

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Starts a game again in a new game process, for changes that only take hold in one: a GPU driver
 * or graphics API. A Vulkan driver, once loaded, stays in its process, so starting the player again
 * in the same one after picking another driver hung with no window (the system driver after a
 * custom one). The game ends its process; this, in the launcher's process and showing nothing,
 * waits until Android no longer lists that process and then starts the game, which Android puts in
 * a new one. Android's own list, not /proc: a release build's processes are not dumpable, so /proc
 * hides them even from the app's other processes, and the game came back in the dying one. The
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
        val start = SystemClock.uptimeMillis()
        var ended = false
        val launch = object : Runnable {
            override fun run() {
                val waited = SystemClock.uptimeMillis() - start
                if (pid > 0 && alive(pid) && waited < 12_000) {
                    // The game closes itself once its player has shut down (3 s on the emulator);
                    // one that takes far longer is ended here, as the same app may.
                    if (waited > 8_000 && !ended) { ended = true; android.os.Process.killProcess(pid) }
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

    private fun alive(pid: Int): Boolean = runCatching {
        getSystemService(ActivityManager::class.java).runningAppProcesses.orEmpty().any { it.pid == pid }
    }.getOrDefault(false)

    companion object {
        const val EXTRA_TARGET = "relaunchTarget"
        const val EXTRA_PID = "relaunchPid"
    }
}
