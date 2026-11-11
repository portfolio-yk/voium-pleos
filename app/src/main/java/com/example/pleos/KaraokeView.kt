package com.example.pleos

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils

/**
 * 목소리 등록에서 읽을 문장 (Figma karaoke): 72sp ExtraBold 문장, 읽은 곳까지 색이 채워지고
 * 그 위치에 공(●)이 따라가며, 아래 6dp 막대도 같은 위치까지 채워집니다.
 */
class KaraokeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 72f * resources.displayMetrics.scaledDensity
        typeface = ResourcesCompat.getFont(context, R.font.asta_sans)?.let { Typeface.create(it, 800, false) }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var animator: ValueAnimator? = null

    var text: String = ""
        set(value) {
            field = value
            shownChars = 0f
            requestLayout()
            invalidate()
        }

    /** 채워진 색(화자 색, 다시 요청 때는 주황) */
    var fillColor: Int = 0xFFE0457B.toInt()
        set(value) { field = value; invalidate() }

    /** 공 둘레의 옅은 빛(듣는 중에만) */
    var glow: Boolean = true
        set(value) { field = value; invalidate() }

    private val restColor = 0xFFBFC3CC.toInt()
    private var shownChars = 0f

    fun setProgress(chars: Int, animate: Boolean = true) = setProgress(chars.toFloat(), animate)

    /** 앞에서부터 읽은 글자 수(소수 가능)를 짧게 따라갑니다. 0.1초마다 불려도 끊기지 않도록 짧게 움직입니다. */
    fun setProgress(chars: Float, animate: Boolean = true) {
        val target = chars.coerceIn(0f, text.length.toFloat())
        if (target == shownChars && animator == null) return
        animator?.cancel()
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            shownChars = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(shownChars, target).apply {
            duration = 120
            interpolator = DecelerateInterpolator()
            addUpdateListener { shownChars = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { animator = null }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = textPaint.measureText(text).toInt() + (8 * density).toInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize((176 * density).toInt(), heightMeasureSpec))
    }

    /** 앞에서 chars 글자까지의 너비(소수 글자는 다음 글자 너비를 나눠 셈) */
    private fun xAt(chars: Float): Float {
        val whole = chars.toInt().coerceIn(0, text.length)
        val base = textPaint.measureText(text, 0, whole)
        if (whole >= text.length) return base
        val next = textPaint.measureText(text, whole, whole + 1)
        return base + next * (chars - whole)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val textWidth = textPaint.measureText(text)
        val progressX = xAt(shownChars)

        // 글자: 줄 높이 92sp 칸(위에서 36dp)의 가운데에 맞춥니다.
        val lineTop = 36 * density
        val lineHeight = 92 * resources.displayMetrics.scaledDensity
        val metrics = textPaint.fontMetrics
        val baseline = lineTop + (lineHeight - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent
        canvas.save()
        canvas.clipRect(0f, 0f, progressX, height.toFloat())
        textPaint.color = fillColor
        canvas.drawText(text, 0f, baseline, textPaint)
        canvas.restore()
        canvas.save()
        canvas.clipRect(progressX, 0f, width.toFloat(), height.toFloat())
        textPaint.color = restColor
        canvas.drawText(text, 0f, baseline, textPaint)
        canvas.restore()

        // 막대: 위에서 158dp, 높이 6dp
        val trackTop = 158 * density
        val radius = 3 * density
        paint.color = ColorUtils.setAlphaComponent(restColor, 0x80)
        rect.set(0f, trackTop, textWidth, trackTop + 6 * density)
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (progressX > 0f) {
            paint.color = fillColor
            rect.set(0f, trackTop, progressX, trackTop + 6 * density)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        // 공: 읽은 위치 위(가운데 높이 14dp), 듣는 중에는 옅은 빛을 두릅니다.
        if (progressX > 0f && shownChars < text.length) {
            val cy = 14 * density
            if (glow) {
                paint.color = ColorUtils.setAlphaComponent(fillColor, 0x2E)
                canvas.drawCircle(progressX, cy, 20 * density, paint)
            }
            paint.color = fillColor
            canvas.drawCircle(progressX, cy, 10 * density, paint)
        }
    }
}
