package com.yesmin.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.SweepGradient
import android.provider.Settings
import android.view.View
import android.view.WindowManager

/** Screen ke kinare ki glowing line (dusre apps ke upar), touch nahi rokti. */
object EdgeGlow {
    private var view: GlowView? = null
    private var wm: WindowManager? = null

    fun show(ctx: Context) {
        if (view != null) return
        if (!Settings.canDrawOverlays(ctx)) { Bus.log("Edge glow: overlay permission nahi"); return }
        val m = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = GlowView(ctx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        try { m.addView(v, lp); view = v; wm = m } catch (e: Exception) { Bus.log("Edge glow fail: ${e.message}") }
    }

    fun hide() {
        try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
        view = null
    }
}

private class GlowView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val mat = Matrix()
    private val d = ctx.resources.displayMetrics.density
    private var angle = 0f
    private var smooth = 0f

    private fun colors(): IntArray {
        if (Bus.muted.value) return intArrayOf(0xFF8A8A9A.toInt(), 0xFF5A5A6A.toInt(), 0xFF8A8A9A.toInt())
        return when (Bus.state.value) {
            VState.LISTENING -> intArrayOf(0xFF2DE2C9.toInt(), 0xFF9B5CFF.toInt(), 0xFF2DE2C9.toInt())
            VState.THINKING -> intArrayOf(0xFF9B5CFF.toInt(), 0xFFFFD166.toInt(), 0xFF9B5CFF.toInt())
            VState.SPEAKING -> intArrayOf(0xFFFF5FA2.toInt(), 0xFFB06CFF.toInt(), 0xFFFF5FA2.toInt())
            VState.ERROR -> intArrayOf(0xFFFF4D4D.toInt(), 0xFF8A1F1F.toInt(), 0xFFFF4D4D.toInt())
            else -> intArrayOf(0xFF9B5CFF.toInt(), 0xFF5A3CA0.toInt(), 0xFF9B5CFF.toInt())
        }
    }

    override fun onDraw(c: Canvas) {
        val lv = Bus.level.value
        smooth += (lv - smooth) * 0.25f
        angle = (angle + 2.2f) % 360f
        val cx = width / 2f; val cy = height / 2f
        val sg = SweepGradient(cx, cy, colors(), floatArrayOf(0f, 0.5f, 1f))
        mat.setRotate(angle, cx, cy); sg.setLocalMatrix(mat)
        paint.shader = sg
        val base = 3f * d + smooth * 8f * d
        for (i in 5 downTo 0) {
            paint.strokeWidth = base + i * 5f * d
            paint.alpha = if (i == 0) 255 else (70 - i * 10).coerceAtLeast(12)
            val inset = paint.strokeWidth / 2f
            rect.set(inset, inset, width - inset, height - inset)
            c.drawRoundRect(rect, 36f * d, 36f * d, paint)
        }
        postInvalidateOnAnimation()
    }
}
