package dev.konraditurbe.osmosis.pocket4p

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
    var minFactor: Double = 1.0
        set(value) {
            field = value
            factor = factor.coerceAtLeast(value)
            invalidate()
        }
    var maxFactor: Double = 12.0
        set(value) {
            field = value.coerceAtLeast(minFactor)
            factor = factor.coerceAtMost(field)
            invalidate()
        }
    var factor: Double = 1.0
        set(value) {
            field = value.coerceIn(minFactor, maxFactor)
            invalidate()
        }
    var enabledForControl: Boolean = false
        set(value) {
            field = value
            isEnabled = value
            if (!value) stopTracking(notify = false)
            invalidate()
        }
    val isTracking: Boolean get() = tracking

    private var tracking = false
    private var touchAnchorY = 0f
    private var thumbOffsetY = 0f
    private var factorPerSecond = 0.0
    private var lastTickAtMs = 0L
    private var lastSentAtMs = 0L
    private var lastSentFactor = factor
    private val zoomTicker = object : Runnable {
        override fun run() {
            if (!tracking || !enabledForControl) return
            val now = SystemClock.elapsedRealtime()
            factor = PocketZoomVelocity.advance(
                factor = factor,
                factorPerSecond = factorPerSecond,
                elapsedMs = now - lastTickAtMs,
                minFactor = minFactor,
                maxFactor = maxFactor,
            )
            lastTickAtMs = now
            if (now - lastSentAtMs >= COMMAND_INTERVAL_MS &&
                kotlin.math.abs(factor - lastSentFactor) >= MIN_COMMAND_FACTOR_DELTA
            ) {
                lastSentAtMs = now
                lastSentFactor = factor
                listener?.onZoom(factor, false)
            }
            postOnAnimation(this)
        }
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 255, 255)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 184, 255)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(56f).toInt(), widthMeasureSpec),
            resolveSize(dp(152f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (enabledForControl) 255 else 75
        trackPaint.alpha = (115 * alpha / 255f).toInt()
        fillPaint.alpha = alpha
        thumbPaint.alpha = alpha
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
        canvas.drawCircle(centerX, thumbY, dp(10f), thumbPaint)
        canvas.drawText(formatFactor(factor), centerX, dp(14f), textPaint)
        canvas.drawText("+", centerX, top + dp(13f), textPaint)
        canvas.drawText("−", centerX, bottom - dp(5f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForControl) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                tracking = true
                touchAnchorY = event.y
                thumbOffsetY = 0f
                factorPerSecond = 0.0
                lastTickAtMs = SystemClock.elapsedRealtime()
                lastSentAtMs = lastTickAtMs
                lastSentFactor = factor
                removeCallbacks(zoomTicker)
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
                if (tracking) updateVelocity(event.y)
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
        thumbOffsetY = (y - touchAnchorY).coerceIn(-travel, travel)
        factorPerSecond = PocketZoomVelocity.factorPerSecond(thumbOffsetY, travel)
        invalidate()
    }

    private fun stopTracking(notify: Boolean) {
        if (!tracking && thumbOffsetY == 0f) return
        removeCallbacks(zoomTicker)
        tracking = false
        thumbOffsetY = 0f
        factorPerSecond = 0.0
        if (notify) listener?.onZoom(factor, true)
        invalidate()
    }

    private fun formatFactor(value: Double): String {
        val rounded = round(value * 10.0) / 10.0
        return "%.1f×".format(rounded)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val COMMAND_INTERVAL_MS = 50L
        const val MIN_COMMAND_FACTOR_DELTA = 0.01
    }
}
