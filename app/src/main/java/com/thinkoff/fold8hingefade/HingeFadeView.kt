package com.thinkoff.fold8hingefade

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.view.View

/**
 * Draws one captured frame at the same size it had on screen. Two uses:
 *  - inner screen while folding: the frame is invisible, only a veil darkens with [veil] (0..1);
 *  - cover screen: the frame is centre-cropped at 1:1 physical size ([scale] = cover dpi / inner dpi)
 *    and dissolved away with [opacity] going 1 -> 0, so the live cover UI shows through.
 */
class HingeFadeView(context: Context) : View(context) {

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()
    private var bitmap: Bitmap? = null

    /** Black veil strength over the live screen, 0..1. */
    var veil = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** Opacity of the frame itself, 0..1. */
    var opacity = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** Frame pixels per view pixel, 1.0 = same physical size when both panels share a density. */
    var scale = 1f
        set(v) { field = v; invalidate() }

    fun setFrame(b: Bitmap?) {
        bitmap = b
        if (b != null) {
            shader.setInputBuffer("img", BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setFloatUniform("imgRes", b.width.toFloat(), b.height.toFloat())
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (b.isRecycled) return
        shader.setFloatUniform("res", width.toFloat(), height.toFloat())
        shader.setFloatUniform("veil", veil)
        shader.setFloatUniform("opacity", opacity)
        shader.setFloatUniform("scale", scale)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        private const val AGSL = """
            uniform shader img;
            uniform float2 res;
            uniform float2 imgRes;
            uniform float veil;
            uniform float opacity;
            uniform float scale;

            half4 main(float2 xy) {
                // centre-crop: the frame keeps its size, the view shows the middle of it
                float2 uv = (xy - res * 0.5) * scale + imgRes * 0.5;
                float2 inside = step(float2(0.0), uv) * step(uv, imgRes);
                float vis = inside.x * inside.y * opacity;
                half4 col = img.eval(uv);
                half4 frame = half4(col.rgb, 1.0) * half(vis);        // premultiplied
                half4 dark = half4(0.0, 0.0, 0.0, half(veil * (1.0 - vis)));
                return frame + dark;
            }
        """
    }
}
