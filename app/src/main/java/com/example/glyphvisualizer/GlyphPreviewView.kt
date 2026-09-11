package com.example.glyphvisualizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class GlyphPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val matrixSide = 25
    private val totalLeds = matrixSide * matrixSide
    private val displayBuffer = IntArray(totalLeds)
    private var hasData = false

    private val ledPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#050505")
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#181818")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun updateLeds(buffer: IntArray) {
        val len = min(buffer.size, totalLeds)
        System.arraycopy(buffer, 0, displayBuffer, 0, len)
        hasData = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val startX = (width - size) / 2f
        val startY = (height - size) / 2f
        val cxMaster = width / 2f
        val cyMaster = height / 2f
        val radius = size / 2f

        canvas.drawCircle(cxMaster, cyMaster, radius - 2f, bgPaint)
        canvas.drawCircle(cxMaster, cyMaster, radius - 2f, rimPaint)

        val cellStep = size / matrixSide
        val dotRadius = cellStep * 0.38f
        val centerCoord = 12f

        for (y in 0 until matrixSide) {
            for (x in 0 until matrixSide) {
                val dist = hypot(x - centerCoord, y - centerCoord)
                if (dist > 12.5f) continue

                val idx = y * matrixSide + x
                val bright = if (hasData) displayBuffer[idx].coerceIn(0, 255) else 0

                if (bright > 6) {
                    ledPaint.color = Color.rgb(bright, bright, bright)
                } else {
                    ledPaint.color = Color.parseColor("#121212")
                }

                val cx = startX + x * cellStep + cellStep / 2f
                val cy = startY + y * cellStep + cellStep / 2f
                canvas.drawCircle(cx, cy, dotRadius, ledPaint)
            }
        }
    }
}