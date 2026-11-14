package com.example.pleos.ai

import android.content.Context
import android.util.Log
import com.example.pleos.ConversationStarters
import com.example.pleos.ConversationStarters.Kind

/**
 * 대화 제안 질문을 AI(Claude, 없으면 Gleo AI)로 만듭니다. 차량 정보(시간대 · 주행/정차 · 탑승 인원 · 내비 목적지 · 비)와
 * 지난 대화 주제를 함께 보내, 지금 차 안 상황에 맞는 질문을 카드(시간 · 위치 · 날씨)마다 받습니다.
 *
 * 상황이 바뀔 때만 다시 묻고, 받은 질문은 저장해 두었다가 같은 상황이면 다시 씁니다.
 * 답을 받기 전이거나 실패하면 null이며, 그때는 기본 질문([ConversationStarters])을 씁니다.
 */
object SuggestionAi {
    private const val TAG = "SuggestionAi"
    private const val PREFS = "suggestion_ai"
    private const val TIMEOUT_MS = 30_000L
    /** 실패한 뒤 같은 상황으로 다시 묻기까지 기다리는 시간 */
    private const val RETRY_AFTER_MS = 5 * 60_000L

    /** Gleo AI에 알려 줄 지금 차 안 상황 */
    data class Situation(
        val timeText: String,
        val slotLabel: String,
        /** 주행 중이면 false, P단이면 true, 모르면 null */
        val parked: Boolean?,
        val passengers: Int?,
        /** 내비 다음 목적지와 남은 초 */
        val trip: Pair<String, Int>?,
        val raining: Boolean,
        /** 지난 대화의 한 줄 요약(있으면) */
        val lastTopic: String?,
    ) {
        /** 이 값이 같으면 같은 질문을 다시 씁니다. 남은 시간·분 단위 시각은 넣지 않습니다. */
        val key: String
            get() = listOf(slotLabel, parked, passengers?.coerceAtMost(5), trip?.first, raining, lastTopic).joinToString("|")
    }

    private var cachedKey: String? = null
    private var cached: Map<Kind, List<String>> = emptyMap()
    private var pendingKey: String? = null
    private var failedKey: String? = null
    private var failedAt = 0L

    /** 이 상황에서 쓸 Gleo AI 질문. 아직 없으면 null */
    fun questions(context: Context, situation: Situation): Map<Kind, List<String>>? {
        restore(context)
        return cached.takeIf { cachedKey == situation.key && it.isNotEmpty() }
    }

    /** 상황이 바뀌었으면 Gleo AI에 새 질문을 요청합니다. 받으면 onUpdated를 부릅니다(메인 스레드). */
    fun refresh(context: Context, situation: Situation, onUpdated: () -> Unit) {
        restore(context)
        val key = situation.key
        if (key == cachedKey || key == pendingKey || !TextAi.isConfigured) return
        if (key == failedKey && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return
        pendingKey = key
        val app = context.applicationContext
        TextAi.generate(app, promptOf(situation), TIMEOUT_MS) { text ->
            if (pendingKey == key) pendingKey = null
            val parsed = text?.let { parse(it, situation) }.orEmpty()
            if (parsed.isEmpty()) {
                Log.w(TAG, "no usable suggestions")
                failedKey = key
                failedAt = System.currentTimeMillis()
                return@generate
            }
            cachedKey = key
            cached = parsed
            save(app)
            onUpdated()
        }
    }

    fun promptOf(situation: Situation): String {
        val driving = when (situation.parked) {
            true -> "정차 중(P단)"
            false -> "주행 중"
            null -> "알 수 없음"
        }
        val facts = buildList {
            add("- 시각: ${situation.timeText} (${situation.slotLabel})")
            add("- 차 상태: $driving")
            situation.passengers?.takeIf { it > 0 }?.let { add("- 탑승 인원: ${it}명") }
            situation.trip?.let { (place, seconds) ->
                add("- 내비 목적지: $place, ${ConversationStarters.duration((seconds + 59) / 60)} 남음")
            }
            if (situation.raining) add("- 날씨: 비가 와서 와이퍼가 작동 중")
            situation.lastTopic?.let { add("- 지난번 대화: $it") }
        }.joinToString("\n")
        val sections = buildList {
            add("[시간]")
            if (situation.trip != null) add("[위치]")
            if (situation.raining) add("[날씨]")
        }
        val format = sections.joinToString("\n") { "$it\n- 질문\n- 질문\n- 질문" }
        return """
            너는 자동차 안 대화를 자막으로 보여 주는 앱 VOIUM의 대화 제안 도우미야.
            지금 차 안 상황에 맞춰, 함께 탄 사람들이 자연스럽게 이야기를 시작할 수 있는 가벼운 질문을 만들어 줘.

            [지금 상황]
            $facts

            [규칙]
            - 한국어 존댓말, 질문 하나는 띄어쓰기 포함 22자 이내
            - 화면에 두 줄로 크게 보여요. 뜻이 끊기지 않는 자리에 " / "를 한 번 넣어 줄을 나눠 주세요 (예: 경포해변에 가면 / 뭐부터 할까요?)
            - ${sections.joinToString(" ")} 항목마다 그 상황(시간대 · 목적지 · 비)과 바로 이어지는 질문 3개
            - 주행 중이면 운전자가 화면을 오래 보거나 깊이 생각해야 하는 질문은 피하기
            - 건강 · 돈 · 정치 · 개인정보처럼 민감한 주제는 피하기
            - 아래 형식만 쓰고 다른 말은 쓰지 않기

            $format
        """.trimIndent()
    }

    /** "[시간]\n- 질문 …" 형식을 읽어 카드 종류별 질문으로 나눕니다. 형식이 어긋나도 쓸 수 있는 만큼 씁니다. */
    fun parse(text: String, situation: Situation): Map<Kind, List<String>> {
        val result = LinkedHashMap<Kind, MutableList<String>>()
        var current: Kind? = Kind.TIME
        val bullet = Regex("^([-•·*]|\\d+[.)])\\s*")
        for (raw in text.lines()) {
            val line = raw.replace("**", "").trim()
            if (line.isEmpty()) continue
            val header = line.removePrefix("#").trim()
            when {
                bullet.containsMatchIn(line) -> {
                    val question = clean(line.replace(bullet, "")).let(::applyBreak)
                    val kind = current ?: continue
                    if (question.isNotEmpty()) result.getOrPut(kind) { mutableListOf() } += question
                }
                header.startsWith("[") || header.endsWith(":") || header.length <= 4 -> {
                    current = when {
                        "시간" in header -> Kind.TIME
                        "위치" in header || "목적지" in header -> Kind.PLACE
                        "날씨" in header || "비" == header.trim('[', ']', ':') -> Kind.WEATHER
                        else -> current
                    }
                }
            }
        }
        if (situation.trip == null) result.remove(Kind.PLACE)
        if (!situation.raining) result.remove(Kind.WEATHER)
        return result.mapValues { (_, list) -> list.distinct().take(3).map { if ('\n' in it) it else twoLines(it) } }
            .filterValues { it.isNotEmpty() }
    }

    /** AI가 표시한 줄 바꿈(" / ")을 실제 줄 바꿈으로. 한 번만 씁니다. */
    private fun applyBreak(text: String): String {
        val at = text.indexOf("/")
        if (at <= 0) return text
        val first = text.substring(0, at).trim()
        val rest = text.substring(at + 1).replace("/", " ").replace(Regex("\\s+"), " ").trim()
        return if (first.isEmpty() || rest.isEmpty()) "$first$rest" else "$first\n$rest"
    }

    private fun clean(text: String) = text.trim().trim('"', '“', '”', '\'').replace(Regex("\\s+"), " ").take(MAX_CHARS)

    /** 화면에 두 줄로 보이도록 가운데에 가까운 띄어쓰기에서 줄을 바꿉니다. 짧으면 한 줄 그대로. */
    fun twoLines(question: String): String {
        if (question.contains('\n') || question.length <= 9) return question
        val middle = question.length / 2
        val spaces = question.indices.filter { question[it] == ' ' }
        val cut = spaces.minByOrNull { kotlin.math.abs(it - middle) } ?: return question
        return question.substring(0, cut) + "\n" + question.substring(cut + 1)
    }

    private const val MAX_CHARS = 34

    // ---------------------------------------------------------------- 저장 (앱을 다시 켜도 같은 상황이면 바로 씀)

    private var restored = false

    private fun restore(context: Context) {
        if (restored) return
        restored = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cachedKey = prefs.getString("key", null)
        cached = Kind.entries.mapNotNull { kind ->
            prefs.getString(kind.name, null)?.split(SEPARATOR)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { kind to it }
        }.toMap()
    }

    private fun save(context: Context) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().putString("key", cachedKey)
        cached.forEach { (kind, list) -> editor.putString(kind.name, list.joinToString(SEPARATOR)) }
        editor.apply()
    }

    private const val SEPARATOR = "\u001F"
}
