package com.thinkoff.fold8hingefade

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.drawToBitmap
import com.thinkoff.fold8hingefade.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var sensors: SensorManager
    private var lastAngle = Float.NaN
    private var serviceStatus = "off"

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            startForegroundService(Intent(this, FoldService::class.java)
                .putExtra(FoldService.EXTRA_RESULT_CODE, r.resultCode)
                .putExtra(FoldService.EXTRA_RESULT_DATA, r.data))
        } else Toast.makeText(this, "Screen capture not allowed", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        sensors = getSystemService(SensorManager::class.java)

        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnStart.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) { Toast.makeText(this, "Allow drawing over other apps first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            capture.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }
        b.btnStop.setOnClickListener { startService(Intent(this, FoldService::class.java).setAction(FoldService.ACTION_STOP)) }
        b.btnPreview.setOnClickListener { preview() }
        FoldService.status = { s -> serviceStatus = s; render() }
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let { sensors.registerListener(hinge, it, SensorManager.SENSOR_DELAY_UI) }
        render()
    }

    override fun onPause() { super.onPause(); sensors.unregisterListener(hinge) }

    private val hinge = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) { lastAngle = e.values[0]; render() }
    }

    private fun render() {
        val angle = if (lastAngle.isNaN()) "no hinge sensor" else "%.1f°".format(lastAngle)
        b.status.text = "hinge: $angle\noverlay permission: ${if (Settings.canDrawOverlays(this)) "granted" else "missing"}\nservice: ${if (FoldService.running) serviceStatus else "off"}"
    }

    /** Runs the same shader on a capture of this screen, so the look can be judged without folding. */
    private fun preview() {
        val shot = b.root.drawToBitmap()
        val root = window.decorView as ViewGroup
        val v = HingeFadeView(this).apply { setFrame(shot) }
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ValueAnimator.ofFloat(0f, 1f, 1f, 0f).apply {
            duration = 2600
            addUpdateListener { a -> v.progress = a.animatedValue as Float }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { root.removeView(v); shot.recycle() }
            })
            start()
        }
    }
}
