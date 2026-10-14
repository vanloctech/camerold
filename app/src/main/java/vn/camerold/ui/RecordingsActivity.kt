package vn.camerold.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors
import vn.camerold.Gallery
import vn.camerold.R
import vn.camerold.camera.CameraEngine
import vn.camerold.camera.Recorder
import vn.camerold.data.Prefs

/**
 * The camera phone's own list of its recordings: storage used, by day (newest first), watch, save to the gallery,
 * delete one or all. Reads the files directly, so it works whether the camera is running or not; while it runs,
 * deleting goes through the [Recorder] so viewers' lists follow.
 */
class RecordingsActivity : BaseActivity() {
    private lateinit var rows: Rows
    private lateinit var content: LinearLayout
    private val root by lazy { Recorder.rootOf(this) }
    private val bg = Executors.newSingleThreadExecutor()
    /** Days shown open; the newest one opens by itself. */
    private val openDays = HashSet<String>()
    private var opened = false
    private var segs: List<Recorder.Seg> = emptyList()
    /** Waiting for the storage permission (Android 9 and older) to save this one. */
    private var pendingSave: Recorder.Seg? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        topBar(R.string.recs_title)
        rows = Rows(this)
        content = findViewById(R.id.content)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        bg.shutdown()
    }

    private fun load() = bg.execute {
        val list = Recorder.stored(root)
        val used = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val free = try { StatFs(filesDir.path).availableBytes } catch (_: Exception) { -1L }
        runOnUiThread { if (!isDestroyed) show(list, used, free) }
    }

    private fun show(list: List<Recorder.Seg>, used: Long, free: Long) {
        segs = list
        content.removeAllViews()

        // Storage
        val sum = rows.card(content)
        (sum.layoutParams as LinearLayout.LayoutParams).topMargin = dp(8)
        val freeText = if (free >= 0) getString(R.string.recs_free, size(free)) else null
        if (list.isEmpty()) rows.row(sum, R.drawable.ic_film, freeText ?: getString(R.string.recs_title))
        else rows.row(sum, R.drawable.ic_film, resources.getQuantityString(R.plurals.recs_n, list.size, list.size, size(used))).value(freeText)
        CameraEngine.current?.recorder?.current()?.let { (_, start) ->
            rows.row(sum, R.drawable.ic_rec, getString(R.string.recs_live, time(start))).value(getString(R.string.recs_live_sub))
        }
        if (list.isNotEmpty()) {
            rows.row(sum, R.drawable.ic_calendar, getString(R.string.recs_range)) { openRange() }.value(getString(R.string.recs_range_sub))
            val del = rows.row(sum, R.drawable.ic_trash, getString(R.string.recs_delete_all)) { confirmDeleteAll() }
            del.title.setTextColor(getColor(R.color.bad))
            del.view.findViewById<ImageView>(R.id.icon).imageTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.bad))
            del.chevron?.visibility = View.GONE
        }
        if (list.isEmpty()) { rows.note(content, getString(R.string.recs_empty)); return }

        // By day, newest first; a closed day is one row that opens it
        val byDay = list.groupBy { Recorder.dayOf(it.id) }.toSortedMap(compareByDescending { it })
        if (!opened) { opened = true; openDays += byDay.firstKey() }
        for ((day, items) in byDay) {
            val card = rows.section(content, dayLabel(day))
            if (day !in openDays) {
                rows.row(card, R.drawable.ic_film, resources.getQuantityString(R.plurals.recs_n, items.size, items.size,
                    size(items.sumOf { it.size }))) { openDays += day; show(segs, used, free) }
                continue
            }
            items.sortedByDescending { it.start }.forEach { addRow(card, it) }
        }
        rows.note(content, getString(R.string.recs_note))
    }

    private fun addRow(card: LinearLayout, s: Recorder.Seg) {
        val r = rows.row(card, R.drawable.ic_play, "${time(s.start)} – ${time(s.end)}") { play(s) }
        r.value(listOfNotNull(DateUtils.formatElapsedTime(Math.round(s.dur)), size(s.size), s.codec.uppercase().ifEmpty { null },
            if (s.keep) getString(R.string.recs_kept) else null).joinToString(" · "))
        // Thumbnail in place of the icon
        r.view.findViewById<ImageView>(R.id.icon).apply {
            layoutParams = layoutParams.apply { width = dp(64); height = dp(40) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            imageTintList = null
            setImageDrawable(null)
            setBackgroundColor(getColor(R.color.icon_bg))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
            }
            clipToOutline = true
            val f = Recorder.fileOf(root, s.id, ".jpg")
            bg.execute {
                val b = try { BitmapFactory.decodeFile(f.path) } catch (_: Exception) { null } ?: return@execute
                post { setImageBitmap(b) }
            }
        }
        rows.iconAction(r.actions, R.drawable.ic_download, R.string.recs_save) { save(s) }
        rows.iconAction(r.actions, R.drawable.ic_trash, R.string.recs_delete) { confirmDelete(s) }
    }

    private fun play(s: Recorder.Seg) {
        val f = Recorder.fileOf(root, s.id, ".mp4")
        if (!f.exists()) { load(); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.PATH, f.path)
            .putExtra(PlayerActivity.TITLE, "${dayLabel(Recorder.dayOf(s.id))} · ${time(s.start)}"))
    }

    // ---------------- Delete ----------------

    private fun confirmDelete(s: Recorder.Seg) {
        AlertDialog.Builder(this).setMessage(R.string.recs_delete_q)
            .setPositiveButton(R.string.recs_delete) { _, _ -> delete(listOf(s.id)) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun confirmDeleteAll() {
        val ids = segs.map { it.id }
        AlertDialog.Builder(this).setTitle(R.string.recs_delete_all).setMessage(getString(R.string.recs_delete_all_q, ids.size))
            .setPositiveButton(R.string.recs_delete) { _, _ -> delete(ids) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun delete(ids: List<String>) {
        val rec = CameraEngine.current?.recorder
        bg.execute { if (rec != null) rec.deleteNow(ids) else ids.forEach { Recorder.removeFiles(root, it) } }
        load()
    }

    /** Delete what overlaps a span picked by date and time (kept ones only when asked). Starts at today 00:00 → now. */
    private fun openRange() {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        var from = c.timeInMillis
        var to = System.currentTimeMillis() / 60_000 * 60_000 + 60_000
        val pad = dp(24)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        fun pickRow(label: Int): android.widget.TextView {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(52); background = getDrawable(R.drawable.bg_row)
            }
            row.addView(android.widget.TextView(this, null, 0, R.style.T_Value).apply { setText(label) },
                LinearLayout.LayoutParams(dp(56), -2))
            val v = android.widget.TextView(this, null, 0, R.style.T_Body).apply { setTextColor(getColor(R.color.accent)) }
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
            return v
        }
        val fromV = pickRow(R.string.recs_from)
        val toV = pickRow(R.string.recs_to)
        val kept = android.widget.CheckBox(this).apply { setText(R.string.recs_range_kept) }
        box.addView(kept, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val result = android.widget.TextView(this, null, 0, R.style.T_Value)
        box.addView(result, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val dlg = AlertDialog.Builder(this).setTitle(R.string.recs_range).setView(box)
            .setPositiveButton(R.string.recs_delete, null).setNegativeButton(R.string.cancel, null).create()
        fun picked() = segs.filter { it.start < to && it.end > from && (kept.isChecked || !it.keep) }
        fun update() {
            fromV.text = whenText(from); toV.text = whenText(to)
            val p = picked()
            result.text = if (p.isEmpty()) getString(R.string.recs_range_none)
                else resources.getQuantityString(R.plurals.recs_range_n, p.size, p.size, size(p.sumOf { it.size }))
            dlg.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = p.isNotEmpty()
        }
        (fromV.parent as View).setOnClickListener { pickTime(from) { from = it; if (to <= from) to = from + 3_600_000; update() } }
        (toV.parent as View).setOnClickListener { pickTime(to) { to = it; if (to <= from) from = to - 3_600_000; update() } }
        kept.setOnCheckedChangeListener { _, _ -> update() }
        dlg.setOnShowListener {
            update()
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { delete(picked().map { it.id }); dlg.dismiss() }
        }
        dlg.show()
    }

    /** Date, then time. */
    private fun pickTime(t: Long, done: (Long) -> Unit) {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        android.app.DatePickerDialog(this, { _, y, m, d ->
            android.app.TimePickerDialog(this, { _, h, min ->
                c.set(y, m, d, h, min, 0); c.set(Calendar.MILLISECOND, 0); done(c.timeInMillis)
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(this)).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.maxDate = System.currentTimeMillis() + 86_400_000
        }.show()
    }

    private fun whenText(t: Long) = DateUtils.formatDateTime(this, t, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)

    // ---------------- Save to the gallery ----------------

    private fun save(s: Recorder.Seg) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = s
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 5)
            return
        }
        val f = Recorder.fileOf(root, s.id, ".mp4")
        val name = Recorder.fileName(s.start, Prefs.load(this).name)
        Toast.makeText(this, R.string.recs_saving, Toast.LENGTH_SHORT).show()
        val app = applicationContext
        bg.execute {
            val w = Gallery.openVideo(app, name)
            val ok = w != null && try { f.inputStream().use { it.copyTo(w.out, 1 shl 16) }; true } catch (_: Exception) { false }
            w?.finish(ok)
            runOnUiThread { Toast.makeText(app, if (ok) R.string.recs_saved else R.string.recs_save_fail, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val s = pendingSave ?: return
        pendingSave = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) save(s)
        else Toast.makeText(this, R.string.recs_save_fail, Toast.LENGTH_SHORT).show()
    }

    // ---------------- Text ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun size(b: Long) = Formatter.formatShortFileSize(this, b)
    private fun time(t: Long) = DateFormat.getTimeInstance(DateFormat.SHORT, resources.configuration.locales[0]).format(Date(t))

    /** "Today", "Yesterday", else the date with the weekday. */
    private fun dayLabel(day: String): String {
        val c = Calendar.getInstance()
        fun key() = "%04d-%02d-%02d".format(java.util.Locale.US, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        if (key() == day) return getString(R.string.recs_today)
        c.add(Calendar.DAY_OF_MONTH, -1)
        if (key() == day) return getString(R.string.recs_yesterday)
        val (y, m, d) = day.split('-').map { it.toInt() }
        c.set(y, m - 1, d, 12, 0)
        return DateUtils.formatDateTime(this, c.timeInMillis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or
            DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_NO_YEAR)
    }
}
