package com.gemmaguard.app.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import com.gemmaguard.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class ScanningOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.accentSurface)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(color(R.color.panel), 140)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(color(R.color.panel), 170)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.35f)
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.panel)
        style = Paint.Style.FILL
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.accent)
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(dp(18f), BlurMaskFilter.Blur.NORMAL)
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = color(R.color.panel)
        style = Paint.Style.FILL
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var rotationDegrees = 0f
    private var particlePhase = 0f
    private var pulsePhase = 0f
    private var readinessProgress = 1f

    private val rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 18000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            rotationDegrees = it.animatedValue as Float
            invalidate()
        }
    }
    private val particleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            particlePhase = it.animatedValue as Float
            invalidate()
        }
    }
    private val pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener {
            pulsePhase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!rotationAnimator.isStarted) rotationAnimator.start()
        if (!particleAnimator.isStarted) particleAnimator.start()
        if (!pulseAnimator.isStarted) pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        rotationAnimator.cancel()
        particleAnimator.cancel()
        pulseAnimator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updatePaintPalette()
        val size = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val radarRadius = size * 0.42f
        val orbRadius = size * 0.21f

        drawSweepBackground(canvas, cx, cy, radarRadius)
        drawRadar(canvas, cx, cy, radarRadius)
        drawNetwork(canvas, cx, cy, orbRadius)
    }

    fun setReadinessProgress(progress: Float) {
        val normalized = progress.coerceIn(0f, 1f)
        if (readinessProgress != normalized) {
            readinessProgress = normalized
            invalidate()
        }
    }

    private fun updatePaintPalette() {
        val accent = blendColors(
            startColor = color(R.color.inkMuted),
            endColor = color(R.color.accent),
            fraction = readinessProgress,
        )
        val accentSurface = blendColors(
            startColor = withAlpha(color(R.color.inkMuted), 90),
            endColor = color(R.color.accentSurface),
            fraction = readinessProgress,
        )
        val bright = blendColors(
            startColor = withAlpha(color(R.color.panelStroke), 140),
            endColor = color(R.color.panel),
            fraction = readinessProgress,
        )

        ringPaint.color = accentSurface
        axisPaint.color = withAlpha(bright, 150)
        edgePaint.color = withAlpha(bright, 190)
        nodePaint.color = bright
        glowPaint.color = accent
        particlePaint.color = bright
    }

    private fun drawSweepBackground(canvas: Canvas, cx: Float, cy: Float, radarRadius: Float) {
        sweepPaint.shader = RadialGradient(
            cx,
            cy,
            radarRadius,
            intArrayOf(withAlpha(color(R.color.accent), 100), Color.TRANSPARENT),
            floatArrayOf(0.2f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radarRadius, sweepPaint)

        val startAngle = -35f + (rotationDegrees * 0.35f)
        val oval = RectF(cx - radarRadius, cy - radarRadius, cx + radarRadius, cy + radarRadius)
        val gradient = LinearGradient(
            cx,
            cy - radarRadius,
            cx + radarRadius,
            cy,
            withAlpha(color(R.color.accentSurface), 0),
            withAlpha(color(R.color.accentSurface), 130),
            Shader.TileMode.CLAMP,
        )
        sweepPaint.shader = gradient
        canvas.drawArc(oval, startAngle, 42f, true, sweepPaint)
    }

    private fun drawRadar(canvas: Canvas, cx: Float, cy: Float, radarRadius: Float) {
        repeat(4) { index ->
            val fraction = (index + 1) / 4f
            canvas.drawCircle(cx, cy, radarRadius * fraction, ringPaint)
        }

        canvas.drawLine(cx - radarRadius, cy, cx + radarRadius, cy, axisPaint)
        canvas.drawLine(cx, cy - radarRadius, cx, cy + radarRadius, axisPaint)
    }

    private fun drawNetwork(canvas: Canvas, cx: Float, cy: Float, orbRadius: Float) {
        val points = buildPoints(cx, cy, orbRadius)
        val edges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
            0 to 8, 2 to 8, 4 to 8, 6 to 8,
            1 to 9, 3 to 9, 5 to 9, 7 to 9,
            8 to 10, 9 to 10, 0 to 10, 5 to 10,
        )

        val glowRadius = orbRadius * (1.1f + (pulsePhase * 0.08f))
        canvas.drawCircle(cx, cy, glowRadius, glowPaint)

        canvas.withRotation(rotationDegrees, cx, cy) {
            edges.forEach { (start, end) ->
                val startPoint = points[start]
                val endPoint = points[end]
                drawLine(startPoint.x, startPoint.y, endPoint.x, endPoint.y, edgePaint)
            }

            drawParticles(this, points, edges)

            points.forEach { point ->
                drawCircle(point.x, point.y, dp(3.1f), nodePaint)
            }
        }
    }

    private fun drawParticles(canvas: Canvas, points: List<Point>, edges: List<Pair<Int, Int>>) {
        edges.take(12).forEachIndexed { index, edge ->
            val start = points[edge.first]
            val end = points[edge.second]
            val progress = (particlePhase + (index * 0.083f)) % 1f
            val x = lerp(start.x, end.x, progress)
            val y = lerp(start.y, end.y, progress)
            particlePaint.alpha = (170 + (85 * sin(progress * PI))).toInt().coerceIn(110, 255)
            canvas.drawCircle(x, y, dp(2.6f), particlePaint)
        }
    }

    private fun buildPoints(cx: Float, cy: Float, radius: Float): List<Point> {
        val outer = List(8) { index ->
            polarPoint(cx, cy, radius, (-90 + (index * 45)).toDouble())
        }
        val innerRadius = radius * 0.55f
        val inner = List(2) { index ->
            polarPoint(cx, cy, innerRadius, (index * 180).toDouble())
        }
        return outer + inner + listOf(Point(cx, cy))
    }

    private fun polarPoint(cx: Float, cy: Float, radius: Float, degrees: Double): Point {
        val radians = degrees * (PI / 180.0)
        return Point(
            x = cx + (radius * cos(radians)).toFloat(),
            y = cy + (radius * sin(radians)).toFloat(),
        )
    }

    private fun color(resId: Int): Int = ContextCompat.getColor(context, resId)

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun blendColors(startColor: Int, endColor: Int, fraction: Float): Int {
        return Color.argb(
            lerp(Color.alpha(startColor).toFloat(), Color.alpha(endColor).toFloat(), fraction).toInt(),
            lerp(Color.red(startColor).toFloat(), Color.red(endColor).toFloat(), fraction).toInt(),
            lerp(Color.green(startColor).toFloat(), Color.green(endColor).toFloat(), fraction).toInt(),
            lerp(Color.blue(startColor).toFloat(), Color.blue(endColor).toFloat(), fraction).toInt(),
        )
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics,
        )
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + ((end - start) * fraction)
    }

    private data class Point(
        val x: Float,
        val y: Float,
    )
}
