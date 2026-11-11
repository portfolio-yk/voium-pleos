package com.example.pleos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.pleos.car.CarSignals
import com.example.pleos.stt.SpeakerThemes

/**
 * 처음 실행할 때 한 번 보여 주는 소개 (Figma 155:2103, 155:5151, 155:8202, 216:4120).
 *
 * 1. 소개 → 2. 마이크(허용하면 시스템 권한 창) → 3. 앰비언트 라이트 켜고/끄고 시작.
 * 차량이 실내 조명 색을 바꿀 수 없으면 3쪽은 건너뜁니다. 끝나면 다시 보이지 않습니다.
 */
class OnboardingActivity : AppCompatActivity() {

    private enum class Page { INTRO, MIC, LIGHT }

    private val prefs by lazy { getSharedPreferences(SettingsActivity.PREFS, Context.MODE_PRIVATE) }
    private var page = Page.INTRO

    private lateinit var glow: GlowView
    private lateinit var orb: VoiceOrbView
    private lateinit var label: TextView
    private lateinit var title: TextView
    private lateinit var desc: TextView
    private lateinit var tags: LinearLayout
    private lateinit var primary: TextView
    private lateinit var secondary: TextView
    private lateinit var pager: LinearLayout
    private lateinit var note: View

    private val showLightPage get() = CarSignals.lightsSupported != false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        glow = findViewById(R.id.onboarding_glow)
        orb = findViewById(R.id.onboarding_orb)
        label = findViewById(R.id.onboarding_label)
        title = findViewById(R.id.onboarding_title)
        desc = findViewById(R.id.onboarding_desc)
        tags = findViewById(R.id.onboarding_tags)
        primary = findViewById(R.id.onboarding_primary)
        secondary = findViewById(R.id.onboarding_secondary)
        pager = findViewById(R.id.onboarding_pager)
        note = findViewById(R.id.onboarding_note)
        orb.sensitivity = AppMicSensitivity
        primary.setOnClickListener { onPrimary() }
        secondary.setOnClickListener { onSecondary() }
        // 뒤로 가기는 앞 쪽으로, 첫 쪽에서는 앱을 닫지 않고 그대로 둡니다.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page != Page.INTRO) show(Page.entries[page.ordinal - 1])
            }
        })
        buildTags()
        CarSignals.start(this)
        show(Page.INTRO)
    }

    private fun onPrimary() {
        when (page) {
            Page.INTRO -> show(Page.MIC)
            Page.MIC -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    afterMic()
                } else {
                    // 시스템 권한 창 (216:4120 "VOIUM에서 오디오를 녹음하도록 허용하시겠습니까?")
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
                }
            }
            Page.LIGHT -> finishOnboarding(ambient = true)
        }
    }

    private fun onSecondary() {
        when (page) {
            Page.MIC -> afterMic()
            Page.LIGHT -> finishOnboarding(ambient = false)
            else -> Unit
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_MIC) afterMic()
    }

    private fun afterMic() {
        if (showLightPage) show(Page.LIGHT) else finishOnboarding(ambient = prefs.getBoolean(SettingsActivity.KEY_AMBIENT_LIGHT, true))
    }

    private fun finishOnboarding(ambient: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DONE, true)
            .putBoolean(SettingsActivity.KEY_AMBIENT_LIGHT, ambient)
            .apply()
        finish()
    }

    private fun show(next: Page) {
        page = next
        val pink = SpeakerThemes.ALL[4].color
        val (labelText, titleText, descText) = when (next) {
            Page.INTRO -> Triple(R.string.onboarding_intro_label, R.string.onboarding_intro_title, null)
            Page.MIC -> Triple(R.string.onboarding_mic_label, R.string.onboarding_mic_title, R.string.onboarding_mic_desc)
            Page.LIGHT -> Triple(R.string.onboarding_light_label, R.string.onboarding_light_title, R.string.onboarding_light_desc)
        }
        label.setText(labelText)
        title.setText(titleText)
        desc.visibility = if (descText == null) View.GONE else View.VISIBLE
        if (descText != null) desc.setText(descText)
        tags.visibility = if (next == Page.LIGHT) View.VISIBLE else View.GONE
        note.visibility = if (next == Page.MIC) View.VISIBLE else View.GONE

        // 마이크 쪽은 파란 빛과 하늘색 링, 나머지는 보라·분홍 빛과 분홍 링
        if (next == Page.MIC) {
            glow.colors = 0xFF3D6BF0.toInt() to 0xFF3D6BF0.toInt()
            orb.style = AppOrbStyle.copy(color = 0xFF8EA6FF.toInt())
        } else {
            glow.colors = 0xFF8B5CF6.toInt() to 0xFFE0457B.toInt()
            orb.style = AppOrbStyle.copy(color = pink)
        }

        when (next) {
            Page.INTRO -> {
                secondary.visibility = View.GONE
                primary.setText(R.string.onboarding_start)
            }
            Page.MIC -> {
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.onboarding_later)
                primary.setText(R.string.onboarding_allow_mic)
            }
            Page.LIGHT -> {
                secondary.visibility = View.VISIBLE
                secondary.setText(R.string.onboarding_light_off)
                primary.setText(R.string.onboarding_light_on)
            }
        }
        renderPager()
        title.announceForAccessibility(title.text)
    }

    private fun renderPager() {
        val count = if (showLightPage) 3 else 2
        pager.removeAllViews()
        for (i in 0 until count) {
            val active = i == page.ordinal
            pager.addView(View(this).apply {
                background = VoiumUi.pill(context, if (active) Color.WHITE else ColorUtils.setAlphaComponent(Color.WHITE, 0x4D))
            }, LinearLayout.LayoutParams(VoiumUi.dp(this, if (active) 36 else 10), VoiumUi.dp(this, 10)).apply {
                if (i > 0) marginStart = VoiumUi.dp(this@OnboardingActivity, 10)
            })
        }
        pager.contentDescription = getString(R.string.onboarding_page, page.ordinal + 1, count)
    }

    /** 3쪽의 예시 태그 (155:11240): 나 · 엄마 · 민준 · 할머니 */
    private fun buildTags() {
        listOf("나" to 0, "엄마" to 1, "민준" to 2, "할머니" to 3).forEachIndexed { i, (name, colorIndex) ->
            val color = SpeakerThemes.ALL[colorIndex].color
            tags.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = VoiumUi.pill(context, ColorUtils.setAlphaComponent(color, 0x38))
                setPadding(VoiumUi.dp(context, 14), VoiumUi.dp(context, 8), VoiumUi.dp(context, 18), VoiumUi.dp(context, 8))
                addView(View(context).apply { background = VoiumUi.circle(color) }, LinearLayout.LayoutParams(VoiumUi.dp(context, 18), VoiumUi.dp(context, 18)))
                addView(TextView(context, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
                    text = name
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = VoiumUi.dp(this@OnboardingActivity, 8)
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = VoiumUi.dp(this@OnboardingActivity, 12)
            })
        }
    }

    companion object {
        const val KEY_DONE = "onboarding_done"
        private const val RC_MIC = 201
    }
}
