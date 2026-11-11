package com.example.pleos.stt

import kotlin.math.max
import kotlin.math.min

/**
 * "언제 누가 말했는지"만 모아 두는 시간표입니다. 화자 구분(Speechmatics) 결과의 글자는 버리고
 * 시간과 화자만 씁니다. 다른 인식기(Pleos STT)가 낸 문장의 시간 구간을 주면, 그 구간에서 가장 오래
 * 말한 사람을 돌려줍니다. 시간은 모두 녹음 시작부터 센 초입니다.
 */
class SpeakerTimeline(private val keepSeconds: Double = 120.0) {
    private data class Span(val speaker: String, val start: Double, val end: Double)

    private val finals = ArrayList<Span>()
    private var partials: List<Span> = emptyList()

    /** 확정된 화자 구간이 어디까지 왔는지(초). 이 시점까지는 화자가 더 바뀌지 않습니다. */
    var finalUntil = 0.0
        private set

    fun addFinal(segments: List<Segment>) {
        for (segment in segments) {
            if (segment.end <= segment.start && segment.text.isBlank()) continue
            finals += Span(segment.speaker, segment.start, max(segment.start, segment.end))
            finalUntil = max(finalUntil, segment.end)
        }
        // 오래된 구간은 버립니다(긴 운전에서도 메모리가 늘지 않게).
        val cutoff = finalUntil - keepSeconds
        if (cutoff > 0) finals.removeAll { it.end < cutoff }
    }

    fun setPartial(segments: List<Segment>) {
        partials = segments.map { Span(it.speaker, it.start, max(it.start, it.end)) }
    }

    /**
     * [start, end] 구간에서 가장 오래 말한 사람. 확정 구간을 먼저 보고, 없으면 중간 결과도 봅니다.
     * 구간이 아무와도 겹치지 않으면 가장 가까운 구간의 화자, 그것도 없으면 null.
     */
    fun speakerFor(start: Double, end: Double, includePartial: Boolean = true): String? {
        val spans = if (includePartial) finals + partials else finals
        if (spans.isEmpty()) return null
        val overlap = HashMap<String, Double>()
        for (span in spans) {
            val shared = min(end, span.end) - max(start, span.start)
            // 아주 짧은 단어(끝 시간이 시작과 같음)도 구간 안에 있으면 조금은 셉니다.
            val weight = if (shared > 0) shared else if (span.start >= start && span.start <= end) 0.05 else 0.0
            if (weight > 0) overlap[span.speaker] = (overlap[span.speaker] ?: 0.0) + weight
        }
        overlap.maxByOrNull { it.value }?.let { return it.key }
        val middle = (start + end) / 2
        return spans.minByOrNull { distance(it, middle) }?.takeIf { distance(it, middle) <= NEAREST_LIMIT }?.speaker
    }

    /** 가장 최근에 말한 사람(중간 결과 포함). 말하는 중인 자막에 임시로 붙입니다. */
    fun latestSpeaker(): String? =
        (partials.lastOrNull() ?: finals.maxByOrNull { it.end })?.speaker

    fun reset() {
        finals.clear()
        partials = emptyList()
        finalUntil = 0.0
    }

    private fun distance(span: Span, at: Double) = when {
        at < span.start -> span.start - at
        at > span.end -> at - span.end
        else -> 0.0
    }

    companion object {
        /** 겹치는 구간이 없을 때 이만큼(초) 안에 있는 구간까지만 가까운 화자로 봅니다. */
        const val NEAREST_LIMIT = 3.0
    }
}
