package com.example.pleos.stt

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.pleos.BuildConfig
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 마이크 녹음 한 번에 해당하는 Speechmatics 실시간 인식 세션입니다. 녹음할 때마다 새로 만듭니다.
 * sori-node의 speechmatics.js(SpeechmaticsSession)를 Android에 맞게 옮긴 것입니다.
 *
 * 모든 공개 메서드와 콜백은 메인 스레드에서 실행됩니다. 오디오는 별도 스레드에서 읽고 보냅니다.
 *
 * reconnect를 켜면 녹음 중 연결이 끊겨도 세션을 끝내지 않습니다.
 * - 마이크는 계속 켜 두고, 그동안의 소리는 최대 MAX_BACKLOG_CHUNKS(20초)까지 보관합니다.
 * - 0.5 → 1 → 2 → 4 … 최대 30초 간격으로 다시 연결하고, 네트워크가 돌아오면 바로 다시 연결합니다.
 * - 다시 연결되면 보관한 소리부터 보내 끊긴 동안 한 말도 자막으로 나옵니다.
 * 인증 실패·사용 한도 초과처럼 다시 연결해도 안 되는 오류는 지금처럼 세션을 끝냅니다.
 */
class SpeechmaticsSession(
    private val httpClient: OkHttpClient,
    private val tokenProvider: suspend () -> String,
    private val options: Options,
    private val listener: Listener,
    /** 네트워크 복구 감지와 마이크 뺏김 감지에 씁니다. 없으면 두 기능 없이 동작합니다. */
    context: Context? = null,
    /** 녹음한 소리 조각을 함께 받을 곳(녹음 스레드에서 부름). 일시 정지 중에는 무음 조각이 갑니다. */
    private val audioTap: ((ByteArray) -> Unit)? = null,
) {
    data class Options(
        val language: String = "ko",
        val diarization: Boolean = true,
        val maxDelay: Double = 2.0,
        /** 화자 식별: 등록된 목소리입니다. 비어 있으면 일반 화자 구분만 합니다. */
        val enrolledSpeakers: List<EnrolledSpeaker> = emptyList(),
        /** 높을수록 등록된 사람으로 판단하는 기준이 엄격해집니다(0~1). */
        val speakerSensitivity: Double = 0.5,
        /** 목소리 등록: 종료할 때 SpeakersResult로 식별값을 받습니다. */
        val getSpeakers: Boolean = false,
        /** 연결이 끊기면 소리를 보관하며 다시 연결합니다(자막 화면). 목소리 등록은 한 연결로 끝나야 해서 끕니다. */
        val reconnect: Boolean = false,
    )

    /** RECONNECTING: 녹음은 계속하면서 서버에 다시 연결하는 중입니다. */
    enum class State { STOPPED, CONNECTING, RECORDING, RECONNECTING, STOPPING }

    interface Listener {
        fun onState(state: State)
        fun onPartial(segments: List<Segment>)
        fun onFinal(segments: List<Segment>)
        fun onError(message: String)

        /** 마이크 입력 크기입니다. 0(무음)~1 사이이며 100ms마다 호출됩니다. */
        fun onLevel(level: Float) = Unit

        /** 마이크 소리 크기(dBFS, 무음이면 -96)입니다. onLevel과 함께 100ms마다 호출됩니다. */
        fun onDecibel(dbfs: Float) = Unit

        /** 목소리 등록 결과입니다. getSpeakers를 켠 세션이 끝날 때 한 번 호출됩니다. */
        fun onSpeakers(speakers: List<EnrolledSpeaker>) = Unit

        /** 세션을 끝내지 않는 서버 경고입니다. 예: speaker_id(등록된 목소리를 사용하지 못함) */
        fun onWarning(type: String, reason: String) = Unit

        /** 다시 연결하게 된 이유입니다(RECONNECTING으로 바뀌기 직전에 호출). */
        fun onConnectionLost(reason: String) = Unit

        /**
         * 시스템이 이 앱의 마이크 입력을 막았는지입니다(다른 앱·음성 비서·통화가 마이크를 가져감).
         * 막힌 동안 입력은 무음으로 들어오며, 풀리면 그대로 이어집니다. Android 10 이상에서만 알 수 있습니다.
         */
        fun onMicrophoneSilenced(silenced: Boolean) = Unit
    }

    var state = State.STOPPED
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var started = false
    private var disposed = false
    private var cancelled = false
    private var reportedError = false
    private var endReceived = false
    private var speakersReceived = false
    private var batch = 0
    private var wordDelimiter: String? = null

    @Volatile private var socket: WebSocket? = null
    @Volatile private var audioActive = false

    // 보낼 소리: 녹음 스레드가 넣고, 연결돼 있으면 앞에서부터 보냅니다. lock으로 보호합니다.
    private val lock = Any()
    private val backlog = ArrayDeque<ByteArray>()
    private val inFlight = ArrayDeque<ByteArray>()   // 보냈지만 서버가 받았다고 아직 답하지 않은 소리
    @Volatile private var streaming = false            // 지금 연결로 소리를 보내도 되는지
    @Volatile private var paused = false               // 일시 정지 중에는 무음을 보냅니다
    @Volatile private var lastAckAt = 0L
    private var droppedChunks = 0

    // 다시 연결
    private var connection = 0                         // 몇 번째 연결인지(0부터)
    private var deliveredChunks = 0L                   // 지난 연결들에서 서버가 받은 소리 수
    private var timeOffset = 0.0                       // 이 연결의 0초가 세션 전체에서 몇 초인지
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var waitingToReconnect = false
    private val connectivity = context?.getSystemService(ConnectivityManager::class.java)
    private val audioManager = context?.getSystemService(AudioManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var micSilenced = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private val sentChunks = AtomicInteger()
    private val acknowledgedChunks = AtomicInteger()

    private var recognitionStarted: CompletableDeferred<Unit>? = null
    private var endOfTranscript: CompletableDeferred<Unit>? = null

    private val defaultSpeaker get() = if (options.diarization) "UU" else "S1"

    /** 호출 전에 RECORD_AUDIO 권한을 받아 두어야 합니다. */
    @MainThread
    fun start() {
        check(!started && !disposed) { "새 녹음을 위해 새 세션을 만들어 주세요." }
        started = true
        setState(State.CONNECTING)
        startJob = scope.launch {
            try {
                val record = createAudioRecord()
                audioRecord = record
                checkActive()
                // 권한 요청 등으로 짧은 유효기간을 쓰지 않도록, 마이크가 준비된 뒤 토큰을 받습니다.
                val token = tokenProvider().trim()
                checkActive()
                if (token.isEmpty()) throw SttException("Speechmatics 임시 토큰을 발급하지 못했습니다.")
                openRecognition(token)
                checkActive()
                beginStreaming()
                setState(State.RECORDING)
                // RecognitionStarted를 받기 전에는 오디오를 보내지 않습니다.
                startAudioLoop(record)
                if (options.reconnect) watchNetwork()
                watchMicrophone(record)
            } catch (e: Throwable) {
                if (!cancelled && !disposed) reportError(messageOf(e))
                cleanup()
                setState(State.STOPPED)
                if (e is CancellationException) throw e
            }
        }
    }

    /** 남은 확정 결과를 받은 뒤 종료합니다. 연결 중이면 바로 취소합니다. */
    @MainThread
    fun stop() {
        when (state) {
            State.STOPPED, State.STOPPING -> return
            State.CONNECTING -> {
                cancelled = true
                startJob?.cancel()
                cleanup()
                setState(State.STOPPED)
                return
            }
            State.RECONNECTING -> {
                // 연결이 없으니 보관한 소리는 보내지 못하고 끝냅니다.
                cancelled = true
                cleanup()
                setState(State.STOPPED)
                return
            }
            State.RECORDING -> Unit
        }
        setState(State.STOPPING)
        scope.launch {
            try {
                stopAudio()
                flushBacklog()
                val ws = socket ?: return@launch
                val end = CompletableDeferred<Unit>()
                endOfTranscript = end
                completeIfFinished()
                // 오디오 프레임과 EndOfStream은 같은 WebSocket에서 순서대로 전송됩니다.
                // last_seq_no는 보낸 오디오 프레임의 총 개수입니다.
                ws.send(JSONObject().put("message", "EndOfStream").put("last_seq_no", sentChunks.get()).toString())
                val finished = withTimeoutOrNull(END_TIMEOUT_MS) { end.await() }
                if (finished == null) {
                    reportError("최종 자막 수신 시간이 초과되었습니다. 마지막 발화 일부가 누락되었을 수 있습니다.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                reportError(messageOf(e))
            } finally {
                cleanup()
                setState(State.STOPPED)
            }
        }
    }

    /**
     * 잠시 멈춤: 켜 두면 마이크 소리 대신 무음을 보냅니다. 연결은 그대로라 풀면 바로 이어집니다.
     * 통화 중이거나 다른 앱이 잠깐 오디오를 쓸 때 씁니다.
     */
    @MainThread
    fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    /** 화면을 떠날 때 즉시 정리합니다. 남은 확정 결과를 기다리지 않습니다. */
    @MainThread
    fun dispose() {
        if (disposed) return
        disposed = true
        cancelled = true
        startJob?.cancel()
        recognitionStarted?.completeExceptionally(CancellationException())
        endOfTranscript?.complete(Unit)
        cleanup()
        state = State.STOPPED
        scope.cancel()
    }

    @SuppressLint("MissingPermission") // 호출하는 화면에서 권한을 확인합니다.
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) throw SttException("이 기기에서 마이크 녹음을 지원하지 않습니다.")
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                maxOf(minBuffer, CHUNK_BYTES * 4),
            )
        } catch (e: SecurityException) {
            throw SttException("마이크 사용 권한을 허용한 뒤 다시 시작해 주세요.")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw SttException("마이크를 사용할 수 없습니다. 다른 앱에서 사용 중인지 확인해 주세요.")
        }
        return record
    }

    private suspend fun openRecognition(token: String) {
        val started = CompletableDeferred<Unit>()
        recognitionStarted = started
        val url = "$ENDPOINT?jwt=${URLEncoder.encode(token, "UTF-8")}"
        socket = httpClient.newWebSocket(Request.Builder().url(url).build(), SocketListener())
        withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) { started.await() }
            ?: throw SttException("음성 인식 서버의 응답이 없습니다. 토큰과 네트워크 연결을 확인해 주세요.")
        recognitionStarted = null
    }

    private fun startRecognitionMessage(): String =
        RecognitionConfig.startRecognition(options, SAMPLE_RATE).toString()

    /** 종료 시 기다릴 메시지(EndOfTranscript, 등록 중이면 SpeakersResult도)를 모두 받았는지 확인합니다. */
    private fun completeIfFinished() {
        if (endReceived && (!options.getSpeakers || speakersReceived)) endOfTranscript?.complete(Unit)
    }

    private fun startAudioLoop(record: AudioRecord) {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            fail("마이크를 시작하지 못했습니다. 다른 앱에서 사용 중인지 확인해 주세요.")
            return
        }
        audioActive = true
        audioThread = Thread({
            try {
                readAndSend(record)
            } finally {
                // 읽는 도중 해제하지 않도록, 녹음 스레드가 끝날 때 직접 해제합니다.
                runCatching { record.release() }
            }
        }, "speechmatics-audio").also { it.start() }
    }

    private fun readAndSend(record: AudioRecord) {
        val buffer = ByteArray(CHUNK_BYTES)
        var readCount = 0
        val fake = debugMicFile?.takeIf { it.isFile }?.let { runCatching { it.inputStream().buffered() }.getOrNull() }
        fake?.skip(WAV_HEADER_BYTES)
        while (audioActive) {
            val read = record.read(buffer, 0, buffer.size)
            if (!audioActive) break
            if (read < 0) {
                main.post { fail("마이크 연결이 끊어졌습니다. 입력 장치를 확인한 뒤 다시 시작해 주세요.") }
                break
            }
            if (read == 0) continue
            if (fake != null) {
                // 디버그: 마이크 대신 파일 소리를 같은 속도로 넣습니다. 파일이 끝나면 무음입니다.
                java.util.Arrays.fill(buffer, 0, read, 0)
                var at = 0
                while (at < read) {
                    val n = fake.read(buffer, at, read - at)
                    if (n <= 0) break
                    at += n
                }
            }
            val chunk = if (paused) ByteArray(read) else buffer.copyOf(read)
            audioTap?.invoke(chunk)
            reportLevel(chunk, read, ++readCount)
            val problem = queueAndSend(chunk)
            // 같은 문제가 여러 번 올라와도 지금 연결에서 처음 한 번만 처리합니다.
            if (problem != null) main.post { if (state == State.RECORDING) connectionLost(problem) }
        }
    }

    /**
     * 소리를 보관함 뒤에 넣고, 연결돼 있으면 앞에서부터 보냅니다. 다시 연결한 직후에는 한 번에
     * 여러 개씩 보내 밀린 소리를 따라잡습니다. 문제가 생기면 그 이유를 돌려줍니다.
     */
    private fun queueAndSend(chunk: ByteArray?): String? = synchronized(lock) {
        if (chunk != null) {
            backlog.addLast(chunk)
            while (backlog.size > MAX_BACKLOG_CHUNKS) {
                backlog.removeFirst()
                droppedChunks++
            }
        }
        val ws = socket
        if (!streaming || ws == null) return null
        var sentNow = 0
        while (backlog.isNotEmpty() && sentNow < MAX_CHUNKS_PER_READ) {
            val unacknowledged = sentChunks.get() - acknowledgedChunks.get()
            if (ws.queueSize() + CHUNK_BYTES > MAX_QUEUED_BYTES || unacknowledged >= MAX_UNACKNOWLEDGED) break
            val next = backlog.first()
            if (!ws.send(next.toByteString())) return "마이크 음성을 전송하지 못했습니다. 네트워크 연결을 확인해 주세요."
            backlog.removeFirst()
            inFlight.addLast(next)
            sentChunks.incrementAndGet()
            sentNow++
        }
        // 서버가 한동안 받았다고 답하지 않으면 연결이 끊긴 것으로 봅니다.
        val waiting = backlog.isNotEmpty() || inFlight.isNotEmpty()
        if (waiting && SystemClock.uptimeMillis() - lastAckAt > STALL_MS) {
            return "네트워크가 느려 음성을 실시간으로 전송할 수 없습니다. 연결을 확인한 뒤 다시 시작해 주세요."
        }
        null
    }

    /** 마무리 전에 보관함에 남은 소리를 모두 보냅니다(최대 2초 기다림). */
    private suspend fun flushBacklog() = withContext(Dispatchers.IO) {
        val until = SystemClock.uptimeMillis() + 2_000
        while (SystemClock.uptimeMillis() < until) {
            val left = synchronized(lock) { backlog.size }
            if (left == 0 || queueAndSend(null) != null) break
            Thread.sleep(20)
        }
    }

    /**
     * 마이크 입력 크기를 계산해 화면에 알립니다. 디버그 빌드에서는 1초마다 로그도 남깁니다.
     * 확인: adb logcat -s SttAudio
     */
    private fun reportLevel(buffer: ByteArray, length: Int, read: Int) {
        val (dbfs, peak) = AudioLevel.measure(buffer, length)
        // -60dBFS 이하는 무음, 0dBFS는 최대로 봅니다.
        val level = ((dbfs + 60) / 60).coerceIn(0.0, 1.0).toFloat()
        main.post {
            if (!disposed && (state == State.RECORDING || state == State.RECONNECTING)) {
                listener.onLevel(level)
                listener.onDecibel(dbfs.toFloat())
            }
        }
        if (BuildConfig.DEBUG && read % 10 == 0) {
            Log.d(
                AUDIO_TAG,
                String.format(
                    java.util.Locale.US, "level %.1f dBFS, peak %d, chunks sent %d, acked by server %d, waiting %d",
                    dbfs, peak, sentChunks.get(), acknowledgedChunks.get(), synchronized(lock) { backlog.size },
                ),
            )
        }
    }

    /** 녹음을 멈추고, 마지막 오디오 전송이 끝날 때까지 기다립니다. */
    private suspend fun stopAudio() {
        audioActive = false
        audioRecord?.let { runCatching { it.stop() } }
        val thread = audioThread
        if (thread != null) withContext(Dispatchers.IO) { thread.join(1_000) }
        audioThread = null
    }

    private fun handleMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull()
        val type = message?.stringOrNull("message")
        if (message == null || type == null) {
            fail("음성 인식 서버에서 올바르지 않은 응답을 받았습니다.")
            return
        }
        when (type) {
            "RecognitionStarted" -> {
                wordDelimiter = message.optJSONObject("language_pack_info")?.opt("word_delimiter") as? String
                recognitionStarted?.complete(Unit)
            }
            "AudioAdded" -> {
                val seq = message.optInt("seq_no", -1)
                if (seq >= 0) synchronized(lock) {
                    val acked = acknowledgedChunks.accumulateAndGet(seq) { a, b -> maxOf(a, b) }
                    while (inFlight.size > sentChunks.get() - acked) inFlight.removeFirst()
                    lastAckAt = SystemClock.uptimeMillis()
                }
            }
            "AddTranscript" -> {
                val segments = adjust(
                    TranscriptParser.parse(message, options.language, wordDelimiter, "final-${batch++}", defaultSpeaker),
                )
                listener.onPartial(emptyList())
                if (segments.isNotEmpty()) listener.onFinal(segments)
            }
            "AddPartialTranscript" -> listener.onPartial(
                adjust(TranscriptParser.parse(message, options.language, wordDelimiter, "partial", defaultSpeaker)),
            )
            "EndOfTranscript" -> {
                // 먼저 상태를 확인합니다. completeIfFinished()가 마무리를 끝내면 상태가 바로 STOPPED로 바뀌기 때문입니다.
                val expected = state == State.STOPPING
                endReceived = true
                if (expected) completeIfFinished()
                else connectionLost("음성 인식 세션이 종료되었습니다. 다시 시작해 주세요.")
            }
            "SpeakersResult" -> {
                speakersReceived = true
                // 다시 연결한 세션이면 자막과 같은 이름(S1~2 등)으로 맞춥니다.
                listener.onSpeakers(RecognitionConfig.parseSpeakers(message).map { speaker ->
                    val enrolled = options.enrolledSpeakers.any { it.label == speaker.label }
                    if (connection == 0 || enrolled) speaker else speaker.copy(label = "${speaker.label}~$connection")
                })
                completeIfFinished()
            }
            "Warning" -> listener.onWarning(
                message.stringOrNull("type") ?: "warning",
                message.stringOrNull("reason").orEmpty(),
            )
            "Error" -> {
                val errorType = message.stringOrNull("type") ?: "오류"
                val reason = message.stringOrNull("reason") ?: "음성 인식을 완료하지 못했습니다."
                connectionLost("Speechmatics $errorType: $reason", retryable = errorType in RETRYABLE_ERRORS)
            }
        }
    }

    /**
     * 다시 연결하면 서버의 시간은 0초부터, 등록하지 않은 사람의 번호(S1, S2…)는 처음부터 다시 매겨집니다.
     * 시간은 세션 전체 기준으로 옮기고, 번호는 연결마다 다른 사람으로 구분되게 바꿉니다
     * (이전 연결의 S1과 새 연결의 S1이 같은 사람이라는 보장이 없음). 등록한 사람의 이름은 그대로입니다.
     */
    private fun adjust(segments: List<Segment>): List<Segment> {
        if (connection == 0) return segments
        return segments.map {
            val enrolled = it.speaker == defaultSpeaker || options.enrolledSpeakers.any { e -> e.label == it.speaker }
            it.copy(
                speaker = if (enrolled) it.speaker else "${it.speaker}~$connection",
                start = it.start + timeOffset,
                end = it.end + timeOffset,
            )
        }
    }

    private fun handleClosed(code: Int) {
        if (cancelled || disposed) return
        if (endReceived && state == State.STOPPING) {
            endOfTranscript?.complete(Unit)
            return
        }
        connectionLost(closeMessage(code), retryable = code !in FATAL_CLOSE_CODES)
    }

    // ---------------------------------------------------------------- 다시 연결

    /** 새 연결로 소리를 보내기 시작합니다. 서버의 번호(seq_no)는 연결마다 1부터 셉니다. */
    private fun beginStreaming() = synchronized(lock) {
        sentChunks.set(0)
        acknowledgedChunks.set(0)
        inFlight.clear()
        lastAckAt = SystemClock.uptimeMillis()
        streaming = true
    }

    /**
     * 연결이 끊겼을 때: 다시 연결할 수 있으면 녹음은 계속하며 다시 연결하고, 아니면 세션을 끝냅니다.
     * 다시 연결하는 중 실패하면 다음 시도를 기다립니다.
     */
    private fun connectionLost(message: String, retryable: Boolean = true) {
        if (cancelled || disposed) return
        val canRetry = options.reconnect && retryable && audioActive
        when (state) {
            State.RECORDING -> if (!canRetry) { fail(message); return }
            State.RECONNECTING -> {
                if (!retryable) fail(message) else recognitionStarted?.completeExceptionally(SttException(message))
                return
            }
            State.STOPPED -> return
            else -> { fail(message); return }
        }
        Log.w(TAG, "connection lost: $message")
        synchronized(lock) {
            streaming = false
            // 서버가 받았다고 답하지 않은 소리는 다시 보내도록 보관함 앞에 돌려놓습니다.
            while (inFlight.isNotEmpty()) backlog.addFirst(inFlight.removeLast())
            deliveredChunks += acknowledgedChunks.get()
        }
        closeSocket()
        listener.onPartial(emptyList())
        listener.onConnectionLost(message)
        reconnectAttempt = 0
        setState(State.RECONNECTING)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val wait = (RECONNECT_FIRST_MS shl reconnectAttempt.coerceAtMost(6)).coerceAtMost(RECONNECT_MAX_MS)
        reconnectAttempt++
        reconnectJob = scope.launch {
            waitingToReconnect = true
            delay(wait)
            waitingToReconnect = false
            try {
                val token = tokenProvider().trim()
                if (token.isEmpty()) throw SttException("Speechmatics 임시 토큰을 발급하지 못했습니다.")
                if (state != State.RECONNECTING) return@launch
                openRecognition(token)
                if (state != State.RECONNECTING) return@launch
                connection++
                timeOffset = deliveredChunks * CHUNK_SECONDS
                beginStreaming()
                reconnectAttempt = 0
                Log.i(TAG, "reconnected (#$connection, ${synchronized(lock) { backlog.size }} chunks waiting, $droppedChunks dropped)")
                setState(State.RECORDING)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (state != State.RECONNECTING || cancelled || disposed) return@launch
                Log.w(TAG, "reconnect failed: ${e.message}")
                closeSocket()
                scheduleReconnect()
            }
        }
    }

    /** 네트워크가 돌아오면 기다리지 않고 바로 다시 연결합니다. */
    private fun watchNetwork() {
        val manager = connectivity ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (state == State.RECONNECTING && waitingToReconnect && !disposed) {
                        Log.i(TAG, "network available, reconnecting now")
                        reconnectAttempt = 0
                        scheduleReconnect()
                    }
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback, main) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "network watch unavailable: ${it.message}") }
    }

    /** 다른 앱·통화가 마이크를 가져가 이 앱 입력이 무음이 되는지 지켜봅니다(Android 10+). */
    private fun watchMicrophone(record: AudioRecord) {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val sessionId = record.audioSessionId
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
                val silenced = mine.isClientSilenced
                if (silenced != micSilenced && !disposed) {
                    micSilenced = silenced
                    Log.i(TAG, "microphone silenced=$silenced")
                    listener.onMicrophoneSilenced(silenced)
                }
            }
        }
        manager.registerAudioRecordingCallback(callback, main)
        recordingCallback = callback
    }

    private fun closeSocket() {
        val ws = socket
        socket = null
        ws?.close(NORMAL_CLOSURE, null)
    }

    /** OkHttp 스레드의 이벤트를 메인 스레드로 옮기고, 지난 연결의 이벤트는 버립니다. */
    private inner class SocketListener : WebSocketListener() {
        private fun onMain(ws: WebSocket, block: () -> Unit) {
            main.post { if (socket === ws && !disposed) block() }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) = onMain(webSocket) {
            if (!webSocket.send(startRecognitionMessage())) {
                connectionLost("음성 인식 설정을 전송하지 못했습니다. 다시 시작해 주세요.")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) = onMain(webSocket) {
            if (!cancelled) handleMessage(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            onMain(webSocket) { handleClosed(code) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            onMain(webSocket) { handleClosed(code) }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onMain(webSocket) {
            if (cancelled) return@onMain
            if (endReceived) {
                endOfTranscript?.complete(Unit)
                return@onMain
            }
            when (response?.code) {
                401 -> connectionLost("Speechmatics 인증에 실패했습니다. 임시 토큰의 유효 기간과 API 키를 확인해 주세요.", retryable = false)
                else -> connectionLost("Speechmatics에 연결하지 못했습니다. 네트워크 연결을 확인해 주세요.")
            }
        }
    }

    private fun checkActive() {
        if (cancelled || disposed) throw CancellationException("연결이 취소되었습니다.")
    }

    private fun setState(next: State) {
        if (state == next) return
        state = next
        if (!disposed) listener.onState(next)
    }

    private fun reportError(message: String) {
        if (reportedError || disposed || cancelled) return
        reportedError = true
        listener.onError(message)
    }

    private fun fail(message: String) {
        reportError(message)
        cancelled = true
        recognitionStarted?.completeExceptionally(SttException(message))
        endOfTranscript?.complete(Unit)
        cleanup()
        setState(State.STOPPED)
    }

    private fun cleanup() {
        audioActive = false
        streaming = false
        reconnectJob?.cancel()
        reconnectJob = null
        networkCallback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        recordingCallback?.let { cb -> audioManager?.unregisterAudioRecordingCallback(cb) }
        recordingCallback = null
        synchronized(lock) {
            backlog.clear()
            inFlight.clear()
        }
        val record = audioRecord
        val thread = audioThread
        audioRecord = null
        audioThread = null
        if (record != null) {
            // stop()은 막혀 있는 read()를 풀어 줍니다. 녹음 스레드가 있으면 해제는 그 스레드가 합니다.
            runCatching { record.stop() }
            if (thread == null || !thread.isAlive) runCatching { record.release() }
        }
        recognitionStarted = null
        closeSocket()
        endOfTranscript = null
        if (!disposed) {
            listener.onPartial(emptyList())
            listener.onLevel(0f)
        }
    }

    private fun messageOf(error: Throwable): String = when (error) {
        is SttException -> error.message.orEmpty()
        is SecurityException -> "마이크 사용 권한을 허용한 뒤 다시 시작해 주세요."
        else -> "음성 인식 연결 중 오류가 발생했습니다."
    }

    private fun closeMessage(code: Int): String = when (code) {
        4001 -> "Speechmatics 인증에 실패했습니다. 다시 시작해 주세요."
        4005 -> "동시에 사용할 수 있는 음성 인식 연결 수를 넘었습니다. 잠시 후 다시 시작해 주세요."
        4006 -> "Speechmatics 사용 한도를 넘었습니다. 계정의 사용량을 확인해 주세요."
        1008 -> "오랫동안 음성이 없어 연결이 종료되었습니다. 다시 시작해 주세요."
        else -> "음성 인식 서버와의 연결이 끊어졌습니다. 확정된 자막은 보관되어 있습니다."
    }

    companion object {
        private const val TAG = "SttSession"
        private const val AUDIO_TAG = "SttAudio"
        private const val ENDPOINT = "wss://global.rt.speechmatics.com/v2"
        const val SAMPLE_RATE = 16_000

        /** 디버그 빌드 시험용: 정하면 마이크 대신 이 WAV(16kHz 모노 16비트) 소리를 씁니다. */
        @Volatile var debugMicFile: java.io.File? = null
        private const val WAV_HEADER_BYTES = 44L
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT // pcm_s16le

        /** 100ms 분량(16000Hz × 2바이트 × 0.1초)입니다. */
        private const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10
        private const val MAX_QUEUED_BYTES = SAMPLE_RATE * 2 * 5L
        private const val CHUNK_SECONDS = 0.1
        private const val MAX_UNACKNOWLEDGED = 50

        /** 끊긴 동안 보관할 소리: 20초. 넘으면 오래된 것부터 버립니다(너무 늦은 자막은 의미가 적음). */
        private const val MAX_BACKLOG_CHUNKS = 200
        /** 밀린 소리를 따라잡을 때 0.1초마다 최대 몇 개 보낼지(실제 속도의 4배) */
        private const val MAX_CHUNKS_PER_READ = 4
        /** 보낸 소리에 서버가 이 시간 넘게 답하지 않으면 끊긴 것으로 봅니다. */
        private const val STALL_MS = 8_000L
        private const val RECONNECT_FIRST_MS = 500L
        private const val RECONNECT_MAX_MS = 30_000L

        /** 다시 연결해도 소용없는 종료 코드: 인증 실패, 사용 한도 초과 */
        private val FATAL_CLOSE_CODES = setOf(4001, 4006)
        /** 다시 연결하면 풀릴 수 있는 서버 오류 */
        private val RETRYABLE_ERRORS = setOf("timelimit_exceeded", "quota_exceeded", "job_error", "buffer_error", "unknown_error", "internal_error")
        private const val RECOGNITION_TIMEOUT_MS = 20_000L
        private const val END_TIMEOUT_MS = 12_000L
        private const val NORMAL_CLOSURE = 1000
    }
}
