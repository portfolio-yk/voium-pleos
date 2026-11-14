package com.example.pleos.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.example.pleos.BuildConfig
import java.time.Duration
import java.util.concurrent.Executors

/**
 * Claude API로 짧은 한국어 글(대화 요약, 대화 제안 질문)을 만듭니다.
 * 키는 local.properties의 anthropic.apiKey, 모델은 anthropic.model(기본 claude-opus-5-5)에서 읽습니다(BuildConfig).
 * 요청은 백그라운드 스레드에서 보내고, 결과는 메인 스레드로 돌려줍니다. 실패하면 null.
 */
object ClaudeAi {
    private const val TAG = "ClaudeAi"
    private const val MAX_TOKENS = 16_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    // 요청끼리 줄 서지 않게 여러 개를 동시에 보냅니다(대화 제안이 길어도 빠른 답장이 기다리지 않게).
    private val worker by lazy { Executors.newFixedThreadPool(3) { Thread(it, "claude-ai") } }
    private val client: AnthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(BuildConfig.ANTHROPIC_API_KEY)
            .build()
    }

    val isConfigured get() = BuildConfig.ANTHROPIC_API_KEY.isNotBlank()

    /**
     * 대화를 시작할 때 미리 부릅니다. SDK 준비(처음 1~2초)와 서버 연결(TLS)을 미리 해 두어,
     * 첫 빠른 답장 추천이 늦지 않게 합니다. 모델을 부르지 않는 가벼운 요청(모델 정보 조회)이라 토큰이 들지 않습니다.
     */
    fun warmUp() {
        if (!isConfigured) return
        worker.execute {
            runCatching { client.models().retrieve(BuildConfig.ANTHROPIC_MODEL) }
                .onFailure { Log.w(TAG, "warm-up failed: ${it.javaClass.simpleName}") }
        }
    }

    fun generate(prompt: String, timeoutMs: Long, onResult: (String?) -> Unit) {
        if (!isConfigured) {
            main.post { onResult(null) }
            return
        }
        worker.execute {
            val text = runCatching {
                val params = MessageCreateParams.builder()
                    .model(BuildConfig.ANTHROPIC_MODEL)
                    .maxTokens(MAX_TOKENS)
                    // 짧고 단순한 글이라 깊이 생각할 필요가 없습니다(빠르고 저렴하게).
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                    .addUserMessage(prompt)
                    .build()
                val response = client.withOptions { it.timeout(Duration.ofMillis(timeoutMs)) }.messages().create(params)
                if (response.stopReason().map { it.toString() }.orElse("") == "refusal") {
                    Log.w(TAG, "refused")
                    null
                } else {
                    response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                        .joinToString("").trim()
                }
            }.onFailure {
                val detail = (it as? AnthropicServiceException)?.let { e -> "HTTP ${e.statusCode()}" } ?: it.javaClass.simpleName
                Log.w(TAG, "request failed: $detail ${it.message?.take(200)}")
            }.getOrNull()
            main.post { onResult(text?.takeIf { it.isNotBlank() }) }
        }
    }
}
