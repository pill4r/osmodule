package dev.pillar.osmodule.pocket4p

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Small self-centering two-axis control. Values are normalized to -1…1. */
internal class PocketJoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    fun interface Listener {
        fun onStick(x: Float, y: Float, held: Boolean)
    }

    var listener: Listener? = null
    var enabledForControl: Boolean = false
        set(value) {
            field = value
            if (!value) reset(send = true)
            invalidate()
        }

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(75, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(135, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.25f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(78, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private var stickX = 0f
    private var stickY = 0f
    private var held = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val suggested = dp(144f).toInt()
        val width = resolveSize(suggested, widthMeasureSpec)
        val height = resolveSize(suggested, heightMeasureSpec)
        val size = minOf(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) * 0.43f
        val activeAlpha = if (enabledForControl) 255 else 92
        shadowPaint.alpha = (70 * activeAlpha / 255f).toInt()
        canvas.drawCircle(cx, cy + dp(3f), radius + dp(1f), shadowPaint)
        surfacePaint.shader = LinearGradient(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius,
            intArrayOf(
                Color.argb(92 * activeAlpha / 255, 255, 255, 255),
                Color.argb(62 * activeAlpha / 255, 70, 85, 100),
                Color.argb(112 * activeAlpha / 255, 10, 15, 22),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, surfacePaint)
        basePaint.alpha = (175 * activeAlpha / 255f).toInt()
        crossPaint.alpha = (90 * activeAlpha / 255f).toInt()
        thumbRingPaint.alpha = (205 * activeAlpha / 255f).toInt()
        canvas.drawOval(RectF(cx - radius, cy - radius, cx + radius, cy + radius), basePaint)
        canvas.drawCircle(cx, cy, radius * 0.62f, crossPaint)
        canvas.drawLine(cx - radius * 0.72f, cy, cx + radius * 0.72f, cy, crossPaint)
        canvas.drawLine(cx, cy - radius * 0.72f, cx, cy + radius * 0.72f, crossPaint)
        val thumbX = cx + stickX * radius
        val thumbY = cy + stickY * radius
        val thumbRadius = radius * 0.235f
        shadowPaint.alpha = (95 * activeAlpha / 255f).toInt()
        canvas.drawCircle(thumbX, thumbY + dp(2f), thumbRadius + dp(1f), shadowPaint)
        thumbPaint.shader = RadialGradient(
            thumbX - thumbRadius * 0.3f,
            thumbY - thumbRadius * 0.35f,
            thumbRadius * 1.4f,
            Color.argb(245 * activeAlpha / 255, 255, 255, 255),
            Color.argb(160 * activeAlpha / 255, 175, 196, 214),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(
            thumbX,
            thumbY,
            thumbRadius,
            thumbPaint,
        )
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbRingPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabledForControl) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                held = true
                val radius = minOf(width, height) * 0.43f
                var x = (event.x - width / 2f) / radius
                var y = (event.y - height / 2f) / radius
                val length = hypot(x, y)
                if (length > 1f) {
                    x /= length
                    y /= length
                }
                stickX = x
                stickY = y
                listener?.onStick(x, y, true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                reset(send = true)
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
        reset(send = true)
        super.onDetachedFromWindow()
    }

    private fun reset(send: Boolean) {
        val wasHeld = held
        held = false
        stickX = 0f
        stickY = 0f
        if (send && wasHeld) listener?.onStick(0f, 0f, false)
        invalidate()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
