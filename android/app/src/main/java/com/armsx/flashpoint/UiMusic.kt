package com.armsx.flashpoint

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Launcher background music, in the manner of ARMSX1/2/3's library music: plays only while
 * the launcher is on screen, never under a game.
 *
 * Fades follow the timings settled on in ARMSX2: in over 2 s, out over 1.5 s. Leaving the
 * launcher (a game starting, the app going to the background) fades out and pauses in
 * place; coming back fades in from the same spot.
 *
 * Track: "Awestruck".
 */
object UiMusic {
    private const val FADE_IN_MS = 2000L
    private const val FADE_OUT_MS = 1500L
    private const val STEP_MS = 30L

    val enabled = mutableStateOf(true)
    /** 0..1. Low by default: music under a launcher should sit well below the UI. */
    val volume = mutableFloatStateOf(0.25f)

    private var player: MediaPlayer? = null
    private var level = 0f
    private var target = 0f
    private var fadeMs = FADE_IN_MS
    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var focus: AudioFocusRequest? = null

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        enabled.value = p.getBoolean("music", true)
        volume.floatValue = p.getFloat("musicVolume", 0.25f)
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putBoolean("music", enabled.value).putFloat("musicVolume", volume.floatValue).apply()
    }

    /** The launcher is visible: start, or fade back in from where it paused. */
    fun start(ctx: Context) {
        if (!enabled.value) return stop()
        val app = ctx.applicationContext
        val am = audio ?: (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).also { audio = it }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> fadeTo(0f, FADE_OUT_MS)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> fadeTo(volume.floatValue * 0.3f, 400)
                    AudioManager.AUDIOFOCUS_GAIN -> if (enabled.value) fadeTo(volume.floatValue, FADE_IN_MS)
                }
            }.build().also { focus = it }
        if (am.requestAudioFocus(req) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val mp = player ?: MediaPlayer.create(app, R.raw.ui_music, attrs, am.generateAudioSessionId())?.apply {
            isLooping = true
            setVolume(0f, 0f)
        }?.also { player = it; level = 0f } ?: return
        if (!mp.isPlaying) mp.start()
        fadeTo(volume.floatValue, FADE_IN_MS)
    }

    /** The launcher is going away: fade out and pause in place. */
    fun pause() = fadeTo(0f, FADE_OUT_MS)

    fun stop() {
        main.removeCallbacks(tick)
        player?.release()
        player = null
        level = 0f
        focus?.let { audio?.abandonAudioFocusRequest(it) }
    }

    /** Volume slider: applied live. */
    fun applyVolume() {
        if (player != null && target > 0f) fadeTo(volume.floatValue, 200)
    }

    private var last = 0L
    private fun fadeTo(to: Float, ms: Long) {
        target = to
        fadeMs = ms.coerceAtLeast(1)
        last = SystemClock.uptimeMillis()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val now = SystemClock.uptimeMillis()
            val step = (now - last).toFloat() / fadeMs * maxOf(volume.floatValue, 0.05f)
            last = now
            level = if (level < target) minOf(target, level + step) else maxOf(target, level - step)
            // Perceived loudness is closer to the square of the gain than to the gain itself.
            val g = level * level / maxOf(volume.floatValue, 0.01f)
            p.setVolume(g, g)
            if (level != target) {
                main.postDelayed(this, STEP_MS)
            } else if (level == 0f) {
                if (p.isPlaying) p.pause()
                focus?.let { audio?.abandonAudioFocusRequest(it) }
            }
        }
    }
}
