package vn.camerold.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import org.webrtc.ProCapturer
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import vn.camerold.App
import vn.camerold.R
import vn.camerold.camera.CameraEngine
import vn.camerold.camera.CameraService
import vn.camerold.data.CamConfig
import vn.camerold.data.Prefs
import vn.camerold.signaling.SigCrypto

/**
 * Camera screen: preview with live status, how viewers connect (code, password, QR) and the start/stop button.
 * Picture, sound, 4G and background settings are on their own screen (gear icon).
 */
class CameraActivity : BaseActivity() {

    companion object { const val MAX_NAME = 40 }

    /** Code/password being edited (saved when streaming starts or the screen is left). */
    private lateinit var draft: CamConfig
    private lateinit var rows: Rows

    private lateinit var statusDot: View
    private lateinit var status: TextView
    private lateinit var preview: SurfaceViewRenderer
    private lateinit var previewHint: View
    private lateinit var previewTitle: TextView
    private lateinit var previewSub: TextView
    private val pulses = mutableListOf<android.animation.Animator>()
    private lateinit var rowSettings: Rows.Row
    private lateinit var qrTile: Tile
    private lateinit var soundTile: Tile
    private lateinit var banner: View
    private lateinit var btnStart: View
    private lateinit var btnIcon: ImageView
    private lateinit var btnText: TextView
    private lateinit var btnDim: View
    private lateinit var blackOverlay: View

    private lateinit var rowName: Rows.Row
    private lateinit var rowRoom: Rows.Row
    private lateinit var password: EditText
    private lateinit var passActions: LinearLayout
    /** Locked while streaming. */
    private val lockable = mutableListOf<View>()

    private var boundEngine: CameraEngine? = null
    private var pendingStart = false
    private var micAsked = false
    private var weakPasswordOk = false
    private var passwordVisible = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)
        topBar(R.string.camera_title, R.drawable.ic_settings, R.string.camera_settings_title) { openSettings() }
        rows = Rows(this)
        statusDot = findViewById(R.id.statusDot)
        status = findViewById(R.id.status)
        preview = findViewById(R.id.preview)
        previewHint = findViewById(R.id.previewHint)
        previewTitle = findViewById(R.id.previewTitle)
        previewSub = findViewById(R.id.previewSub)
        qrTile = Tile(findViewById(R.id.tileQr))
        soundTile = Tile(findViewById(R.id.tileSound))
        banner = findViewById(R.id.banner)
        btnStart = findViewById(R.id.btnStart)
        btnIcon = findViewById(R.id.btnIcon)
        btnText = findViewById(R.id.btnText)
        btnDim = findViewById(R.id.btnDim)
        blackOverlay = findViewById(R.id.blackOverlay)

        draft = Prefs.load(this)
        buildConnection()
        qrTile.onClick { showQr() }
        // Sound on/off right here (it can't change while streaming: the microphone is set up when streaming starts)
        soundTile.onClick {
            if (CameraService.running) return@onClick
            Prefs.save(this, Prefs.load(this).copy(audio = !Prefs.load(this).audio))
            refreshUi()
        }
        rowSettings = rows.row(findViewById(R.id.groupCam), R.drawable.ic_sliders, R.string.row_camera_settings) { openSettings() }

        // 4:3 preview frame
        findViewById<View>(R.id.previewBox).let { box ->
            box.post { box.layoutParams = box.layoutParams.apply { height = box.width * 3 / 4 } }
        }

        btnStart.setOnClickListener { if (CameraService.running) stopStreaming() else startStreaming() }
        btnDim.setOnClickListener { setBlack(true) }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean { setBlack(false); return true }
        })
        blackOverlay.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }

        preview.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        refreshUi()
        App.showLastCrash(this)
    }

    private fun openSettings() {
        saveDraft()
        startActivity(Intent(this, CameraSettingsActivity::class.java))
    }

    // ---------------- How viewers connect ----------------

    private fun buildConnection() {
        val conn = findViewById<LinearLayout>(R.id.groupConn)
        // The name can change any time, even while streaming (viewers see it at once)
        rowName = rows.row(conn, R.drawable.ic_camera, R.string.row_cam_name) { editName() }
        rowRoom = rows.row(conn, R.drawable.ic_hash, R.string.row_code) { editRoom() }
        rows.iconAction(rowRoom.actions, R.drawable.ic_share, R.string.share_code) { shareRoom() }
        lockable += rowRoom.view

        password = rows.editRow(conn, R.drawable.ic_key, R.string.row_password, getString(R.string.password_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.setText(draft.password)
        passActions = (password.tag as View).findViewById(R.id.actions)
        lateinit var eye: ImageView
        eye = rows.iconAction(passActions, R.drawable.ic_eye, R.string.toggle_password) { showPassword(eye, !passwordVisible) }
        rows.iconAction(passActions, R.drawable.ic_dice, R.string.gen_password) {
            password.setText(SigCrypto.randomPassword())
            showPassword(eye, true)
            toast(R.string.password_generated)
        }
        lockable += password.tag as View
    }

    private fun showPassword(eye: ImageView, show: Boolean) {
        passwordVisible = show
        val sel = password.selectionEnd
        Rows.setInputType(password, InputType.TYPE_CLASS_TEXT or
            if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
        password.setSelection(sel.coerceIn(0, password.text.length))
        eye.setImageResource(if (show) R.drawable.ic_eye_off else R.drawable.ic_eye)
    }

    private fun saveDraft() {
        if (CameraService.running) return
        draft = Prefs.load(this).copy(room = SigCrypto.normalizeRoom(draft.room), password = password.text.toString())
        Prefs.save(this, draft)
    }

    /** QR code with camera ID + password: the viewer scans it to connect. Auto-closes after 90 seconds. */
    private fun showQr() {
        val c = if (CameraService.running) Prefs.load(this) else draft.copy(password = password.text.toString())
        if (c.password.length < 8) { toast(R.string.qr_need_password); return }
        // Just the code on a white card: the row that opens it already says what it's for
        val size = dp(264)
        val img = ImageView(this).apply {
            setImageBitmap(QrCode.bitmap(QrCode.joinUri(c), size))
        }
        val card = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(32).toFloat() }
            setPadding(dp(18), dp(18), dp(18), dp(18))
            addView(img, FrameLayout.LayoutParams(size, size))
        }
        val d = android.app.Dialog(this).apply {
            setContentView(card)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setCanceledOnTouchOutside(true)
        }
        card.setOnClickListener { d.dismiss() }
        d.show()
        img.postDelayed({ if (d.isShowing) d.dismiss() }, 90_000) // don't leave the password on screen
    }

    private fun showName() {
        val name = Prefs.load(this).name
        rowName.value(name.ifEmpty { getString(R.string.cam_name_unset, Build.MODEL) })
        findViewById<TextView>(R.id.barTitle).text = name.ifEmpty { getString(R.string.camera_title) }
    }

    private fun editName() {
        val input = EditText(this).apply {
            setText(Prefs.load(this@CameraActivity).name)
            hint = getString(R.string.cam_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_NAME))
            setSingleLine()
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.row_cam_name)
            .setMessage(R.string.cam_name_msg)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim().replace(Regex("\\s+"), " ").take(MAX_NAME)
                Prefs.save(this, Prefs.load(this).copy(name = name))
                draft = draft.copy(name = name)
                CameraEngine.current?.setName(name)
                showName()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun editRoom() {
        val input = EditText(this).apply {
            setText(draft.room)
            Rows.setInputType(this, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(R.string.row_code)
            .setMessage(R.string.code_dialog_msg)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> draft = draft.copy(room = SigCrypto.normalizeRoom(input.text.toString())); rowRoom.value(draft.room) }
            .setNeutralButton(R.string.code_new) { _, _ -> draft = draft.copy(room = SigCrypto.randomId(10)); rowRoom.value(draft.room) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun shareRoom() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", draft.room))
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_text, draft.room)), getString(R.string.share_code)))
    }

    // ---------------- Start / stop ----------------

    private fun startStreaming() {
        saveDraft()
        val cfg = Prefs.load(this)
        if (cfg.room.length < 6) { toast(R.string.err_code_short); return }
        if (cfg.password.length < 8) { toast(R.string.err_pass_short); password.requestFocus(); return }
        if (cfg.password.length < 12 && !weakPasswordOk) {
            AlertDialog.Builder(this)
                .setTitle(R.string.weak_title)
                .setMessage(R.string.weak_msg)
                .setPositiveButton(R.string.weak_generate) { _, _ -> password.setText(SigCrypto.randomPassword()); startStreaming() }
                .setNegativeButton(R.string.weak_keep) { _, _ -> weakPasswordOk = true; startStreaming() }
                .show()
            return
        }
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need += Manifest.permission.CAMERA
        if (cfg.audio && !micAsked && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) {
            pendingStart = true
            requestPermissions(need.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_START))
        setStatusLine(getString(R.string.status_starting), R.color.warn)
        showPlaceholder(opening = true)
        status.postDelayed({ refreshUi() }, 400)
        status.postDelayed({ refreshUi() }, 1500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!pendingStart) return
        pendingStart = false
        micAsked = true // if mic is denied, still stream video and don't ask again
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startStreaming()
        else toast(R.string.need_camera)
    }

    private fun stopStreaming() {
        unbindPreview()
        startService(Intent(this, CameraService::class.java).setAction(CameraService.ACTION_STOP))
        status.postDelayed({ refreshUi() }, 300)
    }

    private fun refreshUi() {
        val running = CameraService.running
        btnStart.background = getDrawable(if (running) R.drawable.bg_pill_danger else R.drawable.bg_pill)
        btnIcon.setImageResource(if (running) R.drawable.ic_stop else R.drawable.ic_play)
        btnText.setText(if (running) R.string.btn_stop else R.string.btn_start)
        // White on red while streaming; the primary button's own text color otherwise
        val fg = if (running) 0xFFFFFFFF.toInt() else getColor(R.color.on_accent)
        btnText.setTextColor(fg); btnIcon.imageTintList = ColorStateList.valueOf(fg)
        val audio = Prefs.load(this).audio
        qrTile.set(R.drawable.ic_qr, getString(R.string.tile_qr), getString(R.string.tile_qr_sub))
        soundTile.set(if (audio) R.drawable.ic_mic else R.drawable.ic_mic_off, getString(R.string.tile_sound),
            getString(if (audio) R.string.tile_on else R.string.tile_off), on = audio)
        soundTile.enabled = !running
        btnDim.visibility = if (running) View.VISIBLE else View.GONE
        lockable.forEach { Rows.setChildrenEnabled(it, !running); it.alpha = if (running) 0.45f else 1f }
        if (running) draft = Prefs.load(this)
        rowRoom.value(draft.room)
        showName()
        updateSummary()
        updateBanner()
        val engine = CameraEngine.current
        if (running && engine != null) {
            bindPreview(engine)
            showStatus(engine.lastStatus)
        } else {
            unbindPreview()
            setStatusLine(getString(R.string.status_idle), 0)
        }
    }

    private fun updateSummary() {
        val c = Prefs.load(this)
        val lens = try { ProCapturer.listLenses(this).firstOrNull { it.key == c.lens }?.label } catch (_: Exception) { null }
        rowSettings.value(CameraSettingsActivity.summary(this, c, lens))
    }

    /** One problem at a time, with its fix right there. */
    private fun updateBanner() {
        if (CameraSettingsActivity.backgroundAllowed(this)) { banner.visibility = View.GONE; return }
        banner.visibility = View.VISIBLE
        findViewById<TextView>(R.id.bannerText).setText(R.string.banner_bg)
        findViewById<TextView>(R.id.bannerBtn).apply {
            setText(R.string.banner_bg_fix)
            setOnClickListener { CameraSettingsActivity.askBackground(this@CameraActivity) }
        }
    }

    /** color 0 = idle (grey). */
    private fun setStatusLine(text: String, color: Int) {
        status.text = text
        statusDot.backgroundTintList = ColorStateList.valueOf(if (color == 0) 0xFFB0B0B0.toInt() else getColor(color))
    }

    private fun showStatus(s: CameraEngine.Status) {
        setStatusLine(s.message, when {
            s.brokersUp == 0 -> R.color.warn
            s.viewers > 0 -> R.color.bad
            else -> R.color.ok
        })
    }

    private fun bindPreview(engine: CameraEngine) {
        if (boundEngine === engine) return
        unbindPreview()
        showPlaceholder(opening = true)
        preview.init(engine.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
            // Hide the placeholder only once a real frame is on screen
            override fun onFirstFrameRendered() = runOnUiThread { hidePlaceholder() }
            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {}
        })
        engine.setPreview(preview)
        engine.statusListener = { s -> runOnUiThread { showStatus(s); updateSummary() } }
        boundEngine = engine
    }

    private fun unbindPreview() {
        boundEngine?.let {
            it.setPreview(null)
            it.statusListener = null
            preview.release()
        }
        boundEngine = null
        showPlaceholder(opening = false)
    }

    /** Preview placeholder: idle ("start streaming to see the picture") or opening the camera (pulsing rings). */
    private fun showPlaceholder(opening: Boolean) {
        previewHint.animate().cancel()
        previewHint.alpha = 1f
        previewHint.visibility = View.VISIBLE
        previewTitle.setText(if (opening) R.string.preview_opening else R.string.preview_title)
        previewSub.setText(if (opening) R.string.preview_opening_sub else R.string.preview_hint)
        pulses.forEach { it.cancel() }
        pulses.clear()
        val rings = listOf(findViewById<View>(R.id.previewRing1), findViewById<View>(R.id.previewRing2))
        rings.forEach { it.alpha = 0f; it.scaleX = 1f; it.scaleY = 1f }
        if (!opening) return
        rings.forEachIndexed { i, ring ->
            pulses += android.animation.ObjectAnimator.ofPropertyValuesHolder(ring,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.75f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.75f),
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f),
            ).apply {
                duration = 1800
                startDelay = i * 900L
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.DecelerateInterpolator()
                start()
            }
        }
    }

    private fun hidePlaceholder() {
        pulses.forEach { it.cancel() }
        pulses.clear()
        previewHint.animate().alpha(0f).setDuration(300).withEndAction { previewHint.visibility = View.GONE }.start()
    }

    private fun setBlack(on: Boolean) {
        blackOverlay.visibility = if (on) View.VISIBLE else View.GONE
        boundEngine?.setPreview(if (on) null else preview)
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toast(@StringRes s: Int) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onPause() {
        saveDraft()
        // Detach the preview when leaving the screen (camera keeps streaming in the background)
        if (!isChangingConfigurations) unbindPreview()
        super.onPause()
    }

    override fun onDestroy() {
        unbindPreview()
        super.onDestroy()
    }
}
