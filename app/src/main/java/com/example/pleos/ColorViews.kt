package com.example.pleos

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * 가로 그라데이션 막대 위에서 손잡이를 끌어 0~1 값을 고릅니다 (Figma "사용자 지정"의 색상·밝기 막대).
 * 막대 높이 24dp, 손잡이는 지금 색으로 채운 44dp 원에 흰 테두리입니다.
 */
class GradientSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var colors: IntArray = intArrayOf(Color.BLACK, Color.WHITE)
        set(value) {
            field = value
            shaderWidth = -1f
            invalidate()
        }

    /** 손잡이 안쪽 색 */
    var thumbColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var value: Float = 0f
        private set

    var onChange: ((Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val rect = RectF()
    private var shaderWidth = -1f

    init {
        isClickable = true
        isFocusable = true
    }

    fun setValue(v: Float, notify: Boolean = false) {
        value = v.coerceIn(0f, 1f)
        invalidate()
        if (notify) onChange?.invoke(value)
    }

    private val thumbRadius get() = 22f * density
    private val left get() = thumbRadius
    private val right get() = width - thumbRadius

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val half = 12f * density
        if (shaderWidth != width.toFloat()) {
            track.shader = LinearGradient(left, 0f, right, 0f, colors, null, Shader.TileMode.CLAMP)
            shaderWidth = width.toFloat()
        }
        rect.set(0f, cy - half, width.toFloat(), cy + half)
        canvas.drawRoundRect(rect, half, half, track)
        val x = left + (right - left) * value
        canvas.drawCircle(x, cy + 2f * density, thumbRadius, shadow)
        thumbFill.color = thumbColor
        canvas.drawCircle(x, cy, thumbRadius, thumbFill)
        thumbRing.strokeWidth = 4f * density
        canvas.drawCircle(x, cy, thumbRadius - 2f * density, thumbRing)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                setValue((event.x - left) / (right - left).coerceAtLeast(1f), notify = true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    // 화면 읽기 프로그램에서는 위아래로 넘겨 5%씩 바꿉니다.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0f, 100f, value * 100f)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> setValue(value + 0.05f, notify = true)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> setValue(value - 0.05f, notify = true)
            else -> return super.performAccessibilityAction(action, arguments)
        }
        return true
    }
}

/**
 * 색 고르기 줄 마지막의 "직접 지정" 동그라미 (Figma swatch/custom).
 * 직접 고른 색이 없으면 무지개 고리 + 흰 원 + 회색 더하기, 있으면 그 색 동그라미(선택되면 고리)를 그립니다.
 * 76dp 기준: 무지개 고리 지름 56, 안쪽 흰 원 지름 39, 더하기 18×3.
 */
class CustomSwatchView(context: Context) : View(context) {
    /** 직접 고른 색. null이면 무지개 "+" 모양 */
    var customColor: Int? = null
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF6B707A.toInt() }
    private val selection = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

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
        val unit = minOf(width, height) / 76f
        val cx = width / 2f
        val cy = height / 2f
        val color = customColor
        if (color != null) {
            // SwatchView와 같은 크기: 안쪽 원 반지름 26/72, 선택 고리 34/72
            val scale = minOf(width, height) / 72f
            fill.color = color
            canvas.drawCircle(cx, cy, 26f * scale, fill)
            if (isSelected) {
                selection.color = color
                selection.strokeWidth = 4f * scale
                canvas.drawCircle(cx, cy, 34f * scale, selection)
            }
            return
        }
        val outer = 28f * unit
        val inner = 19.5f * unit
        ring.strokeWidth = outer - inner
        ring.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFFFF3B30.toInt()),
            null,
        )
        canvas.drawCircle(cx, cy, (outer + inner) / 2f, ring)
        fill.color = Color.WHITE
        canvas.drawCircle(cx, cy, inner, fill)
        val len = 9f * unit
        val thick = 1.5f * unit
        canvas.drawRoundRect(cx - len, cy - thick, cx + len, cy + thick, unit, unit, plus)
        canvas.drawRoundRect(cx - thick, cy - len, cx + thick, cy + len, unit, unit, plus)
    }
}

/**
 * "사용자 지정" 색 고르기 (Figma 169:4966, 170:1861).
 * HEX 코드 입력과 색상·밝기 막대가 서로 맞춰 움직입니다. 너무 어두운 색은 조명으로 잘 보이지 않아 쓸 수 없게 합니다.
 */
class ColorPicker(
    private val preview: View,
    private val hexInput: EditText,
    private val hue: GradientSlider,
    private val brightness: GradientSlider,
    private val note: TextView,
    /** 색이 바뀔 때마다 (색, 쓸 수 있는지) */
    private val onColor: (Int, Boolean) -> Unit,
) {
    private val hsv = floatArrayOf(0f, 1f, 1f)
    private var updatingText = false

    val color: Int get() = Color.HSVToColor(hsv)
    val usable: Boolean get() = !isTooDark(color)

    init {
        hue.colors = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        hue.onChange = { v ->
            hsv[0] = v * 360f
            if (hsv[1] < 0.05f) hsv[1] = 1f // 회색에서 색상을 움직이면 색이 보이게
            refresh(updateText = true)
        }
        brightness.onChange = { v ->
            hsv[2] = v
            refresh(updateText = true)
        }
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updatingText) return
                parseHex(s?.toString())?.let {
                    Color.colorToHSV(it, hsv)
                    refresh(updateText = false)
                }
            }
        })
    }

    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        refresh(updateText = true)
    }

    private fun refresh(updateText: Boolean) {
        val c = color
        hue.setValue(hsv[0] / 360f)
        hue.thumbColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        brightness.colors = intArrayOf(0xFF131417.toInt(), Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
        brightness.setValue(hsv[2])
        brightness.thumbColor = c
        preview.background = VoiumUi.circle(c)
        if (updateText) {
            updatingText = true
            hexInput.setText(hexOf(c))
            hexInput.setSelection(hexInput.text.length)
            updatingText = false
        }
        val ok = !isTooDark(c)
        note.setText(if (ok) R.string.color_picker_note else R.string.color_picker_too_dark)
        note.setTextColor(VoiumUi.color(note.context, if (ok) R.color.voium_picker_note else R.color.voium_danger))
        onColor(c, ok)
    }

    companion object {
        fun hexOf(color: Int) = String.format("#%06X", color and 0xFFFFFF)

        fun parseHex(text: String?): Int? {
            val clean = text?.trim()?.removePrefix("#") ?: return null
            if (clean.length != 6) return null
            val rgb = clean.toIntOrNull(16) ?: return null
            return 0xFF000000.toInt() or rgb
        }

        /** 실내 조명으로 잘 보이지 않을 만큼 어두운 색: HSV 밝기 35% 미만 */
        fun isTooDark(color: Int): Boolean {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            return (hsv[2] * 100).roundToInt() < 35
        }
    }
}
