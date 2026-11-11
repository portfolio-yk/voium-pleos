package com.example.pleos.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileColorTest {

    private fun profile(label: String, color: String? = null, sentences: Int = 0) =
        VoiceProfile(label, label, listOf("id-$label"), 0L, color, sentences)

    @Test
    fun oldProfilesGetUnusedColorsInOrder() {
        val colored = VoiceProfileRules.withColors(listOf(profile("a", "blue"), profile("b"), profile("c")))
        assertEquals(listOf("blue", "green", "orange"), colored.map { it.colorKey })
    }

    @Test
    fun keepsChosenColorsEvenWhenDuplicated() {
        val colored = VoiceProfileRules.withColors(listOf(profile("a", "pink"), profile("b", "pink")))
        assertEquals(listOf("pink", "pink"), colored.map { it.colorKey })
    }

    @Test
    fun colorAndSentencesSurviveJson() {
        val saved = VoiceProfileRules.fromJson(VoiceProfileRules.toJson(listOf(profile("Pabc", "yellow", 3))))
        assertEquals("yellow", saved.single().colorKey)
        assertEquals(3, saved.single().sentences)
    }

    @Test
    fun oldJsonHasNoColorAndNeedsReenroll() {
        val old = """[{"label":"Pabc","name":"엄마","identifiers":["x"],"createdAt":1}]"""
        val loaded = VoiceProfileRules.fromJson(old).single()
        assertNull(loaded.colorKey)
        assertEquals(true, loaded.needsReenroll)
    }

    @Test
    fun firstUnusedSkipsUsedColors() {
        assertEquals("orange", SpeakerThemes.firstUnused(listOf("blue", "green")).key)
    }
}
