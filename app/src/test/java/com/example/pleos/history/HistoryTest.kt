package com.example.pleos.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class HistoryTest {
    @Test
    fun durationFormatsHoursAndMinutes() {
        assertEquals("1시간 35분", HistoryFormat.duration(0, 95 * 60_000L))
        assertEquals("36분", HistoryFormat.duration(0, 36 * 60_000L))
        assertEquals("2시간", HistoryFormat.duration(0, 120 * 60_000L))
        assertEquals("1분", HistoryFormat.duration(0, 10_000L))
    }

    @Test
    fun dayLabelsTodayAndYesterday() {
        val now = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 11, 9, 0) }.timeInMillis
        val today = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 11, 0, 30) }.timeInMillis
        val yesterday = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 10, 23, 50) }.timeInMillis
        val older = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 7, 16, 2) }.timeInMillis
        assertEquals("오늘", HistoryFormat.day(today, now))
        assertEquals("어제", HistoryFormat.day(yesterday, now))
        assertEquals("11월 7일 (토)", HistoryFormat.day(older, now))
    }

    @Test
    fun parsesHeadlineAndBullets() {
        val (headline, bullets) = ConversationSummarizer.parse(
            "휴게소에 들러 호두과자를 사 먹기로 했어요\n- 휴게소에 들르기로 했어요\n- 할머니는 팥이 좋았다고 했어요\n- 20분 남았어요",
        )!!
        assertEquals("휴게소에 들러 호두과자를 사 먹기로 했어요", headline)
        assertEquals(3, bullets.size)
        assertEquals("휴게소에 들르기로 했어요", bullets[0])
        assertNull(ConversationSummarizer.parse("   "))
    }

    @Test
    fun recordsRoundTripThroughJson() {
        val record = ConversationRecord(
            id = "a", startedAt = 1, endedAt = 2,
            lines = listOf(RecordLine(1, "나", "blue", "안녕"), RecordLine(2, "화자 1", null, "네")),
            headline = "인사했어요", summary = listOf("인사했어요"), summaryState = SummaryState.DONE,
        )
        val back = ConversationHistoryStore.fromJson(ConversationHistoryStore.toJson(listOf(record)))
        assertEquals(listOf(record), back)
        assertEquals(listOf("나" to "blue", "화자 1" to null), back.first().participants)
    }
}
