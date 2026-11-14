package com.example.pleos.stt

import org.json.JSONArray
import org.json.JSONObject

/**
 * 등록된 목소리 하나입니다. label은 인식 결과의 speaker 값으로 그대로 돌아옵니다.
 * Speechmatics 내부 라벨(S1, S2, UU)과 겹치면 안 됩니다.
 */
data class EnrolledSpeaker(val label: String, val identifiers: List<String>)

/** Speechmatics StartRecognition 메시지를 만듭니다. */
object RecognitionConfig {
    /** 세션 하나에 넣을 수 있는 목소리 식별값의 최대 개수입니다(모든 화자 합계). */
    const val MAX_IDENTIFIERS = 50

    private val RESERVED_LABEL = Regex("^(S\\d+|UU)$")

    fun isReservedLabel(label: String) = RESERVED_LABEL.matches(label)

    fun startRecognition(options: SpeechmaticsSession.Options, sampleRate: Int): JSONObject {
        val transcription = JSONObject()
            .put("language", options.language)
            .put("model", "enhanced")
            .put("diarization", if (options.diarization) "speaker" else "none")
            .put("enable_partials", true)
            .put("max_delay", options.maxDelay.coerceIn(0.7, 4.0))
        if (options.diarization) {
            // 대화에서는 prefer_current_speaker를 끕니다. 켜면 비슷한 목소리(가족)가 이어 말할 때 화자가 바뀐 걸 놓칩니다.
            // 목소리 등록(한 사람만 말함)에서는 켜 두어 한 사람이 둘로 나뉘지 않게 합니다.
            // 새 화자를 알아보는 감도는 등록 목소리가 없어도 항상 보냅니다.
            val diarization = JSONObject()
                .put("prefer_current_speaker", options.getSpeakers)
                .put("speaker_sensitivity", options.speakerSensitivity.coerceIn(0.0, 1.0))
            // 목소리 등록: 세션이 끝날 때 SpeakersResult로 식별값을 받습니다.
            if (options.getSpeakers) diarization.put("get_speakers", true)
            val speakers = limitIdentifiers(options.enrolledSpeakers)
            if (speakers.isNotEmpty()) {
                // 화자 식별: 등록된 목소리는 결과에 등록 라벨로 표시됩니다.
                diarization.put("speakers", JSONArray(speakers.map { speaker ->
                    JSONObject()
                        .put("label", speaker.label)
                        .put("speaker_identifiers", JSONArray(speaker.identifiers))
                }))
            }
            transcription.put("speaker_diarization_config", diarization)
        }
        return JSONObject()
            .put("message", "StartRecognition")
            .put(
                "audio_format",
                JSONObject().put("type", "raw").put("encoding", "pcm_s16le").put("sample_rate", sampleRate),
            )
            .put("transcription_config", transcription)
    }

    /**
     * 잘못된 라벨과 빈 식별값을 버리고, 전체 식별값이 최대 개수를 넘지 않게 자릅니다.
     * 화자마다 최신 식별값(목록의 뒤쪽)을 남깁니다.
     */
    fun limitIdentifiers(speakers: List<EnrolledSpeaker>): List<EnrolledSpeaker> {
        var remaining = MAX_IDENTIFIERS
        val result = mutableListOf<EnrolledSpeaker>()
        for (speaker in speakers) {
            val label = speaker.label.trim()
            if (label.isEmpty() || label != speaker.label || isReservedLabel(label)) continue
            if (result.any { it.label == label }) continue
            val identifiers = speaker.identifiers.filter { it.isNotBlank() }.distinct().takeLast(remaining)
            if (identifiers.isEmpty()) continue
            remaining -= identifiers.size
            result += EnrolledSpeaker(label, identifiers)
            if (remaining == 0) break
        }
        return result
    }

    /** SpeakersResult 메시지를 읽습니다. */
    fun parseSpeakers(message: JSONObject): List<EnrolledSpeaker> {
        val array = message.optJSONArray("speakers") ?: return emptyList()
        val speakers = mutableListOf<EnrolledSpeaker>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val label = item.stringOrNull("label") ?: continue
            val ids = item.optJSONArray("speaker_identifiers") ?: continue
            val identifiers = (0 until ids.length()).mapNotNull { (ids.opt(it) as? String)?.takeIf(String::isNotBlank) }
            if (identifiers.isNotEmpty()) speakers += EnrolledSpeaker(label, identifiers)
        }
        return speakers
    }
}
