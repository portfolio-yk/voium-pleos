package com.example.pleos.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerTimelineTest {
    private fun seg(speaker: String, start: Double, end: Double) = Segment("x", speaker, "말", start, end)

    @Test
    fun picksSpeakerWithLongestOverlap() {
        val timeline = SpeakerTimeline()
        timeline.addFinal(listOf(seg("S1", 0.0, 2.0), seg("S2", 2.0, 5.0)))
        assertEquals("S2", timeline.speakerFor(1.5, 4.0))
        assertEquals("S1", timeline.speakerFor(0.0, 2.2))
        assertEquals(5.0, timeline.finalUntil, 0.0)
    }

    @Test
    fun fallsBackToNearbySpeakerOnlyWithinLimit() {
        val timeline = SpeakerTimeline()
        timeline.addFinal(listOf(seg("S1", 0.0, 1.0)))
        assertEquals("S1", timeline.speakerFor(2.0, 3.0))
        assertNull(timeline.speakerFor(10.0, 11.0))
    }

    @Test
    fun partialsCountUntilFinalsArrive() {
        val timeline = SpeakerTimeline()
        timeline.setPartial(listOf(seg("엄마", 3.0, 4.0)))
        assertEquals("엄마", timeline.speakerFor(3.0, 4.0))
        assertNull(timeline.speakerFor(3.0, 4.0, includePartial = false))
        assertEquals("엄마", timeline.latestSpeaker())
    }
}

class PleosTextFusionTest {
    private class FakeRecognizer : TextRecognizer {
        var callback: TextRecognizer.Callback? = null
        var sent = 0
        var released = false
        override fun start(callback: TextRecognizer.Callback) { this.callback = callback }
        override fun send(chunk: ByteArray) { sent++ }
        override fun release() { released = true }
    }

    private class Recorder : SpeechmaticsSession.Listener {
        val finals = ArrayList<Segment>()
        var partial: List<Segment> = emptyList()
        val states = ArrayList<SpeechmaticsSession.State>()
        override fun onState(state: SpeechmaticsSession.State) { states += state }
        override fun onPartial(segments: List<Segment>) { partial = segments }
        override fun onFinal(segments: List<Segment>) { finals += segments }
        override fun onError(message: String) = Unit
    }

    private val scheduled = ArrayList<() -> Unit>()
    private val recognizer = FakeRecognizer()
    private val out = Recorder()
    private val fusion = PleosTextFusion(out, recognizer) { _, action -> scheduled += action }

    /** 100ms 조각을 seconds초만큼 넣습니다. */
    private fun feed(seconds: Double) = repeat((seconds * 10).toInt()) { fusion.onAudio(ByteArray(3200)) }

    private fun smFinal(speaker: String, text: String, start: Double, end: Double) =
        fusion.onFinal(listOf(Segment("f", speaker, text, start, end)))

    @Test
    fun pleosTextGetsSpeakerFromTimeline() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        val pleos = recognizer.callback!!
        feed(1.0)
        pleos.onPartial("다들 안전")
        feed(1.5)
        pleos.onFinal("다들 안전벨트 했지?")
        // 화자 구분이 아직 오지 않아 기다립니다(임시 자막으로만 보임).
        assertTrue(out.finals.isEmpty())
        assertEquals("다들 안전벨트 했지?", out.partial.single().text)
        // Speechmatics 확정 결과가 그 구간까지 오면 화자를 붙여 확정합니다.
        smFinal("엄마", "다들 안전 벨트 했지", 0.3, 2.2)
        assertEquals(listOf("엄마" to "다들 안전벨트 했지?"), out.finals.map { it.speaker to it.text })
        assertTrue(out.partial.isEmpty())
        // Speechmatics 글자는 화면에 쓰지 않습니다.
        assertEquals(1, out.finals.size)
    }

    @Test
    fun twoSpeakersInOrder() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        val pleos = recognizer.callback!!
        feed(1.0); pleos.onPartial("창문"); feed(1.0); pleos.onFinal("창문 열까요?")
        feed(1.5); pleos.onPartial("괜찮"); feed(1.0); pleos.onFinal("괜찮아요")
        smFinal("S1", "창문 열까요", 0.3, 1.7)
        smFinal("S2", "괜찮아요", 3.2, 4.2)
        assertEquals(listOf("S1", "S2"), out.finals.map { it.speaker })
        assertEquals(listOf("창문 열까요?", "괜찮아요"), out.finals.map { it.text })
    }

    @Test
    fun mergedSentenceSplitsAtSpeakerChange() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        val pleos = recognizer.callback!!
        // 쉬지 않고 이어 말해 Pleos가 두 사람의 말을 한 문장으로 묶은 경우
        feed(1.0); pleos.onPartial("창문"); feed(3.0); pleos.onFinal("창문 좀 열어 줄까 응 열어 주세요")
        smFinal("S1", "창문 좀 열어 줄까", 0.2, 2.0)
        smFinal("S2", "응 열어 주세요", 2.1, 3.6)
        assertEquals(
            listOf("S1" to "창문 좀 열어 줄까", "S2" to "응 열어 주세요"),
            out.finals.map { it.speaker to it.text },
        )
    }

    @Test
    fun livePartialSwitchesToNewSpeaker() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        val pleos = recognizer.callback!!
        feed(1.0); pleos.onPartial("창문"); feed(2.0)
        smFinal("S1", "창문 좀 열어 줄까", 0.2, 2.0)
        fusion.onPartial(listOf(Segment("p", "S2", "응 열어", 2.1, 3.0)))
        pleos.onPartial("창문 좀 열어 줄까 응 열어")
        // 말하는 중인 자막도 바뀐 화자부터는 새 사람 이름으로 보입니다.
        assertEquals(
            listOf("S1" to "창문 좀 열어 줄까", "S2" to "응 열어"),
            out.partial.map { it.speaker to it.text },
        )
    }

    @Test
    fun splitPointFollowsDiarizedWords() {
        // 글자 수 비율로만 보면 "20분" 뒤에서 나뉘지만, 화자 구분 글자와 맞춰 "남았대" 뒤에서 나눕니다.
        val turns = listOf(
            SpeakerTimeline.Turn("S2", 4.3, 6.26, 1.96, 10.0, "휴게소까지 20분 남았대"),
            SpeakerTimeline.Turn("S1", 6.29, 8.5, 2.21, 10.0, "주차할 자리가 있으려나"),
        )
        val pieces = PleosTextFusion.splitBySpeaker("휴게소까지 20분 남았대 자리가 있으려나", 4.3, 8.5, turns)!!
        assertEquals(listOf("S2" to "휴게소까지 20분 남았대", "S1" to "자리가 있으려나"), pieces.map { it.speaker to it.text })
    }

    @Test
    fun shortInterjectionDoesNotSplit() {
        val turns = listOf(
            SpeakerTimeline.Turn("S1", 0.0, 1.5, 1.5, 8.0),
            SpeakerTimeline.Turn("S2", 1.5, 1.8, 0.3, 1.0),
            SpeakerTimeline.Turn("S1", 1.8, 3.0, 1.2, 6.0),
        )
        assertEquals(null, PleosTextFusion.splitBySpeaker("오늘 날씨 좋다 그치 바람도 시원하고", 0.0, 3.0, turns))
    }

    @Test
    fun givesUpWaitingAfterMaxWait() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        val pleos = recognizer.callback!!
        fusion.onPartial(listOf(Segment("p", "S3", "안녕", 0.2, 1.0)))
        feed(1.0); pleos.onPartial("안녕"); feed(0.5); pleos.onFinal("안녕하세요")
        assertTrue(out.finals.isEmpty())
        feed(PleosTextFusion.MAX_WAIT_SECONDS)
        scheduled.removeAt(0).invoke()
        assertEquals(listOf("S3" to "안녕하세요"), out.finals.map { it.speaker to it.text })
    }

    @Test
    fun stopFlushesEverything() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        val pleos = recognizer.callback!!
        feed(1.0); pleos.onFinal("첫 문장"); feed(1.0); pleos.onPartial("말하던 중")
        fusion.onState(SpeechmaticsSession.State.STOPPED)
        assertEquals(listOf("첫 문장", "말하던 중"), out.finals.map { it.text })
        assertTrue(recognizer.released)
        assertEquals(SpeechmaticsSession.State.STOPPED, out.states.last())
    }

    @Test
    fun failureSwitchesToSpeechmaticsText() {
        fusion.onState(SpeechmaticsSession.State.RECORDING)
        recognizer.callback!!.onFailed("not a Pleos car")
        smFinal("S1", "스피치매틱스 글자", 0.0, 1.0)
        assertEquals(listOf("스피치매틱스 글자"), out.finals.map { it.text })
        val before = recognizer.sent
        feed(1.0)
        assertEquals(before, recognizer.sent)
    }
}
