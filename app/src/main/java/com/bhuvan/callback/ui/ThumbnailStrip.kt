package com.bhuvan.callback.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** Bottom strip reserved for memorized-object thumbnails (populated in later phases). */
class ThumbnailStrip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    init {
        orientation = HORIZONTAL
    }
}
