package com.example.pleos

import com.example.pleos.ai.ReplyAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyAiTest {
    @Test
    fun asksOnlyForQuestionsAndRequests() {
        listOf(
            "휴게소에서 뭐 먹을래?",
            "창문 조금 열어드릴까요",
            "다들 안전벨트 했지?",
            "에어컨 좀 줄여 줄래요",
            "어디서 쉬었다 갈래",
            "괜찮아?",
        ).forEach { assertTrue(it, ReplyAi.needsReply(it)) }
        listOf(
            "휴게소까지 20분 남았대",
            "그 집 팥이 달지 않아서 좋더라",
            "응",
            "바람 좋네",
        ).forEach { assertFalse(it, ReplyAi.needsReply(it)) }
    }

    @Test
    fun promptIsShortAndHasContext() {
        val prompt = ReplyAi.promptOf("뭐 먹을래?", listOf("엄마: 배고프다"))
        assertTrue(prompt.contains("엄마: 배고프다"))
        assertTrue(prompt.contains("받은 말: 뭐 먹을래?"))
        assertTrue("prompt should stay small", prompt.length < 260)
        val withTopic = ReplyAi.promptOf("뭐 먹을래?", emptyList(), "휴게소에서 호두과자를 먹기로 했어요.")
        assertTrue(withTopic.contains("지난번 대화: 휴게소에서 호두과자를"))
    }

    @Test
    fun contextKeepsNewestLinesWithinBudget() {
        val lines = (1..10).map { "화자 1: 문장 $it" }
        val fitted = ReplyAi.fitContext(lines)
        assertEquals(ReplyAi.CONTEXT_LINES, fitted.size)
        assertEquals("화자 1: 문장 10", fitted.last())
        assertEquals("화자 1: 문장 5", fitted.first())

        val long = (1..10).map { "엄마: " + "가".repeat(120) + it }
        val trimmed = ReplyAi.fitContext(long)
        assertTrue(trimmed.sumOf { it.length } <= ReplyAi.CONTEXT_CHARS)
        assertTrue(trimmed.all { it.length <= ReplyAi.LINE_CHARS + 1 })
        assertTrue(trimmed.last().endsWith("10"))
    }

    @Test
    fun parsesThreeRepliesWithoutMarkers() {
        val text = "1. 좋아요, 먹을래요\n- 배 안 고파요\n\n• 호두과자요\n- 네 번째"
        assertEquals(listOf("좋아요, 먹을래요", "배 안 고파요", "호두과자요"), ReplyAi.parse(text))
    }
}
