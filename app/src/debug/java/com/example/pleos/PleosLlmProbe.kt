package com.example.pleos

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import android.util.Log

/**
 * 디버그 전용: Pleos LLM(Gleo AI)이 이 기기에서 답하는지 로그로 확인합니다.
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es llm device   (ON_DEVICE)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es llm server   (SERVER)
 * adb shell am broadcast -n com.example.pleos/.DebugCarReceiver --es llm ai       (앱과 같은 창구, Claude 우선)
 * 로그: adb logcat -s LlmProbe
 */
object PleosLlmProbe {
    private const val TAG = "LlmProbe"
    private var llm: LLM? = null

    fun handle(context: Context, command: String) {
        if (command.startsWith("ai")) {
            // 앱이 쓰는 창구(TextAi: Claude 우선)로 질문합니다. 예: --es llm ai
            val prompt = if (command == "ai") "안녕하세요라고 한 단어로 답해 줘." else command.removePrefix("ai:")
            Log.i(TAG, "ai ask: $prompt")
            com.example.pleos.ai.TextAi.generate(context, prompt, 30_000) { Log.i(TAG, "ai answer: $it") }
            return
        }
        if (command.startsWith("gleo")) {
            // 앱과 같은 길(GleoAi: 앱 등록 → 요청)로 짧은 질문을 보냅니다. 예: --es llm gleo
            val prompt = if (command == "gleo") "안녕하세요라고 한 단어로 답해 줘." else command.removePrefix("gleo:")
            Log.i(TAG, "gleo ask: $prompt")
            com.example.pleos.ai.GleoAi.generate(context, prompt, 30_000) { Log.i(TAG, "gleo answer: $it") }
            return
        }
        if (command.startsWith("model:")) {
            // 앱 등록 후 모델 이름을 바꿔 짧은 질문을 보냅니다. 예: --es llm model:gpt-4o-mini
            val model = command.removePrefix("model:")
            llm?.let { runCatching { it.release() } }
            val client = LLM(context.applicationContext, Mode.SERVER).also { it.initialize() }
            llm = client
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            main.postDelayed({
                client.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET, object : ai.pleos.playground.llm.listener.OnServerConnectionListener {
                    override fun onConnected() {
                        Log.i(TAG, "registered; model=$model")
                        val listener = object : ResultListener {
                            override fun onResponse(text: String, isFinal: Boolean) = Log.i(TAG, "model answer: $text").let { }
                            override fun onError(message: String) = Log.w(TAG, "model error: $message").let { }
                        }
                        runCatching { client.setModelParameter(ai.pleos.playground.llm.data.ModelParameter.MODEL_NAME, model, listener) }
                            .onFailure { Log.w(TAG, "setModel", it) }
                        main.postDelayed({ client.generateContent("안녕하세요라고 한 단어로 답해 줘.", listener) }, 800)
                    }
                    override fun onFailed(message: String) = Log.w(TAG, "register failed: $message").let { }
                })
            }, 1500)
            return
        }
        llm?.let { runCatching { it.release() } }
        val mode = if (command == "server") Mode.SERVER else Mode.ON_DEVICE
        val client = LLM(context.applicationContext, mode)
        llm = client
        runCatching { client.initialize() }.onFailure { Log.w(TAG, "init", it) }
        Log.i(TAG, "start $mode")
        val prompt = "차 안에서 가족끼리 나눌 대화 질문을 한국어로 한 문장만 만들어 줘. 지금은 저녁이고 비가 와."
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching {
                client.generateContent(prompt, object : ResultListener {
                    override fun onResponse(text: String, isFinal: Boolean) = Log.i(TAG, "response final=$isFinal: $text").let { }
                    override fun onError(message: String) = Log.w(TAG, "error: $message").let { }
                })
            }.onFailure { Log.w(TAG, "generate", it) }
        }, 1500)
    }
}
