package vn.camerold.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import vn.camerold.R

/** Plays one of this phone's recordings full screen (the files are private to the app, so no other player can). */
class PlayerActivity : Activity() {
    companion object {
        const val PATH = "path"
        const val TITLE = "title"
    }

    private lateinit var video: VideoView
    private var pos = 0

    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(LangPref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(PATH) ?: return finish()
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        box.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val d = resources.displayMetrics.density
        box.addView(TextView(this).apply {
            text = intent.getStringExtra(TITLE)
            setTextColor(Color.WHITE)
            textSize = 15f
            setShadowLayer(4 * d, 0f, 0f, Color.BLACK)
            setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        // Clear of the status bar and the notch
        box.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(box)
        val controls = MediaController(this)
        controls.setAnchorView(video)
        video.setMediaController(controls)
        video.setOnPreparedListener { video.seekTo(pos); video.start(); controls.show() }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.recs_play_fail, Toast.LENGTH_LONG).show()
            finish()
            true
        }
        pos = savedInstanceState?.getInt("pos") ?: 0
        video.setVideoPath(path)
        box.setOnClickListener { if (controls.isShowing) controls.hide() else controls.show() }
    }

    override fun onPause() {
        super.onPause()
        pos = video.currentPosition
        video.pause()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("pos", pos)
    }
}
