package com.example.pleos.ai

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.OnServerConnectionListener
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.pleos.BuildConfig

/**
 * Pleos LLM(Gleo AI) 호출을 한곳에 모았습니다. 대화 기록 요약과 대화 제안이 같이 씁니다.
 *
 * Pleos 서버 LLM은 앱 등록(registerApp)으로 받은 토큰이 있어야 답합니다. 앱 키는 local.properties의
 * pleos.clientId / pleos.clientSecret에서 읽습니다(BuildConfig). 처음 한 번 등록하고, 서비스에 연결되기 전이면
 * 잠시 뒤 다시 묻습니다. 답이 없거나 실패하면 null을 돌려줍니다. 모든 콜백은 메인 스레드에서 옵니다.
 */
object GleoAi {
    private const val TAG = "GleoAi"
    private const val MAX_ATTEMPTS = 6
    private const val RETRY_MS = 1_500L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var llm: LLM? = null
    private var registered = false
    private var registering = false
    private val waiting = ArrayList<() -> Unit>()

    val isConfigured get() = BuildConfig.PLEOS_CLIENT_ID.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()

    /** prompt를 보내고 답 전체를 onResult로 돌려줍니다. 실패하거나 timeoutMs 안에 답이 없으면 null. */
    fun generate(context: Context, prompt: String, timeoutMs: Long, onResult: (String?) -> Unit) {
        if (!isConfigured) {
            Log.w(TAG, "pleos.clientId / pleos.clientSecret not set")
            main.post { onResult(null) }
            return
        }
        val client = client(context) ?: run {
            main.post { onResult(null) }
            return
        }
        var done = false
        fun finish(text: String?) {
            if (done) return
            done = true
            onResult(text?.takeIf { it.isNotBlank() })
        }
        main.postDelayed({ if (!done) Log.w(TAG, "timed out").also { finish(null) } }, timeoutMs)

        fun attempt(n: Int) {
            if (done) return
            val buffer = StringBuilder()
            val retry = { reason: String ->
                Log.w(TAG, "try ${n + 1} failed: $reason")
                if (n + 1 < MAX_ATTEMPTS) main.postDelayed({ attempt(n + 1) }, RETRY_MS) else finish(null)
            }
            runCatching {
                client.generateContent(prompt, object : ResultListener {
                    override fun onResponse(response: String, isFinal: Boolean) {
                        main.post {
                            buffer.append(response)
                            if (isFinal || response.isNotBlank()) finish(buffer.toString())
                        }
                    }

                    override fun onError(message: String) {
                        main.post { if (!done) retry(message) }
                    }
                })
            }.onFailure { retry(it.message.orEmpty()) }
        }
        whenRegistered(client) { attempt(0) }
    }

    private fun client(context: Context): LLM? = llm ?: runCatching {
        LLM(context.applicationContext, Mode.SERVER).also { it.initialize() }
    }.onFailure { Log.w(TAG, "LLM init failed: ${it.message}") }.getOrNull().also { llm = it }

    /** 앱 등록을 한 번만 합니다. 등록 중에 온 요청은 모아 두었다가 끝나면 이어서 보냅니다. */
    private fun whenRegistered(client: LLM, action: () -> Unit) {
        if (registered) {
            action()
            return
        }
        waiting += action
        if (registering) return
        registering = true
        fun release() {
            registering = false
            val actions = waiting.toList()
            waiting.clear()
            actions.forEach { it() }
        }
        // initialize()는 서비스에 연결하는 데 잠시 걸립니다.
        main.postDelayed({
            runCatching {
                client.registerApp(
                    BuildConfig.PLEOS_CLIENT_ID,
                    BuildConfig.PLEOS_CLIENT_SECRET,
                    object : OnServerConnectionListener {
                        override fun onConnected() = main.post {
                            registered = true
                            release()
                        }.let { }

                        override fun onFailed(message: String) = main.post {
                            Log.w(TAG, "registerApp failed: $message")
                            release()
                        }.let { }
                    },
                )
            }.onFailure {
                Log.w(TAG, "registerApp error: ${it.message}")
                release()
            }
        }, RETRY_MS)
    }
}
