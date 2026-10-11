package vn.camerold.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import vn.camerold.App
import vn.camerold.R
import vn.camerold.camera.CameraEngine
import vn.camerold.camera.CameraService

/** Home: two colored cards (use as camera / watch a camera) and how it works. App settings sit behind the gear icon. */
class MainActivity : BaseActivity() {
    private lateinit var cameraCard: View
    private var livePulse: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // The blue glow starts behind the status bar
        window.setBackgroundDrawableResource(R.drawable.bg_home)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) window.statusBarColor = getColor(R.color.home_glow)

        cameraCard = role(R.id.roleCamera, R.drawable.bg_role_camera, R.drawable.ill_camera, R.drawable.ic_camera,
            R.string.home_camera_title, R.string.home_camera_sub) { startActivity(Intent(this, CameraActivity::class.java)) }
        role(R.id.roleViewer, R.drawable.bg_role_viewer, R.drawable.ill_viewer, R.drawable.ic_eye,
            R.string.home_viewer_title, R.string.home_viewer_sub) { startActivity(Intent(this, ViewerActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        val how = findViewById<LinearLayout>(R.id.howCard)
        step(how, R.drawable.ic_smartphone, R.color.step_blue, R.color.step_blue_soft, R.string.how_1, R.string.how_1_sub)
        step(how, R.drawable.ic_qr, R.color.step_violet, R.color.step_violet_soft, R.string.how_2, R.string.how_2_sub)
        step(how, R.drawable.ic_shield, R.color.step_green, R.color.step_green_soft, R.string.how_3, R.string.how_3_sub)

        App.showLastCrash(this)
        // If already streaming, go straight to the camera screen
        if (CameraService.running && savedInstanceState == null) startActivity(Intent(this, CameraActivity::class.java))
    }

    private fun role(id: Int, bg: Int, art: Int, icon: Int, title: Int, sub: Int, open: () -> Unit): View {
        val v = findViewById<View>(id)
        v.setBackgroundResource(bg)
        v.clipToOutline = true // the illustration is cut off by the rounded corners
        v.findViewById<ImageView>(R.id.roleIcon).setImageResource(icon)
        v.findViewById<ImageView>(R.id.roleArt).setImageResource(art)
        v.findViewById<TextView>(R.id.roleTitle).setText(title)
        v.findViewById<TextView>(R.id.roleSub).setText(sub)
        v.setOnClickListener { open() }
        return v
    }

    private fun step(card: LinearLayout, icon: Int, color: Int, soft: Int, title: Int, sub: Int) {
        val v = layoutInflater.inflate(R.layout.home_step, card, false)
        v.findViewById<ImageView>(R.id.stepIcon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(getColor(color))
            backgroundTintList = ColorStateList.valueOf(getColor(soft))
        }
        v.findViewById<TextView>(R.id.stepTitle).setText(title)
        v.findViewById<TextView>(R.id.stepSub).setText(sub)
        card.addView(v)
    }

    override fun onResume() {
        super.onResume()
        // While this phone is streaming, the camera card says so (pulsing LIVE badge and the current status)
        val live = CameraService.running
        val sub = cameraCard.findViewById<TextView>(R.id.roleSub)
        sub.text = if (live) CameraEngine.current?.lastStatus?.message ?: getString(R.string.home_running)
                   else getString(R.string.home_camera_sub)
        cameraCard.findViewById<View>(R.id.roleLive).visibility = if (live) View.VISIBLE else View.GONE
        cameraCard.contentDescription = getString(R.string.home_camera_title) + ", " + sub.text
        livePulse?.cancel()
        livePulse = if (!live) null else ObjectAnimator.ofFloat(cameraCard.findViewById(R.id.roleLiveDot), View.ALPHA, 1f, 0.25f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    override fun onPause() {
        livePulse?.cancel()
        super.onPause()
    }
}
