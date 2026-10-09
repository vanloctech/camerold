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

/** Viewer mode: runs the bundled web/index.html page itself in a WebView. */
class ViewerActivity : BaseActivity() {

    private lateinit var web: WebView
    private var fullscreen = false
    private var pendingPermission: PermissionRequest? = null
    private var pendingFiles: android.webkit.ValueCallback<Array<Uri>>? = null

    // YouTube-style rotation: the watch screen stays upright; turning the phone sideways enters full screen,
    // turning it back upright leaves it. The full-screen button rotates the screen on its own.
    private var watching = false
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
        web.setBackgroundColor(0xFF000000.toInt())
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
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
                if (!request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) { request.deny(); return@runOnUiThread }
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
         * Saves a snapshot (JPEG, base64) to the phone's Pictures/Camerold so it shows in the gallery.
         * Returns "ok", "perm" (Android 9 and older: storage permission asked, try again) or "error".
         */
        @JavascriptInterface
        fun saveImage(base64: String, name: String): String = try {
            val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
            val file = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").take(120)
            if (Build.VERSION.SDK_INT >= 29) {
                val v = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camerold")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
                contentResolver.update(uri, android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                "ok"
            } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 4) }
                "perm"
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Camerold")
                dir.mkdirs()
                val f = java.io.File(dir, file)
                f.writeBytes(bytes)
                android.media.MediaScannerConnection.scanFile(this@ViewerActivity, arrayOf(f.path), arrayOf("image/jpeg"), null)
                "ok"
            }
        } catch (e: Exception) {
            android.util.Log.e("ViewerActivity", "saveImage", e)
            "error"
        }

        /** The page is showing a camera (not the sign-in form). */
        @JavascriptInterface
        fun setWatching(on: Boolean) = runOnUiThread { watching = on }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Like YouTube: in fullscreen, Back only exits fullscreen
        if (fullscreen) setFullscreen(false) else @Suppress("DEPRECATION") super.onBackPressed()
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

    override fun onResume() { super.onResume(); web.onResume(); if (!isTablet) orientation.enable() }
    override fun onPause() { orientation.disable(); web.onPause(); super.onPause() }
    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
