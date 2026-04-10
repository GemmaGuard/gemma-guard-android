package com.gemmaguard.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.gemmaguard.app.R
import kotlin.math.min

class HighRiskWarningFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val danger = ContextCompat.getColor(context, R.color.riskHigh)
        val edgeDepth = min(width, height) * 0.28f
        val cornerRadius = edgeDepth * 1.35f
        val strongest = withAlpha(danger, 150)
        val middle = withAlpha(danger, 64)
        val clear = Color.TRANSPARENT

        drawTopEdge(canvas, edgeDepth, strongest, middle, clear)
        drawBottomEdge(canvas, edgeDepth, strongest, middle, clear)
        drawLeftEdge(canvas, edgeDepth, strongest, middle, clear)
        drawRightEdge(canvas, edgeDepth, strongest, middle, clear)
        drawCorner(canvas, 0f, 0f, cornerRadius, strongest, middle, clear)
        drawCorner(canvas, width.toFloat(), 0f, cornerRadius, strongest, middle, clear)
        drawCorner(canvas, 0f, height.toFloat(), cornerRadius, strongest, middle, clear)
        drawCorner(canvas, width.toFloat(), height.toFloat(), cornerRadius, strongest, middle, clear)
    }

    private fun drawTopEdge(canvas: Canvas, depth: Float, strongest: Int, middle: Int, clear: Int) {
        edgePaint.shader = LinearGradient(
            0f,
            0f,
            0f,
            depth,
            intArrayOf(strongest, middle, clear),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), depth, edgePaint)
    }

    private fun drawBottomEdge(canvas: Canvas, depth: Float, strongest: Int, middle: Int, clear: Int) {
        edgePaint.shader = LinearGradient(
            0f,
            height.toFloat(),
            0f,
            height - depth,
            intArrayOf(strongest, middle, clear),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, height - depth, width.toFloat(), height.toFloat(), edgePaint)
    }

    private fun drawLeftEdge(canvas: Canvas, depth: Float, strongest: Int, middle: Int, clear: Int) {
        edgePaint.shader = LinearGradient(
            0f,
            0f,
            depth,
            0f,
            intArrayOf(strongest, middle, clear),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, depth, height.toFloat(), edgePaint)
    }

    private fun drawRightEdge(canvas: Canvas, depth: Float, strongest: Int, middle: Int, clear: Int) {
        edgePaint.shader = LinearGradient(
            width.toFloat(),
            0f,
            width - depth,
            0f,
            intArrayOf(strongest, middle, clear),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(width - depth, 0f, width.toFloat(), height.toFloat(), edgePaint)
    }

    private fun drawCorner(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        strongest: Int,
        middle: Int,
        clear: Int,
    ) {
        cornerPaint.shader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(strongest, middle, clear),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, cornerPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }

}
