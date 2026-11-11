package com.example.pleos.stt

import org.json.JSONObject
import kotlin.math.max

/** 한 화자의 연속된 인식 결과 한 덩어리입니다. */
data class Segment(
    val id: String,
    val speaker: String,
    val text: String,
    val start: Double,
    val end: Double,
)

/**
 * Speechmatics AddTranscript / AddPartialTranscript 메시지를 화자별 세그먼트로 바꿉니다.
 * sori-node의 transcript.js(parseTranscript)를 옮긴 것입니다.
 */
object TranscriptParser {
    private val NO_SPACE_LANGUAGE = Regex("^(ja|zh|cmn|yue)(-|$)")
    private val OPENING_MARK = Regex("^[(\\[{“‘「『《〈]$")

    fun parse(
        message: JSONObject,
        language: String = "ko",
        wordDelimiter: String? = null,
        prefix: String? = null,
        defaultSpeaker: String = "UU",
    ): List<Segment> {
        val idPrefix = prefix
            ?: if (message.optString("message") == "AddPartialTranscript") "partial" else "final"
        val delimiter = wordDelimiter ?: if (NO_SPACE_LANGUAGE.containsMatchIn(language)) "" else " "
        val metadata = message.optJSONObject("metadata")
        val results = collectResults(message)
        val segments = mutableListOf<Builder>()
        var previousAttachesNext = false

        for ((index, result) in results.withIndex()) {
            val alternative = firstAlternative(result)
            val content = alternative?.stringOrNull("content")?.trim().orEmpty()
            if (content.isEmpty()) continue

            val punctuation = result.optString("type") == "punctuation"
            val attachment = result.stringOrNull("attaches_to")
                ?: if (punctuation) (if (OPENING_MARK.matches(content)) "next" else "previous") else "none"
            val previous = segments.lastOrNull()

            var speaker = alternative?.stringOrNull("speaker")
            if (speaker == null && punctuation && (attachment == "next" || attachment == "both")) {
                speaker = results.drop(index + 1)
                    .firstOrNull { it.optString("type") != "punctuation" }
                    ?.let { firstAlternative(it)?.stringOrNull("speaker") }
            }
            if (speaker == null && punctuation) speaker = previous?.speaker
            val resolvedSpeaker = speaker ?: defaultSpeaker

            val start = finiteTime(result.opt("start_time"), finiteTime(metadata?.opt("start_time")))
            val end = max(start, finiteTime(result.opt("end_time"), start))
            val joinsPrevious = attachment == "previous" || attachment == "both"

            if (previous != null && previous.speaker == resolvedSpeaker) {
                val needsDelimiter = !(previousAttachesNext || joinsPrevious ||
                    previous.text.lastOrNull()?.isWhitespace() == true)
                if (needsDelimiter) previous.text.append(delimiter)
                previous.text.append(content)
                previous.end = max(previous.end, end)
            } else {
                segments += Builder("$idPrefix-${segments.size}-$start", resolvedSpeaker, StringBuilder(content), start, end)
            }
            previousAttachesNext = attachment == "next" || attachment == "both"
        }

        if (segments.isEmpty()) {
            val transcript = metadata?.stringOrNull("transcript")?.trim().orEmpty()
            if (transcript.isNotEmpty()) {
                val start = finiteTime(metadata?.opt("start_time"))
                val end = max(start, finiteTime(metadata?.opt("end_time"), start))
                return listOf(Segment("$idPrefix-0-$start", defaultSpeaker, transcript, start, end))
            }
        }
        return segments.map { Segment(it.id, it.speaker, it.text.toString(), it.start, it.end) }
    }

    /** 엔티티 결과에 alternatives가 없으면 written_form을 대신 씁니다. */
    private fun collectResults(message: JSONObject): List<JSONObject> {
        val array = message.optJSONArray("results") ?: return emptyList()
        val results = mutableListOf<JSONObject>()
        for (i in 0 until array.length()) {
            val result = array.optJSONObject(i) ?: continue
            if (hasContent(result)) {
                results += result
            } else {
                val written = result.optJSONArray("written_form") ?: continue
                for (j in 0 until written.length()) written.optJSONObject(j)?.let { results += it }
            }
        }
        return results
    }

    private fun hasContent(result: JSONObject): Boolean {
        val alternatives = result.optJSONArray("alternatives") ?: return false
        for (i in 0 until alternatives.length()) {
            if (alternatives.optJSONObject(i)?.stringOrNull("content") != null) return true
        }
        return false
    }

    private fun firstAlternative(result: JSONObject): JSONObject? =
        result.optJSONArray("alternatives")?.optJSONObject(0)

    private class Builder(
        val id: String,
        val speaker: String,
        val text: StringBuilder,
        val start: Double,
        var end: Double,
    )
}

internal fun finiteTime(value: Any?, fallback: Double = 0.0): Double {
    val number = (value as? Number)?.toDouble() ?: return fallback
    return if (number.isFinite()) max(0.0, number) else fallback
}

/** 값이 비어 있지 않은 문자열일 때만 돌려줍니다. */
internal fun JSONObject.stringOrNull(name: String): String? =
    (opt(name) as? String)?.takeIf { it.isNotEmpty() }
