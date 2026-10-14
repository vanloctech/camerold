package vn.camerold.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.webrtc.ProCapturer
import vn.camerold.R
import vn.camerold.camera.CameraEngine
import vn.camerold.camera.CameraService
import vn.camerold.camera.Recorder
import vn.camerold.camera.Rtc
import vn.camerold.data.CamConfig
import vn.camerold.data.Prefs
import vn.camerold.signaling.Brokers

/**
 * Camera settings: picture, sound, watching over 4G, background running, advanced.
 * Changes are saved right away. While streaming, the picture/sound/connection settings are locked
 * (viewers can still change quality and lens live from their side).
 */
class CameraSettingsActivity : BaseActivity() {

    companion object {
        val RES = listOf(720, 1080, 2160)
        val FPS = listOf(15, 24, 30, 60)
        val ASPECTS = listOf(CameraEngine.ASPECT_FULL, CameraEngine.ASPECT_WIDE)
        val CODECS = listOf("H264", "VP8")

        /** One line describing the current setup, shown on the camera screen. */
        fun summary(a: android.content.Context, c: CamConfig, lensLabel: String?): String = listOfNotNull(
            qualityName(c), "${c.fps} fps", c.aspect, lensLabel,
            a.getString(if (c.audio) R.string.sum_sound_on else R.string.sum_sound_off),
            if (c.recOn) a.getString(R.string.sum_rec) else null,
        ).joinToString(" · ")

        fun qualityName(c: CamConfig) = when (minOf(c.res, Rtc.maxRes(c.codec, c.aspect))) { 2160 -> "4K"; 720 -> "HD"; else -> "Full HD" }

        /** dontkillmyapp.com page for phone makers known to stop background apps (Pixel and stock Android don't). */
        fun oemGuideSlug(): String? {
            val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
            return listOf(
                "xiaomi" to "xiaomi", "redmi" to "xiaomi", "poco" to "xiaomi", "huawei" to "huawei", "honor" to "huawei",
                "samsung" to "samsung", "oneplus" to "oneplus", "oppo" to "oppo", "realme" to "realme", "vivo" to "vivo",
                "meizu" to "meizu", "asus" to "asus", "lenovo" to "lenovo", "sony" to "sony", "nokia" to "nokia", "hmd" to "nokia",
                "wiko" to "wiko",
            ).firstOrNull { m.contains(it.first) }?.second
        }

        fun backgroundAllowed(a: android.content.Context) = try {
            a.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(a.packageName)
        } catch (_: Exception) { false }

        @SuppressLint("BatteryLife")
        fun askBackground(a: android.app.Activity) {
            if (backgroundAllowed(a)) { Toast.makeText(a, R.string.bg_already, Toast.LENGTH_SHORT).show(); return }
            a.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        }
    }

    private lateinit var rows: Rows
    private var cfg: CamConfig = CamConfig("", "", 1080, 30, "", "H264")
    private var lenses: List<ProCapturer.Lens> = emptyList()
    private val lockable = mutableListOf<View>()

    private lateinit var rRes: Rows.Row
    private lateinit var rAspect: Rows.Row
    private lateinit var rFps: Rows.Row
    private lateinit var rLens: Rows.Row
    private lateinit var rCodec: Rows.Row
    private lateinit var rBattery: Rows.Row
    private lateinit var rTurnTest: Rows.Row
    private lateinit var turnUrls: EditText
    private lateinit var turnUser: EditText
    private lateinit var turnPass: EditText
    private lateinit var lockNote: View
    private lateinit var rHeatStart: Rows.Row
    private lateinit var recRows: List<Rows.Row>
    private lateinit var rRecQ: Rows.Row
    private lateinit var rRecFps: Rows.Row
    private lateinit var rRecKeep: Rows.Row
    private lateinit var rRecFormat: Rows.Row
    private lateinit var recNote: android.widget.TextView
    private lateinit var rRecList: Rows.Row
    private val canHevc by lazy { Recorder.canHevc() }

    private lateinit var rBrokerMode: Rows.Row
    private lateinit var rProvider: Rows.Row
    private lateinit var rBrokerTest: Rows.Row
    private lateinit var rSignup: Rows.Row
    private lateinit var ownCard: LinearLayout
    private lateinit var brokerHost: EditText
    private lateinit var brokerUser: EditText
    private lateinit var brokerPass: EditText
    private lateinit var brokerNote: android.widget.TextView

    private val resLabels by lazy { listOf(R.string.q_hd, R.string.q_fhd, R.string.q_4k).map(::getString) }
    private val fpsLabels by lazy { listOf(R.string.fps_15, R.string.fps_24, R.string.fps_30, R.string.fps_60).map(::getString) }
    private val aspectLabels by lazy { listOf(R.string.aspect_full, R.string.aspect_wide).map(::getString) }
    private val codecLabels by lazy { listOf(R.string.format_std, R.string.format_compat).map(::getString) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        topBar(R.string.camera_settings_title)
        rows = Rows(this)
        cfg = Prefs.load(this)
        lenses = try { ProCapturer.listLenses(this) } catch (_: Exception) { emptyList() }
        val content = findViewById<LinearLayout>(R.id.content)

        lockNote = layoutInflater.inflate(R.layout.row_item, content, false).apply {
            findViewById<android.widget.ImageView>(R.id.icon).setImageResource(R.drawable.ic_info)
            findViewById<android.widget.TextView>(R.id.title).setText(R.string.settings_locked)
            background = getDrawable(R.drawable.bg_banner)
            visibility = View.GONE
        }
        content.addView(lockNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * resources.displayMetrics.density).toInt() })

        // Picture
        val pic = rows.section(content, R.string.section_picture)
        rRes = rows.row(pic, R.drawable.ic_hd, R.string.row_quality) {
            // Only the qualities this phone's video chip can encode
            val n = RES.count { it <= Rtc.maxRes(cfg.codec, cfg.aspect) }.coerceAtLeast(1)
            rows.choose(R.string.row_quality, resLabels.take(n), RES.indexOf(cfg.res).coerceAtMost(n - 1)) { update(cfg.copy(res = RES[it])) }
        }
        rAspect = rows.row(pic, R.drawable.ic_frame, R.string.row_aspect) {
            rows.choose(R.string.row_aspect, aspectLabels, ASPECTS.indexOf(cfg.aspect)) { update(cfg.copy(aspect = ASPECTS[it])) }
        }
        rFps = rows.row(pic, R.drawable.ic_speed, R.string.row_fps) {
            rows.choose(R.string.row_fps, fpsLabels, FPS.indexOf(cfg.fps)) { update(cfg.copy(fps = FPS[it])) }
        }
        rLens = rows.row(pic, R.drawable.ic_lens, R.string.row_lens) {
            if (lenses.isEmpty()) return@row
            rows.choose(R.string.row_lens, lenses.map { it.label }, lenses.indexOfFirst { it.key == lensKey() }) {
                update(cfg.copy(lens = lenses[it].key, zoom = lenses[it].zoomPreset ?: 1f))
            }
        }
        lockable += listOf(rRes.view, rAspect.view, rFps.view, rLens.view)

        // Sound
        val sound = rows.section(content, R.string.section_sound)
        val (rAudio, _) = rows.switchRow(sound, R.drawable.ic_mic, R.string.row_sound, cfg.audio) { update(cfg.copy(audio = it)) }
        rAudio.value(getString(R.string.sound_sub))
        lockable += rAudio.view

        // Continuous recording: can change any time, also while streaming (applied live)
        val rec = rows.section(content, R.string.section_rec)
        val (rRec, _) = rows.switchRow(rec, R.drawable.ic_rec, R.string.rec_on, cfg.recOn) { on ->
            if (on && !charging()) Toast.makeText(this, R.string.rec_charge, Toast.LENGTH_LONG).show()
            updateRec(cfg.copy(recOn = on))
        }
        rRec.value(getString(R.string.rec_on_sub))
        rRecQ = rows.row(rec, R.drawable.ic_hd, R.string.rec_quality) {
            rows.choose(R.string.rec_quality, listOf(R.string.rec_q_hd, R.string.rec_q_fhd).map(::getString),
                Recorder.QUALITIES.indexOf(cfg.recQ).coerceAtLeast(0)) { updateRec(cfg.copy(recQ = Recorder.QUALITIES[it])) }
        }
        rRecFps = rows.row(rec, R.drawable.ic_speed, R.string.rec_fps) {
            rows.choose(R.string.rec_fps, Recorder.FPS.map { getString(R.string.rec_fps_value, it) },
                Recorder.FPS.indexOf(cfg.recFps).coerceAtLeast(0)) { updateRec(cfg.copy(recFps = Recorder.FPS[it])) }
        }
        rRecKeep = rows.row(rec, R.drawable.ic_shield, R.string.rec_keep) {
            rows.choose(R.string.rec_keep, Recorder.KEEP_DAYS.map(::keepLabel),
                Recorder.KEEP_DAYS.indexOf(cfg.recDays).coerceAtLeast(0)) { updateRec(cfg.copy(recDays = Recorder.KEEP_DAYS[it])) }
        }
        rRecFormat = rows.row(rec, R.drawable.ic_chip, R.string.rec_format) {
            if (!canHevc) return@row
            rows.choose(R.string.rec_format, listOf(getString(R.string.rec_hevc), getString(R.string.rec_h264)),
                if (cfg.recH264) 1 else 0) { updateRec(cfg.copy(recH264 = it == 1)) }
        }
        val (rRecView, _) = rows.switchRow(rec, R.drawable.ic_eye, R.string.rec_view_only, cfg.recViewOnly) {
            updateRec(cfg.copy(recViewOnly = it))
        }
        rRecView.value(getString(R.string.rec_view_only_sub))
        recRows = listOf(rRecQ, rRecFps, rRecKeep, rRecFormat, rRecView)
        // The recordings themselves: watch, save to the gallery, delete (also with recording off)
        rRecList = rows.row(rec, R.drawable.ic_film, R.string.rec_list) { startActivity(Intent(this, RecordingsActivity::class.java)) }
        recNote = rows.note(content, "")

        // Where the camera and its viewers meet: public brokers, or the user's own
        val srv = rows.section(content, R.string.section_broker)
        val modes = listOf(Brokers.MODE_PUBLIC, Brokers.MODE_OWN)
        rBrokerMode = rows.row(srv, R.drawable.ic_server, R.string.row_broker_mode) {
            rows.choose(R.string.row_broker_mode, listOf(getString(R.string.broker_public), getString(R.string.broker_own)),
                modes.indexOf(cfg.brokerMode).coerceAtLeast(0)) { saveBroker(); update(cfg.copy(brokerMode = modes[it])) }
        }
        ownCard = rows.card(content)
        (ownCard.layoutParams as LinearLayout.LayoutParams).topMargin = (12 * resources.displayMetrics.density).toInt()
        rProvider = rows.row(ownCard, R.drawable.ic_chip, R.string.row_provider) {
            rows.choose(R.string.row_provider, Brokers.PROVIDERS.map(::providerName),
                Brokers.PROVIDERS.indexOfFirst { it.id == cfg.brokerProvider }.coerceAtLeast(0)) {
                saveBroker(); update(cfg.copy(brokerProvider = Brokers.PROVIDERS[it].id))
            }
        }
        brokerHost = rows.editRow(ownCard, R.drawable.ic_globe, R.string.broker_host, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        brokerUser = rows.editRow(ownCard, R.drawable.ic_user, R.string.turn_user, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        brokerPass = rows.editRow(ownCard, R.drawable.ic_key, R.string.turn_pass, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        brokerHost.setText(cfg.brokerHost); brokerUser.setText(cfg.brokerUser); brokerPass.setText(cfg.brokerPass)
        val (rBackup, _) = rows.switchRow(ownCard, R.drawable.ic_server, R.string.broker_backup, cfg.brokerBackup) {
            saveBroker(); update(cfg.copy(brokerBackup = it))
        }
        rBackup.value(getString(R.string.broker_backup_sub))
        rBrokerTest = rows.row(ownCard, R.drawable.ic_check, R.string.turn_test) { testBroker() }
        rSignup = rows.row(ownCard, R.drawable.ic_external, R.string.broker_signup) {
            Brokers.provider(cfg.brokerProvider).signup?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
        }
        brokerNote = rows.note(content, "")
        lockable += listOf(rBrokerMode.view, rProvider.view, brokerHost, brokerUser, brokerPass, rBackup.view, rBrokerTest.view)

        // Watching over 4G (relay server)
        val turn = rows.section(content, R.string.section_4g)
        turnUrls = rows.editRow(turn, R.drawable.ic_globe, R.string.turn_url, getString(R.string.turn_url_hint),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        turnUser = rows.editRow(turn, R.drawable.ic_user, R.string.turn_user, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        turnPass = rows.editRow(turn, R.drawable.ic_key, R.string.turn_pass, "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        turnUrls.setText(cfg.turnUrls); turnUser.setText(cfg.turnUser); turnPass.setText(cfg.turnPass)
        lockable += listOf(turnUrls, turnUser, turnPass)
        rTurnTest = rows.row(turn, R.drawable.ic_check, R.string.turn_test) { testTurn() }
        rTurnTest.value(getString(if (cfg.turnUrls.isEmpty()) R.string.turn_empty else R.string.turn_saved))
        rows.note(content, getString(R.string.turn_help))

        // Heat protection: can change any time, also while streaming (applied live)
        val heat = rows.section(content, R.string.section_heat)
        val (rHeat, _) = rows.switchRow(heat, R.drawable.ic_thermo, R.string.heat_guard, cfg.heatGuard) {
            updateHeat(cfg.copy(heatGuard = it))
        }
        rHeat.value(getString(R.string.heat_guard_sub))
        rHeatStart = rows.row(heat, R.drawable.ic_speed, R.string.heat_start) {
            val temps = (vn.camerold.camera.ThermalGuard.MIN_START_C..vn.camerold.camera.ThermalGuard.MAX_START_C step 2).toList()
            rows.choose(R.string.heat_start, temps.map { getString(R.string.heat_start_value, it, it + 3, it + 6) },
                temps.indexOf(cfg.heatStart)) { updateHeat(cfg.copy(heatStart = temps[it])) }
        }
        rows.note(content, getString(R.string.heat_note))

        // Background running
        val bg = rows.section(content, R.string.section_background)
        rBattery = rows.row(bg, R.drawable.ic_battery, R.string.row_background) { askBackground(this) }
        oemGuideSlug()?.let { slug ->
            rows.row(bg, R.drawable.ic_external, R.string.row_oem) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/$slug")))
            }.value(getString(R.string.row_oem_sub, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }))
        }

        // Advanced
        val adv = rows.section(content, R.string.section_advanced)
        rCodec = rows.row(adv, R.drawable.ic_chip, R.string.row_format) {
            rows.choose(R.string.row_format, codecLabels, CODECS.indexOf(cfg.codec)) { update(cfg.copy(codec = CODECS[it])) }
        }
        lockable += rCodec.view
        sync()
    }

    private fun updateHeat(c: CamConfig) {
        update(Prefs.load(this).copy(heatGuard = c.heatGuard, heatStart = c.heatStart))
        CameraEngine.current?.setHeatGuard(c.heatGuard, c.heatStart)
    }

    private fun updateRec(c: CamConfig) {
        update(Prefs.load(this).copy(recOn = c.recOn, recQ = c.recQ, recFps = c.recFps, recDays = c.recDays,
            recH264 = c.recH264, recViewOnly = c.recViewOnly))
        CameraEngine.current?.setRecording(cfg)
    }

    private fun keepLabel(d: Int) = if (d == 0) getString(R.string.rec_keep_full) else getString(R.string.rec_keep_days, d)

    private fun charging() = try {
        registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    } catch (_: Exception) { true }

    /** "12 recordings · 3.1 GB", or "None yet". */
    private fun recCount(): String {
        val days = Recorder.rootOf(this).listFiles { f -> f.isDirectory && f.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: emptyArray()
        val mp4 = days.flatMap { d -> d.listFiles { f -> f.name.endsWith(".mp4") }?.toList() ?: emptyList() }
        return if (mp4.isEmpty()) getString(R.string.rec_list_empty)
        else resources.getQuantityString(R.plurals.recs_n, mp4.size, mp4.size,
            android.text.format.Formatter.formatShortFileSize(this, mp4.sumOf { it.length() }))
    }

    /** Size an hour (measured on recent recordings when the camera runs) and how long free storage lasts. */
    private fun recEstimate(): String {
        val hevc = canHevc && !cfg.recH264
        val live = CameraEngine.current?.recorder?.json()
        val perHour = live?.optLong("rate")?.takeIf { it > 0 && live.optInt("q") == cfg.recQ && live.optInt("fps") == cfg.recFps }
            ?: Recorder.bytesPerHour(cfg.recQ, cfg.recFps, hevc)
        val st = try { android.os.StatFs(filesDir.path) } catch (_: Exception) { null }
        val used = java.io.File(filesDir, "rec").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val room = ((st?.availableBytes ?: 0L) + used - Recorder.minFree(st?.totalBytes ?: 0L)).coerceAtLeast(0)
        val hours = room / perHour
        val holds = if (hours >= 48) getString(R.string.rec_days_n, (hours / 24).toInt()) else getString(R.string.rec_hours_n, hours.toInt())
        return getString(R.string.rec_note, android.text.format.Formatter.formatShortFileSize(this, perHour), holds)
    }

    private fun providerName(p: Brokers.Provider) = p.name.ifEmpty { getString(R.string.provider_custom) }

    /** Keeps what was typed in the server fields. */
    private fun saveBroker() {
        if (CameraService.running) return
        val c = cfg.copy(brokerHost = brokerHost.text.toString().trim(), brokerUser = brokerUser.text.toString().trim(),
            brokerPass = brokerPass.text.toString())
        if (c != cfg) update(c)
    }

    private fun testBroker() {
        saveBroker()
        val url = Brokers.ownUrl(cfg)
        val fail = when {
            url == null -> R.string.broker_need_host
            !Brokers.isValid(url) -> R.string.broker_bad_url
            else -> 0
        }
        if (fail != 0) { rBrokerTest.value(getString(fail)); rBrokerTest.summary.setTextColor(getColor(R.color.bad)); return }
        rBrokerTest.value(getString(R.string.turn_testing))
        rBrokerTest.summary.setTextColor(getColor(R.color.text2))
        Brokers.test(url!!) { r ->
            runOnUiThread {
                rBrokerTest.value(getString(when (r) {
                    Brokers.TestResult.OK -> R.string.broker_ok
                    Brokers.TestResult.LOGIN_REFUSED -> R.string.broker_refused
                    Brokers.TestResult.NO_MESSAGES -> R.string.broker_no_msg
                    Brokers.TestResult.UNREACHABLE -> R.string.broker_unreachable
                }))
                rBrokerTest.summary.setTextColor(getColor(if (r == Brokers.TestResult.OK) R.color.ok else R.color.bad))
            }
        }
    }

    private fun lensKey() = lenses.firstOrNull { it.key == cfg.lens }?.key
        ?: lenses.firstOrNull { !it.front && it.zoomPreset == 1f }?.key ?: lenses.firstOrNull()?.key

    private fun update(c: CamConfig) {
        cfg = c
        Prefs.save(this, cfg)
        sync()
    }

    private fun sync() {
        rRes.value(resLabels[RES.indexOf(minOf(cfg.res, Rtc.maxRes(cfg.codec, cfg.aspect))).coerceAtLeast(0)])
        rAspect.value(aspectLabels[ASPECTS.indexOf(cfg.aspect).coerceAtLeast(1)])
        rFps.value(fpsLabels[FPS.indexOf(cfg.fps).coerceAtLeast(0)])
        rLens.value(lenses.firstOrNull { it.key == lensKey() }?.label ?: getString(R.string.lens_default))
        rCodec.value(codecLabels[CODECS.indexOf(cfg.codec).coerceAtLeast(0)])
        rHeatStart.value(getString(R.string.heat_start_value, cfg.heatStart, cfg.heatStart + 3, cfg.heatStart + 6))
        rHeatStart.enabled = cfg.heatGuard
        rRecQ.value(getString(if (cfg.recQ == 1080) R.string.rec_q_fhd else R.string.rec_q_hd))
        rRecFps.value(getString(R.string.rec_fps_value, cfg.recFps))
        rRecKeep.value(keepLabel(cfg.recDays))
        rRecFormat.value(getString(if (!canHevc) R.string.rec_no_hevc else if (cfg.recH264) R.string.rec_h264 else R.string.rec_hevc))
        recRows.forEach { it.enabled = cfg.recOn }
        recNote.text = recEstimate()
        rRecList.value(recCount())
        val own = cfg.brokerMode == Brokers.MODE_OWN
        val p = Brokers.provider(cfg.brokerProvider)
        rBrokerMode.value(getString(if (own) R.string.broker_own else R.string.broker_public))
        ownCard.visibility = if (own) View.VISIBLE else View.GONE
        rProvider.value(providerName(p))
        brokerHost.hint = p.hostHint
        rSignup.visible = p.signup != null
        rSignup.value(p.name)
        brokerNote.setText(if (own) R.string.broker_note_own else R.string.broker_note_public)
        val allowed = backgroundAllowed(this)
        rBattery.value(getString(if (allowed) R.string.bg_allowed else R.string.bg_not_allowed))
        rBattery.summary.setTextColor(getColor(if (allowed) R.color.ok else R.color.warn))
    }

    private fun testTurn() {
        val urls = Rtc.parseTurnUrls(turnUrls.text.toString())
        if (urls.isEmpty()) { Toast.makeText(this, R.string.turn_need_url, Toast.LENGTH_SHORT).show(); return }
        rTurnTest.value(getString(R.string.turn_testing))
        rTurnTest.summary.setTextColor(getColor(R.color.text2))
        Rtc.testTurn(this, urls, turnUser.text.toString().trim(), turnPass.text.toString().trim()) { ok ->
            runOnUiThread {
                if (ok) saveTurn()
                rTurnTest.value(getString(if (ok) R.string.turn_ok else R.string.turn_fail))
                rTurnTest.summary.setTextColor(getColor(if (ok) R.color.ok else R.color.bad))
            }
        }
    }

    private fun saveTurn() {
        if (CameraService.running) return
        // Only if edited here: a relay server sent by a viewer while this screen was open must not be overwritten
        val saved = Prefs.load(this)
        if (turnUrls.text.toString().trim() == cfg.turnUrls && turnUser.text.toString().trim() == cfg.turnUser &&
            turnPass.text.toString().trim() == cfg.turnPass) { cfg = saved; return }
        update(Prefs.load(this).copy(turnUrls = turnUrls.text.toString().trim(), turnUser = turnUser.text.toString().trim(),
            turnPass = turnPass.text.toString().trim()))
    }

    override fun onResume() {
        super.onResume()
        if (CameraService.running) {
            cfg = Prefs.load(this) // viewers may have changed it remotely, including the relay server
            if (turnUrls.text.toString() != cfg.turnUrls) turnUrls.setText(cfg.turnUrls)
            if (turnUser.text.toString() != cfg.turnUser) turnUser.setText(cfg.turnUser)
            if (turnPass.text.toString() != cfg.turnPass) turnPass.setText(cfg.turnPass)
        }
        val running = CameraService.running
        lockNote.visibility = if (running) View.VISIBLE else View.GONE
        lockable.forEach { v ->
            val row = (v.tag as? View) ?: v
            Rows.setChildrenEnabled(row, !running)
            row.alpha = if (running) 0.45f else 1f
        }
        sync()
    }

    override fun onPause() {
        saveBroker()
        saveTurn()
        super.onPause()
    }
}
