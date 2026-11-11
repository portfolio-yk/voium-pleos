package com.example.pleos.stt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerIdentificationTest {

    private fun profile(label: String, name: String, vararg ids: String) = VoiceProfile(label, name, ids.toList(), 0L)

    // ------------------------------------------------------------ 이름 표시

    @Test
    fun enrolledSpeakerShowsNameAndOthersAreNumberedInOrder() {
        val directory = SpeakerDirectory(listOf(profile("Pabc", "홍길동", "id1")))
        assertEquals("홍길동: 에어컨 줄여줘", directory.line("Pabc", " 에어컨 줄여줘 "))
        assertEquals("화자 1: 음악 바꿔줘", directory.line("S2", "음악 바꿔줘"))
        assertEquals("화자 2: 창문 열어줘", directory.line("S1", "창문 열어줘"))
        assertEquals("화자 1", directory.resolve("S2").label)
        assertTrue(directory.resolve("Pabc").isEnrolled)
    }

    @Test
    fun undeterminedSpeakerHasNoNumberOrColor() {
        val directory = SpeakerDirectory()
        val unknown = directory.resolve("UU")
        assertEquals(SpeakerDirectory.UNKNOWN_LABEL, unknown.label)
        assertNull(unknown.colorIndex)
        assertEquals("화자 1", directory.resolve("S1").label)
    }

    @Test
    fun provisionalSpeakersDoNotConsumeNumbers() {
        val directory = SpeakerDirectory()
        assertEquals("화자 1", directory.resolve("S3", commit = false).label)
        // S3는 중간 결과에서 사라지고, 확정 결과에는 S1만 나왔습니다.
        assertEquals("화자 1", directory.resolve("S1").label)
        assertEquals("화자 2", directory.resolve("S3", commit = false).label)
    }

    @Test
    fun colorsFollowAppearanceOrderAcrossEnrolledAndGuests() {
        val directory = SpeakerDirectory(listOf(profile("Pabc", "홍길동", "id1")))
        assertEquals(0, directory.resolve("S1").colorIndex)
        assertEquals(1, directory.resolve("Pabc").colorIndex)
        assertEquals(0, directory.resolve("S1").colorIndex)
        directory.reset()
        assertEquals(0, directory.resolve("Pabc").colorIndex)
    }

    @Test
    fun renamedProfileIsReflectedWithoutReenrolling() {
        val directory = SpeakerDirectory(listOf(profile("Pabc", "홍길동", "id1")))
        directory.setProfiles(listOf(profile("Pabc", "길동이", "id1")))
        assertEquals("길동이", directory.resolve("Pabc").label)
    }

    // ------------------------------------------------------------ StartRecognition

    @Test
    fun identificationSendsEnrolledSpeakersAndSensitivity() {
        val options = SpeechmaticsSession.Options(
            enrolledSpeakers = listOf(EnrolledSpeaker("Pabc", listOf("id1", "id2"))),
            speakerSensitivity = 0.8,
        )
        val config = RecognitionConfig.startRecognition(options, 16000)
        val diarization = config.getJSONObject("transcription_config").getJSONObject("speaker_diarization_config")
        val speaker = diarization.getJSONArray("speakers").getJSONObject(0)
        assertEquals("Pabc", speaker.getString("label"))
        assertEquals(2, speaker.getJSONArray("speaker_identifiers").length())
        assertEquals(0.8, diarization.getDouble("speaker_sensitivity"), 0.0)
        assertFalse(diarization.has("get_speakers"))
        assertEquals("StartRecognition", config.getString("message"))
        assertEquals(16000, config.getJSONObject("audio_format").getInt("sample_rate"))
    }

    @Test
    fun enrollmentRequestsSpeakersAndPlainSessionSendsNeither() {
        val enroll = RecognitionConfig.startRecognition(SpeechmaticsSession.Options(getSpeakers = true), 16000)
            .getJSONObject("transcription_config").getJSONObject("speaker_diarization_config")
        assertTrue(enroll.getBoolean("get_speakers"))
        assertFalse(enroll.has("speakers"))

        val plain = RecognitionConfig.startRecognition(SpeechmaticsSession.Options(), 16000)
            .getJSONObject("transcription_config").getJSONObject("speaker_diarization_config")
        assertFalse(plain.has("speakers"))
        assertFalse(plain.has("speaker_sensitivity"))
        assertTrue(plain.getBoolean("prefer_current_speaker"))
    }

    @Test
    fun identifierLimitAndInvalidLabelsAreEnforced() {
        val many = (1..40).map { "a$it" }
        val limited = RecognitionConfig.limitIdentifiers(
            listOf(
                EnrolledSpeaker("S1", listOf("reserved")),
                EnrolledSpeaker("UU", listOf("reserved")),
                EnrolledSpeaker(" Pspace", listOf("x")),
                EnrolledSpeaker("Pa", many),
                EnrolledSpeaker("Pa", listOf("duplicate")),
                EnrolledSpeaker("Pb", (1..20).map { "b$it" }),
                EnrolledSpeaker("Pc", listOf("c1")),
            ),
        )
        assertEquals(listOf("Pa", "Pb"), limited.map { it.label })
        assertEquals(RecognitionConfig.MAX_IDENTIFIERS, limited.sumOf { it.identifiers.size })
        assertEquals((11..20).map { "b$it" }, limited[1].identifiers)
    }

    @Test
    fun parsesSpeakersResult() {
        val message = JSONObject(
            """{"message":"SpeakersResult","speakers":[{"label":"S1","speaker_identifiers":["<id1>"]},{"label":"S2","speaker_identifiers":[]},{"speaker_identifiers":["x"]}]}""",
        )
        assertEquals(listOf(EnrolledSpeaker("S1", listOf("<id1>"))), RecognitionConfig.parseSpeakers(message))
    }

    // ------------------------------------------------------------ 프로필

    @Test
    fun profilesRoundTripThroughJsonAndDropBrokenEntries() {
        val profiles = listOf(profile("Pabc", "홍길동", "id1", "id2"), profile("Pdef", "김철수", "id3"))
        assertEquals(profiles, VoiceProfileRules.fromJson(VoiceProfileRules.toJson(profiles)))
        val broken = """[{"label":"S1","name":"x","identifiers":["a"]},{"label":"Pz","name":"y","identifiers":[]},{"name":"z"},"oops"]"""
        assertTrue(VoiceProfileRules.fromJson(broken).isEmpty())
        assertTrue(VoiceProfileRules.fromJson("not json").isEmpty())
    }

    @Test
    fun nameValidation() {
        val existing = listOf(profile("Pabc", "홍길동", "id1"))
        assertNotNull(VoiceProfileRules.validateName("  ", existing))
        assertNotNull(VoiceProfileRules.validateName("홍길동", existing))
        assertNull(VoiceProfileRules.validateName("홍길동", existing, editingLabel = "Pabc"))
        assertNotNull(VoiceProfileRules.validateName("발화자1", existing))
        assertNotNull(VoiceProfileRules.validateName("발화자 2", existing))
        assertNotNull(VoiceProfileRules.validateName("미등록 1", existing))
        assertNotNull(VoiceProfileRules.validateName("미등록3", existing))
        assertNotNull(VoiceProfileRules.validateName("화자 1", existing))
        assertNotNull(VoiceProfileRules.validateName("가".repeat(VoiceProfileRules.MAX_NAME_LENGTH + 1), existing))
        assertNull(VoiceProfileRules.validateName("엄마", existing))
        val label = VoiceProfileRules.newLabel()
        assertFalse(RecognitionConfig.isReservedLabel(label))
        assertTrue(label.startsWith("P"))
    }
}
