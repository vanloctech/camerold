package vn.camerold.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import vn.camerold.App
import vn.camerold.R
import vn.camerold.camera.CameraService

/** Home: pick what this phone does. App-wide settings sit behind the gear icon. */
class MainActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val openCamera = View.OnClickListener { startActivity(Intent(this, CameraActivity::class.java)) }
        findViewById<View>(R.id.tileCamera).setOnClickListener(openCamera)
        findViewById<View>(R.id.runningCard).setOnClickListener(openCamera)
        findViewById<View>(R.id.tileViewer).setOnClickListener { startActivity(Intent(this, ViewerActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        App.showLastCrash(this)
        // If already streaming, go straight to the camera screen
        if (CameraService.running && savedInstanceState == null) startActivity(Intent(this, CameraActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        findViewById<View>(R.id.runningCard).visibility = if (CameraService.running) View.VISIBLE else View.GONE
    }
}
