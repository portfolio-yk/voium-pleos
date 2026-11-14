package com.example.pleos

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 디버그 전용: Pleos TextToSpeech가 이 기기에서 읽어 주는지 로그로 확인합니다.
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es tts device   (ON_DEVICE)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es tts server   (SERVER, 앱 등록)
 * 로그: adb logcat -s TtsProbe
 */
object PleosTtsProbe {
    private const val TAG = "TtsProbe"
    private var tts: TextToSpeech? = null

    fun handle(context: Context, command: String) {
        tts?.let { runCatching { it.release() } }
        val mode = when (command) { "server" -> Mode.SERVER; "hybrid" -> Mode.HYBRID; else -> Mode.ON_DEVICE }
        val engine = TextToSpeech(context.applicationContext, mode)
        tts = engine
        Log.i(TAG, "init $mode")
        engine.initialize(object : OnInitializeListener {
            override fun onSuccess() {
                Log.i(TAG, "init ok")
                engine.addEventListener(object : EventListener {
                    override fun onStart() = Log.i(TAG, "start").let { }
                    override fun onReady() = Log.i(TAG, "ready").let { }
                    override fun onDone() = Log.i(TAG, "done").let { }
                    override fun onError(type: ErrorType) = Log.w(TAG, "error $type").let { }
                    override fun onStop() = Log.i(TAG, "stop").let { }
                    override fun onUpdatedRms(rms: Double) = Unit
                })
                runCatching { engine.setLocale(Locale.KOREA) }.onFailure { Log.w(TAG, "locale", it) }
                val say = { runCatching { engine.speak("안녕하세요. 휴게소에 들러요.", true) }.onFailure { Log.w(TAG, "speak", it) } }
                if (mode == Mode.ON_DEVICE) say() else engine.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET,
                    object : ai.pleos.playground.tts.listener.OnServerConnectionListener {
                        override fun onConnected() { Log.i(TAG, "registered"); say() }
                        override fun onFailed(type: ErrorType) = Log.w(TAG, "register failed $type").let { }
                    })
            }
            override fun onFailure(type: ErrorType) = Log.w(TAG, "init fail $type").let { }
        })
    }
}
