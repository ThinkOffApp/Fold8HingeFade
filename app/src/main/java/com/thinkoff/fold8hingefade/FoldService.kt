package com.thinkoff.fold8hingefade

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * Keeps a screen-capture session alive, watches the hinge, and on the first degree of fold grabs
 * the inner screen once. That one frame is crossfaded down on the inner screen as the hinge closes
 * and shown again on the cover screen the moment it wakes, then faded out over the real cover UI.
 */
class FoldService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "stop"
        private const val TAG = "HingeFade"
        private const val CHANNEL = "hingefade"
        private const val NOTIF_ID = 1

        /** Angle above which the phone counts as flat; below it the fold has begun. */
        const val FLAT_ANGLE = 168f
        /** Angle at which the inner card has fully shrunk (the inner panel switches off soon after). */
        const val CLOSED_ANGLE = 40f
        const val REARM_ANGLE = 172f

        @Volatile var running = false
        @Volatile var status: (String) -> Unit = {}
    }

    private enum class State { FLAT, FOLDING, HANDED_OFF }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var latest: Image? = null
    private var innerW = 0
    private var innerH = 0
    private var innerArea = 0L

    private var state = State.FLAT
    private var frame: Bitmap? = null
    private var overlay: HingeFadeView? = null
    private var overlayWm: WindowManager? = null
    private var presentation: Presentation? = null
    private var coverAnim: ValueAnimator? = null
    private var captureAtMs = 0L

    // ---- lifecycle ----
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        if (code == 0 || data == null) { stopSelf(); return START_NOT_STICKY }

        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { Log.i(TAG, "projection stopped"); stopSelf() }
        }, main)
        projection = p
        startCapture(p)

        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            sensors.registerListener(hinge, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(TAG, "no hinge sensor")
        displays.registerDisplayListener(displayListener, main)
        running = true
        report("armed, waiting for the fold")
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { sensors.unregisterListener(hinge) } catch (_: Exception) {}
        try { displays.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        hideAll()
        latest?.close(); latest = null
        virtualDisplay?.release(); reader?.close()
        projection?.stop(); projection = null
        report("stopped")
        super.onDestroy()
    }

    // ---- capture session: the inner screen mirrored at half size, latest frame kept ----
    private fun startCapture(p: MediaProjection) {
        val d = displays.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d.mode
        innerW = mode.physicalWidth; innerH = mode.physicalHeight
        innerArea = innerW.toLong() * innerH
        val w = innerW / 2; val h = innerH / 2
        val dpi = resources.displayMetrics.densityDpi
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close(); latest = img
        }, main)
        reader = r
        virtualDisplay = p.createVirtualDisplay("hingefade", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, main)
        Log.i(TAG, "capturing ${w}x$h of inner ${innerW}x$innerH")
    }

    private fun grabFrame(): Bitmap? {
        val img = latest ?: return null
        val plane = img.planes[0]
        val stride = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(stride, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(plane.buffer.also { it.rewind() })
        return if (stride == img.width) full else Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
    }

    // ---- hinge ----
    private val hinge = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) { onAngle(e.values[0]) }
    }

    private fun onAngle(a: Float) {
        when (state) {
            State.FLAT -> if (a < FLAT_ANGLE) beginFold(a)
            State.FOLDING -> {
                overlay?.progress = progressFor(a)
                if (a >= REARM_ANGLE) reset("unfolded again")
            }
            State.HANDED_OFF -> if (a >= REARM_ANGLE) reset("flat again, re-armed")
        }
        report("hinge %.0f°  %s".format(a, state.name.lowercase()))
    }

    private fun progressFor(a: Float) = ((FLAT_ANGLE - a) / (FLAT_ANGLE - CLOSED_ANGLE)).coerceIn(0f, 1f)

    private fun beginFold(a: Float) {
        val t0 = SystemClock.elapsedRealtime()
        val b = grabFrame() ?: run { Log.w(TAG, "no frame yet"); return }
        frame?.recycle(); frame = b
        captureAtMs = SystemClock.elapsedRealtime() - t0
        state = State.FOLDING
        showInnerOverlay(b, progressFor(a))
        Log.i(TAG, "fold began at %.1f°, frame ${b.width}x${b.height} in ${captureAtMs} ms".format(a))
    }

    private fun reset(why: String) {
        hideAll()
        state = State.FLAT
        Log.i(TAG, "reset: $why")
    }

    // ---- inner screen overlay ----
    private fun showInnerOverlay(b: Bitmap, p: Float) {
        if (overlay != null) { overlay?.setFrame(b); overlay?.progress = p; return }
        val ctx = createDisplayContext(displays.getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = HingeFadeView(ctx).apply { setFrame(b); progress = p }
        wm.addView(v, overlayParams())
        overlay = v; overlayWm = wm
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    private fun hideAll() {
        coverAnim?.cancel(); coverAnim = null
        overlay?.let { v -> try { overlayWm?.removeViewImmediate(v) } catch (_: Exception) {} }
        overlay = null; overlayWm = null
        presentation?.dismiss(); presentation = null
    }

    // ---- cover screen hand-off ----
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = checkCover()
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = checkCover()
    }

    /** The cover is whichever lit display is much smaller than the inner one we captured. */
    private fun checkCover() {
        if (state != State.FOLDING) return
        val cover = displays.displays.firstOrNull { d ->
            d.state == Display.STATE_ON && d.mode.physicalWidth.toLong() * d.mode.physicalHeight < innerArea * 6 / 10
        } ?: return
        val b = frame ?: return
        state = State.HANDED_OFF
        Log.i(TAG, "cover display ${cover.displayId} is on, handing off")
        if (cover.displayId == Display.DEFAULT_DISPLAY) {
            // Samsung style: display 0 itself became the cover panel; our overlay window follows it.
            val v = overlay ?: return
            v.setFrame(b)
            runCoverFinish(v)
        } else {
            hideAll()
            val ctx = createDisplayContext(cover)
            val pres = Presentation(ctx, cover).apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
            val v = HingeFadeView(pres.context).apply { setFrame(b) }
            pres.setContentView(v)
            pres.show()
            presentation = pres
            runCoverFinish(v)
        }
    }

    /** The card lands: it grows from the folded size to fill the cover, then fades out over the live UI. */
    private fun runCoverFinish(v: View) {
        val fade = v as HingeFadeView
        fade.progress = 0.55f; fade.opacity = 1f
        coverAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 650
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                fade.progress = 0.55f * (1f - (t / 0.5f).coerceIn(0f, 1f))
                fade.opacity = 1f - ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
            }
            doOnEndCompat { hideAll() }
            start()
        }
        report("handed off to the cover screen (capture ${captureAtMs} ms)")
    }

    private fun ValueAnimator.doOnEndCompat(f: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = f()
    })

    // ---- misc ----
    private fun report(s: String) = main.post { status(s) }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Hinge fade", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 1, Intent(this, FoldService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Hinge fade armed")
            .setContentText("Fold the phone: the screen you see crossfades onto the cover.")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }
}
