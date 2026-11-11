package com.example.pleos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * 온보딩 배경의 흐린 빛 두 덩어리 (Figma glow: 지름 760 원, 32% 불투명, 흐림 110).
 * 위치는 1700×996 화면 기준이며 화면 폭에 맞춰 늘입니다.
 */
class GlowView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 왼쪽 위 빛과 오른쪽 아래 빛의 색 */
    var colors: Pair<Int, Int> = 0xFF8B5CF6.toInt() to 0xFFE0457B.toInt()
        set(value) {
            field = value
            invalidate()
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = width / 1700f
        draw(canvas, 760f * scale, 340f * scale, scale, colors.first)
        draw(canvas, 940f * scale, 420f * scale, scale, colors.second)
    }

    private fun draw(canvas: Canvas, cx: Float, cy: Float, scale: Float, color: Int) {
        // 반지름 380 원을 110만큼 흐리게 한 모양: 가운데 32%, 바깥 600 근처에서 사라짐
        val radius = 600f * scale
        paint.shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(ColorUtils.setAlphaComponent(color, 82), ColorUtils.setAlphaComponent(color, 70), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)
    }
}
