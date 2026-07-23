package com.example.audiocapture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

class TrimWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var peaks: FloatArray = FloatArray(0)
    private var selStart = 0f
    private var selEnd = 1f
    private var minGapFraction = 0.01f
    private var playFraction = -1f

    var onSelectionChanged: ((startFraction: Float, endFraction: Float) -> Unit)? = null

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_active)
        strokeCap = Paint.Cap.ROUND
    }
    private val dimPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.trim_dim)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.trim_handle)
        strokeWidth = 6f
    }
    private val progressPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.trim_progress)
        strokeWidth = 3f
    }

    private var dragTarget = DRAG_NONE

    companion object {
        private const val DRAG_NONE = 0
        private const val DRAG_START = 1
        private const val DRAG_END = 2
        private const val TOUCH_SLOP_PX = 96
    }

    fun setPeaks(peaks: FloatArray, durationMs: Long) {
        this.peaks = peaks
        minGapFraction = if (durationMs > 0)
            (AudioTrimmer.MIN_TRIM_MS.toFloat() / durationMs).coerceAtMost(0.5f)
        else 0.01f
        invalidate()
    }

    fun setSelection(startFraction: Float, endFraction: Float) {
        selStart = startFraction.coerceIn(0f, 1f - minGapFraction)
        selEnd = endFraction.coerceIn(selStart + minGapFraction, 1f)
        invalidate()
    }

    fun setPlayFraction(fraction: Float) {
        playFraction = fraction
        invalidate()
    }

    fun getSelection(): Pair<Float, Float> = selStart to selEnd

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (peaks.isEmpty()) return

        val barCount = peaks.size
        val barWidth = width / barCount.toFloat()
        barPaint.strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f)
        val centerY = height / 2f

        for (i in peaks.indices) {
            val x = i * barWidth + barWidth / 2f
            val half = (peaks[i].coerceIn(0.02f, 1f) * height * 0.9f) / 2f
            canvas.drawLine(x, centerY - half, x, centerY + half, barPaint)
        }

        val startX = selStart * width
        val endX = selEnd * width
        canvas.drawRect(0f, 0f, startX, height.toFloat(), dimPaint)
        canvas.drawRect(endX, 0f, width.toFloat(), height.toFloat(), dimPaint)

        canvas.drawLine(startX, 0f, startX, height.toFloat(), handlePaint)
        canvas.drawLine(endX, 0f, endX, height.toFloat(), handlePaint)
        canvas.drawCircle(startX, centerY, 14f, handlePaint)
        canvas.drawCircle(endX, centerY, 14f, handlePaint)

        if (playFraction in 0f..1f) {
            val px = playFraction * width
            canvas.drawLine(px, 0f, px, height.toFloat(), progressPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val startX = selStart * width
                val endX = selEnd * width
                dragTarget = when {
                    abs(event.x - startX) < TOUCH_SLOP_PX &&
                        abs(event.x - startX) <= abs(event.x - endX) -> DRAG_START
                    abs(event.x - endX) < TOUCH_SLOP_PX -> DRAG_END
                    else -> DRAG_NONE
                }
                return dragTarget != DRAG_NONE
            }
            MotionEvent.ACTION_MOVE -> {
                val fraction = (event.x / width).coerceIn(0f, 1f)
                when (dragTarget) {
                    DRAG_START -> {
                        selStart = fraction.coerceIn(0f, selEnd - minGapFraction)
                        onSelectionChanged?.invoke(selStart, selEnd)
                        invalidate()
                    }
                    DRAG_END -> {
                        selEnd = fraction.coerceIn(selStart + minGapFraction, 1f)
                        onSelectionChanged?.invoke(selStart, selEnd)
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragTarget = DRAG_NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
