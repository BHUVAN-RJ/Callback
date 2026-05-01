package com.bhuvan.callback.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Draws a 2D arrow from the screen center toward a target (or an edge hint when off-screen). */
object ArrowRenderer {
    /**
     * Draws an arrow from the view center toward ([targetX], [targetY]) in pixel space.
     * When [targetVisible] is false, draws an on-screen edge cue instead.
     */
    fun draw(
        canvas: Canvas,
        viewWidth: Float,
        viewHeight: Float,
        targetX: Float,
        targetY: Float,
        targetVisible: Boolean,
        strokePaint: Paint,
        headPaint: Paint,
    ) {
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        if (!targetVisible) {
            drawEdgeCue(canvas, cx, cy, viewWidth, viewHeight, strokePaint, headPaint)
            return
        }
        val dx = targetX - cx
        val dy = targetY - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        val maxLen = min(viewWidth, viewHeight) * 0.38f
        val len = dist.coerceAtMost(maxLen)
        val ux = dx / dist
        val uy = dy / dist
        val tipX = cx + ux * len
        val tipY = cy + uy * len
        canvas.drawLine(cx, cy, tipX, tipY, strokePaint)
        drawHead(canvas, tipX, tipY, atan2(uy.toDouble(), ux.toDouble()).toFloat(), headPaint)
    }

    private fun drawHead(canvas: Canvas, tipX: Float, tipY: Float, angle: Float, headPaint: Paint) {
        val headLen = 36f
        val spread = 0.45f
        val path = Path()
        path.moveTo(tipX, tipY)
        path.lineTo(
            tipX - headLen * cos(angle - spread),
            tipY - headLen * sin(angle - spread),
        )
        path.lineTo(
            tipX - headLen * cos(angle + spread),
            tipY - headLen * sin(angle + spread),
        )
        path.close()
        canvas.drawPath(path, headPaint)
    }

    private fun drawEdgeCue(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        viewWidth: Float,
        viewHeight: Float,
        strokePaint: Paint,
        headPaint: Paint,
    ) {
        val margin = 48f
        val tipX = viewWidth / 2f
        val tipY = margin
        canvas.drawLine(cx, cy, tipX, tipY, strokePaint)
        drawHead(canvas, tipX, tipY, -kotlin.math.PI.toFloat() / 2f, headPaint)
    }
}
