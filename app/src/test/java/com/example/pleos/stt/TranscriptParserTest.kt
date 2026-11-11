package com.example.pleos.stt

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** sori-node tests/transcript.test.js를 옮긴 테스트입니다. */
class TranscriptParserTest {

    private fun word(content: String, speaker: String?, start: Double, end: Double = start + 0.3) = JSONObject()
        .put("type", "word").put("start_time", start).put("end_time", end)
        .put("alternatives", JSONArray().put(JSONObject().put("content", content).apply { speaker?.let { put("speaker", it) } }))

    private fun punctuation(content: String, time: Double, attachesTo: String = "previous") = JSONObject()
        .put("type", "punctuation").put("start_time", time).put("end_time", time).put("attaches_to", attachesTo)
        .put("alternatives", JSONArray().put(JSONObject().put("content", content)))

    private fun message(vararg results: JSONObject, type: String = "AddTranscript") =
        JSONObject().put("message", type).put("results", JSONArray(results.toList()))

    @Test
    fun koreanSpacingAndPunctuationStayWithTheRightSpeaker() {
        val segments = TranscriptParser.parse(
            message(
                word("안녕하세요", "S1", 0.0), punctuation(",", 0.3), word("반갑습니다", "S1", 0.4), punctuation(".", 0.7),
                word("네", "S2", 1.0), punctuation(",", 1.3), word("저도요", "S2", 1.4), punctuation("!", 1.7),
            ),
        )
        assertEquals(listOf("S1" to "안녕하세요, 반갑습니다.", "S2" to "네, 저도요!"), segments.map { it.speaker to it.text })
        assertEquals(0.0, segments[0].start, 0.0)
        assertEquals(0.7, segments[0].end, 1e-9)
        assertEquals(1.7, segments[1].end, 1e-9)
    }

    @Test
    fun missingSpeakerStaysUnidentified() {
        val segments = TranscriptParser.parse(
            message(word("확인", "S1", 0.0), word("불확실", "UU", 1.0), word("알수없음", null, 2.0), word("응답", "S2", 3.0)),
        )
        assertEquals(
            listOf("S1" to "확인", "UU" to "불확실 알수없음", "S2" to "응답"),
            segments.map { it.speaker to it.text },
        )
    }

    @Test
    fun openingPunctuationFollowsTheNextSpeaker() {
        val segments = TranscriptParser.parse(
            message(
                word("말했어요", "S1", 0.0), punctuation(".", 0.3), punctuation("“", 1.0, "next"),
                word("정말", "S2", 1.0), word("좋아요", "S2", 1.4), punctuation("”", 1.7),
            ),
        )
        assertEquals("“정말 좋아요”", segments[1].text)
        assertEquals("S2", segments[1].speaker)
    }

    @Test
    fun languageDefaultsAndServiceDelimiterAreRespected() {
        val msg = message(word("今日", "S1", 0.0), word("晴れ", "S1", 1.0))
        assertEquals("今日晴れ", TranscriptParser.parse(msg, language = "ja")[0].text)
        assertEquals("今日 晴れ", TranscriptParser.parse(msg, language = "ja", wordDelimiter = " ")[0].text)
    }

    @Test
    fun partialIdsAreStableAndFinalIdsDiffer() {
        val first = TranscriptParser.parse(message(word("회의", "S1", 0.0), type = "AddPartialTranscript"))
        val revision = TranscriptParser.parse(
            message(word("회의를", "S1", 0.0), word("시작합니다", "S1", 1.0), type = "AddPartialTranscript"),
        )
        assertEquals(first[0].id, revision[0].id)
        assertEquals("회의를 시작합니다", revision[0].text)
        val final = TranscriptParser.parse(message(word("끝", "S1", 0.0)), prefix = "final-1")
        assertNotEquals(first[0].id, final[0].id)
        assertTrue(TranscriptParser.parse(message(type = "AddPartialTranscript")).isEmpty())
    }

    @Test
    fun metadataOnlyAndWrittenFormAreVisibleAndTimesSanitized() {
        val metadataOnly = JSONObject().put("message", "AddTranscript")
            .put("metadata", JSONObject().put("transcript", "  완료했습니다.  ").put("start_time", -1))
        assertEquals(Segment("final-0-0.0", "UU", "완료했습니다.", 0.0, 0.0), TranscriptParser.parse(metadataOnly)[0])

        val written = JSONObject().put("results", JSONArray().put(JSONObject().put("written_form", JSONArray().put(word("42", "S1", 1.0)))))
        assertEquals("42", TranscriptParser.parse(written)[0].text)

        val broken = JSONObject().put("results", JSONArray().put(JSONObject.NULL).put(JSONObject())
            .put(JSONObject().put("alternatives", JSONArray().put(JSONObject()))))
        assertTrue(TranscriptParser.parse(broken).isEmpty())
    }

    @Test
    fun parsesRealServerMessageShape() {
        val json = """
            {"message":"AddTranscript","format":"2.9",
             "metadata":{"start_time":1.2,"end_time":2.0,"transcript":"안녕하세요. "},
             "results":[
               {"type":"word","start_time":1.2,"end_time":1.8,"alternatives":[{"content":"안녕하세요","confidence":0.98,"language":"ko","speaker":"S1"}]},
               {"type":"punctuation","start_time":1.8,"end_time":1.8,"attaches_to":"previous","is_eos":true,"alternatives":[{"content":".","confidence":1.0,"speaker":"S1"}]}
             ]}
        """.trimIndent()
        val segments = TranscriptParser.parse(JSONObject(json), defaultSpeaker = "UU")
        assertEquals(listOf("S1" to "안녕하세요."), segments.map { it.speaker to it.text })
    }
}
