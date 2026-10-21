package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorDataTest {
    @Test fun standardPidsUseSaeScalingAndRejectWrongResponses() {
        assertEquals(60f, StandardPid.decode("0D", "41 0D 3C"), .001f)
        assertEquals(1726f, StandardPid.decode("0C", "410C1AF8"), .001f)
        assertEquals(83f, StandardPid.decode("05", "41057B"), .001f)
        assertEquals(12.34f, StandardPid.decode("10", "411004D2"), .001f)
        assertEquals(14.1f, StandardPid.decode("42", "41423714"), .001f)
        assertEquals(2.5f, StandardPid.decode("5E", "415E0032"), .001f)
        assertThrows(IllegalArgumentException::class.java) { StandardPid.decode("0D", "NO DATA") }
        assertThrows(Exception::class.java) { StandardPid.decode("0C", "410C12") }
    }

    @Test fun supportedMaskDoesNotInventSupport() {
        assertEquals(setOf("01", "20"), StandardPid.supported("410080000001", 0))
        assertEquals(emptySet<String>(), StandardPid.supported("412000000000", 32))
    }

    @Test fun stoppedEngineAndDirectFuelRateAreDistinctFromMissingData() {
        assertEquals(FuelSource.ENGINE_STOPPED, Consumption.from(ObdData(rpm = 0f)).source)
        assertFalse(Consumption.from(ObdData(rpm = 0f)).estimated)
        assertEquals(FuelSource.UNAVAILABLE, Consumption.from(ObdData()).source)
        assertEquals(0.0, Consumption.from(ObdData(rpm = 0f)).litersHour!!, .00001)
        assertNull(Consumption.from(ObdData()).litersHour)
        val direct = Consumption.from(ObdData(rpm = 1000f, fuelRate = 2f))
        assertFalse(direct.estimated)
        assertEquals(2.0, direct.litersHour!!, .00001)
        assertTrue(Consumption.from(ObdData(rpm = 1000f, maf = 10f)).estimated)
        assertNull(direct.per100(0f))
        assertNull(direct.per100(4f))
        assertNull(direct.per100(null))
        assertEquals(4.0, direct.per100(50f)!!, .0001)
    }

    @Test fun trafficStopsRemainInTripAverageAndHaveSeparateFuelTotal() {
        val meter = TripMeter()
        var now = 0L

        fun add(d: ObdData) {
            meter.update(now, d)
            now += 1000
        }
        val moving = ObdData(speed = 36f, rpm = 1200f, fuelRate = 3.6f)
        repeat(61) { add(moving) }
        val beforeStop = meter.snapshot
        val stop = ObdData(speed = 0f, rpm = 1000f, fuelRate = 1.8f)
        repeat(61) { add(stop) }
        val afterStop = meter.snapshot
        assertEquals(.6, beforeStop.distanceKm, .000001)
        assertEquals(10.0, beforeStop.average!!, .0001)
        assertTrue(afterStop.average!! > beforeStop.average!!)
        assertEquals(.03, afterStop.stoppedLiters, .000001)
        assertEquals(60.0, afterStop.stoppedSeconds, .000001)
        assertEquals(100.0, afterStop.coverage, .000001)
    }

    @Test fun gapsAndMissingSpeedDoNotCreateFuelOrDistance() {
        val meter = TripMeter()
        val d = ObdData(speed = 60f, rpm = 1500f, fuelRate = 3f)
        meter.update(0, d)
        meter.update(1000, d)
        val before = meter.snapshot
        meter.update(30000, d)
        assertEquals(before, meter.snapshot)
        meter.update(31000, ObdData(rpm = 1500f, fuelRate = 3f))
        assertEquals(before.liters, meter.snapshot.liters, .000001)
        assertEquals(before.distanceKm, meter.snapshot.distanceKm, .000001)
        assertEquals(50.0, meter.snapshot.coverage, .000001)
    }

    @Test fun mafFuelUsesPetrolAssumptionsAndZeroRpmStopsFuel() {
        val c = Consumption.from(ObdData(speed = 50f, rpm = 1200f, maf = 14.7f))
        assertEquals(3600 / 745.0, c.litersHour!!, .00001)
        assertEquals(0.0, Consumption.from(ObdData(rpm = 0f, maf = 10f)).litersHour!!, .00001)
    }
}
