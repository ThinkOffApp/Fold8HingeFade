package com.thinkoff.fold8hingefade

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.drawToBitmap
import com.thinkoff.fold8hingefade.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var sensors: SensorManager
    private var lastAngle = Float.NaN
    private var serviceStatus = "off"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        sensors = getSystemService(SensorManager::class.java)
        b.btnOverlay.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        b.btnStart.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.btnStop.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        b.btnPreview.setOnClickListener { preview() }
        HingeService.status = { s -> serviceStatus = s; render() }
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
        b.status.text = "hinge: $angle\noverlay permission: ${if (Settings.canDrawOverlays(this)) "granted" else "missing"}\nservice: ${if (HingeService.running) serviceStatus else "off (enable it under Accessibility)"}"
    }

    /** Runs the same shader on a capture of this screen, so the look can be judged without folding. */
    private fun preview() {
        val shot = b.root.drawToBitmap()
        val root = window.decorView as ViewGroup
        val v = HingeFadeView(this).apply { setFrame(shot); opacity = 1f; scale = 1f }
        root.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        b.status.text = "preview: the frozen frame dissolves into the live screen"
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 1400; startDelay = 600
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a -> v.opacity = a.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { root.removeView(v); shot.recycle(); render() }
            })
            start()
        }
    }
}
