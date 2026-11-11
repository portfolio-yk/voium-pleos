package com.example.pleos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.example.pleos.car.CarSignals
import com.example.pleos.history.ConversationHistoryStore
import com.example.pleos.history.ConversationRecord
import com.example.pleos.history.ConversationSummarizer
import com.example.pleos.history.HistoryFormat
import com.example.pleos.history.SummaryState
import com.example.pleos.stt.SpeakerTheme
import com.example.pleos.stt.SpeakerThemes
import com.example.pleos.stt.VoiceProfile
import com.example.pleos.stt.VoiceProfileRules
import com.example.pleos.stt.VoiceProfileStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 설정 시트 (Figma Design 영역 113:1421 ~ 117:11750).
 *
 * 자막 화면 위에 반투명하게 뜨는 시트입니다. 왼쪽 메뉴로 프로필 · 앰비언트 라이트 · 데이터 관리를 고릅니다.
 * - 프로필: 등록된 사람 카드. 누르면 상세에서 이름·색(직접 지정 포함)을 바로 고칠 수 있습니다(바로 저장).
 * - 앰비언트 라이트: 차 안 조명 연동, 밝기, 등록된 색 미리보기(정차 중에만).
 * - 데이터 관리: 마이크 권한, 저장된 목소리 수, 개인정보 처리방침.
 * 주행 중(기어가 P가 아님)에는 프로필과 데이터 항목을 바꿀 수 없습니다.
 *
 * 설정에 있는 동안 자막은 멈춰 있습니다(자막 화면이 가려지면 마이크를 끄는 Pleos 정책).
 */
class SettingsActivity : AppCompatActivity() {

    private enum class Page { PROFILES, DETAIL, COLOR, AMBIENT, HISTORY, RECORD, DATA, MIC, PRIVACY }

    private val store by lazy { VoiceProfileStore(this) }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var navProfiles: TextView
    private lateinit var navAmbient: TextView
    private lateinit var navData: TextView
    private lateinit var navHistory: TextView
    private lateinit var historySwitch: PillSwitchView
    private val historyStore by lazy { ConversationHistoryStore(this) }
    private var recordId: String? = null
    private val historyListener: () -> Unit = { if (page == Page.HISTORY || page == Page.RECORD) render() }
    private lateinit var panels: Map<Page, View>
    private lateinit var footnote: View
    private lateinit var footnoteText: TextView
    private lateinit var toast: TextView
    private lateinit var grid: LinearLayout
    private lateinit var addButton: TextView
    private lateinit var nameInput: EditText
    private lateinit var ambientSwitch: PillSwitchView
    private lateinit var colorPicker: ColorPicker

    private var page = Page.PROFILES
    private var detailLabel: String? = null
    private var highlightLabel: String? = null
    private var bindingName = false

    // 조명 미리보기: 등록된 색을 1초씩 차례로 켭니다.
    private var previewIndex = -1
    private val previewStep = object : Runnable {
        override fun run() {
            val profiles = store.load()
            previewIndex++
            if (previewIndex >= profiles.size || !previewAllowed()) {
                stopLightPreview()
                return
            }
            CarSignals.setCabinColor(themeOf(profiles[previewIndex]).color)
            renderAmbient()
            handler.postDelayed(this, LIGHT_PREVIEW_STEP_MS)
        }
    }

    private val carListener = CarSignals.Listener {
        if (previewIndex >= 0 && !previewAllowed()) stopLightPreview()
        render()
    }

    /** 주행 중(기어가 P가 아님)이면 프로필을 편집할 수 없습니다. 기어를 모르면 막지 않습니다. */
    private val editingLocked get() = CarSignals.isParked == false

    /** 실내 조명 색을 바꿀 수 있다고 확인된 차량에서만 앰비언트 라이트 메뉴를 보여 줍니다. */
    private val ambientAvailable get() = CarSignals.lightsSupported == true

    // 목소리 등록에서 새 프로필을 저장하고 돌아오면 그 카드를 강조하고 알림을 띄웁니다.
    private val enrollLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val label = result.data?.getStringExtra(VoiceEnrollmentActivity.RESULT_LABEL) ?: return@registerForActivityResult
        val profile = store.find(label) ?: return@registerForActivityResult
        highlightLabel = label
        detailLabel = null
        page = Page.PROFILES
        render()
        showToast(getString(R.string.profile_enrolled_toast, profile.name))
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        onBackPressedDispatcher.addCallback(this, backCallback)

        navProfiles = findViewById(R.id.nav_profiles)
        navAmbient = findViewById(R.id.nav_ambient)
        navData = findViewById(R.id.nav_data)
        navHistory = findViewById(R.id.nav_history)
        historySwitch = findViewById(R.id.history_switch)
        panels = mapOf(
            Page.PROFILES to findViewById(R.id.panel_profiles),
            Page.DETAIL to findViewById(R.id.panel_detail),
            Page.COLOR to findViewById(R.id.panel_color),
            Page.AMBIENT to findViewById(R.id.panel_ambient),
            Page.HISTORY to findViewById(R.id.panel_history),
            Page.RECORD to findViewById(R.id.panel_history_detail),
            Page.DATA to findViewById(R.id.panel_data),
            Page.MIC to findViewById(R.id.panel_mic),
            Page.PRIVACY to findViewById(R.id.panel_privacy),
        )
        footnote = findViewById(R.id.footnote)
        footnoteText = findViewById(R.id.footnote_text)
        toast = findViewById(R.id.sheet_toast)
        grid = findViewById(R.id.profile_grid)
        addButton = findViewById(R.id.add_profile_button)
        nameInput = findViewById(R.id.detail_name_input)
        ambientSwitch = findViewById(R.id.ambient_switch)

        // 시트 바깥(어두운 곳)이나 닫기를 누르면 자막 화면으로 돌아갑니다.
        findViewById<View>(R.id.root).setOnClickListener { finish() }
        findViewById<View>(R.id.settings_close).setOnClickListener { finish() }
        navProfiles.setOnClickListener { open(Page.PROFILES) }
        navAmbient.setOnClickListener { open(Page.AMBIENT) }
        navData.setOnClickListener { open(Page.DATA) }
        navHistory.setOnClickListener { open(Page.HISTORY) }
        findViewById<View>(R.id.history_toggle_row).setOnClickListener {
            val on = !historySwitch.isChecked
            prefs.edit().putBoolean(KEY_HISTORY_ENABLED, on).apply()
            historySwitch.setChecked(on)
            render()
        }
        findViewById<View>(R.id.record_back).setOnClickListener { goBack() }
        findViewById<View>(R.id.record_delete).setOnClickListener { confirmDeleteRecord() }
        addButton.setOnClickListener { startEnrollment(null) }
        findViewById<View>(R.id.first_profile_button).setOnClickListener { startEnrollment(null) }
        findViewById<View>(R.id.detail_back).setOnClickListener { goBack() }
        findViewById<View>(R.id.color_back).setOnClickListener { goBack() }
        findViewById<View>(R.id.mic_back).setOnClickListener { goBack() }
        findViewById<View>(R.id.privacy_back).setOnClickListener { goBack() }
        findViewById<View>(R.id.ambient_row).setOnClickListener { setAmbientLight(!ambientSwitch.isChecked) }
        findViewById<View>(R.id.light_preview_button).setOnClickListener {
            if (previewIndex >= 0) stopLightPreview() else startLightPreview()
        }
        findViewById<View>(R.id.mic_settings_button).setOnClickListener { openAppSettings() }
        findViewById<View>(R.id.color_use).setOnClickListener { useCustomColor() }

        findViewById<VoiceOrbView>(R.id.empty_orb).style = IdleOrbStyle.copy(opacity = 0.8f)
        setupNameInput()
        setupColorPicker()
        buildBrightnessSegments()
        buildMicUses()
        buildPrivacy()
        ambientSwitch.setChecked(prefs.getBoolean(KEY_AMBIENT_LIGHT, true), animate = false)
        CarSignals.cabinBrightness = BRIGHTNESS_LEVELS[prefs.getInt(KEY_AMBIENT_BRIGHTNESS, 1).coerceIn(0, 2)]
        historySwitch.setChecked(prefs.getBoolean(KEY_HISTORY_ENABLED, true), animate = false)
        ConversationHistoryStore.addListener(historyListener)
        CarSignals.start(this)
        CarSignals.addListener(carListener)
        // 자막 화면의 "대화 기록" 칩으로 열면 바로 대화 기록을 보여 줍니다.
        if (intent.getStringExtra(EXTRA_OPEN) == OPEN_HISTORY) page = Page.HISTORY
        render()
    }

    override fun onResume() {
        super.onResume()
        // 목소리 등록·권한 설정에서 돌아오면 다시 그립니다.
        render()
    }

    override fun onPause() {
        super.onPause()
        stopLightPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        CarSignals.removeListener(carListener)
        ConversationHistoryStore.removeListener(historyListener)
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- 화면 이동

    private fun open(next: Page) {
        if (next != Page.AMBIENT) stopLightPreview()
        hideKeyboard()
        page = next
        if (next == Page.PROFILES) detailLabel = null
        render()
    }

    private fun goBack() {
        when (page) {
            Page.DETAIL -> open(Page.PROFILES)
            Page.COLOR -> open(Page.DETAIL)
            Page.MIC, Page.PRIVACY -> open(Page.DATA)
            Page.RECORD -> open(Page.HISTORY)
            else -> finish()
        }
    }

    private fun render() {
        navAmbient.visibility = if (ambientAvailable) View.VISIBLE else View.GONE
        if (page == Page.AMBIENT && !ambientAvailable) page = Page.PROFILES
        if ((page == Page.DETAIL || page == Page.COLOR) && detailProfile() == null) page = Page.PROFILES
        // 주행 중에는 지난 대화를 열어볼 수 없습니다.
        if (page == Page.RECORD && (editingLocked || recordId?.let { historyStore.find(it) } == null)) page = Page.HISTORY
        val section = when (page) {
            Page.PROFILES, Page.DETAIL, Page.COLOR -> Page.PROFILES
            Page.AMBIENT -> Page.AMBIENT
            Page.HISTORY, Page.RECORD -> Page.HISTORY
            else -> Page.DATA
        }
        mapOf(Page.PROFILES to navProfiles, Page.AMBIENT to navAmbient, Page.HISTORY to navHistory, Page.DATA to navData).forEach { (key, nav) ->
            nav.isSelected = key == section
            nav.setTextColor(VoiumUi.color(this, if (nav.isSelected) R.color.voium_text_primary else R.color.voium_text_secondary))
        }
        panels.forEach { (key, panel) -> panel.visibility = if (key == page) View.VISIBLE else View.GONE }
        when (page) {
            Page.PROFILES -> renderProfiles()
            Page.DETAIL -> renderDetail(detailProfile()!!)
            Page.COLOR -> Unit
            Page.AMBIENT -> renderAmbient()
            Page.HISTORY -> renderHistory()
            Page.RECORD -> renderRecord(historyStore.find(recordId!!)!!)
            Page.DATA -> renderData()
            Page.MIC -> renderMic()
            Page.PRIVACY -> Unit
        }
        renderFootnote()
    }

    private fun renderFootnote() {
        val text = when (page) {
            Page.PROFILES -> when {
                editingLocked -> R.string.settings_footnote_driving
                store.load().isEmpty() -> R.string.settings_footnote_empty
                else -> R.string.settings_footnote
            }
            Page.AMBIENT -> when {
                previewIndex >= 0 -> R.string.ambient_footnote_preview
                !ambientSwitch.isChecked -> R.string.ambient_footnote_off
                else -> R.string.ambient_footnote
            }
            Page.DATA -> R.string.data_footnote
            Page.MIC -> R.string.mic_footnote
            else -> null
        }
        footnote.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null) footnoteText.setText(text)
    }

    // ---------------------------------------------------------------- 프로필 목록

    private fun renderProfiles() {
        val profiles = store.load()
        grid.removeAllViews()
        profiles.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, profile ->
                val card = LayoutInflater.from(this).inflate(R.layout.item_profile_card, row, false)
                bindCard(card, profile)
                (card.layoutParams as LinearLayout.LayoutParams).marginStart = if (i == 0) 0 else VoiumUi.dp(this, 24)
                row.addView(card)
            }
            // 한 장만 있는 줄도 카드 폭이 같도록 빈 칸을 채웁니다.
            if (pair.size == 1) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = VoiumUi.dp(this@SettingsActivity, 24) })
            }
            grid.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = VoiumUi.dp(this@SettingsActivity, 24)
            })
        }
        val empty = profiles.isEmpty()
        findViewById<View>(R.id.profile_scroll).visibility = if (empty) View.GONE else View.VISIBLE
        findViewById<View>(R.id.profiles_empty).visibility = if (empty) View.VISIBLE else View.GONE
        findViewById<View>(R.id.driving_banner).visibility = if (editingLocked && !empty) View.VISIBLE else View.GONE
        addButton.visibility = if (empty) View.GONE else View.VISIBLE
        val full = profiles.size >= VoiceProfileRules.MAX_PROFILES
        setUsable(addButton, !full && !editingLocked)
        // 주행 중에는 추가 버튼을 테두리만 있는 비활성 모양으로 (113:13137)
        addButton.setBackgroundResource(if (editingLocked) R.drawable.bg_chip_outline else R.drawable.bg_button_basic_medium)
        setUsable(findViewById(R.id.first_profile_button), !editingLocked)
        addButton.text = if (full) getString(R.string.profile_full, VoiceProfileRules.MAX_PROFILES) else getString(R.string.profile_add)
    }

    private fun bindCard(card: View, profile: VoiceProfile) {
        val theme = themeOf(profile)
        card.findViewById<TextView>(R.id.card_avatar).apply {
            text = profile.name.take(1)
            background = VoiumUi.circle(theme.color)
        }
        card.findViewById<TextView>(R.id.card_name).text = profile.name
        card.findViewById<View>(R.id.card_chevron).visibility = if (editingLocked) View.GONE else View.VISIBLE
        if (profile.label == highlightLabel) {
            card.background = (ContextCompat.getDrawable(this, R.drawable.bg_card_highlight)!!.mutate() as android.graphics.drawable.GradientDrawable)
                .apply { setStroke(VoiumUi.dp(this@SettingsActivity, 2), theme.color) }
        }
        card.contentDescription = profile.name
        card.isClickable = !editingLocked
        card.setOnClickListener {
            if (editingLocked) return@setOnClickListener
            detailLabel = profile.label
            open(Page.DETAIL)
        }
    }

    private fun themeOf(profile: VoiceProfile): SpeakerTheme = SpeakerThemes.forKey(profile.colorKey) ?: SpeakerThemes.ALL.first()

    private fun detailProfile() = detailLabel?.let { store.find(it) }

    // ---------------------------------------------------------------- 프로필 상세

    private fun renderDetail(profile: VoiceProfile) {
        val theme = themeOf(profile)
        bindDetailHeader(profile.name, theme)
        if (!nameInput.hasFocus() && nameInput.text.toString() != profile.name) {
            bindingName = true
            nameInput.setText(profile.name)
            bindingName = false
        }
        nameInput.isEnabled = !editingLocked
        renderNameClear()

        // 색: 기본 6가지 + 직접 지정
        val swatches = findViewById<LinearLayout>(R.id.detail_swatches)
        swatches.removeAllViews()
        val colorNames = resources.getStringArray(R.array.color_names)
        SpeakerThemes.ALL.forEachIndexed { index, option ->
            val swatch = SwatchView(this, option.color)
            swatch.isSelected = option.key == theme.key
            swatch.contentDescription = getString(R.string.color_name, colorNames[index])
            swatch.setOnClickListener { saveProfile(profile.copy(colorKey = option.key)) }
            setUsable(swatch, !editingLocked)
            swatches.addView(swatch, swatchParams(68, index))
        }
        val custom = CustomSwatchView(this).apply {
            customColor = if (SpeakerThemes.isCustom(profile.colorKey)) theme.color else null
            isSelected = SpeakerThemes.isCustom(profile.colorKey)
            contentDescription = getString(if (customColor != null) R.string.color_custom else R.string.color_custom_add)
            setOnClickListener {
                colorPicker.setColor(if (SpeakerThemes.isCustom(profile.colorKey)) theme.color else DEFAULT_CUSTOM_COLOR)
                open(Page.COLOR)
            }
        }
        setUsable(custom, !editingLocked)
        swatches.addView(custom, swatchParams(68, SpeakerThemes.ALL.size))

        // 목소리
        findViewById<VoiceOrbView>(R.id.detail_voice_orb).style = AppOrbStyle.copy(color = theme.dot, dotMinRatio = 0.012f, dotMaxRatio = 0.02f, pointCount = 140)
        val date = SimpleDateFormat("M월 d일", Locale.KOREAN).format(Date(profile.createdAt))
        findViewById<TextView>(R.id.detail_voice_title).setText(R.string.voice_registered)
        findViewById<TextView>(R.id.detail_voice_detail).text = if (profile.needsReenroll) {
            getString(R.string.voice_reenroll_hint)
        } else {
            getString(R.string.voice_detail, profile.sentences, date)
        }
        findViewById<View>(R.id.detail_reenroll).setOnClickListener { startEnrollment(profile.label) }
        findViewById<View>(R.id.detail_delete).setOnClickListener { confirmDelete(profile) }
        setUsable(findViewById(R.id.detail_reenroll), !editingLocked)
        setUsable(findViewById(R.id.detail_delete), !editingLocked)
    }

    private fun bindDetailHeader(name: String, theme: SpeakerTheme) {
        findViewById<TextView>(R.id.detail_title).text = name
        findViewById<TextView>(R.id.detail_name).text = name
        findViewById<TextView>(R.id.detail_avatar).apply {
            text = name.take(1)
            background = VoiumUi.circle(theme.color)
        }
    }

    private fun swatchParams(sizeDp: Int, index: Int) =
        LinearLayout.LayoutParams(VoiumUi.dp(this, sizeDp), VoiumUi.dp(this, sizeDp)).apply {
            if (index > 0) marginStart = VoiumUi.dp(this@SettingsActivity, 16)
        }

    /** 이름은 입력하는 대로 확인하고, 문제없으면 바로 저장합니다(Figma "변경 사항은 바로 저장됩니다"). */
    private fun setupNameInput() {
        nameInput.filters = arrayOf(InputFilter.LengthFilter(VoiceProfileRules.MAX_NAME_LENGTH))
        val error = findViewById<TextView>(R.id.detail_name_error)
        nameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderNameClear()
                if (bindingName) return
                val profile = detailProfile() ?: return
                val name = s.toString().trim()
                val problem = VoiceProfileRules.validateName(name, store.load(), profile.label)
                error.text = problem.orEmpty()
                error.visibility = if (problem == null || name.isEmpty()) View.GONE else View.VISIBLE
                bindDetailHeader(name.ifEmpty { profile.name }, themeOf(profile))
                if (problem == null && !editingLocked && name != profile.name) store.upsert(profile.copy(name = name))
            }
        })
        nameInput.setOnFocusChangeListener { _, _ -> renderNameClear() }
        nameInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                render()
                true
            } else {
                false
            }
        }
        findViewById<View>(R.id.detail_name_clear).setOnClickListener { nameInput.setText("") }
    }

    private fun renderNameClear() {
        findViewById<View>(R.id.detail_name_clear).visibility =
            if (nameInput.hasFocus() && nameInput.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        if (!::nameInput.isInitialized) return
        nameInput.clearFocus()
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(nameInput.windowToken, 0)
    }

    private fun saveProfile(profile: VoiceProfile) {
        if (editingLocked) return
        if (VoiceProfileRules.validateName(profile.name, store.load(), profile.label) != null) return
        store.upsert(profile)
        render()
    }

    private fun confirmDelete(profile: VoiceProfile) {
        VoiumDialog.show(
            context = this,
            title = getString(R.string.profile_delete_title),
            description = getString(R.string.profile_delete_message),
            confirm = getString(R.string.delete),
        ) {
            store.delete(profile.label)
            open(Page.PROFILES)
            showToast(getString(R.string.profile_deleted))
            true
        }
    }

    private fun startEnrollment(label: String?) {
        if (editingLocked) return
        enrollLauncher.launch(
            Intent(this, VoiceEnrollmentActivity::class.java)
                .putExtra(VoiceEnrollmentActivity.EXTRA_LABEL, label)
                .putExtra(VoiceEnrollmentActivity.EXTRA_OVER_SHEET, true),
        )
    }

    // ---------------------------------------------------------------- 사용자 지정 색

    private fun setupColorPicker() {
        val use = findViewById<TextView>(R.id.color_use)
        colorPicker = ColorPicker(
            preview = findViewById(R.id.picker_preview),
            hexInput = findViewById(R.id.picker_hex),
            hue = findViewById(R.id.picker_hue),
            brightness = findViewById(R.id.picker_brightness),
            note = findViewById(R.id.picker_note),
        ) { _, usable -> setUsable(use, usable) }
    }

    private fun useCustomColor() {
        val profile = detailProfile() ?: return
        if (!colorPicker.usable || editingLocked) return
        store.upsert(profile.copy(colorKey = SpeakerThemes.custom(colorPicker.color).key))
        open(Page.DETAIL)
    }

    // ---------------------------------------------------------------- 앰비언트 라이트

    private fun setAmbientLight(on: Boolean) {
        prefs.edit().putBoolean(KEY_AMBIENT_LIGHT, on).apply()
        ambientSwitch.setChecked(on)
        if (!on) stopLightPreview()
        render()
    }

    private fun buildBrightnessSegments() {
        val container = findViewById<LinearLayout>(R.id.brightness_segments)
        listOf(R.string.ambient_brightness_dim, R.string.ambient_brightness_normal, R.string.ambient_brightness_bright)
            .forEachIndexed { index, label ->
                val item = TextView(this, null, 0, R.style.Voium_Text_BodyMediumStrong).apply {
                    setText(label)
                    setBackgroundResource(R.drawable.bg_segment_item)
                    setPadding(VoiumUi.dp(context, 28), VoiumUi.dp(context, 12), VoiumUi.dp(context, 28), VoiumUi.dp(context, 12))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        prefs.edit().putInt(KEY_AMBIENT_BRIGHTNESS, index).apply()
                        CarSignals.cabinBrightness = BRIGHTNESS_LEVELS[index]
                        renderAmbient()
                    }
                }
                container.addView(item, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    if (index > 0) marginStart = VoiumUi.dp(this@SettingsActivity, 4)
                })
            }
    }

    private fun renderAmbient() {
        val on = ambientSwitch.isChecked
        val previewing = previewIndex >= 0
        findViewById<TextView>(R.id.ambient_hint).setText(
            when {
                previewing -> R.string.ambient_hint_previewing
                on -> R.string.ambient_hint_on
                else -> R.string.ambient_hint_off
            },
        )
        val row = findViewById<View>(R.id.ambient_row)
        row.contentDescription = getString(R.string.ambient_follow_speaker)
        ViewCompat.setStateDescription(row, getString(if (on) R.string.state_on else R.string.state_off))

        // 연동을 끄면 밝기와 미리보기는 흐리게 (117:3540)
        val brightnessRow = findViewById<View>(R.id.brightness_row)
        brightnessRow.alpha = if (on) 1f else 0.4f
        findViewById<View>(R.id.brightness_hint).visibility = if (on) View.VISIBLE else View.GONE
        val segments = findViewById<LinearLayout>(R.id.brightness_segments)
        val level = prefs.getInt(KEY_AMBIENT_BRIGHTNESS, 1)
        for (i in 0 until segments.childCount) {
            val item = segments.getChildAt(i) as TextView
            item.isSelected = i == level
            item.isEnabled = on
            item.setTextColor(VoiumUi.color(this, if (item.isSelected) R.color.voium_text_primary else R.color.voium_text_secondary))
        }

        val profiles = store.load()
        val previewRow = findViewById<View>(R.id.preview_row)
        val canPreview = on && previewAllowed() && profiles.isNotEmpty()
        previewRow.alpha = if (on) 1f else 0.4f
        findViewById<View>(R.id.light_preview_hint).visibility = if (on) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.light_preview_title).setText(if (previewing) R.string.ambient_preview_running else R.string.ambient_preview)
        val count = findViewById<TextView>(R.id.light_preview_count)
        val button = findViewById<TextView>(R.id.light_preview_button)
        if (previewing && previewIndex < profiles.size) {
            findViewById<TextView>(R.id.light_preview_hint).text = getString(R.string.ambient_preview_now, profiles[previewIndex].name)
            count.text = getString(R.string.ambient_preview_count, previewIndex + 1, profiles.size)
            count.visibility = View.VISIBLE
            button.setText(R.string.ambient_preview_stop)
            button.setBackgroundResource(R.drawable.bg_button_filled_small)
            button.setTextColor(VoiumUi.color(this, android.R.color.white))
            setUsable(button, true)
        } else {
            findViewById<TextView>(R.id.light_preview_hint).setText(R.string.ambient_preview_hint)
            count.visibility = View.GONE
            button.setText(R.string.ambient_preview_start)
            button.setBackgroundResource(R.drawable.bg_button_basic_small)
            button.setTextColor(VoiumUi.color(this, R.color.voium_text_primary))
            button.isEnabled = canPreview
            button.alpha = if (canPreview || !on) 1f else 0.4f
        }
        renderFootnote()
    }

    private fun previewAllowed() = CarSignals.isParked != false && ambientAvailable

    private fun startLightPreview() {
        if (store.load().isEmpty() || !previewAllowed() || !ambientSwitch.isChecked) return
        previewIndex = -1
        handler.removeCallbacks(previewStep)
        previewStep.run()
    }

    private fun stopLightPreview() {
        if (previewIndex < 0) return
        handler.removeCallbacks(previewStep)
        previewIndex = -1
        CarSignals.setCabinColor(null)
        if (::ambientSwitch.isInitialized && page == Page.AMBIENT) renderAmbient()
    }

    // ---------------------------------------------------------------- 대화 기록

    private fun renderHistory() {
        val records = historyStore.load()
        val on = historySwitch.isChecked
        val notice = findViewById<TextView>(R.id.history_notice)
        when {
            editingLocked && records.isNotEmpty() -> {
                notice.setText(R.string.history_driving_notice)
                notice.setBackgroundResource(R.drawable.bg_driving_banner)
                notice.setTextColor(VoiumUi.color(this, R.color.voium_driving_text))
                notice.visibility = View.VISIBLE
            }
            !on && records.isNotEmpty() -> {
                notice.setText(R.string.history_off_notice)
                notice.setBackgroundResource(R.drawable.bg_voice_box)
                notice.setTextColor(VoiumUi.color(this, R.color.voium_text_primary))
                notice.visibility = View.VISIBLE
            }
            else -> notice.visibility = View.GONE
        }
        val row = findViewById<View>(R.id.history_toggle_row)
        row.contentDescription = getString(R.string.history_save)
        ViewCompat.setStateDescription(row, getString(if (on) R.string.state_on else R.string.state_off))

        findViewById<View>(R.id.history_empty).visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.history_scroll).visibility = if (records.isEmpty()) View.GONE else View.VISIBLE
        val list = findViewById<LinearLayout>(R.id.history_list)
        list.removeAllViews()
        // 저장을 껐거나 주행 중이면 목록을 흐리게 (153:3631, 153:10232)
        list.alpha = if (!on || editingLocked) 0.5f else 1f
        records.forEachIndexed { index, record ->
            list.addView(recordItem(record), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = VoiumUi.dp(this@SettingsActivity, 12)
            })
        }
    }

    /** 기록 한 건 (153:3576): 날짜·시간 · 말한 사람 · AI 요약 한 줄 · 오른쪽 화살표 */
    private fun recordItem(record: ConversationRecord): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_record_item)
            setPadding(VoiumUi.dp(context, 32), VoiumUi.dp(context, 20), VoiumUi.dp(context, 24), VoiumUi.dp(context, 20))
            isClickable = !editingLocked
            isFocusable = true
            setOnClickListener {
                if (editingLocked) return@setOnClickListener
                recordId = record.id
                open(Page.RECORD)
            }
        }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val whenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        whenRow.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply { text = HistoryFormat.day(record.startedAt) }, wrap())
        whenRow.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
            text = "${HistoryFormat.range(record)} · ${HistoryFormat.duration(record.startedAt, record.endedAt)}"
            setTextColor(VoiumUi.color(context, R.color.voium_text_quaternary))
        }, wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 12) })
        info.addView(whenRow, wrap())
        info.addView(peopleRow(record), wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 8) })
        info.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLarge).apply {
            text = summaryLine(record)
            setTextColor(VoiumUi.color(context, R.color.voium_text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = VoiumUi.dp(this@SettingsActivity, 8)
        })
        item.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!editingLocked) {
            item.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(VoiumUi.dp(this, 40), VoiumUi.dp(this, 40)).apply { marginStart = VoiumUi.dp(this@SettingsActivity, 24) })
        }
        item.contentDescription = "${HistoryFormat.day(record.startedAt)} ${HistoryFormat.range(record)}, ${summaryLine(record)}"
        return item
    }

    /** "✦ AI 요약  휴게소에 들러…" (요약이 없으면 상태 안내) */
    private fun summaryLine(record: ConversationRecord): CharSequence {
        val label = "✦ ${getString(R.string.ai_summary)}"
        val body = when (record.summaryState) {
            SummaryState.DONE -> record.headline.orEmpty()
            SummaryState.PENDING -> getString(R.string.ai_summary_list_pending)
            SummaryState.FAILED -> record.lines.firstOrNull()?.text ?: getString(R.string.ai_summary_failed)
        }
        val text = if (record.summaryState == SummaryState.FAILED) body else "$label  $body"
        return android.text.SpannableString(text).apply {
            if (record.summaryState != SummaryState.FAILED) {
                setSpan(android.text.style.ForegroundColorSpan(AI_BLUE), 0, label.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    /** 말한 사람 태그 줄: 색 점 + 이름, 그 사람 색 12% 바탕 (등록하지 않은 사람은 회색) */
    private fun peopleRow(record: ConversationRecord): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        record.participants.forEachIndexed { i, (name, colorKey) ->
            row.addView(speakerTag(name, colorKey), wrap().apply { if (i > 0) marginStart = VoiumUi.dp(this@SettingsActivity, 8) })
        }
        return row
    }

    private fun speakerTag(name: String, colorKey: String?): View {
        val theme = SpeakerThemes.forKey(colorKey)
        val color = theme?.color ?: VoiumUi.color(this, R.color.voium_guest_dot)
        val text = theme?.text ?: VoiumUi.color(this, R.color.voium_text_secondary)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = VoiumUi.pill(context, if (theme != null) VoiumUi.tint12(color) else VoiumUi.color(context, R.color.voium_basic100))
            setPadding(VoiumUi.dp(context, 10), VoiumUi.dp(context, 4), VoiumUi.dp(context, 14), VoiumUi.dp(context, 4))
            addView(View(context).apply { background = VoiumUi.circle(color) }, LinearLayout.LayoutParams(VoiumUi.dp(context, 14), VoiumUi.dp(context, 14)))
            addView(TextView(context, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
                this.text = name
                setTextColor(text)
            }, wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 6) })
        }
    }

    /** 기록 상세 (153:6886): 제목·삭제, 길이·줄 수·말한 사람, AI 요약, 대화 원문 */
    private fun renderRecord(record: ConversationRecord) {
        findViewById<TextView>(R.id.record_title).text = "${HistoryFormat.day(record.startedAt)} · ${HistoryFormat.range(record)}"
        val meta = findViewById<LinearLayout>(R.id.record_meta)
        meta.removeAllViews()
        meta.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
            text = getString(R.string.history_meta, HistoryFormat.duration(record.startedAt, record.endedAt), record.lines.size)
            setTextColor(VoiumUi.color(context, R.color.voium_text_quaternary))
        }, wrap())
        record.participants.forEach { (name, colorKey) ->
            meta.addView(speakerTag(name, colorKey), wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 10) })
        }
        renderSummaryBox(record)

        val transcript = findViewById<LinearLayout>(R.id.record_transcript)
        transcript.removeAllViews()
        record.lines.forEachIndexed { i, line ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
                text = HistoryFormat.timeOf(line.at)
                setTextColor(VoiumUi.color(context, R.color.voium_text_quaternary))
            }, LinearLayout.LayoutParams(VoiumUi.dp(this, 130), LinearLayout.LayoutParams.WRAP_CONTENT))
            row.addView(speakerTag(line.speaker, line.colorKey), wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 16) })
            row.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply { text = line.text },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = VoiumUi.dp(this@SettingsActivity, 16) })
            transcript.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = VoiumUi.dp(this@SettingsActivity, 18)
            })
        }
    }

    private fun renderSummaryBox(record: ConversationRecord) {
        val box = findViewById<LinearLayout>(R.id.record_summary)
        box.removeAllViews()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.ic_ai_spark) }, LinearLayout.LayoutParams(VoiumUi.dp(this, 26), VoiumUi.dp(this, 26)))
        head.addView(TextView(this, null, 0, R.style.Voium_Text_BodyMediumStrong).apply {
            setText(R.string.ai_summary)
            setTextColor(AI_BLUE)
        }, wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 10) })
        head.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
            setText(
                when (record.summaryState) {
                    SummaryState.DONE -> R.string.ai_summary_auto
                    SummaryState.PENDING -> R.string.ai_summary_pending
                    SummaryState.FAILED -> R.string.ai_summary_failed
                },
            )
            textSize = 22f
            setTextColor(0xFF8C919E.toInt())
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 10) })
        box.addView(head, wrap())

        when (record.summaryState) {
            SummaryState.DONE -> {
                record.summary.forEach { point ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(View(this).apply { background = VoiumUi.circle(VoiumUi.color(context, R.color.voium_text_primary)) },
                        LinearLayout.LayoutParams(VoiumUi.dp(this, 8), VoiumUi.dp(this, 8)))
                    row.addView(TextView(this, null, 0, R.style.Voium_Text_BodyMediumStrong).apply {
                        text = point
                        lineHeight = VoiumUi.dp(context, 38)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = VoiumUi.dp(this@SettingsActivity, 14) })
                    box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = VoiumUi.dp(this@SettingsActivity, 8)
                    })
                }
                box.addView(footnoteText(R.string.ai_summary_disclaimer), wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 14) })
            }
            SummaryState.PENDING -> {
                // 요약 생성 중 (173:1915): 회색 막대 세 줄
                listOf(0.8f, 0.6f, 0.7f).forEach { ratio ->
                    box.addView(View(this).apply { setBackgroundResource(R.drawable.bg_skeleton) },
                        LinearLayout.LayoutParams((VoiumUi.dp(this, 970) * ratio).toInt(), VoiumUi.dp(this, 20)).apply {
                            topMargin = VoiumUi.dp(this@SettingsActivity, 16)
                        })
                }
                box.addView(footnoteText(R.string.ai_summary_pending_note), wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 16) })
            }
            SummaryState.FAILED -> {
                box.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
                    setText(R.string.ai_summary_retry)
                    setBackgroundResource(R.drawable.bg_button_basic_small)
                    gravity = Gravity.CENTER
                    setPadding(VoiumUi.dp(context, 24), 0, VoiumUi.dp(context, 24), 0)
                    setOnClickListener { ConversationSummarizer.summarize(this@SettingsActivity, record.id) }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, VoiumUi.dp(this, 64)).apply {
                    topMargin = VoiumUi.dp(this@SettingsActivity, 16)
                })
            }
        }
    }

    private fun footnoteText(res: Int) = TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
        setText(res)
        textSize = 20f
        setTextColor(0xFF999EA8.toInt())
    }

    private fun confirmDeleteRecord() {
        val id = recordId ?: return
        VoiumDialog.show(
            context = this,
            title = getString(R.string.history_delete_title),
            confirm = getString(R.string.delete),
        ) {
            historyStore.delete(id)
            recordId = null
            open(Page.HISTORY)
            showToast(getString(R.string.history_deleted))
            true
        }
    }

    // ---------------------------------------------------------------- 데이터 관리

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun renderData() {
        val list = findViewById<LinearLayout>(R.id.data_list)
        list.removeAllViews()
        addDataRow(list, R.string.data_mic, R.string.data_mic_hint, getString(if (hasMic()) R.string.data_mic_allowed else R.string.data_mic_denied)) {
            open(Page.MIC)
        }
        addDivider(list)
        addDataRow(list, R.string.data_voices, R.string.data_voices_hint, getString(R.string.data_voices_count, store.load().size)) {
            open(Page.PROFILES)
        }
        addDivider(list)
        addDataRow(list, R.string.data_history, R.string.data_history_hint, getString(R.string.data_history_value), chevron = false) {
            open(Page.HISTORY)
        }
        addDivider(list)
        addDataRow(list, R.string.data_privacy, null, null) { open(Page.PRIVACY) }
    }

    /** 데이터 관리 한 줄 (Figma row/마이크 권한): 제목·설명 · 값 · 오른쪽 화살표 */
    private fun addDataRow(list: LinearLayout, title: Int, hint: Int?, value: String?, chevron: Boolean = true, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_settings_row)
            setPadding(VoiumUi.dp(context, 36), VoiumUi.dp(context, 16), VoiumUi.dp(context, 32), VoiumUi.dp(context, 16))
            minimumHeight = VoiumUi.dp(context, if (hint == null) 72 else 108)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply { setText(title) }, wrap())
        if (hint != null) {
            texts.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply {
                setText(hint)
                setTextColor(VoiumUi.color(context, R.color.voium_text_quaternary))
            }, wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 4) })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            row.addView(TextView(this, null, 0, R.style.Voium_Text_BodyMediumStrong).apply {
                text = value
                setTextColor(VoiumUi.color(context, R.color.voium_text_secondary))
            }, wrap().apply { marginStart = VoiumUi.dp(this@SettingsActivity, 24) })
        }
        if (chevron) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(VoiumUi.dp(this, 40), VoiumUi.dp(this, 40)).apply { marginStart = VoiumUi.dp(this@SettingsActivity, 8) })
        }
        row.contentDescription = listOfNotNull(getString(title), value).joinToString(", ")
        list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun addDivider(list: LinearLayout) {
        list.addView(View(this).apply { setBackgroundColor(VoiumUi.color(context, R.color.voium_divider)) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, VoiumUi.dp(this, 2)))
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    /** 마이크 권한 (117:10121): 허용됨이면 초록 상자, 아니면 빨간 상자 */
    private fun renderMic() {
        val ok = hasMic()
        findViewById<View>(R.id.mic_status_box).setBackgroundResource(if (ok) R.drawable.bg_permission_ok else R.drawable.bg_chip_error)
        findViewById<View>(R.id.mic_status_box).background.mutate().let {
            if (it is android.graphics.drawable.GradientDrawable) it.cornerRadius = VoiumUi.dp(this, 24).toFloat()
        }
        val color = VoiumUi.color(this, if (ok) R.color.voium_listening_text else R.color.voium_error_text)
        findViewById<ImageView>(R.id.mic_status_icon).apply {
            setImageResource(if (ok) R.drawable.ic_mic else R.drawable.ic_mic_off)
            VoiumUi.tint(this, color)
        }
        findViewById<TextView>(R.id.mic_status_title).apply {
            setText(if (ok) R.string.data_mic_allowed else R.string.data_mic_denied)
            setTextColor(color)
        }
        findViewById<TextView>(R.id.mic_status_detail).setText(if (ok) R.string.mic_allowed_detail else R.string.mic_denied_detail)
    }

    private fun buildMicUses() {
        val list = findViewById<LinearLayout>(R.id.mic_uses)
        listOf(
            R.string.mic_use_caption to R.string.mic_use_caption_detail,
            R.string.mic_use_enroll to R.string.mic_use_enroll_detail,
            R.string.mic_use_pause to R.string.mic_use_pause_detail,
        ).forEach { (title, detail) ->
            list.addView(TextView(this, null, 0, R.style.Voium_Text_BodySmallStrong).apply { setText(title) },
                wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 20) })
            list.addView(TextView(this, null, 0, R.style.Voium_Text_BodyMediumStrong).apply {
                setText(detail)
                setTextColor(VoiumUi.color(context, R.color.voium_text_secondary))
                textFontWeight400()
            }, wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 2) })
        }
    }

    private fun TextView.textFontWeight400() {
        typeface = android.graphics.Typeface.create(typeface, 400, false)
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    /** 개인정보 처리방침 (117:11750): 네 항목 */
    private fun buildPrivacy() {
        val body = findViewById<LinearLayout>(R.id.privacy_body)
        listOf(
            R.string.privacy_1_title to R.string.privacy_1_body,
            R.string.privacy_2_title to R.string.privacy_2_body,
            R.string.privacy_3_title to R.string.privacy_3_body,
            R.string.privacy_4_title to R.string.privacy_4_body,
        ).forEach { (title, text) ->
            body.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply { setText(title) },
                wrap().apply { topMargin = VoiumUi.dp(this@SettingsActivity, 32) })
            body.addView(TextView(this, null, 0, R.style.Voium_Text_BodyMediumStrong).apply {
                setText(text)
                setTextColor(VoiumUi.color(context, R.color.voium_text_secondary))
                textFontWeight400()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = VoiumUi.dp(this@SettingsActivity, 8)
            })
        }
    }

    // ---------------------------------------------------------------- 공통

    private fun setUsable(view: View, usable: Boolean) {
        view.isEnabled = usable
        view.alpha = if (usable) 1f else 0.4f
    }

    private val hideToast = Runnable { toast.visibility = View.GONE }

    private fun showToast(message: String) {
        handler.removeCallbacks(hideToast)
        toast.text = message
        toast.visibility = View.VISIBLE
        handler.postDelayed(hideToast, 2_500)
    }

    companion object {
        const val PREFS = "caption_screen"
        const val KEY_AMBIENT_LIGHT = "ambient_light"
        /** 조명 밝기: 0 어둡게, 1 보통, 2 밝게 */
        const val KEY_AMBIENT_BRIGHTNESS = "ambient_brightness"
        /** 대화 기록 저장 (기본 켜짐) */
        const val KEY_HISTORY_ENABLED = "history_enabled"
        /** 처음 열 화면 */
        const val EXTRA_OPEN = "open"
        const val OPEN_HISTORY = "history"
        private const val AI_BLUE = 0xFF3D6BF0.toInt()
        val BRIGHTNESS_LEVELS = floatArrayOf(0.45f, 0.75f, 1f)
        /** 자막 화면의 탑승 인원(예전 상태 줄용, 지금은 쓰지 않음) */
        const val EXTRA_PASSENGERS = "passengers"
        /** 지금 대화에 나온 등록된 사람의 label 목록 */
        const val EXTRA_PRESENT_LABELS = "present_labels"

        private const val LIGHT_PREVIEW_STEP_MS = 1_000L
        private val DEFAULT_CUSTOM_COLOR = 0xFF00B8D9.toInt()
    }
}
