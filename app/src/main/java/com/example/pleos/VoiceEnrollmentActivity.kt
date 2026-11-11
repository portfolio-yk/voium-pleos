package com.example.pleos

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.pleos.car.CarSignals
import com.example.pleos.stt.EnrolledSpeaker
import com.example.pleos.stt.Segment
import com.example.pleos.stt.SpeakerTheme
import com.example.pleos.stt.SpeakerThemes
import com.example.pleos.stt.SpeechmaticsSession
import com.example.pleos.stt.SpeechmaticsTokenProvider
import com.example.pleos.stt.VoiceProfile
import com.example.pleos.stt.VoiceProfileRules
import com.example.pleos.stt.VoiceProfileStore
import okhttp3.OkHttpClient

/**
 * 목소리 등록 시트 (Figma Design 영역 135:1958 시작, 135:3527 듣는 중, 135:5096 다시 요청, 135:6665 분석 중,
 * 135:8234 이름·색, 135:9803 그만두기 확인, 169:4966 사용자 지정 색).
 *
 * 1. 시작: 안내와 동의를 보여 줍니다.
 * 2. 문장 3개를 차례로 읽습니다. 인식된 글자를 문장과 맞춰 읽은 곳까지 색을 채우고, 끝까지 읽으면 다음 문장으로 넘어갑니다.
 *    한동안 더 읽히지 않으면 "주변 소리가 커서 잘 듣지 못했어요"를 보여 주고 그 문장을 다시 읽게 합니다.
 * 3. 세 문장을 다 읽으면 세션을 끝내고, Speechmatics가 돌려준 목소리 식별값 중 가장 많이 말한 화자를 고릅니다.
 * 4. 새 프로필이면 이름과 색을 고른 뒤 저장하고, 다시 등록이면 기존 이름·색으로 바로 저장합니다.
 */
class VoiceEnrollmentActivity : AppCompatActivity() {

    private enum class Phase { INTRO, CONNECTING, READING, RETRY, ANALYZING, NAMING, COLOR, MERGE, FAILED }

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private val tokenProvider by lazy { SpeechmaticsTokenProvider(BuildConfig.SPEECHMATICS_API_KEY, httpClient) }
    private val store by lazy { VoiceProfileStore(this) }
    private lateinit var audioManager: AudioManager

    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var step: View
    private lateinit var stepLabel: TextView
    private lateinit var stepProgress: LinearLayout
    private lateinit var stageIntro: View
    private lateinit var stageReading: View
    private lateinit var stageAnalyzing: View
    private lateinit var stageNaming: View
    private lateinit var stageColor: View
    private lateinit var stageMerge: View

    /** 자막에서 처음 들은 목소리로 프로필을 만들거나(GUEST_NEW) 기존 프로필에 더할 때(GUEST_MERGE) */
    private var guestMode: String? = null
    private var mergeLabel: String? = null
    private lateinit var nameInput: EditText
    private lateinit var colorPicker: ColorPicker
    private lateinit var readingPill: View
    private lateinit var readingPillIcon: ImageView
    private lateinit var readingPillLabel: TextView
    private lateinit var karaoke: KaraokeView
    private lateinit var readingHelp: TextView
    private lateinit var consent: View
    private lateinit var doneList: LinearLayout
    private lateinit var secondaryButton: TextView
    private lateinit var primaryButton: TextView

    private val sentences by lazy { resources.getStringArray(R.array.enroll_sentences).toList() }
    private var existing: VoiceProfile? = null
    private lateinit var theme: SpeakerTheme

    private var phase = Phase.INTRO
    private var session: SpeechmaticsSession? = null
    private var generation = 0
    private var audioFocusRequest: AudioFocusRequest? = null
    private var errorMessage: String? = null

    // 읽기 진행
    private var sentenceIndex = 0
    private val heardFinal = StringBuilder()
    private var heardPartial = ""
    private var sentenceFloor = 0          // 이 문장을 읽기 시작할 때까지 들린 글자 수(그 앞은 이전 문장)
    private var progress = 0               // 문장에서 읽은 곳의 끝 위치(인식 결과로 확인된 곳)
    private var guess = 0f                 // 목소리가 들리는 동안 미리 앞서 채우는 곳(화면용)
    private var sentenceStartedAt = 0L
    private var lastProgressAt = 0L
    private var advancing = false
    private val wordsBySpeaker = HashMap<String, Int>()
    private var enrolled: List<EnrolledSpeaker>? = null

    // 이름·색 (새 프로필)
    private var identifiers: List<String> = emptyList()
    private var colorPicked = false
    private var pickerColor = 0

    /** 주행 중(기어가 P가 아님)이면 목소리를 등록할 수 없습니다. 기어를 모르면 막지 않습니다. */
    private val driving get() = CarSignals.isParked == false

    // 등록하는 도중에 주행을 시작하면 녹음을 멈춥니다(Pleos Gear).
    private val carListener = CarSignals.Listener {
        if (driving && (phase == Phase.CONNECTING || phase == Phase.READING || phase == Phase.RETRY)) {
            cancelSession()
            fail(getString(R.string.enroll_driving_stopped))
        } else {
            render()
        }
    }

    private val stallCheck = object : Runnable {
        override fun run() {
            if (phase != Phase.READING) return
            val now = SystemClock.uptimeMillis()
            val heardSomething = progress > 0 || heardSinceSentence().isNotBlank()
            val stalled = heardSomething && !advancing && now - lastProgressAt > STALL_MS
            val silent = !heardSomething && now - sentenceStartedAt > SILENT_MS
            if (stalled || silent) {
                phase = Phase.RETRY
                render()
            } else {
                handler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_enrollment)
        // 설정 시트에서 열면 설정이 이미 뒤를 어둡게 덮고 있어 한 번 더 어둡게 하지 않습니다.
        val root = findViewById<View>(R.id.root)
        if (intent.getBooleanExtra(EXTRA_OVER_SHEET, false)) root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        root.setOnClickListener { requestClose() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        existing = intent.getStringExtra(EXTRA_LABEL)?.let { store.find(it) }
        // 다시 등록이면 그 사람의 색, 새 프로필이면 다른 사람이 쓰지 않는 색으로 시작합니다.
        theme = SpeakerThemes.forKey(existing?.colorKey)
            ?: SpeakerThemes.firstUnused(store.load().map { it.colorKey })

        voiceOrb = findViewById(R.id.voice_orb)
        voiceOrb.sensitivity = AppMicSensitivity
        step = findViewById(R.id.enroll_step)
        stepLabel = findViewById(R.id.enroll_step_label)
        stepProgress = findViewById(R.id.enroll_step_progress)
        stageIntro = findViewById(R.id.stage_intro)
        stageReading = findViewById(R.id.stage_reading)
        stageAnalyzing = findViewById(R.id.stage_analyzing)
        stageNaming = findViewById(R.id.stage_naming)
        stageColor = findViewById(R.id.stage_color)
        stageMerge = findViewById(R.id.stage_merge)
        nameInput = findViewById(R.id.naming_name_input)
        readingPill = findViewById(R.id.reading_pill)
        readingPillIcon = findViewById(R.id.reading_pill_icon)
        readingPillLabel = findViewById(R.id.reading_pill_label)
        karaoke = findViewById(R.id.reading_karaoke)
        readingHelp = findViewById(R.id.reading_help)
        consent = findViewById(R.id.enroll_consent)
        doneList = findViewById(R.id.enroll_done_list)
        secondaryButton = findViewById(R.id.enroll_secondary)
        primaryButton = findViewById(R.id.enroll_primary)

        findViewById<View>(R.id.enroll_close).setOnClickListener { requestClose() }
        findViewById<View>(R.id.enroll_back).setOnClickListener { goBack() }
        setupNaming()
        secondaryButton.setOnClickListener { onSecondary() }
        primaryButton.setOnClickListener { onPrimary() }
        buildIntroSteps()
        CarSignals.start(this)
        CarSignals.addListener(carListener)

        // 자막에서 처음 들은 목소리: 녹음 없이 받은 식별값으로 바로 이름·색 또는 합치기 화면을 엽니다.
        guestMode = intent.getStringExtra(EXTRA_GUEST_MODE)
        if (guestMode != null) {
            identifiers = intent.getStringArrayListExtra(EXTRA_IDENTIFIERS).orEmpty()
            phase = if (guestMode == GUEST_MERGE) Phase.MERGE else Phase.NAMING
            render()
            return
        }
        // 미리보기(디자인 확인용): adb shell am start -n com.example.pleos/.VoiceEnrollmentActivity --es preview reading|retry|analyzing|naming
        intent.getStringExtra(EXTRA_PREVIEW)?.takeIf { BuildConfig.DEBUG }?.let { showPreview(it) } ?: render()
    }

    // 백그라운드로 가면 녹음을 취소합니다. (Pleos 정책)
    override fun onPause() {
        super.onPause()
        if (phase == Phase.CONNECTING || phase == Phase.READING || phase == Phase.RETRY || phase == Phase.ANALYZING) {
            if (session != null) {
                cancelSession()
                fail(getString(R.string.enroll_interrupted))
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CarSignals.removeListener(carListener)
        cancelSession()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- 버튼

    private fun onPrimary() {
        when (phase) {
            Phase.INTRO -> checkPermissionAndStart()
            Phase.RETRY -> readAgain()
            Phase.FAILED -> restart()
            Phase.NAMING -> saveNewProfile()
            Phase.COLOR -> useCustomColor()
            Phase.MERGE -> saveMerge()
            else -> Unit
        }
    }

    private fun onSecondary() {
        when (phase) {
            Phase.READING -> readAgain()
            else -> finish()
        }
    }

    /** 뒤로: 사용자 지정 색이면 이름·색으로, 시작 화면이면 닫고, 나머지는 그만둘지 묻습니다. */
    private fun goBack() {
        if (phase == Phase.COLOR) {
            phase = Phase.NAMING
            render()
        } else {
            requestClose()
        }
    }

    /** 읽는 중이거나 이름을 정하는 중에 닫으면 그만둘지 묻습니다 (Figma 135:9803). */
    private fun requestClose() {
        // 처음 들은 목소리 등록은 녹음한 것이 없어 묻지 않고 닫습니다.
        if (guestMode != null) {
            finish()
            return
        }
        val inProgress = phase in setOf(Phase.CONNECTING, Phase.READING, Phase.RETRY, Phase.ANALYZING, Phase.NAMING, Phase.COLOR)
        if (!inProgress) {
            finish()
            return
        }
        VoiumDialog.show(
            context = this,
            title = getString(R.string.enroll_quit_title),
            description = getString(R.string.enroll_quit_message),
            confirm = getString(R.string.enroll_quit),
            cancel = getString(R.string.enroll_continue),
        ) {
            cancelSession()
            finish()
            true
        }
    }

    // ---------------------------------------------------------------- 녹음

    private fun checkPermissionAndStart() {
        if (driving) {
            render()
            return
        }
        if (!tokenProvider.isConfigured) {
            fail(getString(R.string.not_configured))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startSession()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RC_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startSession()
        else fail(getString(R.string.permission_denied))
    }

    /** 처음부터: 세션을 새로 열고 첫 문장부터 읽습니다. */
    private fun restart() {
        cancelSession()
        checkPermissionAndStart()
    }

    private fun startSession() {
        if (!requestAudioFocus()) {
            fail(getString(R.string.focus_denied))
            return
        }
        sentenceIndex = 0
        heardFinal.clear()
        heardPartial = ""
        wordsBySpeaker.clear()
        enrolled = null
        errorMessage = null
        phase = Phase.CONNECTING
        render()

        val current = ++generation
        fun isCurrent() = current == generation && !isDestroyed
        val candidate = SpeechmaticsSession(
            httpClient = httpClient,
            tokenProvider = tokenProvider::fetch,
            // 목소리 등록은 글자 정확도보다 빠른 반응이 중요해 확정 지연을 가장 짧게(0.7초) 둡니다.
            options = SpeechmaticsSession.Options(language = "ko", diarization = true, maxDelay = 0.7, getSpeakers = true),
            listener = object : SpeechmaticsSession.Listener {
                override fun onFinal(segments: List<Segment>) {
                    if (!isCurrent()) return
                    for (segment in segments) {
                        if (heardFinal.isNotEmpty()) heardFinal.append(' ')
                        heardFinal.append(segment.text)
                        val words = segment.text.split(' ').count { it.isNotBlank() }
                        wordsBySpeaker[segment.speaker] = (wordsBySpeaker[segment.speaker] ?: 0) + words
                    }
                    heardPartial = ""
                    onHeard()
                }

                override fun onPartial(segments: List<Segment>) {
                    if (!isCurrent()) return
                    heardPartial = segments.joinToString(" ") { it.text }
                    onHeard()
                }

                override fun onDecibel(dbfs: Float) {
                    if (!isCurrent()) return
                    voiceOrb.setDecibel(dbfs)
                    advanceGuess(AppMicSensitivity.levelOf(dbfs))
                }

                override fun onSpeakers(speakers: List<EnrolledSpeaker>) {
                    if (isCurrent()) enrolled = speakers
                }

                override fun onState(state: SpeechmaticsSession.State) {
                    if (!isCurrent()) return
                    when (state) {
                        SpeechmaticsSession.State.RECORDING -> startSentence(0)
                        SpeechmaticsSession.State.STOPPED -> {
                            session = null
                            releaseAudioFocus()
                            onSessionFinished()
                        }
                        else -> Unit
                    }
                }

                override fun onError(message: String) {
                    if (isCurrent()) errorMessage = message
                }
            },
        )
        session = candidate
        candidate.start()
    }

    private fun heardAll() = if (heardPartial.isEmpty()) heardFinal.toString() else "$heardFinal $heardPartial"

    private fun heardSinceSentence(): String {
        val all = heardAll()
        return all.substring(sentenceFloor.coerceAtMost(all.length))
    }

    private fun startSentence(index: Int) {
        sentenceIndex = index
        sentenceFloor = heardAll().length
        progress = 0
        guess = 0f
        advancing = false
        sentenceStartedAt = SystemClock.uptimeMillis()
        lastProgressAt = sentenceStartedAt
        karaoke.text = sentences[index]
        phase = Phase.READING
        render()
        handler.removeCallbacks(stallCheck)
        handler.postDelayed(stallCheck, 500)
    }

    /** 다시 읽기: 같은 문장을 처음부터 다시 셉니다(세션은 그대로). */
    private fun readAgain() {
        startSentence(sentenceIndex)
    }

    private fun onHeard() {
        if (phase != Phase.READING) return
        val sentence = sentences[sentenceIndex]
        val heard = heardSinceSentence()
        val read = ReadingProgress.readUpTo(sentence, heard)
        if (read > progress) {
            progress = read
            lastProgressAt = SystemClock.uptimeMillis()
            showReadingProgress()
        }
        if (!advancing && ReadingProgress.isComplete(sentence, heard)) {
            // 끝까지 읽었으면 마지막 글자까지 채운 뒤 잠시 보여 주고 다음 문장으로 넘어갑니다.
            advancing = true
            progress = sentence.length
            karaoke.setProgress(sentence.length)
            handler.postDelayed({ if (phase == Phase.READING) nextSentence() }, ADVANCE_DELAY_MS)
        }
    }

    /**
     * 인식 결과는 단어 단위로, 조금 늦게 옵니다. 그 사이 목소리가 들리면(마이크 크기) 보통 읽는 속도로
     * 조금 앞서 채워 반응이 바로 보이게 합니다. 첫 단어가 인식된 뒤부터만, 확인된 곳보다 GUESS_LEAD 글자 넘게는 앞서지 않고,
     * 문장 끝은 인식으로 확인돼야 채웁니다. 다시 요청·다음 문장 판단은 확인된 곳(progress)만 씁니다.
     */
    private fun advanceGuess(level: Float) {
        if (phase != Phase.READING || advancing) return
        if (level < VOICE_LEVEL) return
        // 첫 단어가 인식되기 전에는 채우지 않습니다(주변 소리나 "음…"에 미리 채워지는 것 방지).
        if (progress == 0) return
        val sentence = sentences[sentenceIndex]
        val limit = minOf(progress + GUESS_LEAD, sentence.length - 1).toFloat()
        guess = minOf(maxOf(guess, progress.toFloat()) + READ_CHARS_PER_TICK, limit)
        showReadingProgress()
    }

    private fun showReadingProgress() {
        karaoke.setProgress(maxOf(progress.toFloat(), guess))
    }

    private fun nextSentence() {
        if (sentenceIndex + 1 < sentences.size) {
            startSentence(sentenceIndex + 1)
        } else {
            // 세 문장을 다 읽었습니다. 세션을 끝내고 목소리 식별값(SpeakersResult)을 기다립니다.
            handler.removeCallbacks(stallCheck)
            sentenceIndex = sentences.size
            phase = Phase.ANALYZING
            render()
            session?.stop()
        }
    }

    private fun onSessionFinished() {
        if (phase != Phase.ANALYZING) {
            // 세 문장을 다 읽기 전에 세션이 끝났습니다(네트워크 오류 등).
            if (phase != Phase.FAILED && phase != Phase.INTRO) fail(errorMessage ?: getString(R.string.enroll_interrupted))
            return
        }
        val speakers = enrolled
        val chosen = chooseSpeaker(speakers.orEmpty())
        when {
            chosen == null && errorMessage != null -> fail(errorMessage!!)
            chosen == null && speakers == null -> fail(getString(R.string.enroll_failed_no_result))
            chosen == null -> fail(getString(R.string.enroll_failed_no_voice))
            existing != null -> {
                // 다시 등록: 이름·색은 그대로 두고 목소리 식별값만 바꿉니다.
                val profile = existing!!
                store.upsert(
                    profile.copy(
                        identifiers = chosen.identifiers,
                        createdAt = System.currentTimeMillis(),
                        sentences = VoiceProfileRules.ENROLL_SENTENCES,
                    ),
                )
                setResult(RESULT_OK, Intent().putExtra(RESULT_LABEL, profile.label))
                finish()
            }
            else -> {
                identifiers = chosen.identifiers
                phase = Phase.NAMING
                render()
            }
        }
    }

    /** 여러 목소리가 잡혔으면 가장 많이 말한 화자를 등록 대상으로 고릅니다. */
    private fun chooseSpeaker(speakers: List<EnrolledSpeaker>): EnrolledSpeaker? {
        if (speakers.size <= 1) return speakers.firstOrNull()
        return speakers.maxByOrNull { wordsBySpeaker[it.label] ?: 0 }
    }

    private fun fail(message: String) {
        handler.removeCallbacks(stallCheck)
        errorMessage = message
        phase = Phase.FAILED
        render()
    }

    private fun cancelSession() {
        generation++
        handler.removeCallbacks(stallCheck)
        session?.dispose()
        session = null
        releaseAudioFocus()
        if (::voiceOrb.isInitialized) voiceOrb.setLevel(0f)
    }

    // ---------------------------------------------------------------- 이름·색

    /** 이름은 직접 입력합니다. 처음에는 아직 쓰지 않은 호칭(엄마, 아빠…)을 넣어 둡니다. */
    private fun setupNaming() {
        nameInput.filters = arrayOf(InputFilter.LengthFilter(VoiceProfileRules.MAX_NAME_LENGTH))
        val others = store.load().map { it.name }.toSet()
        val presets = resources.getStringArray(R.array.profile_name_presets).toList()
        nameInput.setText(presets.firstOrNull { it !in others }.orEmpty())
        nameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                if (phase == Phase.NAMING) renderNamingState()
            }
        })
        nameInput.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                true
            } else {
                false
            }
        }
        colorPicker = ColorPicker(
            preview = findViewById(R.id.picker_preview),
            hexInput = findViewById(R.id.picker_hex),
            hue = findViewById(R.id.picker_hue),
            brightness = findViewById(R.id.picker_brightness),
            note = findViewById(R.id.picker_note),
        ) { color, usable ->
            pickerColor = color
            if (phase == Phase.COLOR) {
                voiceOrb.style = AppOrbStyle.copy(color = SpeakerThemes.custom(color).dot)
                setPrimary(R.string.color_picker_use, enabled = usable)
            }
        }
    }

    private fun hideKeyboard() {
        nameInput.clearFocus()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.hideSoftInputFromWindow(nameInput.windowToken, 0)
    }

    private fun renderNaming() {
        val swatches = findViewById<LinearLayout>(R.id.naming_swatches)
        swatches.removeAllViews()
        val colorNames = resources.getStringArray(R.array.color_names)
        SpeakerThemes.ALL.forEachIndexed { index, option ->
            val swatch = SwatchView(this, option.color)
            swatch.isSelected = option.key == theme.key
            swatch.contentDescription = getString(R.string.color_name, colorNames[index])
            swatch.setOnClickListener {
                theme = option
                colorPicked = true
                render()
            }
            swatches.addView(swatch, swatchParams(index))
        }
        val custom = SpeakerThemes.isCustom(theme.key)
        swatches.addView(CustomSwatchView(this).apply {
            customColor = if (custom) theme.color else null
            isSelected = custom
            contentDescription = getString(if (custom) R.string.color_custom else R.string.color_custom_add)
            setOnClickListener {
                hideKeyboard()
                colorPicker.setColor(if (custom) theme.color else DEFAULT_CUSTOM_COLOR)
                phase = Phase.COLOR
                render()
            }
        }, swatchParams(SpeakerThemes.ALL.size))
        findViewById<TextView>(R.id.naming_color_hint).apply {
            visibility = if (colorPicked && !custom) View.GONE else View.VISIBLE
            text = if (custom) getString(R.string.enroll_color_custom_hint, theme.key) else getString(R.string.enroll_color_auto)
        }
        findViewById<View>(R.id.naming_badge).background = VoiumUi.pill(this, ColorUtils.setAlphaComponent(theme.color, 0x1A))
        findViewById<ImageView>(R.id.naming_badge_check).setImageDrawable(VoiumUi.checkBadge(this, theme.color))
        renderNamingState()
    }

    private fun swatchParams(index: Int) = LinearLayout.LayoutParams(VoiumUi.dp(this, 76), VoiumUi.dp(this, 76)).apply {
        if (index > 0) marginStart = VoiumUi.dp(this@VoiceEnrollmentActivity, 20)
    }

    /** 이름 확인: 비었거나 겹치면 저장 버튼을 막고 이유를 보여 줍니다. */
    private fun renderNamingState() {
        val name = nameInput.text.toString().trim()
        val problem = VoiceProfileRules.validateName(name, store.load())
        val error = findViewById<TextView>(R.id.naming_name_error)
        error.text = problem.orEmpty()
        error.visibility = if (problem != null && name.isNotEmpty()) View.VISIBLE else View.GONE
        setPrimary(R.string.enroll_save, enabled = problem == null && !driving)
    }

    /** 합치기 후보 (211:4258): 아바타 · 이름 · 라디오. 고른 줄은 그 사람 색 테두리와 옅은 바탕 */
    private fun renderMerge() {
        val guestName = intent.getStringExtra(EXTRA_GUEST_NAME).orEmpty()
        findViewById<TextView>(R.id.merge_title).text = getString(R.string.merge_title, guestName)
        val options = findViewById<LinearLayout>(R.id.merge_options)
        options.removeAllViews()
        val profiles = store.load()
        profiles.forEachIndexed { index, profile ->
            val profileTheme = SpeakerThemes.forKey(profile.colorKey) ?: SpeakerThemes.ALL.first()
            val selected = profile.label == mergeLabel
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(VoiumUi.dp(context, 20), VoiumUi.dp(context, 14), VoiumUi.dp(context, 20), VoiumUi.dp(context, 14))
                background = if (selected) {
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = VoiumUi.dp(context, 20).toFloat()
                        setColor(ColorUtils.setAlphaComponent(profileTheme.color, 0x14))
                        setStroke(VoiumUi.dp(context, 3), profileTheme.color)
                    }
                } else {
                    ContextCompat.getDrawable(context, R.drawable.bg_merge_option)
                }
                isClickable = true
                isFocusable = true
                contentDescription = profile.name
                isSelected = selected
                setOnClickListener {
                    mergeLabel = profile.label
                    theme = profileTheme
                    render()
                }
            }
            row.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply {
                text = profile.name.take(1)
                gravity = Gravity.CENTER
                setTextColor(VoiumUi.color(context, android.R.color.white))
                background = VoiumUi.circle(profileTheme.color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(VoiumUi.dp(this, 52), VoiumUi.dp(this, 52)))
            row.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply {
                text = profile.name
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = VoiumUi.dp(this@VoiceEnrollmentActivity, 16) })
            row.addView(View(this).apply { background = radio(selected, profileTheme.color) }, LinearLayout.LayoutParams(VoiumUi.dp(this, 32), VoiumUi.dp(this, 32)))
            options.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = VoiumUi.dp(this@VoiceEnrollmentActivity, 10)
            })
        }
        setPrimary(R.string.merge_save, enabled = mergeLabel != null && !driving)
    }

    /** 라디오: 고르지 않으면 회색 테두리 원, 고르면 그 사람 색으로 채운 원 */
    private fun radio(selected: Boolean, color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        if (selected) {
            setColor(color)
            setStroke(VoiumUi.dp(this@VoiceEnrollmentActivity, 6), VoiumUi.color(this@VoiceEnrollmentActivity, android.R.color.white))
        } else {
            setColor(VoiumUi.color(this@VoiceEnrollmentActivity, android.R.color.white))
            setStroke(VoiumUi.dp(this@VoiceEnrollmentActivity, 2), 0xFFB4B8C0.toInt())
        }
    }

    /** 고른 사람의 목소리 식별값에 이번 목소리를 더합니다(최신 것을 뒤에). */
    private fun saveMerge() {
        if (driving) return
        val profile = mergeLabel?.let { store.find(it) } ?: return
        store.upsert(profile.copy(identifiers = (profile.identifiers + identifiers).distinct()))
        setResult(RESULT_OK, Intent().putExtra(RESULT_LABEL, profile.label).putExtra(RESULT_MERGED, true))
        finish()
    }

    private fun useCustomColor() {
        if (ColorPicker.isTooDark(pickerColor)) return
        theme = SpeakerThemes.custom(pickerColor)
        colorPicked = true
        phase = Phase.NAMING
        render()
    }

    private fun saveNewProfile() {
        if (driving) return
        val name = nameInput.text.toString().trim()
        if (VoiceProfileRules.validateName(name, store.load()) != null) return
        val label = VoiceProfileRules.newLabel()
        store.upsert(
            VoiceProfile(
                label = label,
                name = name,
                identifiers = identifiers,
                createdAt = System.currentTimeMillis(),
                colorKey = theme.key,
                sentences = VoiceProfileRules.ENROLL_SENTENCES,
            ),
        )
        setResult(RESULT_OK, Intent().putExtra(RESULT_LABEL, label))
        finish()
    }

    // ---------------------------------------------------------------- 화면

    private fun render() {
        stageIntro.visibility = if (phase == Phase.INTRO) View.VISIBLE else View.GONE
        stageReading.visibility = if (phase in READING_PHASES) View.VISIBLE else View.GONE
        stageAnalyzing.visibility = if (phase == Phase.ANALYZING) View.VISIBLE else View.GONE
        stageNaming.visibility = if (phase == Phase.NAMING) View.VISIBLE else View.GONE
        stageColor.visibility = if (phase == Phase.COLOR) View.VISIBLE else View.GONE
        stageMerge.visibility = if (phase == Phase.MERGE) View.VISIBLE else View.GONE
        // 처음 들은 목소리로 만드는 프로필은 녹음 단계가 없어 "목소리 등록 완료" 표시를 빼고 제목을 바꿉니다.
        findViewById<View>(R.id.naming_badge).visibility = if (guestMode == null) View.VISIBLE else View.GONE
        consent.visibility = if (phase == Phase.INTRO) View.VISIBLE else View.GONE
        // 왼쪽 자리는 비워 두어 버튼이 오른쪽에 붙어 있게 합니다.
        doneList.visibility = when (phase) {
            Phase.INTRO -> View.GONE
            Phase.CONNECTING, Phase.READING, Phase.RETRY, Phase.FAILED -> View.VISIBLE
            else -> View.INVISIBLE
        }
        findViewById<TextView>(R.id.enroll_title).setText(
            when {
                phase == Phase.COLOR -> R.string.color_picker_header
                guestMode == GUEST_MERGE -> R.string.merge_header
                guestMode == GUEST_NEW -> R.string.guest_new_header
                else -> R.string.enroll_title
            },
        )

        // 링: 다시 요청·실패는 회색, 사용자 지정 중이면 고르는 색, 나머지는 이 사람의 색
        val warning = phase == Phase.RETRY || phase == Phase.FAILED
        val dot = when {
            warning -> VoiumUi.color(this, R.color.voium_guest_dot)
            phase == Phase.COLOR -> SpeakerThemes.custom(pickerColor).dot
            else -> theme.dot
        }
        voiceOrb.style = AppOrbStyle.copy(color = dot)
        if (phase !in LISTENING_PHASES) voiceOrb.setLevel(0f)

        renderStep()
        renderDoneList()
        when (phase) {
            Phase.INTRO -> {
                setSecondary(R.string.cancel)
                setPrimary(R.string.enroll_begin, enabled = !driving)
                // 주행 중이면 동의 안내 자리에 "차를 세운 뒤 시작" 안내를 보여 줍니다.
                findViewById<TextView>(R.id.enroll_consent_text).apply {
                    setText(if (driving) R.string.enroll_driving else R.string.enroll_consent)
                    setTextColor(VoiumUi.color(context, if (driving) R.color.voium_warning else R.color.voium_text_quaternary))
                }
            }
            Phase.CONNECTING -> {
                setReadingPill(warning = false, label = getString(R.string.enroll_connecting_short), solid = false)
                karaoke.visibility = View.INVISIBLE
                readingHelp.setText(R.string.enroll_connecting)
                setSecondary(null)
                setPrimary(null)
            }
            Phase.READING -> {
                setReadingPill(warning = false, label = getString(R.string.enroll_listening), solid = true)
                karaoke.visibility = View.VISIBLE
                karaoke.fillColor = theme.color
                karaoke.glow = true
                karaoke.setProgress(maxOf(progress.toFloat(), guess), animate = false)
                readingHelp.setText(R.string.enroll_reading_help)
                setSecondary(R.string.enroll_restart)
                setPrimary(null)
            }
            Phase.RETRY -> {
                setReadingPill(warning = true, label = getString(R.string.enroll_retry_noise), solid = false)
                karaoke.visibility = View.VISIBLE
                karaoke.fillColor = VoiumUi.color(this, R.color.voium_warning)
                karaoke.glow = false
                // 다시 요청할 때는 미리 채운 부분을 빼고 실제로 들린 곳까지만 보여 줍니다.
                guess = 0f
                karaoke.setProgress(progress, animate = false)
                readingHelp.setText(R.string.enroll_retry_start)
                setSecondary(R.string.enroll_quit)
                setPrimary(R.string.enroll_read_again)
            }
            Phase.FAILED -> {
                setReadingPill(warning = true, label = errorMessage ?: getString(R.string.enroll_failed_no_voice), solid = false)
                karaoke.visibility = View.GONE
                readingHelp.setText(R.string.enroll_retry_start)
                setSecondary(R.string.enroll_quit)
                setPrimary(R.string.enroll_retry)
            }
            Phase.ANALYZING -> {
                setSecondary(null)
                setPrimary(null)
            }
            Phase.NAMING -> {
                setSecondary(null)
                renderNaming()
            }
            Phase.COLOR -> {
                setSecondary(null)
                setPrimary(R.string.color_picker_use, enabled = !ColorPicker.isTooDark(pickerColor))
            }
            Phase.MERGE -> {
                setSecondary(null)
                renderMerge()
            }
        }
    }

    /** "문장 2 / 3"과 세 칸 진행 막대(지금 문장 칸은 넓게). 끝나면 "완료". */
    private fun renderStep() {
        val showStep = phase != Phase.INTRO && phase != Phase.CONNECTING && phase != Phase.COLOR && guestMode == null
        step.visibility = if (showStep) View.VISIBLE else View.GONE
        if (!showStep) return
        val done = phase == Phase.ANALYZING || phase == Phase.NAMING
        stepLabel.text = if (done) getString(R.string.enroll_step_done)
        else getString(R.string.enroll_step_sentence, sentenceIndex + 1, sentences.size)
        stepProgress.removeAllViews()
        val wide = if (done) 1 else sentenceIndex
        for (i in sentences.indices) {
            val filled = done || i <= sentenceIndex
            val segment = View(this)
            segment.background = GradientDrawable().apply {
                cornerRadius = VoiumUi.dp(this@VoiceEnrollmentActivity, 5).toFloat()
                setColor(if (filled) theme.color else VoiumUi.color(this@VoiceEnrollmentActivity, R.color.voium_progress_rest))
            }
            stepProgress.addView(segment, LinearLayout.LayoutParams(VoiumUi.dp(this, if (i == wide) 72 else 40), VoiumUi.dp(this, 10)).apply {
                if (i > 0) marginStart = VoiumUi.dp(this@VoiceEnrollmentActivity, 10)
            })
        }
    }

    /** 아래쪽에 읽은 문장을 체크와 함께 보여 줍니다. */
    private fun renderDoneList() {
        doneList.removeAllViews()
        val count = if (phase == Phase.ANALYZING) sentences.size else sentenceIndex.coerceAtMost(sentences.size)
        val size = if (phase == Phase.ANALYZING) 28 else 32
        for (i in 0 until count) {
            val check = ImageView(this).apply {
                setImageDrawable(VoiumUi.checkBadge(context, theme.color))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            doneList.addView(check, LinearLayout.LayoutParams(VoiumUi.dp(this, size), VoiumUi.dp(this, size)).apply {
                if (i > 0) marginStart = VoiumUi.dp(this@VoiceEnrollmentActivity, 16)
            })
            val text = TextView(this).apply {
                setTextAppearance(R.style.Voium_Text_BodyMediumStrong)
                includeFontPadding = false
                setTextColor(VoiumUi.color(context, R.color.voium_text_quaternary))
                this.text = sentences[i]
                maxLines = 1
            }
            doneList.addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = VoiumUi.dp(this@VoiceEnrollmentActivity, 16)
            })
        }
    }

    /** 듣는 중: 화자 색 바탕 + 흰 점, 다시 요청·실패: 회색 바탕 + 주황 느낌표, 연결 중: 회색 바탕 */
    private fun setReadingPill(warning: Boolean, label: String, solid: Boolean) {
        readingPill.background = VoiumUi.pill(this, if (solid) theme.color else VoiumUi.color(this, R.color.voium_basic100))
        readingPillLabel.text = label
        readingPillLabel.setTextColor(VoiumUi.color(this, if (solid) android.R.color.white else R.color.voium_text_primary))
        val iconSize = VoiumUi.dp(this, if (warning) 30 else 18)
        readingPillIcon.layoutParams = readingPillIcon.layoutParams.apply { width = iconSize; height = iconSize }
        readingPillIcon.setImageDrawable(
            when {
                warning -> ContextCompat.getDrawable(this, R.drawable.ic_alert)
                solid -> VoiumUi.circle(VoiumUi.color(this, android.R.color.white))
                else -> VoiumUi.circle(VoiumUi.color(this, R.color.voium_guest_dot))
            },
        )
    }

    private fun setPrimary(textRes: Int?, enabled: Boolean = true) {
        primaryButton.visibility = if (textRes == null) View.GONE else View.VISIBLE
        if (textRes != null) primaryButton.setText(textRes)
        primaryButton.isEnabled = enabled
        primaryButton.alpha = if (enabled) 1f else 0.4f
    }

    private fun setSecondary(textRes: Int?) {
        secondaryButton.visibility = if (textRes == null) View.GONE else View.VISIBLE
        if (textRes != null) secondaryButton.setText(textRes)
    }

    /** 시작 화면의 두 단계 (90:1224): 회색 원 번호(52dp) + 설명(30sp) */
    private fun buildIntroSteps() {
        val container = findViewById<LinearLayout>(R.id.intro_steps)
        listOf(R.string.enroll_step1, R.string.enroll_step2).forEachIndexed { index, title ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val number = TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply {
                text = (index + 1).toString()
                gravity = Gravity.CENTER
                background = VoiumUi.circle(VoiumUi.color(context, R.color.voium_basic100))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(number, LinearLayout.LayoutParams(VoiumUi.dp(this, 52), VoiumUi.dp(this, 52)))
            row.addView(TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply { setText(title) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = VoiumUi.dp(this@VoiceEnrollmentActivity, 20)
                })
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = VoiumUi.dp(this@VoiceEnrollmentActivity, 16)
            })
        }
    }

    /** 디자인 확인용 화면(마이크·서버 없이). 실제 녹음 흐름과 같은 그리기 코드를 씁니다. */
    private fun showPreview(which: String) {
        when (which) {
            "reading", "retry" -> {
                sentenceIndex = 1
                karaoke.text = sentences[1]
                progress = if (which == "reading") "창문 조금만 열".length else "창문 조금".length
                phase = if (which == "reading") Phase.READING else Phase.RETRY
            }
            "analyzing" -> phase = Phase.ANALYZING
            "naming" -> phase = Phase.NAMING
            "color" -> {
                colorPicker.setColor(DEFAULT_CUSTOM_COLOR)
                phase = Phase.COLOR
            }
        }
        render()
        if (phase == Phase.READING || phase == Phase.RETRY) karaoke.setProgress(progress, animate = false)
    }

    // ---------------------------------------------------------------- 오디오 포커스

    private fun requestAudioFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    if (phase == Phase.CONNECTING || phase == Phase.READING || phase == Phase.RETRY) {
                        cancelSession()
                        fail(getString(R.string.enroll_interrupted))
                    }
                }
            }, handler)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) audioFocusRequest = request
        return granted
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    companion object {
        /** 다시 등록할 때 기존 프로필의 label입니다. 없으면 새 프로필을 만듭니다. */
        const val EXTRA_LABEL = "label"
        /** 설정 시트 위에서 열었는지(뒤를 한 번 더 어둡게 하지 않음) */
        const val EXTRA_OVER_SHEET = "over_sheet"
        /** 저장한 프로필의 label (결과) */
        const val RESULT_LABEL = "result_label"
        /** 기존 프로필에 합쳤는지 (결과) */
        const val RESULT_MERGED = "result_merged"
        /** 자막에서 처음 들은 목소리: GUEST_NEW(새 프로필) 또는 GUEST_MERGE(기존 프로필에 합치기) */
        const val EXTRA_GUEST_MODE = "guest_mode"
        const val GUEST_NEW = "new"
        const val GUEST_MERGE = "merge"
        /** 자막에 보였던 이름("화자 1") */
        const val EXTRA_GUEST_NAME = "guest_name"
        /** 세션이 돌려준 목소리 식별값 */
        const val EXTRA_IDENTIFIERS = "identifiers"
        private val DEFAULT_CUSTOM_COLOR = 0xFF00B8D9.toInt()
        /** 디자인 확인용 단계(reading, retry, analyzing, naming, color). 디버그 빌드에서만 씁니다. */
        const val EXTRA_PREVIEW = "preview"
        private const val RC_MIC = 101

        /** 읽기를 시작한 뒤 이만큼 더 읽히지 않으면 다시 읽기를 요청합니다. */
        private const val STALL_MS = 5_000L
        /** 문장을 보여 준 뒤 이만큼 아무것도 들리지 않으면 다시 읽기를 요청합니다. */
        private const val SILENT_MS = 10_000L
        /** 끝까지 읽은 문장을 잠시 보여 준 뒤 다음 문장으로 넘어갑니다. */
        private const val ADVANCE_DELAY_MS = 350L

        /** 미리 채우기: 이 크기(0~1) 이상이면 말하는 중으로 봅니다. */
        private const val VOICE_LEVEL = 0.15f
        /** 미리 채우기: 0.1초마다 앞서는 글자 수(초당 약 6글자, 보통 읽는 속도) */
        private const val READ_CHARS_PER_TICK = 0.6f
        /** 미리 채우기: 확인된 곳보다 최대 몇 글자 앞설지 */
        private const val GUESS_LEAD = 3

        private val READING_PHASES = setOf(Phase.CONNECTING, Phase.READING, Phase.RETRY, Phase.FAILED)
        private val LISTENING_PHASES = setOf(Phase.CONNECTING, Phase.READING, Phase.RETRY)
    }
}
