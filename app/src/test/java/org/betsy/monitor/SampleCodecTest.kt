package org.betsy.monitor

import org.betsy.model.BatteryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleCodecTest {
    @Test fun missingValuesAndBatteryAvailabilitySurviveJsonRoundTrip() {
        val original = LiveSample(1000, ObdData(speed = 0f, rpm = 0f, coolant = null), null, listOf("ECU fără răspuns"))
        val copy = SampleCodec.fromJson(SampleCodec.json(original))
        assertEquals(original, copy)
        assertNull(copy.obd.coolant)
        assertNull(copy.battery)
        assertEquals(0f, copy.obd.speed!!, .00001f)
    }

    @Test fun unreadHvScalarsAreNotSerializedAsZero() {
        val battery = BatteryModel(soc = Float.NaN, currentAmps = Float.NaN, blockVolts = listOf(15f, 15.1f))
        val copy = SampleCodec.fromJson(SampleCodec.json(LiveSample(1000, battery = battery)))
        assertTrue(copy.battery!!.soc.isNaN())
        assertTrue(copy.battery!!.currentAmps.isNaN())
        assertEquals(listOf(15f, 15.1f), copy.battery!!.blockVolts)
        assertFalse(SampleCodec.json(LiveSample(1000, battery = battery)).contains("NaN"))
    }

    @Test fun csvHasStableColumnsAndEmptyCellsForUnavailableReadings() {
        val fields = SampleCodec.csvRow(LiveSample(1000, ObdData(speed = 50f, rpm = 1200f, maf = 10f))).split(';')
        val header = SampleCodec.csvHeader.split(';')
        assertEquals(header.size, fields.size)
        assertEquals("1970-01-01T00:00:01Z", fields[0])
        assertEquals("50.0", fields[header.indexOf("speed_kmh")])
        assertEquals("", fields[header.indexOf("coolant_c")])
        assertEquals("true", fields[header.indexOf("fuel_estimated")])
        assertEquals("", fields[header.indexOf("block14_v")])
    }
}
