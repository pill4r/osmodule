package dev.konraditurbe.osmosis.pocket4p

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.FILL
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
        basePaint.alpha = if (enabledForControl) 150 else 55
        thumbPaint.alpha = if (enabledForControl) 220 else 75
        canvas.drawOval(RectF(cx - radius, cy - radius, cx + radius, cy + radius), basePaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint)
        canvas.drawCircle(
            cx + stickX * radius,
            cy + stickY * radius,
            radius * 0.23f,
            thumbPaint,
        )
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
