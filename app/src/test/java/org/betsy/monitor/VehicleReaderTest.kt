package org.betsy.monitor

import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmSession
import org.betsy.transport.ElmTransport
import org.betsy.transport.ReplayTransport
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleReaderTest {
    private fun script() =
        mutableMapOf(
            "ATZ" to "ELM327 v1.5",
            "0100" to "4100FFFFFFFF",
            "0120" to "4120FFFFFFFF",
            "0140" to "4140FFFFFFFF",
            "7E0/010D" to "410D32",
            "7E0/0101" to "41018307A521",
            "7E0/010C" to "410C1770",
            "7E0/0105" to "41057A",
            "7E0/0110" to "411003E8",
            "7E0/0142" to "41423714",
            "7E0/015E" to "NO DATA",
            "7E0/010F" to "410F44",
            "7E0/0104" to "410480",
            "7E2/2181" to "6181" + "2E1B".repeat(14) + "AF24",
            "7E2/2101" to "6101" + "00".repeat(21) + "99",
            "7E2/2198" to "6198800099AA64",
            "7E2/2187" to "618700005A005A005A00",
            "7E2/2195" to "6195" + "19".repeat(14),
        )

    @Test fun gen3LivePathUsesPhysicalEcUsAndRetainsMissingFuelRate() {
        val replay = ReplayTransport(script())
        val elm = ElmSession(replay)
        val reader = VehicleReader(elm) { 1000 }
        val result =
            awaitBlocking {
                elm.initialize()
                reader.initialize()
                reader.read()
            }
        assertEquals(VehicleModel.GEN3, reader.info?.model)
        assertEquals(50f, result.first.speed!!, .001f)
        assertEquals(1500f, result.first.rpm!!, .001f)
        assertEquals(82f, result.first.coolant!!, .001f)
        assertTrue(result.first.readiness!!.milOn)
        assertEquals(3, result.first.readiness!!.codeCount)
        assertNull(result.first.fuelRate)
        assertTrue(Consumption.from(result.first).estimated)
        assertEquals(14, result.second!!.blockVolts.size)
        assertEquals(14.4f, result.second!!.blockVolts.first(), .01f)
        assertEquals(60f, result.second!!.soc, .1f)
        assertEquals(listOf(40f, 40f, 40f), result.second!!.temps)
        assertTrue(result.third.isEmpty())
        assertTrue(replay.sent.contains("ATSH7E0"))
        assertTrue(replay.sent.contains("ATSH7E2"))
        assertFalse(replay.sent.contains("04"))
        assertTrue(replay.sent.all { it.startsWith("AT") || it.startsWith("01") || it.startsWith("21") })
    }

    @Test fun genericDataSurvivesUnavailableHybridEcUsAndRejectedPids() {
        val script = script().filterKeys { !it.startsWith("7E2/") }.toMutableMap()
        script["7E0/0110"] = "?"
        val reader = VehicleReader(ElmSession(ReplayTransport(script))) { 1000 }
        val result =
            awaitBlocking {
                reader.initialize()
                reader.read()
            }
        assertNull(reader.info)
        assertNull(result.second)
        assertEquals(50f, result.first.speed!!, .001f)
        assertNull(result.first.maf)
        assertNull(Consumption.from(result.first).litersHour)
    }

    @Test fun missingAndTruncatedResponsesNeverKeepPreviousValuesOrPartialFields() {
        val script = script()
        val reader = VehicleReader(ElmSession(ReplayTransport(script))) { 1000 }
        awaitBlocking {
            reader.initialize()
            reader.read()
        }
        script["7E0/010D"] = "NO DATA"
        script["7E2/2181"] = "61812E1B"
        script["7E2/2198"] = "6198800099AA"
        val result = awaitBlocking { reader.read() }
        assertNull(result.first.speed)
        assertTrue(result.second!!.blockVolts.isEmpty())
        assertTrue(result.second!!.currentAmps.isNaN())
        assertTrue(result.third.any { it.startsWith("2181") })
        assertTrue(result.third.any { it.startsWith("2198") })
        assertNotNull(result.first.rpm)
    }

    @Test fun transportFailuresAreNotMisreportedAsUnsupportedPids() {
        val dead =
            object : ElmTransport {
                override var readTimeoutMs = 100

                override suspend fun send(cmd: String): String = throw TransportException("link lost")

                override fun close() { }
            }
        val reader = VehicleReader(ElmSession(dead)) { 1000 }
        assertThrows(TransportException::class.java) { awaitBlocking { reader.initialize() } }
    }

    @Test fun aZeroVoltageReportedByTheEcuIsNotHiddenByAHealthyVoltageFilter() {
        val script = script()
        script["7E2/2181"] = "6181" + "0000" + "2E1B".repeat(13) + "AF24"
        val reader = VehicleReader(ElmSession(ReplayTransport(script))) { 1000 }
        val result =
            awaitBlocking {
                reader.initialize()
                reader.read()
            }
        assertEquals(VehicleModel.GEN3, reader.info?.model)
        assertEquals(0f, result.second!!.blockVolts.first(), .0001f)
        assertEquals(14, result.second!!.blockVolts.size)
    }

    @Test fun unavailableHvFieldsDoNotWasteEveryPollCycleAndCanRecover() {
        val script = script()
        script["7E2/2198"] = "NO DATA"
        val replay = ReplayTransport(script)
        var now = 1000L
        val reader = VehicleReader(ElmSession(replay)) { now }
        awaitBlocking {
            reader.initialize()
            reader.read()
        }
        assertEquals(1, replay.sent.count { it == "2198" })
        now = 2000
        val unavailable = awaitBlocking { reader.read() }
        assertEquals(1, replay.sent.count { it == "2198" })
        assertTrue(unavailable.second!!.currentAmps.isNaN())
        script["7E2/2198"] = "6198800099AA64"
        now = 17000
        val recovered = awaitBlocking { reader.read() }
        assertEquals(2, replay.sent.count { it == "2198" })
        assertEquals(0f, recovered.second!!.currentAmps, .0001f)
    }
}
