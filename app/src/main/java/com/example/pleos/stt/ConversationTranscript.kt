package com.example.pleos.stt

import kotlin.math.max
import kotlin.math.min

/** 화면에 표시하는 한 문단입니다. 화자가 바뀔 때만 새 문단이 생깁니다. */
data class Turn(
    val id: String,
    val speaker: String,
    val text: String,
    val finalText: String,
    val partialText: String,
    val start: Double,
    val end: Double,
    val isPartial: Boolean,
)

/**
 * 대화는 API 응답 단위가 아니라 화자 순서의 나열입니다.
 * 중간(partial) 결과는 확정된 문단 위에 덧씌우는 임시 투영입니다.
 * sori-node의 conversation.js를 옮긴 것입니다.
 */
class ConversationTranscript(private val delimiter: String = " ") {
    private val turns = mutableListOf<MutableTurn>()
    private var partials: List<Normalized> = emptyList()
    private val finalIds = HashSet<String>()

    /** 확정 결과를 추가합니다. 바로 앞 문단과 화자가 같을 때만 이어 붙입니다. */
    fun addFinal(segments: List<Segment>) {
        clearPartial()
        for (source in segments) {
            val segment = normalize(source) ?: continue
            if (segment.sourceId != null && !finalIds.add(segment.sourceId)) continue
            append(turns, segment, partial = false)
        }
    }

    /** 중간 결과 묶음 전체를 교체합니다. 임시 화자 구분도 함께 바뀝니다. */
    fun setPartial(segments: List<Segment>) {
        partials = segments.mapNotNull(::normalize)
    }

    fun clearPartial() {
        partials = emptyList()
    }

    fun getTurns(): List<Turn> {
        val projection = turns.map { it.copy() }.toMutableList()
        for (segment in partials) append(projection, segment, partial = true)
        return projection.map { it.toTurn(delimiter) }
    }

    fun reset() {
        turns.clear()
        partials = emptyList()
        finalIds.clear()
    }

    private fun append(target: MutableList<MutableTurn>, segment: Normalized, partial: Boolean) {
        var turn = target.lastOrNull()
        if (turn == null || turn.speaker != segment.speaker) {
            turn = MutableTurn(
                id = "turn-${target.size}",
                speaker = segment.speaker,
                start = segment.start,
                end = segment.end,
            )
            target += turn
        }
        if (partial) {
            turn.partialText = joinText(turn.partialText, segment.text, delimiter)
        } else {
            turn.finalText = joinText(turn.finalText, segment.text, delimiter)
        }
        turn.start = min(turn.start, segment.start)
        turn.end = max(turn.end, segment.end)
    }

    private data class Normalized(
        val sourceId: String?,
        val speaker: String,
        val text: String,
        val start: Double,
        val end: Double,
    )

    private data class MutableTurn(
        val id: String,
        val speaker: String,
        var finalText: String = "",
        var partialText: String = "",
        var start: Double,
        var end: Double,
    ) {
        fun toTurn(delimiter: String) = Turn(
            id = id,
            speaker = speaker,
            text = joinText(finalText, partialText, delimiter),
            finalText = finalText,
            partialText = partialText,
            start = start,
            end = end,
            isPartial = partialText.isNotEmpty(),
        )
    }

    private fun normalize(segment: Segment): Normalized? {
        val text = segment.text.replace(WHITESPACE, " ").trim()
        if (text.isEmpty()) return null
        val start = finiteTime(segment.start)
        return Normalized(
            sourceId = segment.id.ifEmpty { null },
            speaker = segment.speaker.ifEmpty { "UU" },
            text = text,
            start = start,
            end = max(start, finiteTime(segment.end, start)),
        )
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        // 확정 결과의 경계에서 문장부호가 따로 올 수 있어 앞뒤 공백을 조정합니다.
        private val ATTACHES_PREVIOUS = Regex("^[,.;:!?…。，、！？：；%‰°℃℉)\\]}’”」』》〉]")
        private val ATTACHES_NEXT = Regex("[(\\[{“‘「『《〈]$")

        internal fun joinText(previous: String, next: String, delimiter: String): String {
            if (previous.isEmpty()) return next
            if (next.isEmpty()) return previous
            val noSpace = ATTACHES_PREVIOUS.containsMatchIn(next) || ATTACHES_NEXT.containsMatchIn(previous)
            return previous + (if (noSpace) "" else delimiter) + next
        }
    }
}
