package com.store.inventoryscanner

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Subtle corner brackets with a stationary red scan line.
 * The line gently fades in/out only while scanning.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * density
    }
    private var scanning = false
    private var lineAlpha = 0
    private var animator: ValueAnimator? = null

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun setScanning(active: Boolean) {
        if (scanning == active) return
        scanning = active
        animator?.cancel()
        animator = null
        if (active) {
            animator = ValueAnimator.ofInt(70, 210).apply {
                duration = 1150L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    lineAlpha = it.animatedValue as Int
                    invalidate()
                }
                start()
            }
        } else {
            lineAlpha = 0
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = if (scanning) 5f * density else 0f
        val left = inset + 1.5f * density
        val top = inset + 1.5f * density
        val right = width - inset - 1.5f * density
        val bottom = height - inset - 1.5f * density
        val len = 22f * density
        val radius = 12f * density

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f * density
        paint.color = 0xFF55D7FF.toInt()
        paint.alpha = if (scanning) 235 else 205

        // Four independent rounded-corner brackets; no full rectangle.
        val path = android.graphics.Path().apply {
            moveTo(left, top + radius + len)
            lineTo(left, top + radius)
            quadTo(left, top, left + radius, top)
            lineTo(left + radius + len, top)

            moveTo(right - radius - len, top)
            lineTo(right - radius, top)
            quadTo(right, top, right, top + radius)
            lineTo(right, top + radius + len)

            moveTo(left, bottom - radius - len)
            lineTo(left, bottom - radius)
            quadTo(left, bottom, left + radius, bottom)
            lineTo(left + radius + len, bottom)

            moveTo(right - radius - len, bottom)
            lineTo(right - radius, bottom)
            quadTo(right, bottom, right, bottom - radius)
            lineTo(right, bottom - radius - len)
        }

        // restrained cyan halo under the brackets
        paint.color = 0x6655D7FF
        paint.strokeWidth = 4f * density
        canvas.drawPath(path, paint)
        paint.color = 0xFF72DFFF.toInt()
        paint.strokeWidth = 1.7f * density
        canvas.drawPath(path, paint)

        if (scanning) {
            val y = height / 2f
            paint.style = Paint.Style.STROKE
            paint.color = 0x66FF2525
            paint.strokeWidth = 7f * density
            paint.alpha = (lineAlpha * 0.45f).toInt()
            canvas.drawLine(left + radius, y, right - radius, y, paint)
            paint.color = 0xFFFF3434.toInt()
            paint.strokeWidth = 1.8f * density
            paint.alpha = lineAlpha
            canvas.drawLine(left + radius, y, right - radius, y, paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
