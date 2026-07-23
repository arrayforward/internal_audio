package com.example.audiocapture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxBars = 90
    private val amplitudes = ArrayDeque<Float>()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_active)
        strokeCap = Paint.Cap.ROUND
    }
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.waveform_idle)
        strokeCap = Paint.Cap.ROUND
    }

    fun addAmplitude(value: Float) {
        synchronized(amplitudes) {
            amplitudes.addLast(value.coerceIn(0.02f, 1f))
            while (amplitudes.size > maxBars) amplitudes.removeFirst()
        }
        postInvalidate()
    }

    fun clear() {
        synchronized(amplitudes) { amplitudes.clear() }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val barWidth = width / maxBars.toFloat()
        val strokeWidth = barWidth * 0.5f
        barPaint.strokeWidth = strokeWidth
        idlePaint.strokeWidth = strokeWidth
        val centerY = height / 2f

        val snapshot: List<Float>
        synchronized(amplitudes) { snapshot = amplitudes.toList() }

        for (i in 0 until maxBars) {
            val x = i * barWidth + barWidth / 2f
            val amp = snapshot.getOrNull(i + maxBars - snapshot.size)
            if (amp != null) {
                val half = (amp * height * 0.9f) / 2f
                canvas.drawLine(x, centerY - half, x, centerY + half, barPaint)
            } else {
                val half = height * 0.03f
                canvas.drawLine(x, centerY - half, x, centerY + half, idlePaint)
            }
        }
    }
}
