package com.example.pleos.stt

import org.junit.Assert.assertEquals
import org.junit.Test

/** sori-node tests/speaker-theme.test.js의 핵심 동작을 옮긴 테스트입니다. */
class SpeakerThemeControllerTest {

    /** 가짜 시계로 예약 작업을 직접 진행시킵니다. */
    private class FakeScheduler : Scheduler {
        var now = 0L
        val timers = mutableMapOf<Int, Pair<Long, Runnable>>()
        private var nextId = 0

        override fun schedule(delayMs: Long, task: Runnable): () -> Unit {
            val id = nextId++
            timers[id] = (now + delayMs) to task
            return { timers.remove(id) }
        }

        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = timers.entries.filter { it.value.first <= target }.minByOrNull { it.value.first } ?: break
                now = next.value.first
                timers.remove(next.key)
                next.value.second.run()
            }
            now = target
        }
    }

    private val one = SpeakerThemes.ALL[0]
    private val two = SpeakerThemes.ALL[1]
    private val neutral = SpeakerThemes.NEUTRAL

    private fun setup(): Triple<SpeakerThemeController, MutableList<SpeakerTheme>, FakeScheduler> {
        val emitted = mutableListOf<SpeakerTheme>()
        val scheduler = FakeScheduler()
        return Triple(SpeakerThemeController(scheduler) { emitted += it }, emitted, scheduler)
    }

    private fun SpeakerThemeController.show(theme: SpeakerTheme?, provisional: Boolean = false) =
        update(theme, provisional, followLatest = true, hasContent = true)

    @Test
    fun emptyConversationStartsNeutralOnce() {
        val (controller, emitted, scheduler) = setup()
        controller.update(null, provisional = false, followLatest = true, hasContent = false)
        controller.update(one, provisional = false, followLatest = true, hasContent = false)
        assertEquals(listOf(neutral), emitted)
        assertEquals(0, scheduler.timers.size)
    }

    @Test
    fun repeatedPartialWordsKeepTheOriginalDeadline() {
        val (controller, emitted, scheduler) = setup()
        controller.show(one, provisional = true)
        scheduler.advance(100)
        controller.show(one, provisional = true)
        scheduler.advance(79)
        assertEquals(listOf(neutral), emitted)
        scheduler.advance(1)
        assertEquals(listOf(neutral, one), emitted)
        controller.show(one)
        scheduler.advance(500)
        assertEquals(2, emitted.size)
        assertEquals(0, scheduler.timers.size)
    }

    @Test
    fun retractedProvisionalSpeakerNeverChangesBackground() {
        val (controller, emitted, scheduler) = setup()
        controller.show(one)
        controller.show(two, provisional = true)
        scheduler.advance(100)
        controller.show(one, provisional = true)
        scheduler.advance(500)
        assertEquals(listOf(neutral, one), emitted)
        assertEquals(0, scheduler.timers.size)
    }

    @Test
    fun finalSpeakerAndNeutralApplyImmediately() {
        val (controller, emitted, _) = setup()
        controller.show(two)
        controller.show(null, provisional = true)
        controller.show(one)
        controller.update(one, provisional = false, followLatest = false, hasContent = true)
        assertEquals(listOf(neutral, two, neutral, one, neutral), emitted)
    }

    @Test
    fun disposeCancelsPendingChange() {
        val (controller, emitted, scheduler) = setup()
        controller.show(one, provisional = true)
        controller.dispose()
        scheduler.advance(500)
        controller.show(two)
        assertEquals(listOf(neutral), emitted)
    }
}
