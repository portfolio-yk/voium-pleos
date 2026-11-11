package com.example.pleos

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.pleos.stt.SpeakerTheme

/** VOIUM 화면들이 함께 쓰는 작은 그리기 도우미입니다. */
object VoiumUi {

    fun dp(context: Context, value: Number): Int = (value.toFloat() * context.resources.displayMetrics.density).toInt()

    fun color(context: Context, id: Int) = ContextCompat.getColor(context, id)

    fun pill(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 999f * context.resources.displayMetrics.density
        setColor(color)
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** 태그·배지 배경: 화자 색의 12% */
    fun tint12(color: Int) = ColorUtils.setAlphaComponent(color, 0x1F)

    /** 화자 색 원 위에 흰 체크(Figma ic_check). */
    fun checkBadge(context: Context, color: Int): Drawable =
        LayerDrawable(arrayOf(circle(color).apply { setSize(dp(context, 32), dp(context, 32)) },
            ContextCompat.getDrawable(context, R.drawable.ic_check_mark)!!))
            .apply { setLayerInset(0, dp(context, 2.67f), dp(context, 2.67f), dp(context, 2.67f), dp(context, 2.67f)) }

    /** 화자 태그(점 + 이름): 등록된 사람은 화자 색, 아니면 회색(Figma speaker_tag). */
    fun bindSpeakerTag(tag: View, dot: View, label: TextView, theme: SpeakerTheme?, text: String) {
        val context = tag.context
        if (theme != null) {
            tag.background = pill(context, tint12(theme.color))
            dot.background = circle(theme.color)
            label.setTextColor(theme.text)
        } else {
            tag.background = pill(context, color(context, R.color.voium_basic100))
            dot.background = circle(color(context, R.color.voium_guest_dot))
            label.setTextColor(color(context, R.color.voium_text_secondary))
        }
        label.text = text
    }

    enum class Badge { RIDING, REGISTERED, REENROLL }

    /** 프로필 상태 배지(include_badge). */
    fun bindBadge(badge: View, kind: Badge, theme: SpeakerTheme) {
        val context = badge.context
        val dot = badge.findViewById<View>(R.id.badge_dot)
        val label = badge.findViewById<TextView>(R.id.badge_label)
        when (kind) {
            Badge.RIDING -> {
                badge.background = pill(context, tint12(theme.color))
                badge.setPaddingRelative(dp(context, 14), dp(context, 2), dp(context, 16), dp(context, 2))
                dot.visibility = View.VISIBLE
                dot.background = circle(theme.color)
                label.setTextColor(theme.text)
                label.setText(R.string.badge_riding)
            }
            Badge.REENROLL -> {
                badge.background = pill(context, color(context, R.color.voium_basic100))
                badge.setPaddingRelative(dp(context, 14), dp(context, 2), dp(context, 16), dp(context, 2))
                dot.visibility = View.VISIBLE
                dot.background = circle(color(context, R.color.voium_text_quaternary))
                label.setTextColor(color(context, R.color.voium_text_secondary))
                label.setText(R.string.badge_reenroll)
            }
            Badge.REGISTERED -> {
                badge.background = null
                badge.setPaddingRelative(0, dp(context, 2), dp(context, 16), dp(context, 2))
                dot.visibility = View.GONE
                label.setTextColor(color(context, R.color.voium_text_quaternary))
                label.setText(R.string.badge_registered)
            }
        }
    }

    /** 목소리 모양 막대(Figma voice_signature). */
    fun fillSignature(container: LinearLayout, heightsDp: IntArray, widthDp: Int, gapDp: Int, radiusDp: Int, color: Int) {
        val context = container.context
        container.removeAllViews()
        heightsDp.forEachIndexed { index, height ->
            val bar = View(context)
            bar.background = GradientDrawable().apply {
                cornerRadius = dp(context, radiusDp).toFloat()
                setColor(color)
            }
            container.addView(bar, LinearLayout.LayoutParams(dp(context, widthDp), dp(context, height)).apply {
                if (index > 0) marginStart = dp(context, gapDp)
            })
        }
    }

    /** 이름 칩: 선택=검정 바탕 흰 글자, 기본=흰 바탕 테두리, 다른 사람이 쓰는 이름=흐린 글자. */
    fun styleChip(chip: TextView, selected: Boolean, enabled: Boolean) {
        val context = chip.context
        chip.isSelected = selected
        chip.isEnabled = enabled
        chip.background = if (selected) {
            pill(context, color(context, R.color.voium_text_primary))
        } else {
            ContextCompat.getDrawable(context, R.drawable.bg_chip_outline)
        }
        chip.setTextColor(
            color(context, when {
                selected -> android.R.color.white
                !enabled -> R.color.voium_text_quaternary
                else -> R.color.voium_text_primary
            }),
        )
    }

    /** 상태 줄의 설정 칩 아이콘 색: 선택(검정 칩)이면 흰색. */
    fun tint(icon: ImageView, color: Int) {
        icon.imageTintList = ColorStateList.valueOf(color)
    }
}
