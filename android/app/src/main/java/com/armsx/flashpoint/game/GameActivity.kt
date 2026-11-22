package com.armsx.flashpoint.game

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.armsx.flashpoint.core.FpCore
import java.util.concurrent.Executors

/**
 * Runs one game in its own process (`:game`): its own game server, its own WebView, and
 * when it ends the process goes away with everything it loaded.
 *
 * Every request the WebView makes goes through the in-process game server, so archived
 * pages load from their original URLs (http://www.example.com/...) straight out of the
 * data-pack zips. Paths under `/__fpm/` are app assets (the Ruffle player) on whatever
 * host the game uses, so sitelocked Flash still sees its own domain.
 */
class GameActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var started = 0L
    private lateinit var inj: Injector
    private lateinit var overlay: OverlayState
    private lateinit var layouts: LayoutStore
    private var gameId = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val runner = Runner.valueOf(intent.getStringExtra(EXTRA_RUNNER) ?: Runner.WEB.name)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val zips = intent.getStringArrayExtra(EXTRA_ZIPS).orEmpty()

        val port = try {
            val p = FpCore.startServer(
                htdocs = intent.getStringExtra(EXTRA_HTDOCS).orEmpty(),
                legacyBase = intent.getStringExtra(EXTRA_LEGACY).orEmpty(),
                cacheRemote = true,
            )
            zips.forEach { FpCore.mount(it) }
            p
        } catch (e: Exception) {
            Toast.makeText(this, "Game server failed: ${e.message}", Toast.LENGTH_LONG).show()
            return finish()
        }

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = Client(this@GameActivity)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    android.util.Log.i("fpgame", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                    return true
                }
            }
        }
        gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty()
        layouts = LayoutStore(this)
        inj = Injector(web)
        overlay = OverlayState(layouts.load(gameId))
        val ui = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent {
                val st = this@GameActivity.overlay
                GameOverlay(
                    st, inj,
                    onSave = { asDefault -> layouts.save(gameId, st.layout, asDefault) },
                    onKeyboard = ::showKeyboard,
                    onExit = ::quit,
                )
            }
        }
        setContentView(FrameLayout(this).apply {
            addView(web)
            addView(ui)
        })
        web.post { inj.moveCursor(web.width / 2f, web.height / 2f) }

        // Ruffle polyfills <embed>/<object> Flash on any page the WebView loads.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, RufflePage.POLYFILL, setOf("*"))
        }

        val target = when (runner) {
            Runner.FLASH -> RufflePage.playerUrl(url)
            else -> url
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back opens the in-game menu; Exit is in there. A second Back closes it again.
            override fun handleOnBackPressed() {
                overlay.editing = false
                overlay.menu = !overlay.menu
            }
        })

        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            // No direct fallback: everything, http and https, goes through the game server.
            val cfg = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
            ProxyController.getInstance().setProxyOverride(cfg, Executors.newSingleThreadExecutor()) {
                runOnUiThread { web.loadUrl(target) }
            }
        } else {
            Toast.makeText(this, "This WebView is too old to route games (needs proxy override).", Toast.LENGTH_LONG).show()
            finish()
        }
        started = SystemClock.elapsedRealtime()
    }

    private fun showKeyboard() {
        web.requestFocus()
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
    }

    // ---- controllers -----------------------------------------------------------------

    private fun isPad(e: android.view.InputEvent): Boolean {
        val src = e.source
        return src and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            src and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            (src and android.view.InputDevice.SOURCE_DPAD == android.view.InputDevice.SOURCE_DPAD &&
                e.device?.let { !it.isVirtual && it.keyboardType != android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (!::inj.isInitialized || !isPad(event)) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (code == android.view.KeyEvent.KEYCODE_BACK || code == android.view.KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == android.view.KeyEvent.ACTION_UP) overlay.menu = !overlay.menu
            return true
        }
        val target = overlay.layout.pad[code] ?: return super.dispatchKeyEvent(event)
        if (event.repeatCount > 0) return true
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        when (target) {
            Keys.MOUSE_LEFT -> { showCursor(); if (down) inj.mouseDown() else inj.mouseUp() }
            Keys.MOUSE_RIGHT -> { showCursor(); if (down) inj.mouseDown(android.view.MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(android.view.MotionEvent.BUTTON_SECONDARY) }
            else -> inj.key(target, down)
        }
        return true
    }

    private var stickMask = 0
    private var hatMask = 0
    private var aimX = 0f
    private var aimY = 0f
    private var trig = 0

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (!::inj.isInitialized || !isPad(ev) || ev.action != android.view.MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(ev)
        val arrows = intArrayOf(android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT)
        fun mask(x: Float, y: Float, t: Float) = (if (y < -t) 1 else 0) or (if (y > t) 2 else 0) or (if (x < -t) 4 else 0) or (if (x > t) 8 else 0)
        fun apply(old: Int, new: Int) {
            for (i in 0..3) { val b = 1 shl i; if ((old and b) != (new and b)) inj.key(arrows[i], new and b != 0) }
        }
        // D-pad as a hat.
        val hat = mask(ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_X), ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y), 0.5f)
        apply(hatMask, hat); hatMask = hat
        val lx = ev.getAxisValue(android.view.MotionEvent.AXIS_X)
        val ly = ev.getAxisValue(android.view.MotionEvent.AXIS_Y)
        val rx = ev.getAxisValue(android.view.MotionEvent.AXIS_Z)
        val ry = ev.getAxisValue(android.view.MotionEvent.AXIS_RZ)
        if (overlay.layout.stickMovesCursor) {
            aimX = rx + lx; aimY = ry + ly
            apply(stickMask, 0); stickMask = 0
        } else {
            val m = mask(lx, ly, 0.5f)
            apply(stickMask, m); stickMask = m
            aimX = rx; aimY = ry
        }
        // Analog triggers on pads that report them as axes instead of L2/R2 keys.
        val t = (if (ev.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER) > 0.5f || ev.getAxisValue(android.view.MotionEvent.AXIS_GAS) > 0.5f) 1 else 0) or
            (if (ev.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER) > 0.5f || ev.getAxisValue(android.view.MotionEvent.AXIS_BRAKE) > 0.5f) 2 else 0)
        if (t != trig) {
            if ((t xor trig) and 1 != 0) { showCursor(); if (t and 1 != 0) inj.mouseDown() else inj.mouseUp() }
            if ((t xor trig) and 2 != 0) { showCursor(); if (t and 2 != 0) inj.mouseDown(android.view.MotionEvent.BUTTON_SECONDARY) else inj.mouseUp(android.view.MotionEvent.BUTTON_SECONDARY) }
            trig = t
        }
        if (hypot2(aimX, aimY) > 0.04f) startAim()
        return true
    }

    private fun hypot2(x: Float, y: Float) = x * x + y * y

    private fun showCursor() {
        overlay.cursor = androidx.compose.ui.geometry.Offset(inj.cursorX, inj.cursorY)
    }

    private var aiming = false
    /** Right stick: move the cursor every frame while it's deflected, faster the further it goes. */
    private fun startAim() {
        if (aiming) return
        aiming = true
        val choreo = android.view.Choreographer.getInstance()
        var last = 0L
        choreo.postFrameCallback(object : android.view.Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                val dt = if (last == 0L) 0.016f else (t - last) / 1e9f
                last = t
                val mag = kotlin.math.sqrt(hypot2(aimX, aimY))
                if (mag < 0.15f) { aiming = false; return }
                val speed = web.width * 0.9f * ((mag - 0.15f) / 0.85f).let { it * it + 0.15f }
                inj.moveCursor(inj.cursorX + aimX / mag * speed * dt, inj.cursorY + aimY / mag * speed * dt)
                showCursor()
                choreo.postFrameCallback(this)
            }
        })
    }

    private fun quit() {
        if (::inj.isInitialized) inj.releaseAll()
        val secs = (SystemClock.elapsedRealtime() - started) / 1000
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_GAME_ID, intent.getStringExtra(EXTRA_GAME_ID)).putExtra(EXTRA_SECONDS, secs))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
        // The game process exists for one game; take everything it loaded with it.
        if (isFinishing) android.os.Process.killProcess(android.os.Process.myPid())
    }

    private class Client(private val ctx: Context) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            RufflePage.intercept(ctx, request.url)

        /**
         * Every request from this WebView goes to the local game server (proxy override), which
         * ends https:// itself with a certificate it made for that host. Those are the only
         * certificates this WebView ever sees, so they are accepted.
         */
        @android.annotation.SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
            handler.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // Games that try to open new pages (sponsor links, "more games") stay put.
            return !request.isForMainFrame || request.url.scheme !in setOf("http", "https")
        }
    }

    companion object {
        const val EXTRA_RUNNER = "runner"
        const val EXTRA_URL = "url"
        const val EXTRA_ZIPS = "zips"
        const val EXTRA_HTDOCS = "htdocs"
        const val EXTRA_LEGACY = "legacy"
        const val EXTRA_GAME_ID = "gameId"
        const val EXTRA_SECONDS = "seconds"

        fun intent(ctx: Context, gameId: String, runner: Runner, url: String, zips: List<String>, htdocs: String, legacy: String) =
            Intent(ctx, GameActivity::class.java)
                .putExtra(EXTRA_GAME_ID, gameId)
                .putExtra(EXTRA_RUNNER, runner.name)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_ZIPS, zips.toTypedArray())
                .putExtra(EXTRA_HTDOCS, htdocs)
                .putExtra(EXTRA_LEGACY, legacy)
    }
}

/** The Ruffle player page and its assets, served under `/__fpm/` on the game's own host. */
object RufflePage {
    private const val PREFIX = "/__fpm/"

    const val CONFIG = """{"autoplay":"on","unmuteOverlay":"hidden","splashScreen":false,"letterbox":"on","contextMenu":"rightClickOnly","openUrlMode":"deny","allowNetworking":"all","publicPath":"/__fpm/ruffle/","polyfills":true,"maxExecutionDuration":30}"""

    val POLYFILL = """
        (function(){
          if (window.__fpmRuffle) return; window.__fpmRuffle = true;
          window.RufflePlayer = window.RufflePlayer || {};
          window.RufflePlayer.config = $CONFIG;
          var s = document.createElement('script'); s.src = '/__fpm/ruffle/ruffle.js';
          (document.head || document.documentElement).appendChild(s);
        })();
    """.trimIndent()

    fun playerUrl(swfUrl: String): String {
        val u = Uri.parse(swfUrl)
        return Uri.Builder().scheme(u.scheme ?: "http").encodedAuthority(u.encodedAuthority)
            .path("/__fpm/player.html").appendQueryParameter("swf", swfUrl).build().toString()
    }

    fun intercept(ctx: Context, url: Uri): WebResourceResponse? {
        val path = url.path ?: return null
        if (!path.startsWith(PREFIX)) return null
        if (path == "${PREFIX}player.html") {
            val swf = url.getQueryParameter("swf").orEmpty()
            return html(playerHtml(swf))
        }
        val asset = path.removePrefix(PREFIX)
        if (asset.contains("..")) return null
        return try {
            val mime = when {
                asset.endsWith(".wasm") -> "application/wasm"
                asset.endsWith(".js") -> "text/javascript"
                else -> "application/octet-stream"
            }
            WebResourceResponse(mime, null, ctx.assets.open(asset)).apply {
                responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun html(s: String) = WebResourceResponse("text/html", "utf-8", s.byteInputStream())

    private fun jsString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c") + "\""

    private fun playerHtml(swf: String) = """
        <!doctype html><html><head>
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden;touch-action:none}
        #p{position:fixed;inset:0}ruffle-player{width:100%;height:100%;display:block}</style>
        <script>if(!window.__fpmRuffle){window.__fpmRuffle=true;window.RufflePlayer=window.RufflePlayer||{};
        window.RufflePlayer.config=$CONFIG;document.write('<script src="/__fpm/ruffle/ruffle.js"><\/script>');}</script>
        </head><body><div id="p"></div><script>
        (function(){
          var url = ${jsString(swf)};
          function go(){
            if (!window.RufflePlayer || !window.RufflePlayer.newest) return setTimeout(go, 20);
            var player = window.RufflePlayer.newest().createPlayer();
            document.getElementById('p').appendChild(player);
            var api = player.ruffle ? player.ruffle() : player;
            api.load({ url: url, base: url.substring(0, url.lastIndexOf('/') + 1) });
            player.focus();
          }
          go();
        })();
        </script></body></html>
    """.trimIndent()
}
