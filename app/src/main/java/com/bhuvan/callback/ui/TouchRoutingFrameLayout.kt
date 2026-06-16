package com.bhuvan.callback.ui

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Routes touch events to the [GLSurfaceView] except when the user touches the bottom chrome
 * (thumbnail strip + ask), so taps pass through the transparent overlay to hit-test in AR.
 */
class TouchRoutingFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var glSurfaceView: GLSurfaceView? = null
    var chromeView: View? = null

    private val glLoc = IntArray(2)
    private val chromeLoc = IntArray(2)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val gl = glSurfaceView
        val chrome = chromeView
        if (gl == null) return super.dispatchTouchEvent(ev)

        if (chrome != null && chrome.visibility == View.VISIBLE && chrome.height > 0) {
            chrome.getLocationOnScreen(chromeLoc)
            val cx = ev.rawX
            val cy = ev.rawY
            val inChrome =
                cx >= chromeLoc[0] &&
                    cx < chromeLoc[0] + chrome.width &&
                    cy >= chromeLoc[1] &&
                    cy < chromeLoc[1] + chrome.height
            if (inChrome) {
                return super.dispatchTouchEvent(ev)
            }
        }

        gl.getLocationOnScreen(glLoc)
        val relX = ev.rawX - glLoc[0]
        val relY = ev.rawY - glLoc[1]
        val copy =
            MotionEvent.obtain(
                ev.downTime,
                ev.eventTime,
                ev.action,
                relX,
                relY,
                ev.pressure,
                ev.size,
                ev.metaState,
                ev.xPrecision,
                ev.yPrecision,
                ev.deviceId,
                ev.edgeFlags,
            )
        val handled = gl.dispatchTouchEvent(copy)
        copy.recycle()
        return handled
    }
}
