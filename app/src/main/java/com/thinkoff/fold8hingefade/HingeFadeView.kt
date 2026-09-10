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
 * Draws one captured frame as a card that shrinks, rounds and dims as [progress] goes 0 -> 1,
 * over a black veil that thickens with it. Pure AGSL, one draw call, driven from the hinge angle.
 */
class HingeFadeView(context: Context) : View(context) {

    private val shader = RuntimeShader(AGSL)
    private val paint = Paint()
    private var bitmap: Bitmap? = null

    /** 0 = flat (invisible), 1 = fully folded card. */
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    /** Whole-view opacity, for the hand-off fade on the cover screen. */
    var opacity = 1f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

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
        shader.setFloatUniform("p", progress)
        shader.setFloatUniform("alpha", opacity)
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    companion object {
        private const val AGSL = """
            uniform shader img;
            uniform float2 res;
            uniform float2 imgRes;
            uniform float p;
            uniform float alpha;

            float rrect(float2 pt, float2 halfSize, float r) {
                float2 d = abs(pt) - halfSize + r;
                return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
            }

            half4 main(float2 xy) {
                float e = p * p * (3.0 - 2.0 * p);
                float s = mix(1.0, 0.62, e);
                float2 c = res * 0.5;
                float2 rel = xy - c;
                float r = mix(0.0, 56.0, e);
                float d = rrect(rel, c * s, r);
                float2 uv = (rel / s + c) / res * imgRes;
                half4 col = img.eval(uv);
                col.rgb *= half(mix(1.0, 0.7, e));
                float inside = 1.0 - smoothstep(-1.0, 1.0, d);
                half4 bg = half4(0.0, 0.0, 0.0, half(e * 0.9));
                half4 outc = mix(bg, half4(col.rgb, 1.0), half(inside));
                return outc * half(alpha);
            }
        """
    }
}
