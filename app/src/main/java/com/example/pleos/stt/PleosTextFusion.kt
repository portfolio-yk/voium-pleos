package com.example.pleos.stt

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** 글자만 내는 음성 인식기. 소리는 앱이 직접 넣습니다. */
interface TextRecognizer {
    interface Callback {
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** 이 기기에서 쓸 수 없거나 도중에 멈춤. 이후로는 글자가 오지 않는다고 봅니다. */
        fun onFailed(reason: String)
    }

    fun start(callback: Callback)
    /** 녹음 스레드에서 부릅니다. 16kHz, 모노, 16비트 PCM. */
    fun send(chunk: ByteArray)
    fun release()
}

/**
 * Pleos SpeechToText SDK의 차량 안(ON_DEVICE) 인식기. 소리가 차 밖으로 나가지 않습니다.
 * 앱이 녹음한 소리를 sendAudio로 넣으면, 한 문장이 끝날 때마다 확정 결과를 주고 다음 문장을 이어 듣습니다.
 */
class PleosTextRecognizer(context: Context) : TextRecognizer {
    private val app = context.applicationContext
    private var engine: SpeechToText? = null
    @Volatile private var ready = false
    private val pending = ArrayDeque<ByteArray>()
    private val lock = Any()

    override fun start(callback: TextRecognizer.Callback) {
        val stt = runCatching { SpeechToText(app, Mode.ON_DEVICE) }.getOrElse {
            callback.onFailed("Pleos STT를 만들 수 없습니다: ${it.message}")
            return
        }
        engine = stt
        stt.addListener(object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) {
                if (isFinal) callback.onFinal(text) else callback.onPartial(text)
            }

            override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) = Unit
            override fun onStartedRecognition() = Unit
            override fun onEndedRecognition() = Unit
            override fun onReady() = Unit
            override fun onUpdatedRms(rms: Double) = Unit
            override fun onError(type: ErrorType) {
                Log.w(TAG, "error $type")
                callback.onFailed("Pleos STT 오류: $type")
            }
        })
        runCatching {
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { stt.setLocale(Locale.KOREA) }
                    // 초기화 전에 들어온 소리를 먼저 넣습니다.
                    synchronized(lock) {
                        while (pending.isNotEmpty()) stt.sendAudio(pending.removeFirst())
                        ready = true
                    }
                }

                override fun onFailure(type: ErrorType) = callback.onFailed("Pleos STT를 시작하지 못했습니다: $type")
            })
        }.onFailure { callback.onFailed("Pleos STT를 시작하지 못했습니다: ${it.message}") }
    }

    override fun send(chunk: ByteArray) {
        val stt = engine ?: return
        if (ready) {
            runCatching { stt.sendAudio(chunk) }
            return
        }
        synchronized(lock) {
            if (ready) {
                runCatching { stt.sendAudio(chunk) }
            } else {
                pending.addLast(chunk)
                while (pending.size > MAX_PENDING_CHUNKS) pending.removeFirst()
            }
        }
    }

    override fun release() {
        ready = false
        engine?.let { stt ->
            runCatching { stt.stop() }
            runCatching { stt.release() }
        }
        engine = null
    }

    companion object {
        private const val TAG = "PleosStt"
        private const val MAX_PENDING_CHUNKS = 50 // 5초
    }
}

/**
 * 글자는 Pleos STT(차 안)에서, 화자는 Speechmatics에서 받아 하나의 자막으로 합칩니다.
 *
 * Speechmatics 세션의 Listener 자리에 끼워 넣습니다. 화자 구분 결과는 [SpeakerTimeline]에만 쌓고,
 * 화면에는 Pleos 문장에 그 구간의 화자를 붙여 보냅니다. 나머지 알림(상태, 소리 크기, 오류 등)은 그대로 넘깁니다.
 * Pleos STT를 쓸 수 없으면(Pleos 차량이 아니거나 도중에 멈춤) 그때부터 Speechmatics 글자를 그대로 씁니다.
 */
class PleosTextFusion(
    private val downstream: SpeechmaticsSession.Listener,
    private val recognizer: TextRecognizer?,
    private val scheduler: Scheduler = HandlerScheduler(),
) : SpeechmaticsSession.Listener {

    fun interface Scheduler {
        fun schedule(delayMs: Long, action: () -> Unit)
    }

    private class HandlerScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        override fun schedule(delayMs: Long, action: () -> Unit) {
            handler.postDelayed(action, delayMs)
        }
    }

    private data class Pending(val id: String, val text: String, val start: Double, val end: Double, val createdAt: Double)

    private val timeline = SpeakerTimeline()
    private val fedBytes = AtomicLong()
    private val pending = ArrayDeque<Pending>()
    private var partialText = ""
    private var partialStart: Double? = null
    private var count = 0
    private var started = false
    private var recheckScheduled = false
    @Volatile private var released = false

    /** true면 Pleos 글자 대신 Speechmatics 결과를 그대로 화면에 씁니다. */
    @Volatile var fallback = recognizer == null
        private set

    /** Pleos STT를 못 쓰게 된 이유(없으면 null). 디버그 로그용입니다. */
    var failureReason: String? = null
        private set

    /** 녹음 시작부터 넣은 소리의 길이(초). Speechmatics 시간과 같은 기준입니다. */
    private val audioSeconds get() = fedBytes.get() / BYTES_PER_SECOND

    /** 녹음 스레드에서 매 조각마다 부릅니다(SpeechmaticsSession.audioTap). */
    fun onAudio(chunk: ByteArray) {
        fedBytes.addAndGet(chunk.size.toLong())
        if (!fallback && !released) recognizer?.send(chunk)
    }

    private val callback = object : TextRecognizer.Callback {
        override fun onPartial(text: String) {
            if (fallback || released) return
            val clean = text.trim()
            if (clean.isEmpty()) return
            if (partialStart == null) partialStart = (audioSeconds - FIRST_PARTIAL_LAG).coerceAtLeast(0.0)
            partialText = clean
            emitPartial()
        }

        override fun onFinal(text: String) {
            if (fallback || released) return
            val clean = text.trim()
            val end = (audioSeconds - FINAL_LAG).coerceAtLeast(0.0)
            val start = (partialStart ?: (end - 1.0)).coerceIn(0.0, end)
            partialText = ""
            partialStart = null
            if (clean.isNotEmpty()) pending += Pending("pleos-${count++}", clean, start, end, audioSeconds)
            resolvePending(force = false)
            emitPartial()
        }

        override fun onFailed(reason: String) {
            if (fallback || released) return
            failureReason = reason
            // 지금까지 받은 문장은 그대로 내보내고, 이후로는 Speechmatics 글자를 씁니다.
            resolvePending(force = true)
            partialText = ""
            partialStart = null
            fallback = true
            downstream.onPartial(emptyList())
            recognizer?.release()
        }
    }

    // ---------------------------------------------------------------- Speechmatics 알림

    override fun onState(state: SpeechmaticsSession.State) {
        when (state) {
            SpeechmaticsSession.State.RECORDING -> if (!started) {
                started = true
                recognizer?.start(callback)
            }
            SpeechmaticsSession.State.STOPPED -> finish()
            else -> Unit
        }
        downstream.onState(state)
    }

    override fun onPartial(segments: List<Segment>) {
        if (fallback) {
            downstream.onPartial(segments)
            return
        }
        timeline.setPartial(segments)
        emitPartial()
    }

    override fun onFinal(segments: List<Segment>) {
        if (fallback) {
            downstream.onFinal(segments)
            return
        }
        timeline.addFinal(segments)
        resolvePending(force = false)
        emitPartial()
    }

    override fun onError(message: String) = downstream.onError(message)
    override fun onLevel(level: Float) = downstream.onLevel(level)
    override fun onDecibel(dbfs: Float) = downstream.onDecibel(dbfs)
    override fun onSpeakers(speakers: List<EnrolledSpeaker>) = downstream.onSpeakers(speakers)
    override fun onWarning(type: String, reason: String) = downstream.onWarning(type, reason)
    override fun onConnectionLost(reason: String) = downstream.onConnectionLost(reason)
    override fun onMicrophoneSilenced(silenced: Boolean) = downstream.onMicrophoneSilenced(silenced)

    // ---------------------------------------------------------------- 합치기

    /**
     * 확정된 Pleos 문장에 화자를 붙여 내보냅니다. 화자 구분은 몇 초 늦게 확정되므로, 그 문장 구간까지
     * 화자가 확정되었거나 기다릴 만큼 기다렸을 때(force) 내보냅니다. 순서는 말한 순서 그대로입니다.
     */
    private fun resolvePending(force: Boolean) {
        val ready = ArrayList<Segment>()
        while (pending.isNotEmpty()) {
            val next = pending.first()
            val confirmed = timeline.finalUntil >= next.end - EDGE_TOLERANCE
            val waited = audioSeconds - next.createdAt >= MAX_WAIT_SECONDS
            if (!force && !confirmed && !waited) {
                if (!recheckScheduled) {
                    recheckScheduled = true
                    scheduler.schedule(RECHECK_MS) {
                        recheckScheduled = false
                        if (!released && !fallback) {
                            resolvePending(false)
                            emitPartial()
                        }
                    }
                }
                break
            }
            pending.removeFirst()
            val speaker = timeline.speakerFor(next.start, next.end) ?: timeline.latestSpeaker() ?: UNKNOWN
            ready += Segment(next.id, speaker, next.text, next.start, next.end)
        }
        if (ready.isNotEmpty()) downstream.onFinal(ready)
    }

    /** 아직 화자를 확정하지 못한 문장과 말하는 중인 문장을 임시 자막으로 보냅니다. */
    private fun emitPartial() {
        if (fallback) return
        val segments = ArrayList<Segment>()
        for (item in pending) {
            val speaker = timeline.speakerFor(item.start, item.end) ?: timeline.latestSpeaker() ?: UNKNOWN
            segments += Segment("partial-${item.id}", speaker, item.text, item.start, item.end)
        }
        if (partialText.isNotEmpty()) {
            val start = partialStart ?: audioSeconds
            val speaker = timeline.speakerFor(start, audioSeconds) ?: timeline.latestSpeaker() ?: UNKNOWN
            segments += Segment("partial-now", speaker, partialText, start, audioSeconds)
        }
        downstream.onPartial(segments)
    }

    /** 대화가 끝나면 남은 문장을 모두 내보내고 인식기를 닫습니다. */
    private fun finish() {
        if (released) return
        if (!fallback) {
            if (partialText.isNotEmpty()) {
                val end = audioSeconds
                pending += Pending("pleos-${count++}", partialText, partialStart ?: end, end, end)
                partialText = ""
                partialStart = null
            }
            resolvePending(force = true)
            downstream.onPartial(emptyList())
        }
        released = true
        recognizer?.release()
    }

    companion object {
        private const val BYTES_PER_SECOND = SpeechmaticsSession.SAMPLE_RATE * 2.0
        /** 첫 중간 결과는 말을 시작하고 이만큼(초) 뒤에 옵니다. */
        private const val FIRST_PARTIAL_LAG = 0.8
        /** 확정 결과는 말을 마치고 이만큼(초) 뒤에 옵니다(끝을 알아채는 데 걸리는 시간). */
        private const val FINAL_LAG = 0.4
        /** 화자 구분 확정 시간이 이만큼 모자라도 확정된 것으로 봅니다. */
        private const val EDGE_TOLERANCE = 0.3
        /** 화자 구분 확정을 최대 이만큼(초) 기다립니다. Speechmatics max_delay(2초)보다 조금 깁니다. */
        const val MAX_WAIT_SECONDS = 3.0
        private const val RECHECK_MS = 500L
        const val UNKNOWN = "UU"
    }
}
