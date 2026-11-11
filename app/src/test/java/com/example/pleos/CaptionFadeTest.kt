package com.example.pleos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionFadeTest {

    private val now = 1_000L

    private fun plan(old: String, new: String, oldStarts: LongArray = LongArray(old.length), fadeFrom: Int = 0) =
        CaptionFade.plan(old, new, oldStarts, now, fadeFrom, enabled = true)

    /** 서서히 나타나는 글자만 남긴 문자열 */
    private fun fading(new: String, starts: LongArray) = new.filterIndexed { i, _ -> starts[i] == now }

    @Test
    fun appendedWordsFadeIn() {
        val new = "다들 안전벨트 했지?"
        assertEquals("했지?", fading(new, plan("다들 안전벨트", new))) // 공백은 보이지 않으므로 바로
    }

    @Test
    fun punctuationOnlyAppearsAtOnce() {
        val new = "먹었습니다."
        assertEquals("", fading(new, plan("먹었습니다", new)))
    }

    @Test
    fun correctionInTheMiddleDoesNotAnimate() {
        val new = "저녁을 먹었습니다"
        assertEquals("", fading(new, plan("저녁 먹었습니다", new)))
    }

    @Test
    fun replacedEndingSwitchesAtOnceAndOnlyExtraWordsFade() {
        val new = "먹었어요 그리고"
        // "어요 "는 원래 "습니다" 자리라 바로 바뀌고, 늘어난 "그리고"만 서서히 나타납니다.
        assertEquals("그리고", fading(new, plan("먹었습니다", new)))
    }

    @Test
    fun fadingLettersKeepTheirProgress() {
        val old = "다들 안전벨트 했지"
        val oldStarts = LongArray(old.length) { if (it >= 8) 900L else 0L } // "했지"는 0.1초 전부터 진해지는 중
        val starts = plan(old, "다들 안전벨트 했지?", oldStarts)
        assertEquals(900L, starts[8])
        assertEquals(900L, starts[9])
        assertEquals(0L, starts[10]) // 물음표만 붙은 것이라 바로 보여 줍니다.
    }

    @Test
    fun keptSuffixKeepsProgressAfterCorrection() {
        val old = "저녁 먹었"
        val oldStarts = LongArray(old.length) { if (it >= 3) 900L else 0L }
        val starts = plan(old, "저녁을 먹었", oldStarts)
        assertEquals(0L, starts[2]) // 끼어든 "을"은 바로
        assertEquals(900L, starts[4]) // "먹"은 진해지던 상태 그대로
    }

    @Test
    fun wordsSeenBeforeDoNotFadeAgain() {
        // 예비 "다들 안전벨트 했지"가 보였다가, 확정 "다들 안전벨트."가 오면서 "했지"가 잠깐 빠진 뒤
        // 다음 예비 결과로 "했지 휴게소"가 다시 붙은 경우: 처음 보는 "휴게소"만 서서히 나타납니다.
        val known = CaptionFade.words("다들 안전벨트 했지")
        val new = "다들 안전벨트. 했지 휴게소"
        val starts = CaptionFade.plan("다들 안전벨트.", new, LongArray(8), now, fadeFrom = 8, enabled = true, knownWords = known)
        assertEquals("휴게소", fading(new, starts))
    }

    @Test
    fun wordsIgnorePunctuation() {
        assertEquals(setOf("다들", "했지"), CaptionFade.words("다들, 했지?"))
    }

    @Test
    fun finalTextAreaNeverFades() {
        val new = "출발할게요 다들"
        val starts = plan("출발할게요", new, fadeFrom = new.length)
        assertTrue(starts.all { it == 0L })
    }
}
