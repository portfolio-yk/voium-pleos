package com.example.pleos

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.text.LineBreakConfig
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.graphics.Color
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.pleos.ai.ReplyAi
import com.example.pleos.ai.ReplySpeaker
import com.example.pleos.ai.SuggestionAi
import com.example.pleos.car.CarSignals
import com.example.pleos.history.ConversationHistoryStore
import com.example.pleos.history.ConversationRecord
import com.example.pleos.history.ConversationSummarizer
import com.example.pleos.history.RecordLine
import com.example.pleos.stt.EnrolledSpeaker
import com.example.pleos.stt.ConversationTranscript
import com.example.pleos.stt.Scheduler
import com.example.pleos.stt.PleosTextFusion
import com.example.pleos.stt.PleosTextRecognizer
import com.example.pleos.stt.Segment
import com.example.pleos.stt.SpeakerDirectory
import com.example.pleos.stt.SpeakerTheme
import com.example.pleos.stt.SpeakerThemeController
import com.example.pleos.stt.SpeakerThemes
import com.example.pleos.stt.SpeechmaticsSession
import com.example.pleos.stt.SpeechmaticsTokenProvider
import com.example.pleos.stt.Turn
import com.example.pleos.stt.VoiceProfile
import com.example.pleos.stt.VoiceProfileStore
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 자동차 안의 실시간 화자 자막 화면입니다. Figma "Design" 영역(219:11863)의 자막 화면들을 따릅니다.
 *
 * - 대기 중에는 "대화를 시작할까요?"와 대화 시작 버튼, 듣는 중에는 자막, 통화 중에는 멈춘 이유, 오류면 오류 안내를 보여 줍니다.
 * - 지금 말하는 사람의 문단을 크게 보여 주고, 바로 앞의 문단들은 위에 흐리게 남깁니다.
 * - 중간 결과는 같은 자리에서 고쳐지고 확정 결과로 바뀝니다.
 * - 오른쪽 입자 링은 마이크 소리에 따라 출렁이고, 앰비언트 라이트를 켜면 말하는 사람의 색이 됩니다.
 * - 등록된 사람은 이름과 프로필 색, 등록하지 않은 사람은 "화자 1"처럼 회색으로 표시합니다.
 * - 대화가 없으면 시간대에 맞는 대화 제안을 보여 줍니다.
 * - API 키가 없으면 예시 대화를 재생하는 미리보기 모드로 동작합니다.
 */
class MainActivity : AppCompatActivity() {

    /** RECONNECTING: 듣기는 계속하면서 끊긴 연결을 다시 잇는 중(그동안 한 말은 보관했다가 보냄) */
    private enum class Phase { STOPPED, CONNECTING, RECORDING, RECONNECTING, STOPPING, ERROR, PREVIEW }

    /** 지난 문단 한 줄 (item_caption_line) */
    private class LineRow(
        val root: View,
        val tag: View,
        val tagDot: View,
        val tagLabel: TextView,
        val text: TextView,
        val time: TextView,
    )

    /** 화면에 보이는 화자 정보입니다. 등록된 사람만 고유 색을 씁니다. */
    private data class SpeakerVisual(val label: String, val isRegistered: Boolean, val theme: SpeakerTheme)

    private lateinit var root: View
    private lateinit var content: View
    private lateinit var voiceOrb: VoiceOrbView
    private lateinit var statusRow: StatusRow
    private val statusButton get() = statusRow.statusButton
    private val statusIcon get() = statusRow.statusIcon
    private lateinit var previousLines: LinearLayout
    private lateinit var previousScroll: android.widget.ScrollView
    /** 손님이 지난 대화를 끌어 올려 보는 중이면, 잠시 새 줄을 따라 내려가지 않습니다. */
    private var previousDraggedAt = 0L
    private lateinit var currentCaption: View
    private lateinit var speakerPill: View
    private lateinit var speakerPillLabel: TextView
    private lateinit var currentTime: TextView
    private lateinit var currentScroll: ScrollView
    private lateinit var currentText: TextView
    private lateinit var suggestion: View
    private lateinit var suggestionContext: TextView
    private lateinit var suggestionText: TextView
    private lateinit var suggestionDots: LinearLayout
    private lateinit var suggestionIcon: ImageView
    private lateinit var suggestionContextRow: View
    private lateinit var quickReplyBar: View
    private lateinit var quickReplies: LinearLayout

    // 빠른 답장: 누르면 차량 스피커로 읽고(Pleos TTS), 다른 사람이 물으면 AI가 답장 3개를 추천합니다.
    private var replySpeaking: String? = null
    /** 다 읽은 뒤 스피커 소리의 끝자락(울림)이 마이크에 잡히지 않도록 잠시 더 무음으로 둡니다. */
    private var replyEchoHold = false
    private val releaseEchoHold = Runnable {
        replyEchoHold = false
        updateMicMute()
    }
    private var replyState = ReplyAi.State.NONE
    /** 들어오며 움직인 추천 묶음(같은 묶음은 다시 그려도 움직이지 않음) */
    private var animatedReplies: List<String>? = null
    private val replySpeaker by lazy {
        ReplySpeaker(this, object : ReplySpeaker.Listener {
            override fun onSpeakingChanged(text: String?) {
                replySpeaking = text
                handler.removeCallbacks(releaseEchoHold)
                if (text == null) {
                    replyEchoHold = true
                    handler.postDelayed(releaseEchoHold, REPLY_ECHO_HOLD_MS)
                }
                // 읽는 동안에는 그 소리가 다시 자막이 되지 않게 마이크 입력을 무음으로 둡니다.
                updateMicMute()
                renderQuickReplies()
            }
        })
    }
    private val replyAi by lazy {
        ReplyAi(this) { state ->
            replyState = state
            renderQuickReplies()
        }
    }
    private var quickReplyCount = 0
    private lateinit var notice: TextView
    private lateinit var startPanel: View
    private lateinit var errorPanel: View
    private lateinit var errorTitle: TextView
    private lateinit var errorSubtitle: TextView
    private lateinit var errorButton: TextView
    private lateinit var helpOverlay: View
    private lateinit var guestCard: View
    private lateinit var audioManager: AudioManager
    private lateinit var atmosphere: SpeakerThemeController

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val tokenProvider by lazy {
        SpeechmaticsTokenProvider(BuildConfig.SPEECHMATICS_API_KEY, httpClient)
    }
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val transcript = ConversationTranscript()
    // 문단마다 처음 화면에 나타난 시각("오후 12:41")
    private val turnTimes = HashMap<String, Long>()
    private val timeFormat = SimpleDateFormat("a h:mm", Locale.KOREAN)
    private val lineRows = ArrayList<LineRow>()

    // 자막 애니메이션: 화면에 보이는 문단을 기억해 바뀐 부분만 움직입니다.
    private val lineTurnIds = ArrayList<String?>()
    private val easeOut = DecelerateInterpolator(2f)

    // 실시간 자막 깜빡임 방지 (renderTurns 참고)
    private var shownTurnCount = 0          // 지금까지 화면에 떠오른 문단 수
    private var shownTurnId: String? = null // 큰 자막에 보이는 문단
    private var shownText = ""              // 큰 자막에 보이는 글자
    private var textChangedAt = 0L
    private var pendingTurnId: String? = null
    private var pendingSince = 0L
    private var lastPartialAt = 0L
    private var nextRenderAt = 0L

    // 새로 나타난 글자만 서서히 진해지게 합니다. 글자마다 나타나기 시작한 시각을 기억해서,
    // 이미 보이던 글자는 다시 흐려지지 않고, 진해지던 글자는 이어서 진해집니다.
    private var charFadeStart = LongArray(0) // 0이면 이미 다 보이는 글자
    private val seenWords = HashSet<String>()   // 이 문단에서 이미 보였던 단어(다시 서서히 나타나지 않음)
    private var fadeFrameScheduled = false
    private val fadeFrame = Runnable {
        fadeFrameScheduled = false
        drawCaptionText()
    }
    private val delayedRender = Runnable {
        nextRenderAt = 0L
        renderTurns()
    }
    private val profileStore by lazy { VoiceProfileStore(this) }
    private val speakerDirectory = SpeakerDirectory()
    private var phase = Phase.STOPPED
    private var isPreview = false
    private var session: SpeechmaticsSession? = null
    private var generation = 0
    private var microphoneHasStarted = false
    private var errorMessage: String? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var focusPaused = false     // 다른 기능이 잠깐 오디오를 가져감(포커스 일시 손실)
    private var inCall = false          // 통화 중(블루투스 통화 음성이 자막에 섞이지 않게)
    private var micSilenced = false     // 시스템이 이 앱의 마이크 입력을 막음
    private var pausedForAudio = false
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private val hideNotice = Runnable { notice.visibility = View.GONE }

    // 블롭 색
    private var ambientLight = true

    // 확정된 자막만 보기: 말하는 동안 바뀌는 중간 결과를 숨기고 확정된 글자만 보여 줍니다.
    // "듣는 중" 칩을 길게 누르면 바뀝니다(비교용).
    private var finalOnly = false
    private var activeTheme = SpeakerThemes.NEUTRAL
    // 대화 기록: 대화 한 건(대화 시작~대화종료)마다 하나씩 저장합니다.
    private val historyStore by lazy { ConversationHistoryStore(this) }
    private var conversationId = ConversationRecord.newId()
    private var conversationStartedAt = 0L
    private var conversationSaved = false

    /** 이번 대화에서 등록하지 않은 사람의 목소리 식별값(세션이 끝날 때 받음). label → 식별값 */
    private val guestVoices = LinkedHashMap<String, List<String>>()

    /** 낯선 목소리 제안 카드에 차례로 보여 줄 사람들 */
    private data class GuestVoice(val name: String, val identifiers: List<String>, val sample: String, val sampleTime: String)
    private val pendingGuests = ArrayList<GuestVoice>()

    private val guestLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val label = result.data?.getStringExtra(VoiceEnrollmentActivity.RESULT_LABEL) ?: return@registerForActivityResult
        val profile = profileStore.find(label) ?: return@registerForActivityResult
        if (pendingGuests.isNotEmpty()) pendingGuests.removeAt(0)
        val merged = result.data?.getBooleanExtra(VoiceEnrollmentActivity.RESULT_MERGED, false) == true
        showNotice(getString(if (merged) R.string.guest_merged_toast else R.string.profile_enrolled_toast, profile.name))
        renderStatus()
    }

    private var orbColor = IdleOrbStyle.color
    private var orbOpacity = IdleOrbStyle.opacity
    private var orbMode: OrbMode? = null
    private var orbColorAnimator: ValueAnimator? = null

    // 대화 제안
    private var suggestionIndex = 0
    private var suggestionShowing = false
    private var lastTopic: String? = null
    private var suggestionRotating = false
    private val rotateSuggestion = object : Runnable {
        override fun run() {
            suggestionIndex++
            renderSuggestion(animate = true)
            handler.postDelayed(this, SUGGESTION_INTERVAL_MS)
        }
    }

    // 미리보기에는 마이크가 없어서, 예시 화자가 말하는 동안 소리 크기를 흉내 내 블롭을 움직입니다.
    private val previewPulse = object : Runnable {
        override fun run() {
            if (!isPreview) return
            val speaking = transcript.getTurns().lastOrNull()?.isPartial == true
            voiceOrb.setLevel(if (speaking) 0.45f + Random.nextFloat() * 0.35f else 0f)
            handler.postDelayed(this, 100)
        }
    }

    private val isConfigured get() = tokenProvider.isConfigured

    /** 자막 화면이 앞에 있는지. 뒤로 가 있을 때는 실내 조명을 바꾸지 않습니다. */
    private var screenActive = false

    // Pleos 차량 정보: 좌석 수가 바뀌면 탑승 인원을, 내비 목적지가 바뀌면 대화 제안을 다시 그립니다.
    private val carListener = CarSignals.Listener {
        // 대화 제안(내비 목적지·비)을 다시 그립니다.
        renderStatus()
        if (suggestion.visibility == View.VISIBLE && !isAudioPaused()) renderSuggestion(animate = false)
    }

    // 오디오 포커스를 잃으면(다른 앱의 음성 기능 등) 자막을 멈춥니다. (Pleos 정책)
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            handler.post {
                if (focusPaused) {
                    focusPaused = false
                    applyPause()
                }
            }
        }
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            handler.post {
                if (isLive()) {
                    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        // 음성 비서 등이 잠깐 오디오를 쓰는 동안은 멈췄다가, 돌려받으면 이어서 듣습니다.
                        focusPaused = true
                        applyPause()
                    } else {
                        showNotice(getString(R.string.focus_lost))
                        stopLive()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val listener = AudioManager.OnModeChangedListener { handler.post { onAudioModeChanged() } }
            audioManager.addOnModeChangedListener(mainExecutor, listener)
            modeListener = listener
        }
        bindViews()
        applyWindowInsets()

        ambientLight = prefs.getBoolean(SettingsActivity.KEY_AMBIENT_LIGHT, true)
        finalOnly = prefs.getBoolean(KEY_FINAL_ONLY, false)
        CarSignals.start(this)
        CarSignals.addListener(carListener)

        atmosphere = SpeakerThemeController(
            scheduler = Scheduler { delayMs, task ->
                handler.postDelayed(task, delayMs)
                return@Scheduler { handler.removeCallbacks(task) }
            },
            onChange = ::applySpeakerTheme,
        )

        // 명시적인 사용자 동작(버튼)으로만 마이크를 시작합니다. (Pleos 정책)
        statusButton.setOnLongClickListener {
            setFinalOnly(!finalOnly)
            true
        }
        // 대화 시작은 대기 화면의 버튼으로, 끝내기는 상단 바의 "대화 종료" 버튼으로 합니다.
        findViewById<View>(R.id.start_button).setOnClickListener { checkPermissionAndStart() }
        statusRow.endButton.setOnClickListener { endConversation() }
        statusRow.helpButton.setOnClickListener { showHelp(true) }
        errorButton.setOnClickListener { onErrorAction() }
        findViewById<View>(R.id.help_close).setOnClickListener { showHelp(false) }
        helpOverlay.setOnClickListener { showHelp(false) }
        bindHelpSteps()
        onBackPressedDispatcher.addCallback(this, helpBack)
        findViewById<View>(R.id.guest_close).setOnClickListener {
            if (pendingGuests.isNotEmpty()) pendingGuests.removeAt(0)
            renderStatus()
        }
        findViewById<View>(R.id.guest_merge).setOnClickListener { openGuest(merge = true) }
        findViewById<View>(R.id.guest_new).setOnClickListener { openGuest(merge = false) }
        // 설정 칩: 프로필·앰비언트 라이트·데이터 관리. 설정에 있는 동안 자막은 멈춥니다(onPause).
        statusRow.settingsButton.setOnClickListener { openSettings() }

        // API 키가 없거나 mode=preview로 실행하면 실제 인식 대신 예시 대화를 보여 줍니다.
        // 예: adb shell am start -n com.example.pleos/.MainActivity --es mode preview
        // 처음 실행하면 소개(온보딩)를 먼저 보여 줍니다. 디버그: --es mode onboarding 으로 다시 볼 수 있습니다.
        val forceOnboarding = BuildConfig.DEBUG && intent.getStringExtra(EXTRA_MODE) == "onboarding"
        if (forceOnboarding || (!prefs.getBoolean(OnboardingActivity.KEY_DONE, false) && intent.getStringExtra(EXTRA_MODE) != MODE_PREVIEW)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        // 디버그: 낯선 목소리 제안 카드 확인용 (식별값 없이 화면만)
        // adb shell am start -n com.example.pleos/.MainActivity --es mode guestdemo
        if (BuildConfig.DEBUG && intent.getStringExtra(EXTRA_MODE) == "guestdemo") {
            pendingGuests += GuestVoice("화자 1", emptyList(), "저 배고파요. 휴게소 들러요?", "오후 12:42")
        }
        if (!isConfigured || intent.getStringExtra(EXTRA_MODE) == MODE_PREVIEW) {
            startPreview()
        } else {
            renderStatus()
            renderTurns()
        }
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.DEBUG) registerDebugReplyReceiver()
        // 설정에서 이름·색을 바꾸거나 지웠을 수 있어 다시 읽습니다.
        speakerDirectory.setProfiles(if (isPreview) PREVIEW_PROFILES else profileStore.load())
        ambientLight = prefs.getBoolean(SettingsActivity.KEY_AMBIENT_LIGHT, true)
        CarSignals.cabinBrightness = SettingsActivity.BRIGHTNESS_LEVELS[prefs.getInt(SettingsActivity.KEY_AMBIENT_BRIGHTNESS, 1).coerceIn(0, 2)]
        // 화면을 떠날 때 원래 색으로 돌려 둔 실내 조명도 다시 맞춥니다.
        screenActive = true
        applyOrbColors()
        renderTurns()
        // "권한 설정 열기"에서 마이크를 허용하고 돌아오면 바로 자막을 시작합니다.
        if (errorKind() == ErrorKind.PERMISSION && hasMicPermission()) startLive()
        if (errorKind() == ErrorKind.MIC) scheduleMicRetry()
    }

    // 백그라운드로 전환되면 마이크를 멈춥니다. (Pleos 정책)
    override fun onPause() {
        super.onPause()
        debugReplyReceiver?.let { unregisterReceiver(it) }
        debugReplyReceiver = null
        if (isLive()) stopLive()
        stopSuggestionRotation()
        handler.removeCallbacks(micRetry)
        // 자막 화면을 떠나면 실내 조명을 원래 색으로 돌려 둡니다.
        screenActive = false
        CarSignals.setCabinColor(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        generation++
        session?.dispose()
        session = null
        releaseAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) modeListener?.let { audioManager.removeOnModeChangedListener(it) }
        atmosphere.dispose()
        CarSignals.removeListener(carListener)
        CarSignals.setCabinColor(null)
        orbColorAnimator?.cancel()
        replySpeaker.release()
        handler.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------------- 마이크와 세션

    private fun checkPermissionAndStart() {
        if (isLive() || phase == Phase.STOPPING) return
        if (!isConfigured) {
            showNotice(getString(R.string.not_configured))
            return
        }
        if (hasMicPermission()) {
            startLive()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RC_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startLive()
        } else {
            phase = Phase.ERROR
            setError(getString(R.string.permission_denied))
            renderStatus()
        }
    }

    private fun startLive() {
        // 빠른 답장·대화 제안의 첫 AI 요청이 늦지 않게 연결을 미리 준비합니다.
        com.example.pleos.ai.TextAi.warmUp()
        if (isLive() || phase == Phase.STOPPING) return
        stopPreview()
        isPreview = false
        setError(null)
        notice.visibility = View.GONE

        if (!requestAudioFocus()) {
            phase = Phase.ERROR
            setError(getString(R.string.focus_denied))
            renderStatus()
            return
        }

        phase = Phase.CONNECTING
        val current = ++generation
        fun isCurrent() = current == generation && !isDestroyed
        var connectedOnce = false
        focusPaused = false
        micSilenced = false
        inCall = isInCall()
        renderStatus()

        // 등록된 목소리를 함께 보내면 결과에 등록한 이름의 라벨이 붙습니다.
        val profiles = profileStore.load()
        speakerDirectory.setProfiles(profiles)
        // 글자는 Pleos STT(차 안)에서, 화자 구분은 Speechmatics에서 받아 합칩니다.
        lateinit var fusion: PleosTextFusion
        val candidate = SpeechmaticsSession(
            httpClient = httpClient,
            tokenProvider = tokenProvider::fetch,
            options = SpeechmaticsSession.Options(
                language = "ko",
                diarization = true,
                maxDelay = 2.0,
                enrolledSpeakers = profiles.map { it.toEnrolledSpeaker() },
                speakerSensitivity = SPEAKER_SENSITIVITY,
                // 터널·지하 주차장에서 잠깐 끊겨도 자막을 끝내지 않고 다시 연결합니다.
                reconnect = true,
                // 대화가 끝나면 등록하지 않은 사람의 목소리 식별값을 받아 "처음 듣는 목소리" 제안에 씁니다.
                getSpeakers = true,
            ),
            context = applicationContext,
            audioTap = { fusion.onAudio(it) },
            listener = PleosTextFusion(recognizer = PleosTextRecognizer(applicationContext), downstream = object : SpeechmaticsSession.Listener {
                override fun onFinal(segments: List<Segment>) {
                    if (!isCurrent()) return
                    if (BuildConfig.DEBUG) {
                        // 예: "홍길동: 에어컨 줄여줘" (adb logcat -s SttResult)
                        segments.forEach { Log.d(RESULT_TAG, speakerDirectory.line(it.speaker, it.text)) }
                    }
                    transcript.addFinal(segments)
                    renderTurns()
                    onHeardForReplies(segments)
                }

                override fun onPartial(segments: List<Segment>) {
                    if (!isCurrent()) return
                    transcript.setPartial(segments)
                    renderTurns()
                }

                override fun onState(state: SpeechmaticsSession.State) {
                    if (!isCurrent()) return
                    phase = when (state) {
                        SpeechmaticsSession.State.STOPPED -> if (errorMessage != null) Phase.ERROR else Phase.STOPPED
                        SpeechmaticsSession.State.CONNECTING -> Phase.CONNECTING
                        SpeechmaticsSession.State.RECORDING -> Phase.RECORDING
                        SpeechmaticsSession.State.RECONNECTING -> Phase.RECONNECTING
                        SpeechmaticsSession.State.STOPPING -> Phase.STOPPING
                    }
                    if (state == SpeechmaticsSession.State.RECORDING) {
                        if (!connectedOnce) {
                            // 새 세션은 화자 번호를 새로 매기므로, 연결에 성공한 뒤 새 대화를 시작합니다.
                            // 끊겼다가 다시 연결된 경우에는 지금까지의 대화를 그대로 둡니다.
                            connectedOnce = true
                            resetConversation()
                            conversationStartedAt = System.currentTimeMillis()
                            microphoneHasStarted = true
                            applyPause()
                        } else {
                            showNotice(getString(R.string.reconnected_notice))
                        }
                    }
                    if (state != SpeechmaticsSession.State.RECORDING && state != SpeechmaticsSession.State.RECONNECTING) {
                        voiceOrb.setLevel(0f)
                    }
                    if (state == SpeechmaticsSession.State.STOPPED) {
                        replySpeaker.stop()
                        replyAi.clear()
                        transcript.clearPartial()
                        releaseAudioFocus()
                        session = null
                        if (connectedOnce) saveConversation(showToast = screenActive && errorMessage == null)
                    }
                    renderTurns()
                    renderStatus()
                }

                override fun onLevel(level: Float) {
                    if (!isCurrent()) return
                    // 말하는 동안 마이크 아이콘이 살짝 커져, 마이크 입력이 들어오는지 바로 알 수 있습니다.
                    val scale = 1f + level * 0.2f
                    statusIcon.scaleX = scale
                    statusIcon.scaleY = scale
                }

                override fun onDecibel(dbfs: Float) {
                    if (!isCurrent()) return
                    voiceOrb.setDecibel(dbfs)
                }

                override fun onSpeakers(speakers: List<EnrolledSpeaker>) {
                    if (!isCurrent()) return
                    val enrolled = profileStore.load().map { it.label }.toSet()
                    for (speaker in speakers) {
                        if (speaker.label !in enrolled && speaker.label != SpeakerDirectory.UNKNOWN) {
                            guestVoices[speaker.label] = speaker.identifiers
                        }
                    }
                }

                override fun onConnectionLost(reason: String) {
                    if (!isCurrent()) return
                    showNotice(getString(R.string.reconnecting_notice))
                }

                override fun onMicrophoneSilenced(silenced: Boolean) {
                    if (!isCurrent()) return
                    micSilenced = silenced
                    renderStatus()
                    renderTurns()
                    if (silenced) showNotice(getString(R.string.paused_mic_notice))
                }

                override fun onWarning(type: String, reason: String) {
                    if (!isCurrent()) return
                    if (type == "speaker_id") showNotice(getString(R.string.speaker_id_warning))
                }

                override fun onError(message: String) {
                    if (!isCurrent()) return
                    setError(message)
                    phase = Phase.ERROR
                    renderStatus()
                }
            }).also { fusion = it },
        )
        session = candidate
        candidate.start()
    }

    private fun stopLive() {
        if (phase == Phase.CONNECTING) {
            // 의도한 취소입니다. 늦게 도착하는 콜백은 무시합니다.
            generation++
            val pending = session
            session = null
            phase = Phase.STOPPED
            pending?.stop()
            releaseAudioFocus()
            renderStatus()
            return
        }
        session?.stop()
    }

    private fun isLive() = phase == Phase.CONNECTING || phase == Phase.RECORDING || phase == Phase.RECONNECTING

    /** 일시 정지 이유: 통화 중, 다른 기능이 잠깐 오디오를 씀, 시스템이 마이크를 막음 */
    private fun pauseReason(): Int? = when {
        inCall -> R.string.paused_call_notice
        focusPaused -> R.string.paused_focus_notice
        micSilenced -> R.string.paused_mic_notice
        else -> null
    }

    /**
     * 통화 중이거나 다른 기능이 잠깐 오디오를 쓰는 동안은 무음을 보내 자막을 멈춥니다.
     * 연결은 그대로라, 끝나면 버튼을 누르지 않아도 바로 이어서 듣습니다.
     */
    private fun applyPause() {
        val pausing = inCall || focusPaused
        val wasPaused = pausedForAudio
        pausedForAudio = pausing
        updateMicMute()
        if (pausing && !wasPaused) pauseReason()?.let { showNotice(getString(it)) }
        renderStatus()
        renderTurns()
    }

    /** 통화 등으로 멈췄거나 빠른 답장을 읽는 중이면 마이크 입력을 무음으로 보냅니다. */
    private fun updateMicMute() {
        session?.setPaused(pausedForAudio || replySpeaking != null || replyEchoHold)
    }

    private fun isInCall(): Boolean = audioManager.mode == AudioManager.MODE_IN_CALL ||
        audioManager.mode == AudioManager.MODE_IN_COMMUNICATION ||
        audioManager.mode == AudioManager.MODE_CALL_SCREENING

    private fun onAudioModeChanged() {
        val calling = isInCall()
        if (calling == inCall) return
        inCall = calling
        if (isLive()) applyPause()
    }

    private fun requestAudioFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusChangeListener, handler)
            .build()
        val granted = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) audioFocusRequest = request
        return granted
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // ---------------------------------------------------------------- 미리보기

    /** 실제 스트리밍처럼 중간 결과가 고쳐지다 확정되는 예시 대화를 보여 줍니다. (C2 시안의 대화) */
    private fun startPreview() {
        if (!isPreview && (isLive() || phase == Phase.STOPPING)) return
        generation++
        stopPreview()
        resetConversation()
        setError(null)
        isPreview = true
        phase = Phase.PREVIEW
        speakerDirectory.setProfiles(PREVIEW_PROFILES)
        renderStatus()
        renderTurns()

        val previewGeneration = generation
        val startedAt = SystemClock.uptimeMillis()
        PREVIEW_STEPS.forEachIndexed { index, step ->
            handler.postAtTime({
                if (!isPreview || previewGeneration != generation) return@postAtTime
                val segment = Segment("preview-$index", step.speaker, step.text, step.atMs / 1000.0, step.atMs / 1000.0 + 1)
                if (step.final) transcript.addFinal(listOf(segment)) else transcript.setPartial(listOf(segment))
                renderTurns()
            }, PREVIEW_TOKEN, startedAt + step.atMs)
        }
        handler.post(previewPulse)
    }

    private fun stopPreview() {
        handler.removeCallbacksAndMessages(PREVIEW_TOKEN)
        handler.removeCallbacks(previewPulse)
        voiceOrb.setLevel(0f)
    }

    // ---------------------------------------------------------------- 대화 시작·종료, 오류, 도움말

    private enum class ErrorKind { PERMISSION, MIC, OTHER }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** 오류 화면의 종류: 마이크 권한, 마이크 장치, 그 밖(네트워크·인증 등) */
    private fun errorKind(): ErrorKind? {
        val message = errorMessage ?: return null
        if (phase != Phase.ERROR) return null
        return when {
            message == getString(R.string.permission_denied) -> ErrorKind.PERMISSION
            message.contains("마이크") -> ErrorKind.MIC
            else -> ErrorKind.OTHER
        }
    }

    /** "대화종료": 듣기를 끝내고 대기 화면으로 돌아갑니다. 미리보기·오류 화면에서도 대기 화면으로 갑니다. */
    private fun endConversation() {
        handler.removeCallbacks(micRetry)
        when {
            isPreview -> {
                generation++
                stopPreview()
                isPreview = false
                phase = Phase.STOPPED
                resetConversation()
            }
            isLive() -> stopLive()
            phase == Phase.ERROR -> {
                phase = Phase.STOPPED
                setError(null)
            }
        }
        renderStatus()
        renderTurns()
    }

    private fun onErrorAction() {
        when (errorKind()) {
            ErrorKind.PERMISSION -> runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                )
            }.onFailure { showNotice(getString(R.string.permission_denied)) }
            else -> {
                handler.removeCallbacks(micRetry)
                checkPermissionAndStart()
            }
        }
    }

    /** 마이크 오류는 잠시 뒤 저절로 다시 시도합니다("잠시 후 자동으로 다시 연결해요"). */
    private val micRetry = Runnable {
        if (screenActive && errorKind() == ErrorKind.MIC) checkPermissionAndStart()
    }

    private fun scheduleMicRetry() {
        handler.removeCallbacks(micRetry)
        handler.postDelayed(micRetry, MIC_RETRY_MS)
    }

    private val helpBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = showHelp(false)
    }

    private fun showHelp(show: Boolean) {
        helpOverlay.visibility = if (show) View.VISIBLE else View.GONE
        helpBack.isEnabled = show
        renderStatus()
        if (show) helpOverlay.findViewById<View>(R.id.help_close).requestFocus()
    }

    /** 도움말 단계: 번호 원(52dp) + 설명(30sp), 바탕 없는 줄 (Figma 217:4277) */
    private fun bindHelpSteps() {
        val steps = findViewById<LinearLayout>(R.id.help_steps)
        listOf(R.string.help_step_1, R.string.help_step_2, R.string.help_step_3, R.string.help_step_4)
            .forEachIndexed { index, text ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, dp(10))
                }
                val number = TextView(this, null, 0, R.style.Voium_Text).apply {
                    this.text = (index + 1).toString()
                    setTextColor(color(android.R.color.white))
                    textSize = 26f
                    typeface = android.graphics.Typeface.create(typeface, 800, false)
                    gravity = Gravity.CENTER
                    setBackgroundResource(R.drawable.bg_help_number)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                row.addView(number, LinearLayout.LayoutParams(dp(52), dp(52)))
                val label = TextView(this, null, 0, R.style.Voium_Text_BodyLargeStrong).apply { setText(text) }
                row.addView(
                    label,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(20) },
                )
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (index > 0) params.topMargin = dp(16)
                steps.addView(row, params)
            }
    }

    // ---------------------------------------------------------------- 자막 렌더링

    /**
     * 등록된 목소리는 등록한 이름과 프로필 색, 그 밖의 사람은 등장 순서대로 "화자 1", "화자 2"와 회색으로 표시합니다.
     * 화자 번호는 세션 안에서 목소리를 구분한 것이며, 사람의 신원을 뜻하지 않습니다.
     * commit이 false면(아직 확정되지 않은 문단) 번호를 미리 보여 주기만 합니다.
     */
    private fun speakerVisual(speaker: String, commit: Boolean = true): SpeakerVisual {
        val name = speakerDirectory.resolve(speaker, commit)
        val theme = if (name.isEnrolled) {
            SpeakerThemes.forKey(name.colorKey)
                ?: name.colorIndex?.let { SpeakerThemes.ALL[it.mod(SpeakerThemes.ALL.size)] }
                ?: SpeakerThemes.GUEST
        } else {
            // 등록하지 않은 사람("화자 1", "나")도 서로 다른 색으로 구분합니다. 등록된 사람의 색은 피합니다.
            name.colorIndex?.let { index ->
                val taken = speakerDirectory.enrolledColorKeys()
                val palette = SpeakerThemes.ALL.filter { it.key !in taken }.ifEmpty { SpeakerThemes.ALL }
                palette[index.mod(palette.size)]
            } ?: SpeakerThemes.GUEST
        }
        return SpeakerVisual(name.label, name.isEnrolled, theme)
    }

    /**
     * 실시간 자막은 중간 결과가 계속 고쳐지며 들어옵니다. 그대로 그리면 깜빡이므로 세 가지를 막습니다.
     * 1. 새 화자의 문단이 중간 결과로만 잠깐 나타났다 사라지는 경우: 잠시(SETTLE_MS) 지켜본 뒤 보여 줍니다.
     * 2. 확정 결과가 오면서 끝부분 글자가 잠깐 사라졌다 다시 나타나는 경우: 끝부분을 잠시(TAIL_HOLD_MS) 남겨 둡니다.
     * 3. "말하는 중"이 확정 순간마다 꺼졌다 켜지는 경우: 마지막 중간 결과 뒤 잠시(SPEAKING_HOLD_MS) 유지합니다.
     */
    private fun renderTurns() {
        // 대기·오류·일시 정지 화면에서는 자막을 숨깁니다.
        val showCaptions = captionsVisible()
        previousScroll.visibility = if (showCaptions) View.VISIBLE else View.GONE
        if (!showCaptions) {
            currentCaption.visibility = View.GONE
            renderEmptyState(true)
            updateAtmosphere()
            return
        }
        val turns = settledTurns(visibleTurns())
        val current = turns.lastOrNull()
        // 처음 보는 문단이 생겼을 때만 움직입니다. 잠깐 사라졌다 돌아온 문단은 다시 움직이지 않습니다.
        val grew = turns.size > shownTurnCount
        bindPreviousLines(turns.dropLast(1).takeLast(MAX_PREVIOUS_LINES), animate = grew)

        if (current == null) {
            currentCaption.visibility = View.GONE
            shownTurnId = null
            shownText = ""
        } else {
            currentCaption.visibility = View.VISIBLE
            val sameTurn = current.id == shownTurnId
            if (!sameTurn) {
                shownTurnId = current.id
                shownText = ""
                // 새 문단(화자가 바뀜)은 아래에서 떠오르며 나타납니다.
                if (grew) riseIn(currentCaption, toAlpha = 1f, distanceDp = 32)
            }
            val visual = speakerVisual(current.speaker, commit = current.finalText.isNotEmpty())
            val now = SystemClock.uptimeMillis()
            if (current.isPartial) lastPartialAt = now
            // 문장이 이어지는 중이면 "말하는 중"을 붙입니다. 확정 순간에 잠깐 꺼지지 않게 조금 더 유지합니다.
            val holdLeft = SPEAKING_HOLD_MS - (now - lastPartialAt)
            val speaking = isShowingLive() && (current.isPartial || (sameTurn && holdLeft > 0))
            if (speaking && !current.isPartial) scheduleRender(holdLeft)
            val label = if (speaking) getString(R.string.speaking, visual.label) else visual.label
            if (speakerPillLabel.text.toString() != label) speakerPillLabel.text = label
            (speakerPill.background as GradientDrawable).setColor(visual.theme.color)
            currentTime.text = timeOf(current)
            bindCurrentText(current, sameTurn)
        }
        shownTurnCount = maxOf(shownTurnCount, turns.size)

        renderEmptyState(turns.isEmpty())
        updateAtmosphere(turns)
    }

    /**
     * 새 화자의 문단이 중간 결과로만 있을 때는 SETTLE_MS 동안 지켜보다가 보여 줍니다.
     * 화자 구분이 잠깐 흔들려 생긴 문단이 나타났다 사라지며 자막 전체가 들썩이는 것을 막습니다.
     */
    private fun settledTurns(all: List<Turn>): List<Turn> {
        val last = all.lastOrNull()
        val unsettled = last != null && all.size > shownTurnCount && last.finalText.isEmpty() && isShowingLive()
        if (!unsettled) {
            pendingTurnId = null
            return all
        }
        val now = SystemClock.uptimeMillis()
        if (pendingTurnId != last!!.id) {
            pendingTurnId = last.id
            pendingSince = now
        }
        val waited = now - pendingSince
        if (waited >= SETTLE_MS) {
            pendingTurnId = null
            return all
        }
        scheduleRender(SETTLE_MS - waited)
        return all.dropLast(1)
    }

    /** 여러 대기 중 가장 이른 시점에 한 번만 다시 그립니다. */
    private fun scheduleRender(delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs.coerceAtLeast(1)
        if (nextRenderAt != 0L && nextRenderAt <= at) return
        handler.removeCallbacks(delayedRender)
        nextRenderAt = at
        handler.postAtTime(delayedRender, at)
    }

    /**
     * 지난 문단: 위가 더 오래된 줄이고 더 흐립니다(아래부터 90%, 75%, 45%).
     * 새 문단이 올라오면 기존 줄은 한 칸 위로 밀려 올라가고, 새로 들어온 줄은 떠오르며 나타납니다.
     * animate가 false면(중간 결과가 흔들려 줄이 바뀐 경우) 움직이지 않고 바로 바꿉니다.
     */
    private fun bindPreviousLines(previous: List<Turn>, animate: Boolean) {
        while (lineRows.size < previous.size) lineRows += makeLineRow()
        while (lineTurnIds.size < lineRows.size) lineTurnIds += null
        val oldIds = lineTurnIds.toList()
        lineRows.forEachIndexed { index, row ->
            val turn = previous.getOrNull(index)
            if (turn == null) {
                row.root.visibility = View.GONE
                lineTurnIds[index] = null
                return@forEachIndexed
            }
            val visual = speakerVisual(turn.speaker, commit = turn.finalText.isNotEmpty())
            val targetAlpha = LINE_ALPHAS[(previous.lastIndex - index).coerceAtMost(LINE_ALPHAS.lastIndex)]
            val oldIndex = oldIds.indexOf(turn.id)
            val wasVisible = row.root.visibility == View.VISIBLE
            row.root.visibility = View.VISIBLE
            when {
                // 그대로인 줄: 진하기만 맞춥니다.
                oldIndex == index -> if (!isAnimating(row.root)) row.root.alpha = targetAlpha
                !animate -> {
                    row.root.animate().cancel()
                    row.root.alpha = targetAlpha
                    row.root.translationY = 0f
                }
                // 아래 칸에서 올라온 줄: 원래 있던 자리에서 미끄러져 올라갑니다.
                oldIndex > index && wasVisible -> slideUp(row.root, steps = oldIndex - index, toAlpha = targetAlpha)
                // 새로 들어온 줄(방금까지 큰 자막이던 문단)
                else -> riseIn(row.root, toAlpha = targetAlpha, distanceDp = 20)
            }
            lineTurnIds[index] = turn.id
            VoiumUi.bindSpeakerTag(row.tag, row.tagDot, row.tagLabel, visual.theme.takeIf { it != SpeakerThemes.GUEST }, visual.label)
            val text = turn.text.trim()
            if (row.text.text.toString() != text) row.text.text = text
            val time = timeOf(turn)
            row.time.text = time
            row.root.contentDescription = "${visual.label}: $text, $time"
        }
        followPreviousLines(smooth = animate)
    }

    /** 새 줄이 생기면 맨 아래(가장 최근)로 내려갑니다. 손님이 방금 끌어 올려 보는 중이면 기다립니다. */
    private fun followPreviousLines(smooth: Boolean) {
        val idleFor = SystemClock.uptimeMillis() - previousDraggedAt
        if (idleFor < PREVIOUS_FOLLOW_DELAY_MS) {
            handler.removeCallbacks(followPrevious)
            handler.postDelayed(followPrevious, PREVIOUS_FOLLOW_DELAY_MS - idleFor)
            return
        }
        previousScroll.post {
            val bottom = (previousLines.height - previousScroll.height).coerceAtLeast(0)
            if (previousScroll.scrollY == bottom) return@post
            if (smooth && areAnimationsEnabled()) previousScroll.smoothScrollTo(0, bottom) else previousScroll.scrollTo(0, bottom)
        }
    }

    private val followPrevious = Runnable { followPreviousLines(smooth = true) }

    /** 문단이 처음 화면에 나타난 시각("오후 12:41") */
    private fun timeOf(turn: Turn): String =
        timeFormat.format(turnTimes.getOrPut(turn.id) { System.currentTimeMillis() })

    /**
     * 예비 글자(중간 결과)도 확정 글자와 같은 진한 색으로 바로 보여 주고, 길어지면 마지막 줄을 따라갑니다.
     * 확정 결과가 오면서 중간 결과(끝부분)가 잠깐 비는 동안에는 보이던 끝부분을 남겨 두어 깜빡이지 않게 합니다.
     */
    private fun bindCurrentText(turn: Turn, sameTurn: Boolean) {
        // 모델이 이어 붙인 결과를 그대로 써서 단어가 겹치거나 문장부호 앞에 공백이 생기지 않게 합니다.
        var partial = if (turn.text.startsWith(turn.finalText)) turn.text.substring(turn.finalText.length) else turn.partialText
        val now = SystemClock.uptimeMillis()
        val plain = turn.finalText + partial
        val holdLeft = TAIL_HOLD_MS - (now - textChangedAt)
        if (sameTurn && partial.isEmpty() && holdLeft > 0 &&
            shownText.length > plain.length && shownText.startsWith(plain)
        ) {
            partial = shownText.substring(plain.length)
            scheduleRender(holdLeft)
        }
        val display = turn.finalText + partial
        val key = "${turn.id}|${turn.finalText.length}|$display"
        if (currentText.tag == key) return
        currentText.tag = key

        // 단어가 처음 화면에 나타날 때만 서서히 나타나게 합니다(규칙은 CaptionFade 참고).
        // 고쳐진 글자, 문장부호만 붙은 경우, 확정되면서 바뀌거나 잠깐 빠졌다 돌아온 글자는 바로 바꿉니다.
        if (!sameTurn) seenWords.clear()
        // 확정된 자막만 보기에서는 확정 글자가 처음 나타나는 글자이므로 서서히 나타나게 합니다.
        // 새 문단의 첫 글자들은 문단 전체가 떠오르므로 따로 흐리게 하지 않습니다.
        charFadeStart = CaptionFade.plan(
            old = shownText,
            new = display,
            oldStarts = charFadeStart,
            now = now,
            fadeFrom = if (finalOnly) 0 else turn.finalText.length,
            enabled = sameTurn && shownText.isNotEmpty() && areAnimationsEnabled(),
            knownWords = seenWords,
        )
        seenWords += CaptionFade.words(display)
        if (display != shownText) {
            shownText = display
            textChangedAt = now
        }
        drawCaptionText()
        // 단어마다 부드럽게 움직이지 않고 바로 이동해 읽는 위치를 예측할 수 있게 합니다.
        currentScroll.post {
            val bottom = (currentText.height - currentScroll.height).coerceAtLeast(0)
            currentScroll.scrollTo(0, bottom)
        }
    }

    /** 확정 글자는 진하게, 중간 글자는 70%로, 막 나타난 글자는 진행 정도만큼 흐리게 그립니다. */
    private fun drawCaptionText() {
        val text = shownText
        val length = text.length
        // 예비 글자와 확정 글자를 같은 색으로 그립니다. 확정될 때 색이 바뀌며 튀지 않습니다.
        val primary = color(R.color.voium_text_primary)
        val styled = SpannableStringBuilder(text)
        if (length > 0) styled.setSpan(ForegroundColorSpan(primary), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val now = SystemClock.uptimeMillis()
        var fading = false
        var i = 0
        while (i < length) {
            val start = charFadeStart.getOrElse(i) { 0L }
            val progress = if (start == 0L) 1f else ((now - start).toFloat() / CHAR_FADE_MS).coerceIn(0f, 1f)
            var j = i + 1
            while (j < length && charFadeStart.getOrElse(j) { 0L } == start) j++
            if (progress < 1f) {
                fading = true
                val eased = 1f - (1f - progress) * (1f - progress)
                // 나중에 붙인 글자색 span이 앞의 색을 덮습니다.
                val faded = ColorUtils.setAlphaComponent(primary, (android.graphics.Color.alpha(primary) * eased).toInt())
                styled.setSpan(ForegroundColorSpan(faded), i, j, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            i = j
        }
        currentText.text = styled
        if (fading && !fadeFrameScheduled) {
            fadeFrameScheduled = true
            currentText.postOnAnimation(fadeFrame)
        }
    }

    /** 아래에서 살짝 떠오르며 나타납니다. */
    private fun riseIn(view: View, toAlpha: Float, distanceDp: Int) {
        view.animate().cancel()
        if (!areAnimationsEnabled()) {
            view.alpha = toAlpha
            view.translationY = 0f
            return
        }
        view.alpha = 0f
        view.translationY = dp(distanceDp).toFloat()
        view.animate().alpha(toAlpha).translationY(0f)
            .setDuration(RISE_MS).setInterpolator(easeOut).start()
    }

    /** steps칸 아래(원래 있던 자리)에서 제자리로 미끄러져 올라갑니다. */
    private fun slideUp(view: View, steps: Int, toAlpha: Float) {
        view.animate().cancel()
        if (!areAnimationsEnabled()) {
            view.alpha = toAlpha
            view.translationY = 0f
            return
        }
        view.translationY = (view.height + dp(LINE_GAP_DP)).toFloat() * steps
        view.animate().alpha(toAlpha).translationY(0f)
            .setDuration(RISE_MS).setInterpolator(easeOut).start()
    }

    private fun isAnimating(view: View) = view.translationY != 0f

    private fun makeLineRow(): LineRow {
        val view = LayoutInflater.from(this).inflate(R.layout.item_caption_line, previousLines, false)
        if (lineRows.isNotEmpty()) {
            (view.layoutParams as LinearLayout.LayoutParams).topMargin = dp(LINE_GAP_DP)
        }
        previousLines.addView(view)
        return LineRow(
            root = view,
            tag = view.findViewById(R.id.speaker_tag),
            tagDot = view.findViewById(R.id.speaker_tag_dot),
            tagLabel = view.findViewById(R.id.speaker_tag_label),
            text = view.findViewById(R.id.line_text),
            time = view.findViewById(R.id.line_time),
        )
    }

    /** 대화에 나온 사람 수입니다. 아직 아무도 말하지 않았으면 숫자 없이 보여 줍니다. */
    // ---------------------------------------------------------------- 대화 없음

    /** 자막을 보여 주는 상태: 듣는 중(연결 중·다시 연결 중 포함)·마무리 중·미리보기. 멈춘 동안은 숨깁니다. */
    private fun captionsVisible() =
        (isPreview || isLive() || phase == Phase.STOPPING) && phase != Phase.ERROR && !isAudioPaused()

    private fun isAudioPaused() = phase == Phase.RECORDING && (pausedForAudio || micSilenced)

    /**
     * 자막이 없을 때 같은 자리(Figma suggestion_placeholder)에 보여 줄 것:
     * 듣는 중이면 대화 제안을 돌려 보여 주고, 통화 등으로 멈췄으면 멈춘 이유를 보여 줍니다.
     */
    private fun renderEmptyState(empty: Boolean) {
        val paused = isAudioPaused()
        val showSuggestion = !paused && empty && captionsVisible()
        suggestion.visibility = if (paused || showSuggestion) View.VISIBLE else View.GONE
        if (showSuggestion != suggestionShowing) {
            suggestionShowing = showSuggestion
            applyOrbColors()
        }
        if (paused) {
            stopSuggestionRotation()
            renderPauseMessage()
        } else if (showSuggestion) {
            if (!suggestionRotating) {
                startSuggestionRotation()
                renderSuggestion(animate = false)
            }
        } else {
            stopSuggestionRotation()
        }
    }

    /** 일시 정지 (Figma 120:5243): 시계 아이콘 + "통화가 끝나면 자동으로 다시 듣습니다", 큰 회색 문구 */
    private fun renderPauseMessage() {
        val title = when {
            inCall -> R.string.paused_call_title
            focusPaused -> R.string.paused_focus_title
            else -> R.string.paused_mic_title
        }
        suggestionContext.setText(if (inCall) R.string.paused_call_context else R.string.paused_audio_context)
        suggestionIcon.setImageResource(R.drawable.ic_context_time)
        resetSuggestionSlide()
        suggestionText.setText(title)
        suggestionDots.visibility = View.GONE
    }

    private fun resetSuggestionSlide() {
        for (view in listOf(suggestionContextRow, suggestionText)) {
            view.animate().cancel()
            view.translationX = 0f
        }
        suggestionContextRow.alpha = 1f
        suggestionText.alpha = SUGGESTION_ALPHA
    }

    /**
     * 대화 제안 카드(시간 · 위치 · 날씨)를 그립니다. animate면 지금 카드를 왼쪽으로 밀어내고
     * 다음 카드를 오른쪽에서 밀어 넣습니다(Figma "슬라이딩 캐러셀"). 점은 카드가 여러 장이면 카드 수,
     * 한 장뿐이면 그 카드의 질문 수만큼 그립니다.
     */
    private fun renderSuggestion(animate: Boolean) {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val time = SimpleDateFormat("a h:mm", Locale.KOREAN).format(now.time)
        val trip = CarSignals.trip?.let { it.place to it.remainSeconds }
        val raining = CarSignals.raining == true
        // 차량 정보로 Gleo AI에 지금 상황에 맞는 질문을 요청합니다. 받기 전에는 기본 질문을 씁니다.
        val situation = SuggestionAi.Situation(
            timeText = time,
            slotLabel = ConversationStarters.slotFor(hour).label,
            parked = CarSignals.isParked,
            passengers = CarSignals.occupiedSeats,
            trip = trip,
            raining = raining,
            lastTopic = lastTopic,
        )
        // 시연용(디버그): 고정한 제안 하나만 넘기지 않고 보여 줍니다. DebugCarReceiver --es suggest 참고.
        val demo = if (BuildConfig.DEBUG) getSharedPreferences("debug_demo", MODE_PRIVATE).getString("suggestion", null) else null
        val card = if (demo != null) {
            val place = trip?.let { ConversationStarters.forTrip(it.first, it.second) }
            ConversationStarters.Card(
                kind = if (place != null) ConversationStarters.Kind.PLACE else ConversationStarters.Kind.TIME,
                label = place?.label ?: "$time · ${ConversationStarters.slotFor(hour).label}",
                question = demo,
            )
        } else {
            val ai = SuggestionAi.questions(this, situation)
            SuggestionAi.refresh(this, situation) { if (suggestionShowing) renderSuggestion(animate = true) }
            val count = ConversationStarters.cards(hour, time, trip, raining, 0, ai).size
            val position = suggestionIndex.mod(count)
            val round = suggestionIndex / count
            ConversationStarters.cards(hour, time, trip, raining, round, ai)[position].also {
                if (count > 1) renderSuggestionDots(count, position) else renderSuggestionDots(it.questionCount, it.questionIndex)
            }
        }
        suggestionDots.visibility = if (demo != null) View.GONE else View.VISIBLE
        // 같은 문장이면 밀어 넘기지 않습니다(시연용 고정 제안).
        if (demo != null && suggestionText.text.toString() == card.question && suggestionContext.text.toString() == card.label) return

        val apply = {
            suggestionIcon.setImageResource(
                when (card.kind) {
                    ConversationStarters.Kind.TIME -> R.drawable.ic_context_time
                    ConversationStarters.Kind.PLACE -> R.drawable.ic_context_place
                    ConversationStarters.Kind.WEATHER -> R.drawable.ic_context_rain
                },
            )
            suggestionContext.text = card.label
            suggestionText.text = card.question
        }
        if (!animate || !areAnimationsEnabled() || suggestionText.text.isEmpty()) {
            resetSuggestionSlide()
            apply()
            return
        }
        val shift = dp(SUGGESTION_SLIDE_DP).toFloat()
        val views = listOf(suggestionContextRow to 1f, suggestionText to SUGGESTION_ALPHA)
        views.forEachIndexed { i, (view, _) ->
            view.animate().cancel()
            view.animate().translationX(-shift).alpha(0f).setDuration(SUGGESTION_SLIDE_MS)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    if (i == 0) apply()
                    view.translationX = shift
                    view.animate().translationX(0f).alpha(views[i].second).setDuration(SUGGESTION_SLIDE_MS)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }.start()
        }
    }

    private fun renderSuggestionDots(count: Int, active: Int) {
        if (suggestionDots.childCount != count) {
            suggestionDots.removeAllViews()
            repeat(count) { i ->
                val dot = View(this)
                val params = LinearLayout.LayoutParams(dp(12), dp(12))
                if (i > 0) params.marginStart = dp(12)
                suggestionDots.addView(dot, params)
            }
        }
        for (i in 0 until count) {
            val dot = suggestionDots.getChildAt(i)
            val isActive = i == active
            dot.layoutParams.width = dp(if (isActive) 36 else 12)
            dot.background = pill(color(if (isActive) R.color.voium_dot_active else R.color.voium_dot_inactive))
            dot.requestLayout()
        }
    }

    private fun startSuggestionRotation() {
        if (suggestionRotating) return
        suggestionRotating = true
        // 사흘 안에 나눈 지난 대화가 있으면 그 주제를 대화 제안에 이어 씁니다.
        lastTopic = historyStore.load().firstOrNull()
            ?.takeIf { System.currentTimeMillis() - it.endedAt < LAST_TOPIC_MS }
            ?.headline
        handler.postDelayed(rotateSuggestion, SUGGESTION_INTERVAL_MS)
    }

    private fun stopSuggestionRotation() {
        suggestionRotating = false
        handler.removeCallbacks(rotateSuggestion)
    }

    // ---------------------------------------------------------------- 블롭 색 (앰비언트 라이트)

    private fun isShowingLive() =
        (isPreview || phase == Phase.RECORDING || phase == Phase.RECONNECTING || phase == Phase.STOPPING) && errorMessage == null

    /**
     * 화면에 쓸 문단입니다. 확정된 자막만 보기에서는 중간 결과 글자를 빼고, 확정 글자가 없는 문단은 숨깁니다.
     * isPartial은 그대로 두어 "말하는 중" 표시는 계속 보여 줍니다.
     */
    private fun visibleTurns(): List<Turn> {
        val turns = transcript.getTurns()
        if (!finalOnly) return turns
        return turns.filter { it.finalText.isNotEmpty() }
            .map { it.copy(text = it.finalText, partialText = "") }
    }

    private fun setFinalOnly(on: Boolean) {
        finalOnly = on
        prefs.edit().putBoolean(KEY_FINAL_ONLY, on).apply()
        showNotice(getString(if (on) R.string.caption_mode_final else R.string.caption_mode_live))
        renderTurns()
    }

    private fun updateAtmosphere(turns: List<Turn> = visibleTurns()) {
        val latest = turns.lastOrNull()
        atmosphere.update(
            theme = latest?.let { speakerVisual(it.speaker, commit = it.finalText.isNotEmpty()).theme },
            provisional = latest?.isPartial == true,
            followLatest = true,
            hasContent = latest != null && isShowingLive(),
        )
    }

    private fun applySpeakerTheme(theme: SpeakerTheme) {
        activeTheme = theme
        applyOrbColors()
    }

    /** 입자 링 모양: 대기(크고 회색), 듣는 중(작아지고 파랑·화자 색), 일시 정지(회색), 오류(숨김) */
    private enum class OrbMode { IDLE, LISTENING, PAUSED, HIDDEN }

    private fun currentOrbMode() = when {
        phase == Phase.ERROR -> OrbMode.HIDDEN
        isAudioPaused() -> OrbMode.PAUSED
        isPreview || isLive() || phase == Phase.STOPPING -> OrbMode.LISTENING
        else -> OrbMode.IDLE
    }

    /**
     * 대기 중 링(1073dp, x=1064 y=-16)과 듣는 중 링(900dp, x=1060 y=28)은 크기와 중심이 다릅니다.
     * 링 뷰는 대기 크기로 두고, 듣는 중에는 줄이고 옮겨 부드럽게 바뀌게 합니다.
     */
    private fun applyOrbMode() {
        val mode = currentOrbMode()
        if (mode == orbMode) return
        val first = orbMode == null
        orbMode = mode
        val small = mode != OrbMode.IDLE
        val scale = if (small) LISTENING_RING_DP / IDLE_RING_DP else 1f
        val dx = if (small) dp(LISTENING_RING_SHIFT_X).toFloat() else 0f
        val dy = if (small) dp(LISTENING_RING_SHIFT_Y).toFloat() else 0f
        val alpha = if (mode == OrbMode.HIDDEN) 0f else 1f
        voiceOrb.animate().cancel()
        if (first || !areAnimationsEnabled()) {
            voiceOrb.scaleX = scale
            voiceOrb.scaleY = scale
            voiceOrb.translationX = dx
            voiceOrb.translationY = dy
            voiceOrb.alpha = alpha
        } else {
            voiceOrb.animate().scaleX(scale).scaleY(scale).translationX(dx).translationY(dy).alpha(alpha)
                .setDuration(600).setInterpolator(easeOut).start()
        }
        applyOrbColors()
    }

    /** 앰비언트 라이트가 켜져 있으면 링을 지금 말하는 사람의 색으로, 아니면 상태 색(대기 회색·듣는 중 파랑)으로 바꿉니다. */
    private fun applyOrbColors() {
        if (!::voiceOrb.isInitialized) return
        val base = when (orbMode) {
            // 듣는 중이어도 아직 대화가 없어 대화 제안을 보여 주는 동안은 회색 링입니다(Figma 243:4245).
            OrbMode.LISTENING -> if (suggestionShowing) IdleOrbStyle else AppOrbStyle
            OrbMode.PAUSED -> PausedOrbStyle
            else -> IdleOrbStyle
        }
        val useSpeaker = orbMode == OrbMode.LISTENING && ambientLight && activeTheme != SpeakerThemes.NEUTRAL
        // 차량 실내 조명(앰비언트 라이트)도 말하는 사람의 색으로. 등록하지 않은 사람·대기 중이면 원래 색입니다.
        if (screenActive) {
            CarSignals.setCabinColor(if (useSpeaker && activeTheme != SpeakerThemes.GUEST) activeTheme.color else null)
        }
        val targetColor = if (useSpeaker) activeTheme.dot else base.color
        val targetOpacity = base.opacity
        orbColorAnimator?.cancel()
        if (!areAnimationsEnabled()) {
            setOrbLook(targetColor, targetOpacity)
            return
        }
        val fromColor = orbColor
        val fromOpacity = orbOpacity
        val evaluator = ArgbEvaluator()
        orbColorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350
            addUpdateListener {
                val f = it.animatedFraction
                setOrbLook(evaluator.evaluate(f, fromColor, targetColor) as Int, fromOpacity + (targetOpacity - fromOpacity) * f)
            }
            start()
        }
    }

    private fun setOrbLook(color: Int, opacity: Float) {
        orbColor = color
        orbOpacity = opacity
        voiceOrb.style = AppOrbStyle.copy(color = color, opacity = opacity)
    }

    /** 탑승 인원: 좌석 센서(Pleos Seat)를 쓸 수 있으면 앉은 사람 수, 아니면 대화에 나온 사람 수 */
    private fun passengerCount(turns: List<Turn>) =
        CarSignals.occupiedSeats
            ?: turns.asSequence().map { it.speaker }
                .filter { it != SpeakerDirectory.UNKNOWN && it != SpeakerDirectory.QUICK_REPLY }.toSet().size

    /** 설정을 엽니다. 지금 대화에 나온 등록된 사람은 설정에서 "지금 탑승 중"으로 보여 줍니다. */
    private fun openSettings() {
        val turns = transcript.getTurns()
        val present = turns.map { it.speaker }.filter { speakerDirectory.resolve(it, commit = false).isEnrolled }.distinct()
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_PASSENGERS, passengerCount(turns))
                .putStringArrayListExtra(SettingsActivity.EXTRA_PRESENT_LABELS, ArrayList(present)),
        )
    }

    // ---------------------------------------------------------------- 상태

    // ---------------------------------------------------------------- 대화 기록 · 낯선 목소리

    /**
     * 대화가 끝나면(대화종료, 화면을 떠남) 확정된 자막을 대화 기록으로 저장하고 AI 요약을 요청합니다.
     * 설정에서 "대화 기록 저장"을 끄면 저장하지 않습니다. 등록하지 않은 목소리는 제안 카드로 넘깁니다.
     */
    private fun saveConversation(showToast: Boolean) {
        if (conversationSaved) return
        conversationSaved = true
        val turns = transcript.getTurns().filter { it.finalText.isNotBlank() }
        if (turns.isEmpty()) return
        collectGuests(turns)
        if (!prefs.getBoolean(SettingsActivity.KEY_HISTORY_ENABLED, true)) return
        val lines = turns.map { turn ->
            val visual = speakerVisual(turn.speaker)
            RecordLine(
                at = turnTimes[turn.id] ?: System.currentTimeMillis(),
                speaker = visual.label,
                colorKey = visual.theme.key.takeIf { visual.theme != SpeakerThemes.GUEST },
                text = turn.finalText.trim(),
            )
        }
        historyStore.upsert(
            ConversationRecord(
                id = conversationId,
                startedAt = conversationStartedAt.takeIf { it > 0 } ?: lines.first().at,
                endedAt = System.currentTimeMillis(),
                lines = lines,
            ),
        )
        ConversationSummarizer.summarize(this, conversationId)
        if (showToast) showNotice(getString(R.string.history_saved_toast))
    }

    /** 이번 대화에서 말한, 등록하지 않은 사람 중 목소리 식별값을 받은 사람을 제안 목록에 넣습니다. */
    private fun collectGuests(turns: List<Turn>) {
        for ((label, identifiers) in guestVoices) {
            val last = turns.lastOrNull { it.speaker == label } ?: continue
            val name = speakerDirectory.resolve(label).label
            pendingGuests += GuestVoice(name, identifiers, last.finalText.trim(), timeOf(last))
        }
        guestVoices.clear()
    }

    /** 낯선 목소리 제안 (93:1389): 대기 화면에서, 정차 중일 때만 */
    private fun renderGuestCard(idle: Boolean) {
        val guest = pendingGuests.firstOrNull()
        val show = guest != null && idle && CarSignals.isParked != false && helpOverlay.visibility != View.VISIBLE
        guestCard.visibility = if (show) View.VISIBLE else View.GONE
        startPanel.alpha = if (show) 0.3f else 1f
        if (!show) return
        findViewById<TextView>(R.id.guest_number).text = (guest!!.name.filter(Char::isDigit).ifEmpty { "1" })
        findViewById<TextView>(R.id.guest_subtitle).text = getString(R.string.guest_subtitle, guest.name)
        findViewById<TextView>(R.id.guest_quote).text = getString(R.string.guest_quote, guest.sample, guest.sampleTime)
        findViewById<View>(R.id.guest_merge).visibility = if (profileStore.load().isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openGuest(merge: Boolean) {
        val guest = pendingGuests.firstOrNull() ?: return
        guestLauncher.launch(
            Intent(this, VoiceEnrollmentActivity::class.java)
                .putExtra(VoiceEnrollmentActivity.EXTRA_GUEST_MODE, if (merge) VoiceEnrollmentActivity.GUEST_MERGE else VoiceEnrollmentActivity.GUEST_NEW)
                .putExtra(VoiceEnrollmentActivity.EXTRA_GUEST_NAME, guest.name)
                .putStringArrayListExtra(VoiceEnrollmentActivity.EXTRA_IDENTIFIERS, ArrayList(guest.identifiers)),
        )
    }

    // ---------------------------------------------------------------- 빠른 답장

    /** 다른 사람의 말이 확정되면 AI 답장 추천을 부탁합니다(질문·부탁일 때만, ReplyAi가 거릅니다). */
    private fun onHeardForReplies(segments: List<Segment>) {
        val heard = segments.filter { it.speaker != SpeakerDirectory.QUICK_REPLY }
        if (heard.isEmpty() || isPreview) return
        val text = heard.filter { it.speaker == heard.last().speaker }.joinToString(" ") { it.text.trim() }
        // 앞 대화는 넉넉히 넘기고, 줄 수·글자 수 상한은 ReplyAi가 맞춥니다.
        val context = transcript.getTurns().filter { it.finalText.isNotBlank() }.dropLast(1).takeLast(ReplyAi.CONTEXT_LINES)
            .map { speakerDirectory.line(it.speaker, it.finalText.takeLast(ReplyAi.LINE_CHARS)) }
        replyAi.onHeard(text, context, lastTopic, asker = heard.last().speaker)
    }

    private fun quickRepliesVisible() = isLive() && phase == Phase.RECORDING && !isAudioPaused() && !isPreview

    /**
     * 빠른 답장 줄: [물은 사람 · 스피너 "답장 고르는 중" | "에게 답장"] [✦ AI 추천 3개].
     * 다른 사람이 물었을 때만 보입니다. 읽는 중인 칩은 진하게, 누르면 멈춥니다. 새 추천은 아래에서 위로 올라옵니다.
     */
    private fun renderQuickReplies() {
        if (!::quickReplies.isInitialized) return
        val ai = replyState.replies
        // 읽는 중인 문장이 목록에 없으면(추천을 눌러 추천이 지워진 경우) 진한 칩으로 남겨 둡니다. 누르면 멈춥니다.
        val speakingExtra = replySpeaking?.takeIf { speaking -> ai.none { it.replace('\n', ' ').trim() == speaking } }
        val visible = quickRepliesVisible() && (replyState.question != null || speakingExtra != null)
        quickReplyBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val animateAi = ai.isNotEmpty() && ai != animatedReplies && areAnimationsEnabled()
        if (ai.isNotEmpty()) animatedReplies = ai
        if (replyState.question == null) animatedReplies = null

        quickReplies.removeAllViews()
        if (replyState.question != null) quickReplies.addView(replyHeader(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(24) })
        var index = 0
        fun add(view: View, aiChip: Boolean) {
            quickReplies.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(88)).apply {
                if (index > 0) marginStart = dp(16)
            })
            if (aiChip && animateAi) {
                // 아래에서 위로 차례로 올라옵니다.
                view.alpha = 0f
                view.translationY = dp(40).toFloat()
                view.animate().alpha(1f).translationY(0f).setStartDelay(index * 80L).setDuration(300)
                    .setInterpolator(easeOut).start()
            }
            index++
        }
        speakingExtra?.let { add(replyChip(it, fromAi = false), aiChip = false) }
        ai.forEach { add(replyChip(it, fromAi = true), aiChip = true) }
        if (animateAi) quickReplyBar.post { (quickReplyBar as android.widget.HorizontalScrollView).smoothScrollTo(0, 0) }
    }

    /** 물은 사람 이름(그 사람 색) 옆에, 고르는 동안은 작은 스피너와 "답장 고르는 중", 다 고르면 "에게 답장" */
    private fun replyHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val asker = replyState.asker?.let { speakerVisual(it, commit = false) }
        val accent = asker?.theme?.takeIf { it != SpeakerThemes.GUEST }?.color ?: color(R.color.voium_text_secondary)
        contentDescription = getString(
            if (replyState.loading) R.string.quick_reply_loading_description else R.string.quick_reply_ready_description,
            asker?.label ?: "",
        )
        if (asker != null) addView(TextView(context, null, 0, R.style.Voium_Text).apply {
            text = asker.label
            textSize = 28f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(accent)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if (replyState.loading) {
            addView(android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(14); marginEnd = dp(10) })
        }
        addView(TextView(context, null, 0, R.style.Voium_Text).apply {
            setText(if (replyState.loading) R.string.quick_reply_loading else R.string.quick_reply_ready)
            textSize = 28f
            setTextColor(color(R.color.voium_hint))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    // 디버그 빌드 시연용: adb로 빠른 답장 칩을 누릅니다(DebugCarReceiver --es reply ai1 | 1).
    private var debugReplyReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugReplyReceiver() {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val which = intent.getStringExtra(EXTRA_DEBUG_REPLY) ?: return
                val ai = which.startsWith("ai")
                val n = which.removePrefix("ai").toIntOrNull() ?: 1
                val chips = (0 until quickReplies.childCount).map { quickReplies.getChildAt(it) }
                    .filter { it.isClickable && it.contentDescription != null }
                    .filter { chip ->
                        val desc = chip.contentDescription.toString()
                        if (ai) desc.startsWith(getString(R.string.quick_reply_ai_description, "").trimEnd())
                        else !desc.startsWith(getString(R.string.quick_reply_ai_description, "").trimEnd())
                    }
                chips.getOrNull(n - 1)?.let { chip ->
                    // 누른 것처럼 물결 효과를 보여 준 뒤 누릅니다.
                    chip.isPressed = true
                    handler.postDelayed({ chip.isPressed = false; chip.performClick() }, 180)
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver, android.content.IntentFilter(ACTION_DEBUG_REPLY), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        debugReplyReceiver = receiver
    }

    private fun replyChip(text: String, fromAi: Boolean): View {
        val speakingThis = replySpeaking == text.replace('\n', ' ').trim()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (fromAi) 28 else 36), 0, dp(36), 0)
            setBackgroundResource(
                when {
                    speakingThis -> R.drawable.bg_reply_speaking
                    fromAi -> R.drawable.bg_reply_ai
                    else -> R.drawable.bg_chip_outline
                },
            )
            isClickable = true
            isFocusable = true
            contentDescription = getString(
                when {
                    speakingThis -> R.string.quick_reply_stop_description
                    fromAi -> R.string.quick_reply_ai_description
                    else -> R.string.quick_reply_ai_description
                },
                text,
            )
            if (fromAi && !speakingThis) {
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_ai_spark)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
            }
            addView(TextView(context, null, 0, R.style.Voium_Text).apply {
                this.text = text
                textSize = 32f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                setTextColor(
                    when {
                        speakingThis -> Color.WHITE
                        fromAi -> 0xFF1E3FAE.toInt()
                        else -> color(R.color.voium_text_primary)
                    },
                )
            })
            setOnClickListener { onQuickReply(text) }
        }
    }

    /** 칩을 누르면: 읽는 중인 칩이면 멈추고, 아니면 읽고 자막·대화 기록에 "나 · 빠른 답장"으로 남깁니다. */
    private fun onQuickReply(text: String) {
        val clean = text.replace('\n', ' ').trim()
        if (replySpeaking == clean) {
            replySpeaker.stop()
            return
        }
        replySpeaker.speak(clean)
        val at = transcript.getTurns().lastOrNull()?.end ?: 0.0
        transcript.addFinal(listOf(Segment("qr-${quickReplyCount++}", SpeakerDirectory.QUICK_REPLY, clean, at, at)))
        // 답했으니 추천은 지우고 기본 답장만 남깁니다.
        replyAi.clear()
        renderTurns()
    }

    private fun renderStatus() {
        val paused = isAudioPaused()
        val kind = errorKind()
        val (label, chip) = when {
            isPreview -> R.string.status_preview to StatusRow.Kind.LISTENING
            kind == ErrorKind.PERMISSION -> R.string.status_mic_permission to StatusRow.Kind.ERROR
            kind == ErrorKind.MIC -> R.string.status_mic_error to StatusRow.Kind.ERROR
            kind == ErrorKind.OTHER -> R.string.status_connect_error to StatusRow.Kind.ERROR
            paused -> R.string.status_audio_paused to StatusRow.Kind.IDLE
            phase == Phase.RECORDING -> R.string.status_listening to StatusRow.Kind.LISTENING
            phase == Phase.RECONNECTING -> R.string.status_reconnecting to StatusRow.Kind.IDLE
            phase == Phase.CONNECTING -> R.string.status_connecting to StatusRow.Kind.IDLE
            phase == Phase.STOPPING -> R.string.status_stopping to StatusRow.Kind.IDLE
            else -> R.string.status_idle to StatusRow.Kind.IDLE
        }
        statusRow.setStatus(getString(label), chip)
        if (chip != StatusRow.Kind.LISTENING) {
            statusIcon.scaleX = 1f
            statusIcon.scaleY = 1f
        }
        val idle = !isPreview && !isLive() && phase != Phase.STOPPING && phase != Phase.ERROR
        statusRow.setEndVisible(!idle && phase != Phase.STOPPING)

        startPanel.visibility = if (idle) View.VISIBLE else View.GONE
        renderGuestCard(idle)
        renderErrorPanel(kind)
        applyOrbMode()
        renderEmptyState(visibleTurns().isEmpty())
        renderQuickReplies()
        updateAtmosphere()
    }

    /** 오류 화면 (Figma 217:4175 마이크 오류, 120:5263 마이크 권한). 그 밖의 오류는 받은 메시지를 설명으로 보여 줍니다. */
    private fun renderErrorPanel(kind: ErrorKind?) {
        errorPanel.visibility = if (kind == null) View.GONE else View.VISIBLE
        if (kind == null) return
        val (title, subtitle, action) = when (kind) {
            ErrorKind.PERMISSION -> Triple(getString(R.string.mic_permission_title), getString(R.string.mic_permission_subtitle), R.string.open_permission_settings)
            ErrorKind.MIC -> Triple(getString(R.string.mic_error_title), getString(R.string.mic_error_subtitle), R.string.retry)
            ErrorKind.OTHER -> Triple(getString(R.string.connect_error_title), errorMessage.orEmpty(), R.string.retry)
        }
        errorTitle.text = title
        errorSubtitle.text = subtitle
        errorButton.setText(action)
        // 권한 설정 열기는 채운 버튼, 다시 시도는 밝은 버튼입니다.
        val filled = kind == ErrorKind.PERMISSION
        errorButton.setBackgroundResource(if (filled) R.drawable.bg_button_filled_medium else R.drawable.bg_button_basic_medium)
        errorButton.setTextColor(color(if (filled) android.R.color.white else R.color.voium_text_primary))
        if (kind == ErrorKind.MIC) scheduleMicRetry()
    }

    private fun resetConversation() {
        conversationId = ConversationRecord.newId()
        conversationSaved = false
        guestVoices.clear()
        atmosphere.reset()
        transcript.reset()
        speakerDirectory.reset()
        turnTimes.clear()
        // 새 대화의 첫 자막도 떠오르며 나타나도록 화면 기억을 지웁니다.
        lineTurnIds.replaceAll { null }
        shownTurnCount = 0
        shownTurnId = null
        shownText = ""
        charFadeStart = LongArray(0)
        seenWords.clear()
        pendingTurnId = null
        lastPartialAt = 0L
    }

    private fun showNotice(message: String) {
        handler.removeCallbacks(hideNotice)
        notice.text = message
        notice.visibility = View.VISIBLE
        handler.postDelayed(hideNotice, 3_200)
    }

    private fun setError(message: String?) {
        errorMessage = message
        if (::atmosphere.isInitialized) {
            renderStatus()
            renderTurns()
        }
    }

    // ---------------------------------------------------------------- 기타

    private fun bindViews() {
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        voiceOrb = findViewById(R.id.voice_orb)
        voiceOrb.style = IdleOrbStyle
        voiceOrb.sensitivity = AppMicSensitivity
        statusRow = StatusRow(this)
        previousLines = findViewById(R.id.previous_lines)
        previousScroll = findViewById(R.id.previous_scroll)
        previousScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE || event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                previousDraggedAt = SystemClock.uptimeMillis()
            }
            false
        }
        currentCaption = findViewById(R.id.current_caption)
        speakerPill = findViewById(R.id.speaker_pill)
        speakerPill.background = pill(SpeakerThemes.GUEST.color)
        speakerPillLabel = findViewById(R.id.speaker_pill_label)
        currentTime = findViewById(R.id.current_time)
        currentScroll = findViewById(R.id.current_scroll)
        currentText = findViewById(R.id.current_text)
        suggestion = findViewById(R.id.suggestion)
        suggestionContext = findViewById(R.id.suggestion_context)
        suggestionText = findViewById(R.id.suggestion_text)
        suggestionDots = findViewById(R.id.suggestion_dots)
        suggestionIcon = findViewById(R.id.suggestion_icon)
        suggestionContextRow = findViewById(R.id.suggestion_context_row)
        quickReplyBar = findViewById(R.id.quick_reply_bar)
        quickReplies = findViewById(R.id.quick_replies)
        notice = findViewById(R.id.notice)
        startPanel = findViewById(R.id.start_panel)
        errorPanel = findViewById(R.id.error_panel)
        errorTitle = findViewById(R.id.error_title)
        errorSubtitle = findViewById(R.id.error_subtitle)
        errorButton = findViewById(R.id.error_button)
        helpOverlay = findViewById(R.id.help_overlay)
        guestCard = findViewById(R.id.guest_card)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 한국어 문장이 어절 중간에서 끊기지 않게 합니다.
            currentText.lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
            suggestionText.lineBreakWordStyle = LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE
        }
    }

    /** 배경과 블롭은 시스템 바 뒤까지 칠하고, 글자와 버튼은 시스템 바를 피해 배치합니다. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            content.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        cornerRadius = 999f * resources.displayMetrics.density
        setColor(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // 시스템의 '애니메이션 삭제' 설정을 따릅니다.
    private fun areAnimationsEnabled() = ValueAnimator.areAnimatorsEnabled()

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private data class PreviewStep(val atMs: Long, val final: Boolean, val speaker: String, val text: String)

    private companion object {
        /** 입자 링: 대기 중 1073dp, 듣는 중 900dp. 듣는 중 링 중심은 대기 중보다 왼쪽 위(-90.5, -42.5dp)입니다. */
        const val IDLE_RING_DP = 1073f
        const val LISTENING_RING_DP = 900f
        const val LISTENING_RING_SHIFT_X = -90
        const val LISTENING_RING_SHIFT_Y = -42
        const val MIC_RETRY_MS = 5_000L

        const val RC_MIC = 100
        const val RESULT_TAG = "SttResult"
        const val PREFS = SettingsActivity.PREFS
        const val KEY_FINAL_ONLY = "final_only"

        /** 지금 문단 위에 남겨 두는 지난 문단 수 (Figma previous_lines) */
        /** 위에 쌓아 두는 지난 문단 수(끌어 올려 볼 수 있음). 화면에는 약 세 줄이 보입니다. */
        const val MAX_PREVIOUS_LINES = 100
        /** 지난 대화를 끌어 올린 뒤 이만큼 손을 떼고 있으면 다시 최근 줄로 내려갑니다. */
        const val PREVIOUS_FOLLOW_DELAY_MS = 6_000L

        /** 지난 문단 진하기: 가장 최근 줄부터 (Figma previous_lines) */
        val LINE_ALPHAS = floatArrayOf(0.9f, 0.75f, 0.45f)

        /** 자막 애니메이션: 문단이 떠오르는 시간, 지난 줄 사이 간격 */
        const val RISE_MS = 320L
        const val LINE_GAP_DP = 20

        /** 깜빡임 방지: 새 화자 문단을 지켜보는 시간, 끝부분을 남겨 두는 시간, "말하는 중"을 유지하는 시간 */
        const val SETTLE_MS = 300L
        const val TAIL_HOLD_MS = 800L
        const val SPEAKING_HOLD_MS = 1_200L

        /** 새로 나타난 글자가 진해지는 시간 */
        const val CHAR_FADE_MS = 280L

        /** 화자 태그 배경: 화자 색의 12% */
        const val TAG_ALPHA = 0x1F

        /** 대화 제안 질문의 진하기 (Figma opacity 40%)와 바뀌는 간격 */
        const val SUGGESTION_ALPHA = 0.4f
        /** 대화 제안 카드가 밀려 나가고 들어오는 거리와 시간 */
        const val SUGGESTION_SLIDE_DP = 120
        const val SUGGESTION_SLIDE_MS = 260L
        /** 이 시간 안에 나눈 지난 대화의 주제만 대화 제안에 씁니다(사흘). */
        const val LAST_TOPIC_MS = 3 * 24 * 60 * 60_000L
        /** 빠른 답장을 다 읽은 뒤 마이크를 더 막아 두는 시간(스피커 울림) */
        const val REPLY_ECHO_HOLD_MS = 500L
        const val ACTION_DEBUG_REPLY = "com.example.pleos.DEBUG_QUICK_REPLY"
        const val EXTRA_DEBUG_REPLY = "reply"
        const val SUGGESTION_INTERVAL_MS = 5_000L

        /**
         * 등록된 목소리로 판단하는 기준입니다(0~1, 높을수록 엄격).
         * 2026-11-08 시험에서 0.5는 비슷한 목소리를 등록된 사람으로 잘못 판단했고, 0.8은 구분했습니다.
         */
        const val SPEAKER_SENSITIVITY = 0.8
        const val EXTRA_MODE = "mode"
        const val MODE_PREVIEW = "preview"
        val PREVIEW_TOKEN = Any()

        /** 미리보기용 등록 화자. 나(파랑)·엄마(초록)는 등록된 사람, S3는 등록하지 않은 사람("화자 1")입니다. */
        val PREVIEW_PROFILES = listOf(
            VoiceProfile(label = "S1", name = "나", identifiers = emptyList(), createdAt = 0L, colorKey = "blue", sentences = 3),
            VoiceProfile(label = "S2", name = "엄마", identifiers = emptyList(), createdAt = 0L, colorKey = "green", sentences = 3),
        )

        /** Figma 66:1093 시안의 대화입니다. 마지막 화면이 시안과 같아집니다. */
        val PREVIEW_STEPS = listOf(
            PreviewStep(400, false, "S1", "창문 조금"),
            PreviewStep(1200, false, "S1", "창문 조금 열어드릴까요?"),
            PreviewStep(2200, true, "S1", "창문 조금 열어드릴까요?"),
            PreviewStep(3400, false, "S2", "괜찮다,"),
            PreviewStep(4200, false, "S2", "괜찮다, 바람 좋네"),
            PreviewStep(5000, true, "S2", "괜찮다, 바람 좋네"),
            PreviewStep(6200, false, "S3", "저 배고파요."),
            PreviewStep(7200, false, "S3", "저 배고파요. 휴게소 들러요?"),
            PreviewStep(8000, true, "S3", "저 배고파요. 휴게소 들러요?"),
            PreviewStep(9200, false, "S1", "다들 안전벨트"),
            PreviewStep(10000, false, "S1", "다들 안전벨트 했지?"),
            PreviewStep(11000, false, "S1", "다들 안전벨트 했지? 휴게소까지 20분"),
            PreviewStep(11800, false, "S1", "다들 안전벨트 했지? 휴게소까지 20분 남았대"),
            PreviewStep(13000, true, "S1", "다들 안전벨트 했지? 휴게소까지 20분 남았대"),
        )
    }
}
