package com.bhuvan.callback.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Bitmap
import android.util.AttributeSet
import android.widget.LinearLayout
import com.bhuvan.callback.R

/** Bottom strip that paints memorized object thumbnails with Canvas. */
class ThumbnailStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val borderPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.getDimension(R.dimen.thumb_border_stroke)
            color = context.getColor(R.color.thumb_border)
        }

    private val drawables = mutableListOf<Bitmap>()
    private val destRect = RectF()
    private val srcRect = Rect()

    init {
        orientation = HORIZONTAL
        setWillNotDraw(false)
    }

    /** Updates the bitmaps shown in the strip (caller owns bitmap lifetimes). */
    fun setThumbnails(bitmaps: List<Bitmap>) {
        drawables.clear()
        drawables.addAll(bitmaps)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawables.isEmpty()) {
            return
        }
        val padding = resources.getDimension(R.dimen.thumb_padding)
        val height = (height - padding * 2f).coerceAtLeast(1f)
        var x = padding
        for (bmp in drawables) {
            val aspect = bmp.height.toFloat() / bmp.width.toFloat().coerceAtLeast(1e-3f)
            val thumbW = height / aspect
            destRect.set(x, padding, x + thumbW, padding + height)
            srcRect.set(0, 0, bmp.width, bmp.height)
            canvas.drawBitmap(bmp, srcRect, destRect, null)
            canvas.drawRect(destRect, borderPaint)
            x += thumbW + padding
        }
    }
}
