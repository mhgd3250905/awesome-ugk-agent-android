package com.ugk.pi.android.testapp

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

internal class SendActionButton(context: android.content.Context) : View(context) {
    enum class State {
        DISABLED,
        ACTIVE,
        BUSY
    }

    var buttonState: State = State.DISABLED
        set(value) {
            field = value
            invalidate()
        }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val radius = (minOf(cx, cy) - context.dp(6).toFloat()).coerceAtLeast(0f)

        // 背景圆（完全受控件自身尺寸约束，100% 圆形绝不发生边缘裁剪）
        bgPaint.color = when (buttonState) {
            State.DISABLED -> Ui.SurfaceSoft
            State.ACTIVE -> Ui.Primary
            State.BUSY -> Ui.Danger
        }
        canvas.drawCircle(cx, cy, radius, bgPaint)

        when (buttonState) {
            State.DISABLED, State.ACTIVE -> {
                iconPaint.color = if (buttonState == State.ACTIVE) Ui.OnPrimary else Ui.DisabledContent
                iconPaint.strokeWidth = radius * 0.16f

                val stemHalf = radius * 0.36f
                val topY = cy - stemHalf
                val bottomY = cy + stemHalf
                // 箭头垂直主干
                canvas.drawLine(cx, bottomY, cx, topY, iconPaint)

                // 箭头两侧翼
                val wingSpan = radius * 0.32f
                val wingLen = radius * 0.30f
                canvas.drawLine(cx - wingSpan, topY + wingLen, cx, topY, iconPaint)
                canvas.drawLine(cx + wingSpan, topY + wingLen, cx, topY, iconPaint)
            }
            State.BUSY -> {
                squarePaint.color = Ui.OnDanger
                val halfSide = radius * 0.30f
                val corner = radius * 0.08f
                canvas.drawRoundRect(
                    cx - halfSide,
                    cy - halfSide,
                    cx + halfSide,
                    cy + halfSide,
                    corner,
                    corner,
                    squarePaint
                )
            }
        }
    }
}
