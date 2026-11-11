package com.example.pleos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * 색 고르기 동그라미 (Figma swatch, swatch/selected).
 * 72dp 기준 안쪽 원 반지름 26, 선택되면 같은 색 4dp 고리(반지름 34)를 두릅니다.
 */
class SwatchView(context: Context, val color: Int) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@SwatchView.color }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = this@SwatchView.color
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val unit = size / 72f
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawCircle(cx, cy, 26f * unit, fill)
        if (isSelected) {
            ring.strokeWidth = 4f * unit
            canvas.drawCircle(cx, cy, 34f * unit, ring)
        }
    }
}
