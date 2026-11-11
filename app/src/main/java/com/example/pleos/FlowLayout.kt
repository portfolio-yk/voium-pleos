package com.example.pleos

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

/** 칩을 가로로 늘어놓다가 자리가 모자라면 다음 줄로 넘깁니다(이름 칩). 간격은 가로·세로 12dp입니다. */
class FlowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val gap = (12 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val unbounded = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val w = child.measuredWidth
            if (!unbounded && x > 0 && x + w > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            x += w + gap
            rowHeight = max(rowHeight, child.measuredHeight)
            widest = max(widest, x - gap)
        }
        val height = y + rowHeight + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val w = child.measuredWidth
            if (x > 0 && x + w > maxWidth) {
                x = 0
                y += rowHeight + gap
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + w, paddingTop + y + child.measuredHeight)
            x += w + gap
            rowHeight = max(rowHeight, child.measuredHeight)
        }
    }
}
