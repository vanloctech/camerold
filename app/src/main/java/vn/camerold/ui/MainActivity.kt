package vn.camerold.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import vn.camerold.App
import vn.camerold.R
import vn.camerold.camera.CameraEngine
import vn.camerold.camera.CameraService

/** Home: two tiles (use as camera / watch a camera) and how it works. App settings sit behind the gear icon. */
class MainActivity : BaseActivity() {
    private lateinit var cameraTile: Tile
    private lateinit var viewerTile: Tile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cameraTile = Tile(findViewById(R.id.tileCamera))
        viewerTile = Tile(findViewById(R.id.tileViewer))
        cameraTile.onClick { startActivity(Intent(this, CameraActivity::class.java)) }
        viewerTile.onClick { startActivity(Intent(this, ViewerActivity::class.java)) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        val rows = Rows(this)
        val how = findViewById<LinearLayout>(R.id.howCard)
        rows.row(how, R.drawable.ic_smartphone, R.string.how_1).value(getString(R.string.how_1_sub))
        rows.row(how, R.drawable.ic_qr, R.string.how_2).value(getString(R.string.how_2_sub))
        rows.row(how, R.drawable.ic_shield, R.string.how_3).value(getString(R.string.how_3_sub))

        App.showLastCrash(this)
        // If already streaming, go straight to the camera screen
        if (CameraService.running && savedInstanceState == null) startActivity(Intent(this, CameraActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        val live = CameraService.running
        cameraTile.set(R.drawable.ic_camera, getString(R.string.home_camera_title),
            if (live) CameraEngine.current?.lastStatus?.message ?: getString(R.string.home_running) else getString(R.string.home_camera_sub), live)
        viewerTile.set(R.drawable.ic_eye, getString(R.string.home_viewer_title), getString(R.string.home_viewer_sub))
    }
}
