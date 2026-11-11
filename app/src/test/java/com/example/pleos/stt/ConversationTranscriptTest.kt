package com.example.pleos.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** sori-node tests/conversation.test.js를 옮긴 테스트입니다. */
class ConversationTranscriptTest {

    private fun segment(id: String, speaker: String, text: String, start: Double = 0.0, end: Double = start + 1) =
        Segment(id, speaker, text, start, end)

    @Test
    fun oneSpeakerStaysOneParagraphAcrossFinals() {
        val conversation = ConversationTranscript()
        conversation.addFinal(listOf(segment("a", "S1", "다음 교차로에서", 1.0, 2.0)))
        conversation.addFinal(listOf(segment("b", "S1", "오른쪽으로", 2.0, 3.0)))
        conversation.addFinal(listOf(segment("c", "S1", "가면 돼요.", 3.0, 5.0)))
        assertEquals(
            listOf(Turn("turn-0", "S1", "다음 교차로에서 오른쪽으로 가면 돼요.", "다음 교차로에서 오른쪽으로 가면 돼요.", "", 1.0, 5.0, false)),
            conversation.getTurns(),
        )
    }

    @Test
    fun speakerChangesCreateDistinctTurns() {
        val conversation = ConversationTranscript()
        conversation.addFinal(
            listOf(
                segment("a", "S1", "여기에서 내려요.", 0.0),
                segment("b", "S2", "조금 더 가도 돼요.", 1.0),
                segment("c", "S2", "입구가 저 앞이에요.", 2.0),
                segment("d", "S1", "네, 알겠어요.", 3.0),
            ),
        )
        assertEquals(
            listOf(
                Triple("turn-0", "S1", "여기에서 내려요."),
                Triple("turn-1", "S2", "조금 더 가도 돼요. 입구가 저 앞이에요."),
                Triple("turn-2", "S1", "네, 알겠어요."),
            ),
            conversation.getTurns().map { Triple(it.id, it.speaker, it.text) },
        )
    }

    @Test
    fun partialRevisionsAreReplacedAndNeverDuplicated() {
        val conversation = ConversationTranscript()
        conversation.addFinal(listOf(segment("a", "S1", "음악", 0.0)))
        conversation.setPartial(listOf(segment("p", "S1", "소리", 1.0)))
        assertEquals("음악 소리", conversation.getTurns()[0].text)
        conversation.setPartial(listOf(segment("p", "S1", "소리를 조금 줄여", 1.0, 3.0)))
        assertEquals("음악 소리를 조금 줄여", conversation.getTurns()[0].text)
        conversation.addFinal(listOf(segment("b", "S1", "소리를 조금 줄여 주세요.", 1.0, 4.0)))
        val turn = conversation.getTurns().single()
        assertEquals("음악 소리를 조금 줄여 주세요.", turn.text)
        assertEquals("", turn.partialText)
        assertFalse(turn.isPartial)
        assertEquals("turn-0", turn.id)
    }

    @Test
    fun provisionalSpeakerChangeCanBeRetracted() {
        val conversation = ConversationTranscript()
        conversation.addFinal(listOf(segment("a", "S1", "창문을", 0.0)))
        conversation.setPartial(listOf(segment("p", "S2", "닫을까요?", 1.0)))
        assertEquals(2, conversation.getTurns().size)
        conversation.setPartial(listOf(segment("p", "S1", "닫을까요?", 1.0)))
        assertEquals(1, conversation.getTurns().size)
        conversation.clearPartial()
        assertEquals("창문을", conversation.getTurns()[0].text)
        conversation.addFinal(listOf(segment("b", "S1", "닫을까요?", 1.0)))
        assertEquals(1, conversation.getTurns().size)
    }

    @Test
    fun interimSplitDoesNotModifyCompletedTurns() {
        val conversation = ConversationTranscript()
        conversation.addFinal(listOf(segment("a", "S1", "오늘은", 0.0)))
        conversation.setPartial(listOf(segment("p1", "S1", "날씨가 좋아요.", 1.0), segment("p2", "S2", "산책할까요?", 2.0)))
        assertEquals(
            listOf(Triple("오늘은", "날씨가 좋아요.", true), Triple("", "산책할까요?", true)),
            conversation.getTurns().map { Triple(it.finalText, it.partialText, it.isPartial) },
        )
        conversation.setPartial(emptyList())
        assertEquals(listOf("오늘은"), conversation.getTurns().map { it.text })
    }

    @Test
    fun duplicateFinalIdsAreIgnored() {
        val conversation = ConversationTranscript()
        val repeated = listOf(segment("a", "S1", "네", 0.0))
        conversation.addFinal(repeated)
        conversation.addFinal(repeated)
        conversation.addFinal(listOf(segment("b", "S1", "네", 1.0)))
        assertEquals("네 네", conversation.getTurns()[0].text)
    }

    @Test
    fun punctuationAtBatchBoundariesAttachesWithoutSpaces() {
        val conversation = ConversationTranscript()
        listOf("반가워요", ",", "정말", "좋네요", ".", "“", "출발", "”").forEachIndexed { index, text ->
            conversation.addFinal(listOf(segment(index.toString(), "S1", text, index.toDouble())))
        }
        assertEquals("반가워요, 정말 좋네요. “출발”", conversation.getTurns()[0].text)
    }

    @Test
    fun blankTextAndBadTimesCannotCreateBrokenTurns() {
        val conversation = ConversationTranscript()
        conversation.addFinal(
            listOf(segment("blank", "S1", "  "), segment("a", "S1", "네"), Segment("b", "", "누구죠?", -1.0, Double.NaN)),
        )
        val turns = conversation.getTurns()
        assertEquals(listOf("S1", "UU"), turns.map { it.speaker })
        assertEquals(0.0, turns[1].start, 0.0)
        assertEquals(0.0, turns[1].end, 0.0)
    }

    @Test
    fun resetClearsEverything() {
        val conversation = ConversationTranscript()
        conversation.addFinal(listOf(segment("a", "S1", "이전 대화")))
        conversation.setPartial(listOf(segment("p", "S2", "미완성")))
        conversation.reset()
        assertTrue(conversation.getTurns().isEmpty())
        conversation.addFinal(listOf(segment("a", "S1", "새 대화")))
        assertEquals("turn-0", conversation.getTurns()[0].id)
        assertEquals("새 대화", conversation.getTurns()[0].text)
    }
}
