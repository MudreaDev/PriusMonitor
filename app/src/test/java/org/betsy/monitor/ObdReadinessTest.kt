package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdReadinessTest {
    @Test fun milCountAndCompletionAreDecodedSeparatelyFromSupport() {
        val r = ObdReadiness.decode("41 01 83 07 A5 21")
        assertTrue(r.milOn)
        assertEquals(3, r.codeCount)
        assertTrue(r.sparkIgnition)
        assertEquals(7, r.supportedCount)
        assertEquals(5, r.completeCount)
        assertEquals(false, r.monitors.single { it.id == "catalyst" }.complete)
        assertEquals(true, r.monitors.single { it.id == "evap" }.complete)
        assertNull(r.monitors.single { it.id == "heated_catalyst" }.complete)
    }

    @Test fun unsupportedDoesNotMeanFinishedAndDieselExtrasAreNotGuessed() {
        val empty = ObdReadiness.decode("4101000000FF")
        assertEquals(0, empty.supportedCount)
        assertEquals(0, empty.completeCount)
        assertTrue(empty.monitors.all { it.complete == null })
        val diesel = ObdReadiness.decode("4101000FFF00")
        assertFalse(diesel.sparkIgnition)
        assertEquals(3, diesel.monitors.size)
        assertThrows(Exception::class.java) { ObdReadiness.decode("41010007") }
        assertThrows(IllegalArgumentException::class.java) { ObdReadiness.decode("NO DATA") }
    }

    @Test fun readinessSurvivesJournalPersistenceWithItsOriginalRawBytes() {
        val sample = LiveSample(1000, ObdData(readiness = ObdReadiness.decode("41018307A521")))
        assertEquals(sample.obd.readiness, SampleCodec.fromJson(SampleCodec.json(sample)).obd.readiness)
    }
}
