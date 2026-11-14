package com.example.pleos.ai

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 빠른 답장 추천. 다른 사람이 나에게 묻거나 부탁하는 말이 확정되면, 그 말에 바로 답할 짧은 문장 3개를 AI에 받습니다.
 *
 * 토큰을 아끼는 규칙
 * - 질문·부탁처럼 보이는 말일 때만 묻습니다([needsReply]). 나머지는 기본 답장만 보여 줍니다.
 * - 같은 말이 이어지면 잠시([SETTLE_MS]) 기다렸다가 마지막 말 하나만 묻습니다.
 * - 맥락은 최근 [CONTEXT_LINES]줄까지, 합쳐서 [CONTEXT_CHARS]자 안에서 최근 것부터 담습니다.
 *   그보다 앞의 흐름은 지난 대화 한 줄 요약(있으면)으로 대신합니다. 답은 짧은 줄 3개만 받습니다.
 * - 같은 질문의 답은 저장해 두고 다시 묻지 않습니다(최근 [CACHE_SIZE]개).
 * - 한 번에 하나만 묻고, 묻는 중에 새 질문이 오면 마지막 것만 이어서 묻습니다.
 */
class ReplyAi(context: Context, private val onChanged: (State) -> Unit) {
    /** 화면에 보일 추천 상태. question이 null이면 추천 없음(기본 답장만). */
    data class State(
        val question: String?,
        val replies: List<String>,
        val loading: Boolean,
        /** 물은 사람(자막의 화자 라벨). 여러 사람이 번갈아 물을 때 누구에게 답하는지 보여 줍니다. */
        val asker: String? = null,
    ) {
        companion object {
            val NONE = State(null, emptyList(), loading = false)
        }
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?) = size > CACHE_SIZE
    }
    private var inFlight = false
    private var queued: Triple<String, List<String>, String?>? = null
    private var latestQuestion: String? = null
    private var settle: Runnable? = null
    private var latestAsker: String? = null
    /** 지금 추천의 질문 뒤에 다른 사람이 한 말의 수. 대화가 흘러가면 추천을 지웁니다. */
    private var turnsAfter = 0
    private val expire = Runnable { clear() }

    var state = State.NONE
        private set

    /**
     * 다른 사람의 말이 확정될 때마다 부릅니다. context는 그 앞의 대화("엄마: …", 오래된 것부터),
     * topic은 지난 대화의 한 줄 요약입니다. 질문이 아닌 말은 무시합니다(보이던 추천도 그대로 둡니다:
     * "뭐 먹을래? 나는 우동." 처럼 질문 뒤에 말이 이어져도 답할 수 있게).
     */
    fun onHeard(text: String, context: List<String>, topic: String? = null, asker: String? = null) {
        val question = text.trim()
        if (!needsReply(question)) {
            if (com.example.pleos.BuildConfig.DEBUG) Log.d(TAG, "skip: $question")
            // 질문 뒤로 대화가 두 마디 이상 흘러가면, 이제 그 질문에 답하기엔 늦었다고 봅니다.
            if (state.question != null && ++turnsAfter >= STALE_TURNS) clear()
            return
        }
        if (com.example.pleos.BuildConfig.DEBUG) Log.d(TAG, "ask: $question")
        settle?.let { main.removeCallbacks(it) }
        val lines = fitContext(context)
        val about = topic?.trim()?.take(TOPIC_CHARS)?.takeIf { it.isNotEmpty() }
        val task = Runnable {
            latestAsker = asker
            turnsAfter = 0
            request(question, lines, about)
        }
        settle = task
        main.postDelayed(task, SETTLE_MS)
    }

    /** 내가 답했거나 대화가 끝났을 때: 추천을 지웁니다. */
    fun clear() {
        settle?.let { main.removeCallbacks(it) }
        main.removeCallbacks(expire)
        queued = null
        latestQuestion = null
        turnsAfter = 0
        publish(State.NONE)
    }

    private fun publish(next: State) {
        if (next == state) return
        state = next
        main.removeCallbacks(expire)
        // 답하지 않고 대화가 흘러가면 추천은 잠시 뒤 스스로 사라집니다.
        if (next.question != null) main.postDelayed(expire, if (next.loading) TIMEOUT_MS + SHOW_MS else SHOW_MS)
        onChanged(next)
    }

    private fun request(question: String, context: List<String>, topic: String?) {
        latestQuestion = question
        val key = normalize(question)
        cache[key]?.let {
            publish(State(question, it, loading = false, asker = latestAsker))
            return
        }
        if (!TextAi.isConfigured) return
        // 답장을 고르는 동안 "✦ 답장 고르는 중"을 보여 줍니다(기본 답장은 그대로 쓸 수 있음).
        publish(State(question, emptyList(), loading = true, asker = latestAsker))
        if (inFlight) {
            queued = Triple(question, context, topic)
            return
        }
        inFlight = true
        TextAi.generate(app, promptOf(question, context, topic), TIMEOUT_MS) { text ->
            inFlight = false
            val replies = text?.let(::parse).orEmpty()
            if (com.example.pleos.BuildConfig.DEBUG) Log.d(TAG, "replies for \"$question\": $replies (raw ${text?.length ?: -1} chars)")
            if (replies.isNotEmpty()) cache[key] = replies
            // 그사이 다른 질문이 왔거나 이미 답했으면 이 답은 버립니다.
            if (latestQuestion == question) {
                publish(if (replies.isNotEmpty()) State(question, replies, loading = false, asker = latestAsker) else State.NONE)
            }
            queued?.let { (q, c, t) ->
                queued = null
                if (q == latestQuestion) request(q, c, t)
            }
        }
    }

    companion object {
        private const val TAG = "ReplyAi"
        const val SETTLE_MS = 700L
        /** 추천을 보여 두는 시간. 그 안에 답하지 않으면 지웁니다. */
        const val SHOW_MS = 25_000L
        /** 질문 뒤로 다른 말이 이만큼 이어지면 추천을 지웁니다. */
        const val STALE_TURNS = 2
        const val CONTEXT_LINES = 6
        /** 맥락 줄을 합친 글자 수 상한(약 300토큰) */
        const val CONTEXT_CHARS = 360
        /** 한 줄이 이보다 길면 끝부분만 씁니다(답할 말은 보통 끝에 있음). */
        const val LINE_CHARS = 80
        const val TOPIC_CHARS = 60
        const val CACHE_SIZE = 60
        const val TIMEOUT_MS = 15_000L
        private const val MAX_CHARS = 14

        private val QUESTION_END = Regex("(까|니|냐|래|나요|가요|죠|지|어때|자)요?[?？!.~…\\s]*$")
        private val QUESTION_WORD = Regex("(뭐|뭘|무슨|어디|언제|누가|누구|왜|어떻게|몇|얼마|할까|될까|갈래|먹을래)")

        /** 답할 만한 말인지: 물음표가 있거나, 묻는·권하는 말끝이거나, 묻는 말이 들어 있으면 true */
        fun needsReply(text: String): Boolean {
            val t = text.trim()
            if (t.length < 2) return false
            if ('?' in t || '？' in t) return true
            return QUESTION_END.containsMatchIn(t) || (t.length <= 30 && QUESTION_WORD.containsMatchIn(t))
        }

        /** 최근 줄부터 거꾸로 담아 줄 수와 글자 수 상한을 넘지 않게 합니다. 순서는 오래된 것부터. */
        fun fitContext(context: List<String>): List<String> {
            val picked = ArrayList<String>()
            var used = 0
            for (raw in context.asReversed()) {
                if (picked.size >= CONTEXT_LINES) break
                val line = raw.trim().let { if (it.length > LINE_CHARS) "…" + it.takeLast(LINE_CHARS) else it }
                if (line.isEmpty()) continue
                if (used + line.length > CONTEXT_CHARS) break
                picked += line
                used += line.length
            }
            return picked.asReversed()
        }

        fun promptOf(question: String, context: List<String>, topic: String? = null): String = buildString {
            append("차 안에서 말로 답하기 어려운 사람이 화면을 눌러 대신 말할 답장을 고릅니다.\n")
            if (topic != null) append("지난번 대화: ").append(topic).append('\n')
            if (context.isNotEmpty()) append("앞 대화:\n").append(context.joinToString("\n")).append('\n')
            append("받은 말: ").append(question).append('\n')
            append("이 말에 바로 할 수 있는 서로 다른 답장 3개를 한 줄에 하나씩, 각 12자 이내 존댓말로만 쓰세요. ")
            append("긍정·부정·다른 선택이 고루 섞이게 하고 번호나 설명은 쓰지 마세요.")
        }

        fun parse(text: String): List<String> = text.lines()
            .map { it.trim().replace(Regex("^([-•·*]|\\d+[.)])\\s*"), "").trim().trim('"', '“', '”') }
            .filter { it.isNotEmpty() && it.length <= MAX_CHARS * 2 }
            .map { it.take(MAX_CHARS * 2) }
            .distinct()
            .take(3)

        private fun normalize(text: String) = text.replace(Regex("[\\s?？!.,~…]"), "")
    }
}
