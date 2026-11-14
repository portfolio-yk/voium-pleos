package com.example.pleos.ai

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 빠른 답장을 차량 스피커로 읽어 줍니다(Pleos TextToSpeech, 차 안 엔진). 소리가 차 밖으로 나가지 않습니다.
 * 처음 쓸 때 엔진을 준비하고, 준비 전에 부탁한 문장은 준비되면 읽습니다. 알림은 모두 메인 스레드로 옵니다.
 */
class ReplySpeaker(context: Context, private val listener: Listener) {
    interface Listener {
        fun onSpeakingChanged(text: String?)
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var current: String? = null
    /** 다른 문장으로 바꾸며 멈춘 시각. 그 직후 오는 멈춤·끝 알림은 앞 문장의 것이라 무시합니다. */
    private var switchedAt = 0L
    /** 끝 알림이 오지 않아도 마이크를 오래 막지 않도록 정한 마감 */
    private val deadline = Runnable { finish() }

    val speaking get() = current != null

    fun speak(text: String) {
        val clean = text.replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        stopInternal()
        current = clean
        listener.onSpeakingChanged(clean)
        if (ready) send(clean) else {
            pending = clean
            prepare()
        }
    }

    fun stop() {
        stopInternal()
        finish()
    }

    fun release() {
        stopInternal()
        current = null
        runCatching { engine?.release() }
        engine = null
        ready = false
    }

    private fun stopInternal() {
        pending = null
        main.removeCallbacks(deadline)
        if (current != null) {
            switchedAt = android.os.SystemClock.uptimeMillis()
            runCatching { engine?.stop() }
        }
    }

    private fun send(text: String) {
        // 두 번째 값(isFinal)이 false면 다음 조각을 기다리며 읽지 않습니다. 한 문장씩 바로 읽습니다.
        main.removeCallbacks(deadline)
        main.postDelayed(deadline, 8_000L + text.length * 250L)
        runCatching { engine?.speak(text, true) }.onFailure {
            Log.w(TAG, "speak failed: ${it.message}")
            finish()
        }
    }

    private fun prepare() {
        if (engine != null) return
        val tts = runCatching { TextToSpeech(app, Mode.ON_DEVICE) }.getOrElse {
            Log.w(TAG, "create failed: ${it.message}")
            finish()
            return
        }
        engine = tts
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() = main.post {
                    // 엔진이 준비된 뒤에 알림을 등록해야 합니다(그 전에는 SDK가 오류를 냅니다).
                    tts.addEventListener(events)
                    runCatching { tts.setLocale(Locale.KOREA) }
                    ready = true
                    pending?.let { send(it) }
                    pending = null
                }.let { }

                override fun onFailure(type: ErrorType) = main.post {
                    Log.w(TAG, "init failed: $type")
                    engine = null
                    finish()
                }.let { }
            })
        }.onFailure {
            Log.w(TAG, "init error: ${it.message}")
            engine = null
            finish()
        }
    }

    private val events = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = main.post { finishUnlessStale() }.let { }
        override fun onStop() = main.post { finishUnlessStale() }.let { }
        override fun onError(type: ErrorType) = main.post {
            Log.w(TAG, "error $type")
            finish()
        }.let { }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun finishUnlessStale() {
        if (android.os.SystemClock.uptimeMillis() - switchedAt < STALE_MS) return
        finish()
    }

    private fun finish() {
        main.removeCallbacks(deadline)
        if (current == null) return
        current = null
        listener.onSpeakingChanged(null)
    }

    companion object {
        private const val TAG = "ReplySpeaker"
        private const val STALE_MS = 500L
    }
}
