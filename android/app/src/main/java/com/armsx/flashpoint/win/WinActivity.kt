package com.armsx.flashpoint.win

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.armsx.flashpoint.core.FpCore
import com.armsx.flashpoint.game.GameActivity
import com.armsx.flashpoint.game.GameInput
import com.armsx.flashpoint.game.GameOverlay
import com.armsx.flashpoint.game.GameScreen
import com.armsx.flashpoint.game.LayoutStore
import com.armsx.flashpoint.game.OverlayHost
import com.armsx.flashpoint.game.OverlayState
import com.armsx.flashpoint.game.PadMapper
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors

/**
 * A Windows plugin game (Shockwave, Unity and others): the desktop launcher's own program
 * (applicationPath + launchCommand) runs under Wine on a virtual X screen, and this screen shows
 * the game's window from it, scaled like every other game, with FlashARM's touch controls, pause
 * menu and controller mapping on top. Runs in the `:game` process like the other players.
 */
class WinActivity : ComponentActivity(), SurfaceHolder.Callback {
    private lateinit var rt: WinRuntime
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var overlay: OverlayState
    private lateinit var layouts: LayoutStore
    private lateinit var input: WinInput
    private lateinit var pad: PadMapper
    private var screen: GameScreen? = null
    private var gameId = ""
    private var started = 0L
    private val xq = Executors.newSingleThreadExecutor()
    @Volatile private var x: XClient? = null
    @Volatile private var gameWin = 0
    @Volatile private var crop = Rect()
    @Volatile private var dst = Rect()
    @Volatile private var holder: SurfaceHolder? = null
    @Volatile private var running = true
    @Volatile private var frames = 0L
    private var scaleMode = "default"

    private val host = object : OverlayHost {
        override val title get() = intent.getStringExtra(GameActivity.EXTRA_TITLE).orEmpty()
        override val logoUrl get() = intent.getStringExtra(GameActivity.EXTRA_LOGO).orEmpty()
        override val gameId get() = intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()
        override val usesDriver = false
        // A Flash game played in Flash Player: the pause menu can switch it back to Ruffle.
        override val isFlash get() = "flashplayer" in program().lowercase()
        override val runner get() = "Windows: " + intent.getStringExtra(EXTRA_APP).orEmpty().substringAfterLast('\\')
        override fun save(asDefault: Boolean) = layouts.save(gameId, overlay.layout, asDefault)
        override fun keyboard() {
            val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            surface.requestFocus(); imm.showSoftInput(surface, 0)
        }
        override fun restart() {
            rt.stopAll()
            val target = if (isFlash) com.armsx.flashpoint.game.FlashPrefs.activity(this@WinActivity, gameId) else WinActivity::class.java
            restarting = true
            startActivity(Intent(intent).setClass(this@WinActivity, target)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_FORWARD_RESULT))
            finish()
        }
        override fun exit() = quit()
        override fun frames(): Long = frames
        override fun menuOpen(open: Boolean) {
            screen?.onMenu(open)
            Thread { rt.setPaused(open) }.start()
        }
        override fun display(scale: String, quality: String) { scaleMode = scale }
        override var menuLeft: Boolean
            get() = layouts.menuLeft
            set(v) { layouts.menuLeft = v }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        rt = WinRuntime(this)
        gameId = intent.getStringExtra(GameActivity.EXTRA_GAME_ID).orEmpty()
        layouts = LayoutStore(this)
        overlay = OverlayState(layouts.load(gameId))
        scaleMode = overlay.layout.scale

        surface = SurfaceView(this).apply { isFocusable = true; isFocusableInTouchMode = true }
        surface.holder.addCallback(this)
        // Direct touch: the finger is the mouse (the overlay handles touchpad and point & click).
        surface.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { input.moveCursor(ev.x, ev.y); input.mouseDown() }
                MotionEvent.ACTION_MOVE -> input.moveCursor(ev.x, ev.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { input.moveCursor(ev.x, ev.y); input.mouseUp() }
            }
            true
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            text = "Starting…"
        }
        input = WinInput()
        pad = PadMapper(input, overlay, { surface.width }) { e -> super@WinActivity.dispatchKeyEvent(e) }
        screen = GameScreen(this, overlay) { if (::pad.isInitialized) pad else null }.also { it.onCreate() }
        val st = overlay
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(ComposeView(this@WinActivity).apply {
                setContent { com.armsx.flashpoint.ui.FpTheme { GameOverlay(st, input, host) } }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { overlay.editing = false; overlay.menu = !overlay.menu }
        })
        started = SystemClock.elapsedRealtime()
        Thread(::boot, "win-boot").start()
    }

    private fun say(s: String) = runOnUiThread { status.text = s; status.visibility = if (s.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE }

    /** Game server, X server, Wine prefix, then the game's own program. */
    private fun boot() {
        if (!rt.installed) { say("Windows support is not installed yet."); return }
        // The game's support pack (normally fetched by the launcher; a pause-menu switch to
        // Flash Player lands here without it).
        val fpRoot = File(intent.getStringExtra(EXTRA_ROOT) ?: (application as com.armsx.flashpoint.FpApp).library.root.path)
        SupportPacks.idFor(program())?.let { id ->
            if (!SupportPacks.installed(fpRoot, id)) {
                say("Downloading the $id player…")
                try {
                    SupportPacks.install(fpRoot, id) { d, t -> if (t > 0) say("Downloading the $id player… ${d * 100 / t}%") }
                } catch (e: Exception) { say("Could not get the $id player: ${e.message}"); return }
            }
        }
        try {
            FpCore.startServer(
                htdocs = intent.getStringExtra(GameActivity.EXTRA_HTDOCS).orEmpty(),
                legacyBase = intent.getStringExtra(GameActivity.EXTRA_LEGACY).orEmpty(),
                cacheRemote = true, liveDomains = emptyList(),
                // The port desktop Flashpoint's players are set up for (the projector's port.txt).
                port = GAME_SERVER_PORT,
            )
            intent.getStringArrayExtra(GameActivity.EXTRA_ZIPS).orEmpty().forEach { FpCore.mount(it) }
        } catch (e: Exception) { say("Game server failed: ${e.message}"); return }
        say("Starting the virtual screen…")
        if (!rt.startX(SCREEN_W, SCREEN_H)) { say("The virtual screen did not start."); return }
        if (!rt.ensurePrefix(::say)) { say("Windows support could not be set up."); return }
        rt.ensureProxy(GAME_SERVER_PORT)
        val fp = fpRoot
        rt.mapDrive('f', fp)
        val app = program()
        // Without a launch command (a switch from Ruffle), Flash Player gets the game's SWF.
        val args = WinRuntime.splitArgs(intent.getStringExtra(EXTRA_ARGS) ?: intent.getStringExtra(GameActivity.EXTRA_URL).orEmpty())
        val cwd = File(fp, app.replace('\\', '/').substringBeforeLast('/', ""))
        say("Starting the game…")
        val p = rt.run(listOf("F:\\$app") + args, cwd.takeIf { it.isDirectory })
        xq.execute {
            runCatching { x = XClient.connect(rt.socket.path) }.onFailure { say("Could not reach the virtual screen: ${it.message}") }
        }
        Thread(::render, "win-render").start()
        Thread(::watchWindows, "win-windows").start()
        // When the program ends, so does the game.
        Thread { p.waitFor(); if (running) runOnUiThread { quit() } }.start()
    }

    /** Follow the game's window: the largest one on the virtual screen. */
    private fun watchWindows() {
        while (running) {
            xq.submit {
                val c = x ?: return@submit
                val w = runCatching { c.windows().firstOrNull() }.getOrNull()
                if (w != null) {
                    // The game's client area: Wine draws it as the largest child window, without
                    // the title bar and menu (Flash Player's File / View / Control / Help).
                    val top = w.second
                    val inner = runCatching { c.children(w.first).firstOrNull()?.second }.getOrNull()
                    val r = if (inner != null && inner.w * inner.h * 2 > top.w * top.h)
                        XClient.Rect(top.x + inner.x, top.y + inner.y, inner.w, inner.h) else top
                    val win = Rect(r.x.coerceAtLeast(0), r.y.coerceAtLeast(0), (r.x + r.w).coerceAtMost(SCREEN_W), (r.y + r.h).coerceAtMost(SCREEN_H))
                    if (w.first != gameWin) { gameWin = w.first; content = Rect(); c.focus(w.first); say("") }
                    crop = trim(win)
                }
            }.get()
            Thread.sleep(500)
        }
    }

    // What the game has drawn so far inside its window (grows, never shrinks, per window).
    private var content = Rect()
    @Volatile private var pixels: IntBuffer? = null
    @Volatile private var stride = 0

    /**
     * The part of [win] the game draws in. Without a window manager, Wine opens windows at a
     * default size and players like Flash's projector draw their stage in one corner of it, under
     * a menu bar: keep the rows and columns that are mostly not the window's background colour.
     */
    private fun trim(win: Rect): Rect {
        val px = pixels ?: return win
        if (win.width() < 32 || win.height() < 32) return win
        // The background: the commonest colour just inside the bottom-right corner (inside the
        // window's frame, which Wine draws in a different colour).
        val bg = (6..14 step 2).flatMap { dy -> (6..14 step 2).map { dx -> px.get((win.bottom - dy) * stride + win.right - dx) and 0xffffff } }
            .groupingBy { it }.eachCount().maxBy { it.value }.key
        val step = 2
        fun busy(n: Int, of: Int) = n * step * 4 > of // over a quarter not background
        var top = -1; var bottom = -1; var left = -1; var right = -1
        for (y in win.top until win.bottom step step) {
            var n = 0
            for (x in win.left until win.right step step) if (px.get(y * stride + x) and 0xffffff != bg) n++
            if (busy(n, win.width())) { if (top < 0) top = y; bottom = y + step }
        }
        for (x in win.left until win.right step step) {
            var n = 0
            for (y in win.top until win.bottom step step) if (px.get(y * stride + x) and 0xffffff != bg) n++
            if (busy(n, win.height())) { if (left < 0) left = x; right = x + step }
        }
        if (top < 0 || left < 0) return if (content.isEmpty) win else content
        val found = Rect(left, top, right.coerceAtMost(win.right), bottom.coerceAtMost(win.bottom))
        if (content.isEmpty) content = found else content.union(found)
        return Rect(content)
    }

    /** Copy the game's window from the X server's framebuffer file to the screen. */
    private fun render() {
        val file = rt.screenFile
        repeat(100) { if (!file.isFile) Thread.sleep(100) }
        val ch = RandomAccessFile(file, "r").channel
        val map = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size()).order(ByteOrder.BIG_ENDIAN)
        // XWD header: header size, then width/height at words 4/5, bytes per line at 12, colours at 19.
        val header = map.getInt(0)
        val bpl = map.getInt(48)
        val colours = map.getInt(76)
        map.position(header + colours * 12)
        val pixels: IntBuffer = map.slice().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val stride = bpl / 4
        this.stride = stride
        this.pixels = pixels.duplicate()
        var bmp: Bitmap? = null
        var row = IntArray(0)
        var out = IntArray(0)
        val paint = android.graphics.Paint().apply { isFilterBitmap = true }
        while (running) {
            val h = holder
            val c = crop
            if (h == null || c.isEmpty || gameWin == 0 || overlay.menu) { Thread.sleep(16); continue }
            val w = c.width(); val ht = c.height()
            if (bmp == null || bmp.width != w || bmp.height != ht) {
                bmp = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
                row = IntArray(w); out = IntArray(w * ht)
            }
            for (y in 0 until ht) {
                pixels.position((c.top + y) * stride + c.left)
                pixels.get(row, 0, w)
                val o = y * w
                for (i in 0 until w) out[o + i] = row[i] or -0x1000000
            }
            bmp.setPixels(out, 0, w, 0, 0, w, ht)
            val canvas = runCatching { h.lockHardwareCanvas() }.getOrNull() ?: continue
            val d = place(canvas.width, canvas.height, w, ht)
            dst = d
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bmp, null, d, paint)
            h.unlockCanvasAndPost(canvas)
            frames++
        }
    }

    /** Where a [w]x[h] game goes on a [vw]x[vh] screen for the chosen scale mode. */
    private fun place(vw: Int, vh: Int, w: Int, h: Int): Rect = when (scaleMode) {
        "stretch" -> Rect(0, 0, vw, vh)
        "original" -> Rect((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
        "fill" -> {
            val s = maxOf(vw.toFloat() / w, vh.toFloat() / h)
            val sw = (w * s).toInt(); val sh = (h * s).toInt()
            Rect((vw - sw) / 2, (vh - sh) / 2, (vw + sw) / 2, (vh + sh) / 2)
        }
        else -> {
            val s = minOf(vw.toFloat() / w, vh.toFloat() / h)
            val sw = (w * s).toInt(); val sh = (h * s).toInt()
            Rect((vw - sw) / 2, (vh - sh) / 2, (vw + sw) / 2, (vh + sh) / 2)
        }
    }

    /** The Windows program: from the launcher, or Flash Player when switched to from the pause menu. */
    private fun program() = (intent.getStringExtra(EXTRA_APP) ?: com.armsx.flashpoint.ui.DEFAULT_FLASH_PLAYER).replace('/', '\\')

    private var restarting = false

    override fun surfaceCreated(h: SurfaceHolder) { holder = h }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { holder = h }
    override fun surfaceDestroyed(h: SurfaceHolder) { holder = null }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::pad.isInitialized && pad.key(event)) return true
        // A hardware or on-screen keyboard: straight to the game.
        if (!overlay.menu && (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) && WinKeys.keysym(event.keyCode) != null) {
            if (event.repeatCount == 0) input.key(event.keyCode, event.action == KeyEvent.ACTION_DOWN)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        (::pad.isInitialized && pad.motion(ev)) || super.dispatchGenericMotionEvent(ev)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        screen?.onTouch(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() { super.onResume(); screen?.onResume() }
    override fun onPause() { screen?.onPause(); super.onPause() }

    private fun quit() {
        if (!running) return
        running = false
        if (::input.isInitialized) input.releaseAll()
        val secs = (SystemClock.elapsedRealtime() - started) / 1000
        setResult(Activity.RESULT_OK, Intent().putExtra(GameActivity.EXTRA_GAME_ID, gameId).putExtra(GameActivity.EXTRA_SECONDS, secs))
        finish()
    }

    override fun onDestroy() {
        running = false
        screen?.onDestroy()
        x?.close()
        rt.stopAll()
        super.onDestroy()
        if (isFinishing && !restarting) android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** GameInput onto the virtual screen: view coordinates map through the game's placement. */
    inner class WinInput : GameInput {
        private val held = HashSet<Int>()
        private var buttons = 0
        override var cursorX = 0f; private set
        override var cursorY = 0f; private set

        private fun toX(vx: Float, vy: Float): Pair<Int, Int> {
            val d = dst; val c = crop
            if (d.isEmpty || c.isEmpty) return 0 to 0
            val gx = c.left + ((vx - d.left) * c.width() / d.width()).toInt()
            val gy = c.top + ((vy - d.top) * c.height() / d.height()).toInt()
            return gx.coerceIn(c.left, c.right - 1) to gy.coerceIn(c.top, c.bottom - 1)
        }

        override fun key(code: Int, down: Boolean) {
            if (down && !held.add(code)) return
            if (!down && !held.remove(code)) return
            val sym = WinKeys.keysym(code) ?: return
            xq.execute { x?.let { c -> c.keycode(sym)?.let { kc -> c.key(kc, down) } } }
        }

        override fun moveCursor(x: Float, y: Float) {
            cursorX = x.coerceAtLeast(0f); cursorY = y.coerceAtLeast(0f)
            val (gx, gy) = toX(cursorX, cursorY)
            xq.execute { this@WinActivity.x?.motion(gx, gy) }
        }

        private fun which(button: Int) = if (button == MotionEvent.BUTTON_SECONDARY) 3 else 1

        override fun mouseDown(button: Int) {
            if (buttons and button != 0) return
            buttons = buttons or button
            val (gx, gy) = toX(cursorX, cursorY)
            android.util.Log.i(WinRuntime.TAG, "press at $gx,$gy (view $cursorX,$cursorY)")
            xq.execute { this@WinActivity.x?.let { it.motion(gx, gy); it.button(which(button), true) } }
        }

        override fun mouseUp(button: Int) {
            if (buttons and button == 0) return
            buttons = buttons and button.inv()
            xq.execute { this@WinActivity.x?.button(which(button), false) }
        }

        override fun releaseAll() {
            held.toList().forEach { key(it, false) }
            if (buttons and MotionEvent.BUTTON_PRIMARY != 0) mouseUp(MotionEvent.BUTTON_PRIMARY)
            if (buttons and MotionEvent.BUTTON_SECONDARY != 0) mouseUp(MotionEvent.BUTTON_SECONDARY)
        }
    }

    companion object {
        const val EXTRA_APP = "winApp"
        const val EXTRA_ARGS = "winArgs"
        const val EXTRA_ROOT = "fpRoot"
        const val GAME_SERVER_PORT = 22500
        const val SCREEN_W = 1600
        const val SCREEN_H = 1200

        fun intent(ctx: Context, base: Intent, app: String, args: String, fpRoot: String): Intent =
            Intent(base).setClass(ctx, WinActivity::class.java)
                .putExtra(EXTRA_APP, app).putExtra(EXTRA_ARGS, args).putExtra(EXTRA_ROOT, fpRoot)
    }
}

/** Android keycodes to X keysyms (US layout), for the keys games use. */
object WinKeys {
    fun keysym(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 0x61 + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> 0x30 + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 0xffbe + (code - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SPACE -> 0x20
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xff0d
        KeyEvent.KEYCODE_ESCAPE -> 0xff1b
        KeyEvent.KEYCODE_TAB -> 0xff09
        KeyEvent.KEYCODE_DEL -> 0xff08
        KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
        KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
        KeyEvent.KEYCODE_DPAD_UP -> 0xff52
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
        KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
        KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
        KeyEvent.KEYCODE_MOVE_END -> 0xff57
        KeyEvent.KEYCODE_PAGE_UP -> 0xff55
        KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
        KeyEvent.KEYCODE_INSERT -> 0xff63
        KeyEvent.KEYCODE_SHIFT_LEFT -> 0xffe1
        KeyEvent.KEYCODE_SHIFT_RIGHT -> 0xffe2
        KeyEvent.KEYCODE_CTRL_LEFT -> 0xffe3
        KeyEvent.KEYCODE_CTRL_RIGHT -> 0xffe4
        KeyEvent.KEYCODE_ALT_LEFT -> 0xffe9
        KeyEvent.KEYCODE_ALT_RIGHT -> 0xffea
        KeyEvent.KEYCODE_COMMA -> 0x2c
        KeyEvent.KEYCODE_PERIOD -> 0x2e
        KeyEvent.KEYCODE_SLASH -> 0x2f
        KeyEvent.KEYCODE_SEMICOLON -> 0x3b
        KeyEvent.KEYCODE_APOSTROPHE -> 0x27
        KeyEvent.KEYCODE_MINUS -> 0x2d
        KeyEvent.KEYCODE_EQUALS -> 0x3d
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x5b
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x5d
        KeyEvent.KEYCODE_BACKSLASH -> 0x5c
        KeyEvent.KEYCODE_GRAVE -> 0x60
        else -> null
    }
}
