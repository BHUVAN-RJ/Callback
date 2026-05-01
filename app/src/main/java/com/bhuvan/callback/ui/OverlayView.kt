package com.bhuvan.callback.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.bhuvan.callback.R

/** Draws 2D anchor markers on top of the GL camera preview. */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val markerPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = context.getColor(R.color.anchor_marker)
        }

    @Volatile
    var markerPositions: List<Pair<Float, Float>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private var markerRadiusPx = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        markerRadiusPx = resources.getDimension(R.dimen.anchor_marker_radius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for ((x, y) in markerPositions) {
            canvas.drawCircle(x, y, markerRadiusPx, markerPaint)
        }
    }
}
