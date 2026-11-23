package com.armsx.flashpoint

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.concurrent.ArrayBlockingQueue

/**
 * ARMFlash's boot animation, played the way an Android bootanimation.zip is (assets/boot/desc.txt:
 * part0 once, then part1 looping), with its sound, then a fade into the launcher. A tap skips it;
 * Config > Visuals turns it off.
 *
 * Frames are decoded on a background thread two ahead of the one on screen, reusing bitmaps, so
 * the animation runs at its own 30 fps without holding every frame in memory.
 */
class BootActivity : Activity() {
    companion object {
        const val EXTRA_PREVIEW = "preview"
    }

    private val fps = 30
    private lateinit var view: ImageView
    private var player: MediaPlayer? = null
    private val decoded = ArrayBlockingQueue<Bitmap>(2)
    private val recycle = ArrayBlockingQueue<Bitmap>(4)
    private lateinit var decoderThread: HandlerThread
    @Volatile private var running = true
    private var done = false
    private var soundDone = false
    private var startNs = 0L
    private var shown: Bitmap? = null
    private var previous: Bitmap? = null
    /** A .swf opened with ARMFlash: added to My Games while the intro plays. */
    private var swfImport: Thread? = null
    @Volatile private var swfResult: Result<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Frontend.swf(this, intent)?.let { uri ->
            val name = com.armsx.flashpoint.ui.displayName(this, uri)
            val lib = (application as FpApp).library
            swfImport = Thread({ swfResult = runCatching { lib.local.import(this, uri, name).id } }, "swf-import").apply { start() }
        }
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val part0 = frames("boot/part0")
        val part1 = frames("boot/part1")
        if (!prefs.getBoolean("bootAnimation", true)) return next()
        // A custom intro (a video, as in ARMSX2) plays in place of the built-in animation.
        val custom = com.armsx.flashpoint.data.CustomMedia.file(this, com.armsx.flashpoint.data.CustomMedia.Kind.BOOT_INTRO)
        if (custom.length() > 0L) return playVideo(custom)
        if (part0.isEmpty()) return next()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // The logo square takes most of the shorter side, like the original 1080 x 1920 frame.
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(view, lp)
            setOnClickListener { finishBoot() }
        })
        view.setPadding(0, 0, 0, 0)

        // Sound: the animation runs until it ends (and at least through part0 and one part1 loop).
        player = runCatching {
            MediaPlayer().apply {
                val fd = assets.openFd("boot/boot_sound.m4a")
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                fd.close()
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setOnCompletionListener { soundDone = true }
                prepare()
            }
        }.getOrNull()
        if (player == null) soundDone = true

        // Decoder: part0 once, then part1 for as long as the animation runs.
        decoderThread = HandlerThread("boot-frames").apply { start() }
        Handler(decoderThread.looper).post {
            val opts = BitmapFactory.Options().apply { inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            var i = 0
            while (running) {
                val name = if (i < part0.size) part0[i] else part1.getOrNull((i - part0.size) % part1.size.coerceAtLeast(1)) ?: break
                opts.inBitmap = recycle.poll()
                val bmp = runCatching { assets.open(name).use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
                    ?: runCatching { opts.inBitmap = null; assets.open(name).use { BitmapFactory.decodeStream(it, null, opts) } }.getOrNull()
                    ?: break
                while (running && !decoded.offer(bmp, 50, java.util.concurrent.TimeUnit.MILLISECONDS)) { /* wait for the screen */ }
                i++
                if (part1.isEmpty() && i >= part0.size) break
            }
        }

        val minFrames = part0.size + part1.size
        var frame = 0
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(now: Long) {
                if (done) return
                if (startNs == 0L) {
                    startNs = now
                    player?.start()
                }
                val due = ((now - startNs) / (1_000_000_000L / fps)).toInt()
                while (frame <= due) {
                    val bmp = decoded.poll() ?: break
                    // Reuse a bitmap only once two newer frames are up (the last one may still be drawing).
                    previous?.let { recycle.offer(it) }
                    previous = shown
                    shown = bmp
                    view.setImageBitmap(bmp)
                    frame++
                }
                if (soundDone && frame >= minFrames) return finishBoot()
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
    }

    /** The user's intro video, fitted to the screen; a tap skips it, an unplayable file goes straight on. */
    private fun playVideo(file: java.io.File) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        val video = android.widget.VideoView(this)
        view = ImageView(this)
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            setOnClickListener { finishVideo(video) }
        })
        video.setOnCompletionListener { finishVideo(video) }
        video.setOnErrorListener { _, _, _ -> finishVideo(video); true }
        video.setVideoPath(file.path)
        video.start()
    }

    private fun finishVideo(video: android.widget.VideoView) {
        if (done) return
        done = true
        video.animate().alpha(0f).setDuration(280).withEndAction { video.stopPlayback(); next() }.start()
    }

    private fun frames(dir: String): List<String> =
        (assets.list(dir) ?: emptyArray()).filter { it.endsWith(".webp") || it.endsWith(".png") }.sorted().map { "$dir/$it" }

    private fun finishBoot() {
        if (done) return
        done = true
        view.animate().alpha(0f).setDuration(280).withEndAction { next() }.start()
        player?.let { p -> runCatching { p.setVolume(0.4f, 0.4f) } }
    }

    private fun next() {
        running = false
        // Config's Preview: just play it and go back.
        if (intent.getBooleanExtra(EXTRA_PREVIEW, false)) return finish()
        // A frontend's launch (a game link, a .armflash file) goes on to the launcher with it.
        val main = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        Frontend.gameId(this, intent)?.let { main.putExtra("gameId", it) }
        swfImport?.let { t ->
            t.join(15_000)
            swfResult?.onSuccess { main.putExtra("gameId", it) }
                ?.onFailure { main.putExtra("status", it.message ?: "Could not add that file.") }
        }
        // Any other file opened with ARMFlash: say what it opens instead of doing nothing.
        if (swfImport == null && !main.hasExtra("gameId") && intent.data?.scheme.let { it == "content" || it == "file" }) {
            main.putExtra("status", "ARMFlash opens Flash games (.swf) and its own game files (.armflash). That file is neither.")
        }
        startActivity(main)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        running = false
        if (::decoderThread.isInitialized) decoderThread.quitSafely()
        player?.release()
        player = null
        super.onDestroy()
    }
}
