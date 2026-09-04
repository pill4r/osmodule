package dev.pillar.osmodule.pocket4p

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.round

/** Spring-centred zoom rocker: hold upward/downward for smooth constant-rate zoom. */
internal class PocketZoomSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    fun interface Listener {
        fun onZoom(factor: Double, final: Boolean)
    }

    var listener: Listener? = null
    private val readout = PocketZoomReadout()
    var minFactor: Double = 1.0
        set(value) {
            field = value
            readout.setBounds(field, maxFactor)
            invalidate()
        }
    var maxFactor: Double = 12.0
        set(value) {
            field = value.coerceAtLeast(minFactor)
            readout.setBounds(minFactor, field)
            invalidate()
        }
    /** Latest camera-confirmed zoom. Delayed telemetry is hidden while the rocker is active. */
    var factor: Double
        get() = readout.confirmedFactor
        set(value) {
            readout.confirm(value, SystemClock.elapsedRealtime())
            updateSettlementCallback()
            invalidate()
        }
    var enabledForControl: Boolean = false
        set(value) {
            field = value
            isEnabled = value
            if (!value) stopTracking(notify = false)
            invalidate()
        }
    val isTracking: Boolean get() = readout.isTracking

    private var thumbOffsetY = 0f
    private var factorPerSecond = 0.0
    private var lastTickAtMs = 0L
    private var lastSentAtMs = 0L
    private var lastSentFactor = readout.requestedFactor
    private val zoomTicker = object : Runnable {
        override fun run() {
            if (!readout.isTracking || !enabledForControl) return
            val now = SystemClock.elapsedRealtime()
            readout.advance(factorPerSecond, now - lastTickAtMs)
            lastTickAtMs = now
            if (PocketZoomVelocity.shouldEmit(
                    targetFactor = readout.requestedFactor,
                    lastSentFactor = lastSentFactor,
                    factorPerSecond = factorPerSecond,
                    elapsedMs = now - lastSentAtMs,
                )
            ) {
                lastSentAtMs = now
                lastSentFactor = readout.requestedFactor
                listener?.onZoom(readout.requestedFactor, false)
            }
            invalidate()
            postOnAnimation(this)
        }
    }
    private val settlementTimeout = Runnable {
        if (readout.expireSettlement(SystemClock.elapsedRealtime())) invalidate()
        updateSettlementCallback()
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(92, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(111, 202, 255)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(205, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.1f)
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val surfaceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(125, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(72, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
    }
    private val trackRect = RectF()
    private val fillRect = RectF()
    private val surfaceRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(56f).toInt(), widthMeasureSpec),
            resolveSize(dp(152f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (enabledForControl) 255 else 75
        val radius = dp(18f)
        surfaceRect.set(dp(2f), dp(1f), width - dp(2f), height - dp(3f))
        shadowPaint.alpha = (75 * alpha / 255f).toInt()
        canvas.drawRoundRect(
            RectF(surfaceRect).apply { offset(0f, dp(3f)) },
            radius,
            radius,
            shadowPaint,
        )
        surfacePaint.shader = LinearGradient(
            surfaceRect.left,
            surfaceRect.top,
            surfaceRect.right,
            surfaceRect.bottom,
            intArrayOf(
                Color.argb(100 * alpha / 255, 255, 255, 255),
                Color.argb(62 * alpha / 255, 72, 88, 105),
                Color.argb(120 * alpha / 255, 9, 14, 21),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(surfaceRect, radius, radius, surfacePaint)
        surfaceStrokePaint.alpha = (155 * alpha / 255f).toInt()
        canvas.drawRoundRect(surfaceRect, radius, radius, surfaceStrokePaint)

        trackPaint.alpha = (125 * alpha / 255f).toInt()
        fillPaint.alpha = alpha
        thumbRingPaint.alpha = (215 * alpha / 255f).toInt()
        textPaint.alpha = alpha

        val centerX = width / 2f
        val top = dp(30f)
        val bottom = height - dp(20f)
        val neutralY = (top + bottom) / 2f
        val trackHalfWidth = dp(4f)
        val thumbY = neutralY + thumbOffsetY
        trackRect.set(centerX - trackHalfWidth, top, centerX + trackHalfWidth, bottom)
        fillRect.set(
            centerX - trackHalfWidth,
            minOf(neutralY, thumbY),
            centerX + trackHalfWidth,
            maxOf(neutralY, thumbY),
        )
        canvas.drawRoundRect(trackRect, trackHalfWidth, trackHalfWidth, trackPaint)
        canvas.drawRoundRect(fillRect, trackHalfWidth, trackHalfWidth, fillPaint)
        canvas.drawCircle(centerX - dp(8f), neutralY, dp(1.3f), trackPaint)
        canvas.drawCircle(centerX + dp(8f), neutralY, dp(1.3f), trackPaint)
        val thumbRadius = dp(10f)
        shadowPaint.alpha = (92 * alpha / 255f).toInt()
        canvas.drawCircle(centerX, thumbY + dp(2f), thumbRadius + dp(1f), shadowPaint)
        thumbPaint.shader = RadialGradient(
            centerX - dp(3f),
            thumbY - dp(3f),
            thumbRadius * 1.45f,
            Color.argb(250 * alpha / 255, 255, 255, 255),
            Color.argb(170 * alpha / 255, 171, 205, 224),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(centerX, thumbY, thumbRadius, thumbPaint)
        canvas.drawCircle(centerX, thumbY, thumbRadius, thumbRingPaint)
        canvas.drawText(formatFactor(readout.displayFactor), centerX, dp(14f), textPaint)
        canvas.drawText("+", centerX, top + dp(13f), textPaint)
        canvas.drawText("−", centerX, bottom - dp(5f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForControl) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(settlementTimeout)
                readout.beginTracking()
                thumbOffsetY = 0f
                factorPerSecond = 0.0
                lastTickAtMs = SystemClock.elapsedRealtime()
                lastSentAtMs = lastTickAtMs
                lastSentFactor = readout.requestedFactor
                removeCallbacks(zoomTicker)
                updateVelocity(event.y)
                postOnAnimation(zoomTicker)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateVelocity(event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (readout.isTracking) updateVelocity(event.y)
                stopTracking(notify = true)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        stopTracking(notify = false)
        super.onDetachedFromWindow()
    }

    private fun updateVelocity(y: Float) {
        val travel = ((height - dp(50f)) / 2f).coerceAtLeast(dp(12f))
        val neutralY = height / 2f + dp(5f)
        thumbOffsetY = (y - neutralY).coerceIn(-travel, travel)
        factorPerSecond = PocketZoomVelocity.factorPerSecond(thumbOffsetY, travel)
        invalidate()
    }

    private fun stopTracking(notify: Boolean) {
        removeCallbacks(zoomTicker)
        removeCallbacks(settlementTimeout)
        thumbOffsetY = 0f
        factorPerSecond = 0.0
        if (notify && readout.isTracking) {
            val target = readout.finishTracking(SystemClock.elapsedRealtime())
            listener?.onZoom(target, true)
            updateSettlementCallback()
        } else {
            readout.cancelTracking()
        }
        invalidate()
    }

    private fun updateSettlementCallback() {
        removeCallbacks(settlementTimeout)
        val delayMs = readout.millisecondsUntilSettlementExpires(
            SystemClock.elapsedRealtime(),
        ) ?: return
        postDelayed(settlementTimeout, delayMs.coerceAtLeast(1L))
    }

    private fun formatFactor(value: Double): String {
        val rounded = round(value * 10.0) / 10.0
        return "%.1f×".format(rounded)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

}
