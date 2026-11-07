package com.arruler.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A projected line on screen, in view pixels. */
class OverlayLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val meters: Float,
    val live: Boolean
)

/** Immutable snapshot produced by the GL thread and drawn by the UI thread. */
class OverlayState(
    val points: FloatArray,
    val pointCount: Int,
    val lines: List<OverlayLine>,
    val reticleOnSurface: Boolean
) {
    companion object {
        val EMPTY = OverlayState(FloatArray(0), 0, emptyList(), false)
    }
}

/**
 * Draws measurement lines, points, distance labels and the centre reticle on top of
 * the camera feed. Drawing in 2D keeps lines crisp and thick on every GPU.
 */
class MeasureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    @Volatile private var state: OverlayState = OverlayState.EMPTY

    @Volatile var unit: MeasureUnit = MeasureUnit.CM
        set(value) {
            field = value
            postInvalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = 0xFF33D17A.toInt()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66000000
        style = Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD54F.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 7f * density), 0f)
    }
    private val pointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.FILL
    }
    private val pointStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xD9000000.toInt()
        style = Paint.Style.FILL
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val reticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val reticleDot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val livePath = Path()
    private val labelRect = RectF()

    init {
        // Lets touches fall through to views below.
        isClickable = false
        isFocusable = false
    }

    /** Thread-safe; called from the GL thread once per frame. */
    fun publish(newState: OverlayState) {
        state = newState
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = state
        val currentUnit = unit

        for (line in s.lines) {
            canvas.drawLine(line.x1, line.y1, line.x2, line.y2, shadowPaint)
            if (line.live) {
                livePath.reset()
                livePath.moveTo(line.x1, line.y1)
                livePath.lineTo(line.x2, line.y2)
                canvas.drawPath(livePath, livePaint)
            } else {
                canvas.drawLine(line.x1, line.y1, line.x2, line.y2, linePaint)
            }
        }

        val radius = 6f * density
        for (i in 0 until s.pointCount) {
            val x = s.points[i * 2]
            val y = s.points[i * 2 + 1]
            canvas.drawCircle(x, y, radius, pointFill)
            canvas.drawCircle(x, y, radius, pointStroke)
        }

        for (line in s.lines) {
            drawLabel(canvas, line, currentUnit)
        }

        drawReticle(canvas, s.reticleOnSurface)
    }

    private fun drawLabel(canvas: Canvas, line: OverlayLine, unit: MeasureUnit) {
        val dx = line.x2 - line.x1
        val dy = line.y2 - line.y1
        // Skip labels for segments that are too short on screen to read.
        if (dx * dx + dy * dy < (24f * density) * (24f * density)) return

        val text = unit.format(line.meters)
        val cx = (line.x1 + line.x2) / 2f
        val cy = (line.y1 + line.y2) / 2f
        val halfW = labelText.measureText(text) / 2f + 10f * density
        val halfH = 15f * density
        labelRect.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        canvas.drawRoundRect(labelRect, halfH, halfH, labelBg)
        val baseline = cy - (labelText.descent() + labelText.ascent()) / 2f
        canvas.drawText(text, cx, baseline, labelText)
    }

    private fun drawReticle(canvas: Canvas, onSurface: Boolean) {
        val cx = width / 2f
        val cy = height / 2f
        val color = if (onSurface) accent else 0xB3FFFFFF.toInt()
        reticlePaint.color = color
        reticleDot.color = color
        val r = 20f * density
        canvas.drawCircle(cx, cy, r, reticlePaint)
        canvas.drawCircle(cx, cy, 3.5f * density, reticleDot)
        if (onSurface) {
            val t = 7f * density
            canvas.drawLine(cx - r - t, cy, cx - r + t * 0.4f, cy, reticlePaint)
            canvas.drawLine(cx + r - t * 0.4f, cy, cx + r + t, cy, reticlePaint)
            canvas.drawLine(cx, cy - r - t, cx, cy - r + t * 0.4f, reticlePaint)
            canvas.drawLine(cx, cy + r - t * 0.4f, cx, cy + r + t, reticlePaint)
        }
    }
}
