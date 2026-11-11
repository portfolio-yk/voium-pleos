package com.example.pleos.history

import ai.pleos.playground.llm.LLM
import ai.pleos.playground.llm.constant.Mode
import ai.pleos.playground.llm.listener.OnServerConnectionListener
import ai.pleos.playground.llm.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 대화 기록의 AI 요약을 만듭니다 (Pleos LLM, Figma 153:6886 "AI 요약", 173:1915 "요약 생성 중").
 *
 * 기록을 저장한 직후 한 번 요청하고, 결과(한 줄 요약 + 요점 3개)를 기록에 채워 넣습니다.
 * Pleos 서버 LLM은 앱 등록(registerApp)으로 받은 토큰이 있어야 답합니다. 앱 키는 local.properties의
 * pleos.clientId / pleos.clientSecret에서 읽습니다(BuildConfig). 키가 없거나 실패하면 "요약을 만들지 못했어요"로 둡니다.
 */
object ConversationSummarizer {
    private const val TAG = "Summarizer"
    private const val MAX_ATTEMPTS = 6
    private const val RETRY_MS = 1_500L
    private const val TIMEOUT_MS = 45_000L
    /** 너무 긴 대화는 앞뒤를 남기고 줄입니다(요청 크기 제한). */
    private const val MAX_PROMPT_LINES = 400

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var llm: LLM? = null
    private var registered = false
    private val running = HashSet<String>()

    val isConfigured get() = com.example.pleos.BuildConfig.PLEOS_CLIENT_ID.isNotBlank() &&
        com.example.pleos.BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()

    fun summarize(context: Context, recordId: String) {
        val app = context.applicationContext
        val store = ConversationHistoryStore(app)
        val record = store.find(recordId) ?: return
        if (record.lines.isEmpty() || !running.add(recordId)) return
        store.upsert(record.copy(summaryState = SummaryState.PENDING))
        if (!isConfigured) {
            Log.w(TAG, "pleos.clientId / pleos.clientSecret not set")
            finish(store, recordId, null)
            return
        }
        val client = llm ?: runCatching { LLM(app, Mode.SERVER).also { it.initialize() } }
            .onFailure { Log.w(TAG, "LLM init failed: ${it.message}") }
            .getOrNull()
            .also { llm = it }
        if (client == null) {
            finish(store, recordId, null)
            return
        }
        val prompt = promptOf(record)
        var done = false
        val timeout = Runnable {
            if (!done) {
                done = true
                Log.w(TAG, "summary timed out")
                finish(store, recordId, null)
            }
        }
        main.postDelayed(timeout, TIMEOUT_MS)

        fun attempt(n: Int) {
            if (done) return
            val buffer = StringBuilder()
            runCatching {
                client.generateContent(prompt, object : ResultListener {
                    override fun onResponse(response: String, isFinal: Boolean) {
                        main.post {
                            if (done) return@post
                            buffer.append(response)
                            if (isFinal || response.isNotBlank()) {
                                done = true
                                main.removeCallbacks(timeout)
                                finish(store, recordId, parse(buffer.toString()))
                            }
                        }
                    }

                    override fun onError(message: String) {
                        main.post {
                            if (done) return@post
                            Log.w(TAG, "summary error (try ${n + 1}): $message")
                            // 서비스 연결 전이면 잠시 뒤 다시 묻습니다.
                            if (n + 1 < MAX_ATTEMPTS) {
                                main.postDelayed({ attempt(n + 1) }, RETRY_MS)
                            } else {
                                done = true
                                main.removeCallbacks(timeout)
                                finish(store, recordId, null)
                            }
                        }
                    }
                })
            }.onFailure {
                Log.w(TAG, "summary request failed: ${it.message}")
                if (n + 1 < MAX_ATTEMPTS) main.postDelayed({ attempt(n + 1) }, RETRY_MS)
                else {
                    done = true
                    main.removeCallbacks(timeout)
                    finish(store, recordId, null)
                }
            }
        }
        // initialize()는 서비스에 연결하는 데 잠시 걸립니다. 처음 한 번은 앱을 등록해 토큰을 받습니다.
        main.postDelayed({
            if (registered) {
                attempt(0)
                return@postDelayed
            }
            runCatching {
                client.registerApp(
                    com.example.pleos.BuildConfig.PLEOS_CLIENT_ID,
                    com.example.pleos.BuildConfig.PLEOS_CLIENT_SECRET,
                    object : OnServerConnectionListener {
                        override fun onConnected() = main.post {
                            registered = true
                            attempt(0)
                        }.let { }

                        override fun onFailed(message: String) = main.post {
                            Log.w(TAG, "registerApp failed: $message")
                            if (!done) {
                                done = true
                                main.removeCallbacks(timeout)
                                finish(store, recordId, null)
                            }
                        }.let { }
                    },
                )
            }.onFailure {
                Log.w(TAG, "registerApp error: ${it.message}")
                attempt(0)
            }
        }, RETRY_MS)
    }

    private fun finish(store: ConversationHistoryStore, id: String, result: Pair<String, List<String>>?) {
        running.remove(id)
        val record = store.find(id) ?: return
        store.upsert(
            if (result == null) record.copy(summaryState = SummaryState.FAILED)
            else record.copy(headline = result.first, summary = result.second, summaryState = SummaryState.DONE),
        )
    }

    fun promptOf(record: ConversationRecord): String {
        val lines = record.lines.let { all ->
            if (all.size <= MAX_PROMPT_LINES) all
            else all.take(MAX_PROMPT_LINES / 2) + all.takeLast(MAX_PROMPT_LINES / 2)
        }
        val transcript = lines.joinToString("\n") { "${it.speaker}: ${it.text}" }
        return """
            다음은 자동차 안에서 가족이 나눈 대화를 받아 적은 자막입니다.
            대화 내용을 한국어로 요약해 주세요. 반드시 아래 형식만 쓰고 다른 말은 쓰지 마세요.
            첫 줄: 대화 전체를 한 문장으로 (30자 이내, "~했어요" 말투)
            그다음 세 줄: "- "로 시작하는 요점 3개 (각 40자 이내, "~했어요" 말투, 사람 이름은 자막에 나온 그대로)

            [자막]
            $transcript
        """.trimIndent()
    }

    /** "한 줄 요약\n- 요점\n- 요점\n- 요점" 형태를 읽습니다. 형식이 어긋나도 쓸 수 있는 만큼 씁니다. */
    fun parse(text: String): Pair<String, List<String>>? {
        val lines = text.lines().map { it.trim().trim('*').trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val bulletPrefix = Regex("^([-•·*]|\\d+[.)])\\s*")
        val bullets = lines.filter { bulletPrefix.containsMatchIn(it) }.map { it.replace(bulletPrefix, "").trim() }
            .filter { it.isNotBlank() }
        val headline = lines.firstOrNull { !bulletPrefix.containsMatchIn(it) }
            ?.removePrefix("첫 줄:")?.removePrefix("요약:")?.trim()
            ?: bullets.firstOrNull()
            ?: return null
        return headline to bullets.take(3).ifEmpty { listOf(headline) }
    }
}
