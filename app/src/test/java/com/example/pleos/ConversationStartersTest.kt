package com.example.pleos

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointInfo
import com.example.pleos.car.CarSignals
import com.example.pleos.car.TripPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationStartersTest {

    @Test
    fun tripLabelUsesPlaceAndMinutes() {
        val slot = ConversationStarters.forTrip("휴게소", 20 * 60)
        assertEquals("휴게소까지 20분", slot.label)
        assertTrue(slot.questions.first().startsWith("휴게소에 도착하면"))
    }

    @Test
    fun longTripSuggestsRest() {
        val slot = ConversationStarters.forTrip("부산역", 125 * 60)
        assertEquals("부산역까지 2시간 5분", slot.label)
        assertTrue(slot.questions.any { it.contains("쉬었다 갈까요") })
    }

    @Test
    fun durationRoundsUpToMinutes() {
        assertEquals("1분", ConversationStarters.duration(0))
        assertEquals("1시간", ConversationStarters.duration(60))
        assertEquals("1시간 1분", ConversationStarters.duration(61))
    }

    private fun waypoint(name: String, remain: Int) = WaypointInfo(name, "", 0.0, 0.0, null, null, 0, remain, "", null)
    private fun destination(name: String, remain: Int) = DestinationInfo(0.0, 0.0, null, null, name, "", 0, remain, 0, "", null)

    @Test
    fun nextStopPrefersWaypoint() {
        val next = TripPlanner.nextStop(destination("부산역", 9000), listOf(waypoint("휴게소", 1200)))
        assertEquals(CarSignals.Trip("휴게소", 1200), next)
    }

    @Test
    fun nextStopFallsBackToDestination() {
        assertEquals(CarSignals.Trip("부산역", 9000), TripPlanner.nextStop(destination("부산역", 9000), emptyList()))
        assertNull(TripPlanner.nextStop(null, emptyList()))
        assertNull(TripPlanner.nextStop(destination("", 100), emptyList()))
    }
}
