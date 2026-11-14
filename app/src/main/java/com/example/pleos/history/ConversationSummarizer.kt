package com.example.pleos.history

import android.content.Context
import com.example.pleos.ai.TextAi

/**
 * 대화 기록의 AI 요약을 만듭니다 (Claude 또는 Gleo AI, Figma 153:6886 "AI 요약", 173:1915 "요약 생성 중").
 *
 * 기록을 저장한 직후 한 번 요청하고, 결과(한 줄 요약 + 요점 3개)를 기록에 채워 넣습니다.
 * AI 호출은 [TextAi]가 맡습니다. 키가 없거나 실패하면 "요약을 만들지 못했어요"로 둡니다.
 */
object ConversationSummarizer {
    private const val TIMEOUT_MS = 45_000L
    /** 너무 긴 대화는 앞뒤를 남기고 줄입니다(요청 크기 제한). */
    private const val MAX_PROMPT_LINES = 400

    private val running = HashSet<String>()

    val isConfigured get() = TextAi.isConfigured

    fun summarize(context: Context, recordId: String) {
        val app = context.applicationContext
        val store = ConversationHistoryStore(app)
        val record = store.find(recordId) ?: return
        if (record.lines.isEmpty() || !running.add(recordId)) return
        store.upsert(record.copy(summaryState = SummaryState.PENDING))
        TextAi.generate(app, promptOf(record), TIMEOUT_MS) { text ->
            finish(store, recordId, text?.let(::parse))
        }
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
