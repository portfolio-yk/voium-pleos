package com.example.pleos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Figma ivi_switch (Medium): 80×48dp, 둥근 모서리 16dp, 안쪽 여백 8dp, 32dp 흰 손잡이(모서리 8dp).
 * 누르는 동작은 감싸는 칩이 받고, 이 뷰는 켜짐/꺼짐 모양만 그립니다.
 */
class PillSwitchView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val onColor = ContextCompat.getColor(context, R.color.voium_switch_on)
    private val offColor = ContextCompat.getColor(context, R.color.voium_switch_off)
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()
    private var position = 0f // 0 = 꺼짐, 1 = 켜짐
    private var animator: ValueAnimator? = null

    var isChecked = false
        private set

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setChecked(checked: Boolean, animate: Boolean = true) {
        if (checked == isChecked && animator == null) return
        isChecked = checked
        animator?.cancel()
        val target = if (checked) 1f else 0f
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            position = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(position, target).apply {
            duration = 180
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { animator = null }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        track.color = ColorUtils.blendARGB(offColor, onColor, position)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 16 * density, 16 * density, track)
        val pad = 8 * density
        val size = 32 * density
        val left = pad + position * (width - pad * 2 - size)
        val top = (height - size) / 2
        rect.set(left, top, left + size, top + size)
        canvas.drawRoundRect(rect, 8 * density, 8 * density, knob)
    }
}
