package vn.camerold.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import vn.camerold.R

/** Viewer mode: runs the bundled web/index.html page itself in a WebView. */
class ViewerActivity : BaseActivity() {

    private lateinit var web: WebView
    private var fullscreen = false
    private var pendingPermission: PermissionRequest? = null
    private var pendingFiles: android.webkit.ValueCallback<Array<Uri>>? = null
    /** Timelapse video being saved (see Bridge.fileOpen). */
    @Volatile private var download: vn.camerold.Gallery.Writer? = null

    // YouTube-style rotation: the watch screen stays upright; turning the phone sideways enters full screen,
    // turning it back upright leaves it. The full-screen button rotates the screen on its own.
    private var watching = false
        set(v) { field = v; updatePip() }
    private var autoEnterArmed = true   // false after leaving full screen while still holding the phone sideways
    private var autoExitArmed = false   // true once the phone has really been held sideways in full screen
    private val isTablet get() = resources.configuration.smallestScreenWidthDp >= 600
    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                val sideways = deg in 65..115 || deg in 245..295
                val upright = deg <= 25 || deg >= 335
                if (fullscreen) {
                    if (sideways) autoExitArmed = true
                    else if (upright && autoExitArmed && autoRotateOn()) setFullscreen(false)
                } else {
                    if (upright) autoEnterArmed = true
                    else if (sideways && autoEnterArmed && watching && autoRotateOn()) setFullscreen(true)
                }
            }
        }
    }

    /** Respect the system "Auto-rotate" switch, like YouTube does. */
    private fun autoRotateOn() =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web = WebView(this)
        web.setBackgroundColor(getColor(R.color.bg)) // no black flash before the page draws
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        // Only the bundled page runs here (served by the asset loader): no file:// or content:// access
        web.settings.allowFileAccess = false
        web.settings.allowContentAccess = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // External links (e.g. TURN sign-up page) open in the browser
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == "appassets.androidplatform.net") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, request.url)) } catch (_: Exception) {}
                return true
            }
        }
        // Let the web page use the camera to scan QR codes
        web.webChromeClient = object : WebChromeClient() {
            // <input type="file">: pick a QR code image (e.g. a screenshot of the camera's QR)
            override fun onShowFileChooser(view: WebView, cb: android.webkit.ValueCallback<Array<Uri>>,
                                           params: FileChooserParams): Boolean {
                pendingFiles?.onReceiveValue(null)
                pendingFiles = cb
                return try { startActivityForResult(params.createIntent(), 3); true }
                catch (_: Exception) { pendingFiles = null; false }
            }

            override fun onPermissionRequest(request: PermissionRequest) = runOnUiThread {
                // Only our own page, and only the camera (to scan QR codes)
                if (request.origin?.host != "appassets.androidplatform.net" ||
                    !request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { request.deny(); return@runOnUiThread }
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                } else {
                    pendingPermission = request
                    requestPermissions(arrayOf(Manifest.permission.CAMERA), 2)
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "CameroldApp")
        if (!isTablet) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        setContentView(web)
        applyBars(false)
        web.loadUrl(pageUrl(intent))
    }

    /** camerold://join?r=ROOM&k=PASS -> passed to the page via #r=..&k=..&auto=1 (the fragment never leaves the device). */
    private fun pageUrl(i: Intent?): String {
        // Tell the web page: running inside the app + current light/dark theme
        val base = "https://appassets.androidplatform.net/assets/index.html?app=1&theme=" +
            (if (ThemePref.isDark(this)) "dark" else "light") + "&lang=" + resources.configuration.locales[0].language
        val d = i?.data ?: return base
        if (d.scheme != "camerold") return base
        val r = d.getQueryParameter("r") ?: return base
        val k = d.getQueryParameter("k") ?: ""
        // b/p: the camera's own MQTT broker (and whether to also use the public ones), see signaling/Brokers.kt
        val extra = listOf("b", "p").mapNotNull { n -> d.getQueryParameter(n)?.let { "&$n=${Uri.encode(it)}" } }.joinToString("")
        return "$base#r=${Uri.encode(r)}&k=${Uri.encode(k)}$extra&auto=1"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) web.loadUrl(pageUrl(intent))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != 3) return super.onActivityResult(requestCode, resultCode, data)
        pendingFiles?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        pendingFiles = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 4) return // storage (old Android): the user just taps the shutter again
        if (requestCode == 5) return // notifications for battery alerts: nothing to do now
        val req = pendingPermission ?: return
        pendingPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) req.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        else req.deny()
    }

    inner class Bridge {
        @JavascriptInterface
        fun toggleFullscreen() = runOnUiThread { setFullscreen(!fullscreen) }

        @JavascriptInterface
        fun setFullscreen(on: Boolean) = runOnUiThread { this@ViewerActivity.setFullscreen(on) }

        /**
         * Saves a snapshot (JPEG) or QR code (PNG), base64, to the phone's Pictures/Camerold so it shows in the gallery.
         * Returns "ok", "perm" (Android 9 and older: storage permission asked, try again) or "error".
         */
        @JavascriptInterface
        fun saveImage(base64: String, name: String): String = try {
            if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4) }
                "perm"
            } else {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val mime = if (name.endsWith(".png", true)) "image/png" else "image/jpeg"
                if (vn.camerold.Gallery.saveImage(this@ViewerActivity, name, bytes, mime)) "ok" else "error"
            }
        } catch (e: Exception) {
            android.util.Log.e("ViewerActivity", "saveImage", e)
            "error"
        }

        /** Shares a picture (PNG or JPEG, base64), e.g. a camera's QR code: the system share sheet. */
        @JavascriptInterface
        fun shareImage(base64: String, name: String, title: String) = runOnUiThread {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                vn.camerold.ShareProvider.share(this@ViewerActivity, name, bytes,
                    if (name.endsWith(".png", true)) "image/png" else "image/jpeg", title)
            } catch (e: Exception) {
                android.util.Log.e("ViewerActivity", "shareImage", e)
            }
        }

        /**
         * Saving a downloaded timelapse video to Movies/Camerold, in pieces (a whole video is too big for one call):
         * fileOpen(name) -> "ok" / "perm" (Android 9 and older: storage permission asked, try again) / "error",
         * then fileWrite(base64) for each piece and fileClose(ok).
         */
        @JavascriptInterface
        fun fileOpen(name: String): String {
            download?.finish(false)
            download = null
            if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4) }
                return "perm"
            }
            download = vn.camerold.Gallery.openVideo(this@ViewerActivity, name) ?: return "error"
            return "ok"
        }

        @JavascriptInterface
        fun fileWrite(base64: String): Boolean = try {
            download!!.out.write(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)); true
        } catch (e: Exception) { false }

        @JavascriptInterface
        fun fileClose(ok: Boolean) {
            download?.finish(ok)
            download = null
        }

        /** Status/navigation bar colors follow the page: the page background on lists and settings, black under video. */
        @JavascriptInterface
        fun setDarkBars(dark: Boolean) = runOnUiThread { applyBars(dark) }

        /** The phone can show a floating window (picture in picture); some Android Go phones can't. */
        @JavascriptInterface
        fun canPip(): Boolean = pipAvailable

        /** The picture's shape as shown (rotation applied): the floating window takes it. */
        @JavascriptInterface
        fun setPipShape(w: Int, h: Int) = runOnUiThread { pipW = w; pipH = h; updatePip() }

        /** The page's picture-in-picture button. */
        @JavascriptInterface
        fun enterPip(w: Int, h: Int) = runOnUiThread { pipW = w; pipH = h; enterPip() }

        /** The page is showing a camera (not the sign-in form). */
        @JavascriptInterface
        fun setWatching(on: Boolean) = runOnUiThread { watching = on }

        /**
         * The saved cameras changed: [json] = [{room, pass, name, brokers}]. They're checked in the background for
         * low battery (alerts/BatteryAlerts). The first time there's one, ask for permission to notify.
         */
        @JavascriptInterface
        fun syncCams(json: String) {
            vn.camerold.alerts.BatteryAlerts.sync(applicationContext, json)
            runOnUiThread { askNotifyOnce(json) }
        }

        /** The page's back arrow on the sign-in screen: back to the app's home screen. */
        @JavascriptInterface
        fun close() = runOnUiThread { finish() }
    }

    // Picture in picture: the watch screen shrinks to a floating window (the button, or going Home while watching).
    // The page hides its controls in it (onAppPip); swiping the window away stops watching (onAppPipClosed).
    private val pipAvailable by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private var pipW = 16
    private var pipH = 9
    /** Left the floating window: back to full size (onResume follows) or swiped away (onStop follows). */
    private var leftPip = false
    private var webPaused = false
    private var active = false

    private fun pipParams(): android.app.PictureInPictureParams {
        // Android refuses shapes beyond 2.39:1 either way
        val r = (pipW.toDouble() / pipH.coerceAtLeast(1)).coerceIn(1 / 2.39, 2.39)
        val b = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(if (r >= 1) android.util.Rational((r * 1000).toInt(), 1000) else android.util.Rational(1000, (1000 / r).toInt()))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(watching).setSeamlessResizeEnabled(false)
        return b.build()
    }

    private fun updatePip() {
        if (!pipAvailable) return
        try { setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun enterPip() {
        if (!pipAvailable || !watching || isInPictureInPictureMode) return
        try { enterPictureInPictureMode(pipParams()) } catch (e: Exception) { android.util.Log.w("ViewerActivity", "pip", e) }
    }

    // Android 8–11: going Home while watching opens the floating window (12+: setAutoEnterEnabled, smoother)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode) leftPip = true
        web.evaluateJavascript("window.onAppPip && onAppPip($isInPictureInPictureMode)", null)
    }

    private fun askNotifyOnce(json: String) {
        if (Build.VERSION.SDK_INT < 33 || json.length < 5 || !vn.camerold.alerts.BatteryAlerts.isEnabled(this)) return
        if (vn.camerold.alerts.BatteryAlerts.canNotify(this)) return
        val p = getSharedPreferences("alerts", MODE_PRIVATE)
        if (p.getBoolean("asked", false)) return
        p.edit().putBoolean("asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    // Like YouTube: in fullscreen, Back only exits fullscreen.
    // Android 13+: a back callback registered only while in fullscreen (works with predictive back);
    // older versions: onBackPressed.
    private val exitFullscreenOnBack: Any? =
        if (Build.VERSION.SDK_INT >= 33) android.window.OnBackInvokedCallback { setFullscreen(false) } else null

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val cb = exitFullscreenOnBack as android.window.OnBackInvokedCallback
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cb)
        if (fullscreen) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) setFullscreen(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    @Suppress("DEPRECATION")
    private fun applyBars(dark: Boolean) {
        val color = if (dark) 0xFF000000.toInt() else getColor(R.color.bg)
        window.statusBarColor = color
        window.navigationBarColor = color
        window.decorView.setBackgroundColor(color) // what shows behind the bars on Android 15+ (edge to edge)
        val lightIcons = dark || ThemePref.isDark(this)
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightIcons) 0 else flags, flags)
        } else {
            var v = window.decorView.systemUiVisibility
            v = if (lightIcons) v and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else v or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26)
                v = if (lightIcons) v and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else v or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = v
        }
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(on: Boolean) {
        if (fullscreen != on) {
            // Entering by the button while upright: don't leave again until the phone has been turned sideways.
            // Leaving while still sideways: don't jump back in until the phone has been turned upright.
            autoExitArmed = false
            autoEnterArmed = false
        }
        fullscreen = on
        updateBackHandling()
        requestedOrientation = when {
            on -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            isTablet -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        web.evaluateJavascript("window.onAppFullscreen && onAppFullscreen($on)", null)
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (on) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else c.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = if (on)
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            else 0
        }
    }

    // Folding or unfolding a foldable switches between phone (upright, turn sideways for full screen) and tablet
    // (any way up) without rebuilding the screen
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (fullscreen || isInPictureInPictureMode) return
        val want = if (isTablet) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (requestedOrientation != want) requestedOrientation = want
        if (isTablet) orientation.disable() else if (active) orientation.enable()
    }

    override fun onResume() {
        super.onResume()
        active = true
        leftPip = false
        webPaused = false
        web.onResume()
        if (!isTablet) orientation.enable()
    }

    override fun onPause() {
        active = false
        orientation.disable()
        // In the floating window the picture keeps playing
        if (!isInPictureInPictureMode) { web.onPause(); webPaused = true }
        super.onPause()
    }

    override fun onStop() {
        if (leftPip) {
            leftPip = false
            web.evaluateJavascript("window.onAppPipClosed && onAppPipClosed()", null)
        }
        if (!webPaused) { web.onPause(); webPaused = true }
        super.onStop()
    }
    override fun onDestroy() { download?.finish(false); web.destroy(); super.onDestroy() }
}
