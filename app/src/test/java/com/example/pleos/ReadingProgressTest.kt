package com.example.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressTest {
    private val sentence = "창문 조금만 열어줄래요?"

    @Test
    fun readsAll() {
        assertEquals(sentence.length, ReadingProgress.readUpTo(sentence, "창문 조금만 열어줄래요"))
        assertTrue(ReadingProgress.isComplete(sentence, "창문 조금만 열어 줄래요."))
    }

    @Test
    fun readsPartially() {
        assertEquals("창문 조금만 열".length, ReadingProgress.readUpTo(sentence, "창문 조금만 열"))
        assertFalse(ReadingProgress.isComplete(sentence, "창문 조금만 열"))
    }

    @Test
    fun ignoresSpacingAndPunctuation() {
        assertEquals("창문 조금".length, ReadingProgress.readUpTo(sentence, "창문조금"))
    }

    @Test
    fun skipsOneMisheardLetter() {
        // "조금만"이 "조근만"으로 들려도 이어서 셉니다.
        assertEquals(sentence.length, ReadingProgress.readUpTo(sentence, "창문 조근만 열어줄래요"))
    }

    @Test
    fun findsSentenceAfterEarlierWords() {
        // 앞 문장 끝부분이 섞여 있어도 이 문장을 찾습니다.
        assertEquals(sentence.length, ReadingProgress.readUpTo(sentence, "가는 길이에요 창문 조금만 열어줄래요"))
    }

    @Test
    fun stopsAtUnrelatedSpeech() {
        assertEquals("창문".length, ReadingProgress.readUpTo(sentence, "창문 닫아 주세요 빨리"))
        assertEquals(0, ReadingProgress.readUpTo(sentence, "음악 틀어줘"))
    }

    @Test
    fun lastWordBeforeStall() {
        assertEquals("조금만", ReadingProgress.lastWord(sentence, "창문 조금만".length))
        assertEquals("조금", ReadingProgress.lastWord(sentence, "창문 조금".length)) // 시안의 예
        assertEquals("창문", ReadingProgress.lastWord(sentence, "창문 조".length))
        assertEquals(null, ReadingProgress.lastWord(sentence, 0))
    }
}
