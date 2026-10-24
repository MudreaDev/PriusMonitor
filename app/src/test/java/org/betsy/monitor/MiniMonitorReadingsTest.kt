package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMonitorReadingsTest {
    private val now = 1_000_000L
    private val trip = TripSnapshot(distanceKm = 10.0, distanceCoveredKm = 10.0, liters = .42)

    private fun readings(
        obd: ObdData = ObdData(speed = 50f, rpm = 1200f, fuelRate = 2f),
        time: Long = now,
        trip: TripSnapshot = this.trip,
        connected: Boolean = true,
        connecting: Boolean = false,
        imperial: Boolean = false,
    ): MiniMonitorReadings =
        MiniMonitorReadings.from(
            sample = LiveSample(time = time, obd = obd, trip = trip),
            connected = connected,
            connecting = connecting,
            imperial = imperial,
            now = now,
        )

    @Test fun movingEcuReadingsShowAverageAndCalculatedLiveConsumption() {
        val result = readings()

        assertEquals("50", result.speed)
        assertEquals("km/h", result.speedUnit)
        assertEquals("4,2", result.average)
        assertEquals("4,0", result.live)
        assertEquals("l/100 km", result.liveUnit)
        assertTrue(result.source.contains("ECU"))
        assertTrue(result.notificationLine.contains("50"))
        assertTrue(result.notificationLine.contains("4,2"))
        assertTrue(result.notificationLine.contains("4,0"))
    }

    @Test fun lowSpeedUsesHourlyFuelAndFiveKmhStartsDistanceConsumption() {
        val creeping = readings(obd = ObdData(speed = 4.9f, rpm = 1000f, fuelRate = 1.5f))
        assertEquals("1,5", creeping.live)
        assertEquals("l/h", creeping.liveUnit)

        val moving = readings(obd = ObdData(speed = 5f, rpm = 1000f, fuelRate = 1.5f))
        assertEquals("30,0", moving.live)
        assertEquals("l/100 km", moving.liveUnit)
    }

    @Test fun confirmedStoppedEngineIsZeroWhileMissingFuelRemainsUnavailable() {
        val engineStopped = readings(obd = ObdData(speed = 0f, rpm = 0f))
        assertEquals("0", engineStopped.speed)
        assertEquals("0,0", engineStopped.live)
        assertEquals("l/h", engineStopped.liveUnit)

        val missingFuel = readings(obd = ObdData(speed = 0f))
        assertEquals("—", missingFuel.live)
        assertEquals("l/h", missingFuel.liveUnit)
    }

    @Test fun missingSpeedCannotInventDistanceConsumptionFromAvailableFuel() {
        val result = readings(obd = ObdData(rpm = 1200f, fuelRate = 2f))

        assertEquals("—", result.speed)
        assertEquals("—", result.live)
        assertEquals("l/100 km", result.liveUnit)
        assertEquals("4,2", result.average)
    }

    @Test fun mphPreferenceOnlyConvertsDisplayedSpeed() {
        val result = readings(imperial = true)

        assertEquals("31", result.speed)
        assertEquals("mph", result.speedUnit)
        assertEquals("4,0", result.live)
        assertEquals("l/100 km", result.liveUnit)
    }

    @Test fun twentySecondOldAndFutureSamplesDoNotAppearLive() {
        assertEquals("50", readings(time = now - 19_999).speed)

        for (time in listOf(now - 20_000, now + 1, 0L)) {
            val result = readings(time = time)
            assertEquals("—", result.speed)
            assertEquals("—", result.live)
            assertEquals("4,2", result.average)
        }
    }

    @Test fun nonFiniteReadingsNeverReachTheMiniMonitorAsNumbers() {
        val result =
            readings(
                obd = ObdData(speed = Float.NaN, rpm = 1200f, fuelRate = Float.POSITIVE_INFINITY),
                trip = trip.copy(liters = Double.NaN),
            )

        assertEquals("—", result.speed)
        assertEquals("—", result.live)
        assertEquals("—", result.average)
    }

    @Test fun infiniteSpeedCannotTurnAvailableFuelIntoZeroConsumption() {
        val result = readings(obd = ObdData(speed = Float.POSITIVE_INFINITY, rpm = 1200f, fuelRate = 2f))

        assertEquals("—", result.speed)
        assertEquals("—", result.live)
    }

    @Test fun stoppedOrReconnectingSessionCannotShowTheLastSampleAsCurrent() {
        for (connecting in listOf(false, true)) {
            val result = readings(connected = false, connecting = connecting)
            assertEquals("—", result.speed)
            assertEquals("—", result.live)
            assertEquals("—", result.average)
            assertEquals(if (connecting) "CONECTARE" else "OPRIT", result.state)
        }
    }

    @Test fun mafConsumptionKeepsItsEstimateLabel() {
        val result = readings(obd = ObdData(speed = 50f, rpm = 1200f, maf = 14.7f))

        assertEquals("9,7", result.live)
        assertTrue(result.source.contains("MAF"))
    }
}
