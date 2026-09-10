package com.thinkoff.fold8hingefade

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Presentation
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.DecelerateInterpolator

/**
 * The whole effect lives in an accessibility service, because that is the one component a normal app
 * has that can take a screenshot without a consent dialog per session and keeps running across folds.
 *
 * Watches the hinge; at the first degree of fold takes one screenshot of the inner screen, crossfades
 * it down as the hinge closes, and shows the same picture on the cover screen as it wakes, fading out
 * over the real cover UI. Unfolding back to flat re-arms it.
 */
class HingeService : AccessibilityService() {

    companion object {
        private const val TAG = "HingeFade"
        const val FLAT_ANGLE = 168f      // below this the fold has begun
        const val CLOSED_ANGLE = 40f     // the inner card is fully shrunk here
        const val REARM_ANGLE = 172f     // back above this = flat again

        @Volatile var running = false
        @Volatile var status: (String) -> Unit = {}
    }

    private enum class State { WAIT_FLAT, FLAT, FOLDING, HANDED_OFF }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sensors: SensorManager
    private lateinit var displays: DisplayManager
    private var state = State.WAIT_FLAT
    private var frame: Bitmap? = null
    private var innerArea = 0L
    private var capturing = false
    private var captureMs = 0L
    private var overlay: HingeFadeView? = null
    private var overlayWm: WindowManager? = null
    private var presentation: Presentation? = null
    private var coverAnim: ValueAnimator? = null

    override fun onServiceConnected() {
        sensors = getSystemService(SensorManager::class.java)
        displays = getSystemService(DisplayManager::class.java)
        innerArea = displays.displays.maxOf { it.mode.physicalWidth.toLong() * it.mode.physicalHeight }
        sensors.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)?.let {
            sensors.registerListener(hinge, it, SensorManager.SENSOR_DELAY_GAME)
        } ?: Log.w(TAG, "no hinge sensor")
        displays.registerDisplayListener(displayListener, main)
        running = true
        report("armed, waiting for the fold")
        Log.i(TAG, "connected, inner area $innerArea")
    }

    override fun onDestroy() {
        running = false
        try { sensors.unregisterListener(hinge) } catch (_: Exception) {}
        try { displays.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        hideAll()
        report("off")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- hinge ----
    private val hinge = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(e: SensorEvent) { onAngle(e.values[0]) }
    }

    private fun onAngle(a: Float) {
        lastAngle = a
        when (state) {
            State.WAIT_FLAT -> if (a >= REARM_ANGLE) { state = State.FLAT; hideAll() }
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
        if (capturing) return
        capturing = true
        state = State.FOLDING
        val t0 = SystemClock.elapsedRealtime()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                capturing = false
                val hw = r.hardwareBuffer
                val b = Bitmap.wrapHardwareBuffer(hw, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                hw.close()
                if (b == null) { Log.w(TAG, "screenshot gave no bitmap"); state = State.FLAT; return }
                captureMs = SystemClock.elapsedRealtime() - t0
                frame?.recycle(); frame = b
                if (state != State.FOLDING) return          // unfolded again while the shot was taken
                showInnerOverlay(b, progressFor(lastAngle))
                Log.i(TAG, "fold began at %.1f°, frame ${b.width}x${b.height} in $captureMs ms".format(a))
                checkCover()                                 // the cover may already be lit on a fast fold
            }
            override fun onFailure(code: Int) {
                capturing = false; state = State.FLAT
                Log.w(TAG, "screenshot failed $code")
                report("screenshot failed ($code)")
            }
        })
    }

    private var lastAngle = 180f
    private fun reset(why: String) { hideAll(); state = State.FLAT; Log.i(TAG, "reset: $why") }

    // ---- inner overlay ----
    private fun showInnerOverlay(b: Bitmap, p: Float) {
        overlay?.let { it.setFrame(b); it.progress = p; return }
        val ctx = createDisplayContext(displays.getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val wm = ctx.getSystemService(WindowManager::class.java)
        val v = HingeFadeView(ctx).apply { setFrame(b); progress = p }
        try { wm.addView(v, overlayParams()) } catch (e: Exception) { Log.w(TAG, "overlay refused: $e"); return }
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

    // ---- cover hand-off ----
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = checkCover()
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = checkCover()
    }

    /** The cover is whichever lit display is much smaller than the biggest one. */
    private fun checkCover() {
        if (state != State.FOLDING) return
        val cover = displays.displays.firstOrNull { d ->
            d.state == Display.STATE_ON && d.mode.physicalWidth.toLong() * d.mode.physicalHeight < innerArea * 6 / 10
        } ?: return
        val b = frame ?: return
        state = State.HANDED_OFF
        Log.i(TAG, "cover display ${cover.displayId} (${cover.mode.physicalWidth}x${cover.mode.physicalHeight}) is on, handing off")
        if (cover.displayId == Display.DEFAULT_DISPLAY) {
            // display 0 itself became the cover panel: the overlay window follows it, just re-run the finish
            val v = overlay ?: run { showInnerOverlay(b, 0.55f); overlay } ?: return
            runCoverFinish(v)
        } else {
            hideAll()
            val pres = Presentation(createDisplayContext(cover), cover).apply {
                window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
            val v = HingeFadeView(pres.context).apply { setFrame(b) }
            pres.setContentView(v)
            try { pres.show() } catch (e: Exception) { Log.w(TAG, "presentation refused: $e"); return }
            presentation = pres
            runCoverFinish(v)
        }
    }

    /** The card lands on the cover: grows from the folded size to fill it, then fades out over the live UI. */
    private fun runCoverFinish(v: HingeFadeView) {
        v.progress = 0.55f; v.opacity = 1f
        coverAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            // debug knob: adb shell settings put global hingefade_slow 1 -> 4 s finish, to screenshot it
            duration = if (android.provider.Settings.Global.getInt(contentResolver, "hingefade_slow", 0) == 1) 4000L else 650L
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                v.progress = 0.55f * (1f - (t / 0.5f).coerceIn(0f, 1f))
                v.opacity = 1f - ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { hideAll() }
            })
            start()
        }
        report("handed off to the cover (capture $captureMs ms)")
    }

    private fun report(s: String) = main.post { status(s) }
}
