package com.jarvis.assistant

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

class ArcReactorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { IDLE, LISTENING, SPEAKING }

    private var state: State = State.IDLE
    private var angle = 0f
    private var pulsePhase = 0f

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#00E5FF")
    }

    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        color = Color.parseColor("#80F3FF")
    }

    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#00B8D4")
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E5FF")
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(65, 0, 229, 255)
    }

    private val rect = RectF()

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 6000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            pulsePhase += when (state) {
                State.IDLE -> 0.05f
                State.LISTENING -> 0.18f
                State.SPEAKING -> 0.28f
            }
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    fun setState(newState: State) {
        state = newState
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.88f

        val pulse = (sin(pulsePhase.toDouble()).toFloat() + 1f) / 2f
        val glowScale = when (state) {
            State.IDLE -> 0.35f + pulse * 0.08f
            State.LISTENING -> 0.45f + pulse * 0.25f
            State.SPEAKING -> 0.50f + pulse * 0.32f
        }

        glowPaint.color = when (state) {
            State.IDLE -> Color.argb((45 + pulse * 35).toInt(), 0, 229, 255)
            State.LISTENING -> Color.argb((80 + pulse * 90).toInt(), 0, 255, 198)
            State.SPEAKING -> Color.argb((90 + pulse * 100).toInt(), 0, 229, 255)
        }
        canvas.drawCircle(cx, cy, maxR * glowScale * 1.4f, glowPaint)

        canvas.drawCircle(cx, cy, maxR, outerRingPaint)

        val segRadius = maxR * 0.78f
        rect.set(cx - segRadius, cy - segRadius, cx + segRadius, cy + segRadius)
        canvas.save()
        canvas.rotate(angle, cx, cy)
        for (i in 0 until 8) {
            canvas.drawArc(rect, i * 45f + 6f, 26f, false, segmentPaint)
        }
        canvas.restore()

        val innerR = maxR * 0.56f
        rect.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        canvas.save()
        canvas.rotate(-angle * 1.5f, cx, cy)
        for (i in 0 until 4) {
            canvas.drawArc(rect, i * 90f + 10f, 65f, false, innerRingPaint)
        }
        canvas.restore()

        val coreR = maxR * (0.26f + pulse * if (state == State.IDLE) 0.03f else 0.09f)
        canvas.drawCircle(cx, cy, coreR, corePaint)
    }
}
