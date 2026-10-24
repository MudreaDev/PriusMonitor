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
    /** The runtime alternates live samples with one optional request per turn. */
    private suspend fun readFullCycle(reader: VehicleReader): VehicleReading {
        repeat(28) {
            reader.readLive()
            reader.readSupplementary()
        }
        return reader.readLive()
    }

    @Test fun liveReadReturnsSpeedRpmAndDirectFuelBeforeAnySupplementaryRequest() {
        val script = script().apply { this["7E0/015E"] = "415E0028" }
        val replay = ReplayTransport(script)
        var now = 1000L
        val link =
            object : ElmTransport {
                override var readTimeoutMs = 2500

                override suspend fun send(cmd: String): String {
                    val response = replay.send(cmd)
                    if (!cmd.startsWith("AT")) now += 200
                    return response
                }

                override fun close() = replay.close()
            }
        val reader = VehicleReader(ElmSession(link)) { now }
        awaitBlocking { reader.initialize() }
        replay.sent.clear()
        val started = now
        val result = awaitBlocking { reader.readLive() }

        assertEquals(listOf("010D", "010C", "015E"), replay.sent.filterNot { it.startsWith("AT") })
        assertEquals(600L, now - started)
        assertEquals(50f, result.obd.speed!!, .001f)
        assertEquals(1500f, result.obd.rpm!!, .001f)
        assertEquals(2f, result.obd.fuelRate!!, .001f)
        assertNull(result.obd.maf)
        assertNull(result.obd.coolant)
        assertNull(result.obd.readiness)
        assertFalse(result.batteryUpdated)
    }

    @Test fun missingDirectFuelFallsBackToMafWithoutRetryingEveryLiveSample() {
        val replay = ReplayTransport(script())
        val reader = VehicleReader(ElmSession(replay)) { 1000 }
        awaitBlocking { reader.initialize() }
        replay.sent.clear()
        val first = awaitBlocking { reader.readLive() }
        assertEquals(listOf("010D", "010C", "015E", "0110"), replay.sent.filterNot { it.startsWith("AT") })
        assertNull(first.obd.fuelRate)
        assertEquals(10f, first.obd.maf!!, .001f)
        assertTrue(Consumption.from(first.obd).estimated)

        replay.sent.clear()
        val second = awaitBlocking { reader.readLive() }
        assertEquals(listOf("010D", "010C", "0110"), replay.sent.filterNot { it.startsWith("AT") })
        assertNotNull(second.obd.maf)
    }

    @Test fun eachSupplementaryTurnPerformsAtMostOneRequestAndPreservesTheNextLiveBatch() {
        val replay = ReplayTransport(script())
        val reader = VehicleReader(ElmSession(replay)) { 1000 }
        awaitBlocking { reader.initialize() }
        repeat(36) {
            replay.sent.clear()
            awaitBlocking { reader.readSupplementary() }
            assertTrue(replay.sent.count { !it.startsWith("AT") } <= 1)

            replay.sent.clear()
            awaitBlocking { reader.readLive() }
            val commands = replay.sent.filterNot { it.startsWith("AT") }
            assertEquals("010D", commands[0])
            assertEquals("010C", commands[1])
            assertTrue(commands.drop(2).all { it == "015E" || it == "0110" })
        }
    }

    @Test fun successfulReplyIsTimestampedWhenItArrivesRatherThanBeforeWaitingForIt() {
        val replay = ReplayTransport(script())
        var now = 1000L
        var slow = false
        val link =
            object : ElmTransport {
                override var readTimeoutMs = 2500

                override suspend fun send(cmd: String): String {
                    val response = replay.send(cmd)
                    if (slow && cmd == "010D") now += 15001
                    return response
                }

                override fun close() = replay.close()
            }
        val reader = VehicleReader(ElmSession(link)) { now }
        awaitBlocking { reader.initialize() }
        slow = true
        val result = awaitBlocking { reader.readLive() }
        assertEquals(50f, result.obd.speed!!, .001f)
    }

    @Test fun cachedSupplementaryFieldsExpireWhileLiveReadingsContinueToRefresh() {
        var now = 1000L
        val reader = VehicleReader(ElmSession(ReplayTransport(script()))) { now }
        val initial =
            awaitBlocking {
                reader.initialize()
                readFullCycle(reader)
            }
        assertNotNull(initial.obd.readiness)
        assertEquals(14, initial.battery!!.blockVolts.size)

        now += 15000
        val result = awaitBlocking { reader.readLive() }
        assertEquals(50f, result.obd.speed!!, .001f)
        assertEquals(1500f, result.obd.rpm!!, .001f)
        assertNull(result.obd.coolant)
        assertNull(result.obd.readiness)
        assertTrue(result.battery!!.blockVolts.isEmpty())
        assertTrue(result.battery!!.aux12V.isNaN())
        assertTrue(result.battery!!.soc.isNaN())
        assertTrue(result.battery!!.internalResistance.isEmpty())
    }

    @Test fun oneCompletedBatteryRotationSignalsOneObservationRatherThanRepeatedCachedObservations() {
        val reader = VehicleReader(ElmSession(ReplayTransport(script()))) { 1000 }
        awaitBlocking { reader.initialize() }
        assertFalse(awaitBlocking { reader.readLive() }.batteryUpdated)
        awaitBlocking { repeat(10) { reader.readSupplementary() } }
        assertTrue(awaitBlocking { reader.readLive() }.batteryUpdated)
        assertFalse(awaitBlocking { reader.readLive() }.batteryUpdated)
    }

    @Test fun batteryGroupsStayFreshDuringOneSecondCyclesWithASlowStandardPidRotation() {
        var now = 1000L
        val reader = VehicleReader(ElmSession(ReplayTransport(script()))) { now }
        awaitBlocking { reader.initialize() }
        repeat(60) { cycle ->
            val result = awaitBlocking { reader.readLive() }
            if (cycle >= 10) {
                val battery = result.battery!!
                assertEquals(50f, result.obd.speed!!, .001f)
                assertEquals(1500f, result.obd.rpm!!, .001f)
                assertEquals(14, battery.blockVolts.size)
                assertTrue(battery.aux12V.isFinite())
                assertTrue(battery.soc.isFinite())
                assertTrue(battery.currentAmps.isFinite())
                assertEquals(3, battery.temps.size)
                assertEquals(14, battery.internalResistance.size)
                assertTrue(result.errors.none { it.contains("expirată") })
            }
            awaitBlocking { reader.readSupplementary() }
            now += 1000
        }
    }

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
            "7E0/0146" to "41464A",
            "7E0/0111" to "411180",
            "7E0/012F" to "412F80",
            "7E0/011F" to "411F003C",
            "7E0/0106" to "410680",
            "7E0/0107" to "410780",
            "7E0/010E" to "410E80",
            "7E0/010B" to "410B64",
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
                readFullCycle(reader)
            }
        assertEquals(VehicleModel.GEN3, reader.info?.model)
        assertEquals(50f, result.obd.speed!!, .001f)
        assertEquals(1500f, result.obd.rpm!!, .001f)
        assertEquals(82f, result.obd.coolant!!, .001f)
        assertTrue(result.obd.readiness!!.milOn)
        assertEquals(3, result.obd.readiness!!.codeCount)
        assertNull(result.obd.fuelRate)
        assertTrue(Consumption.from(result.obd).estimated)
        assertEquals(14, result.battery!!.blockVolts.size)
        assertEquals(14.4f, result.battery!!.blockVolts.first(), .01f)
        assertEquals(60f, result.battery!!.soc, .1f)
        assertEquals(listOf(40f, 40f, 40f), result.battery!!.temps)
        assertTrue(result.errors.isEmpty())
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
                readFullCycle(reader)
            }
        assertNull(reader.info)
        assertNull(result.battery)
        assertEquals(50f, result.obd.speed!!, .001f)
        assertNull(result.obd.maf)
        assertNull(Consumption.from(result.obd).litersHour)
    }

    @Test fun missingAndTruncatedResponsesNeverKeepPreviousValuesOrPartialFields() {
        val script = script()
        val reader = VehicleReader(ElmSession(ReplayTransport(script))) { 1000 }
        awaitBlocking {
            reader.initialize()
            readFullCycle(reader)
        }
        script["7E0/010D"] = "NO DATA"
        script["7E2/2181"] = "61812E1B"
        script["7E2/2198"] = "6198800099AA"
        val result = awaitBlocking { readFullCycle(reader) }
        assertNull(result.obd.speed)
        assertTrue(result.battery!!.blockVolts.isEmpty())
        assertTrue(result.battery!!.currentAmps.isNaN())
        assertTrue(result.errors.any { it.startsWith("2181") })
        assertTrue(result.errors.any { it.startsWith("2198") })
        assertNotNull(result.obd.rpm)
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
                readFullCycle(reader)
            }
        assertEquals(VehicleModel.GEN3, reader.info?.model)
        assertEquals(0f, result.battery!!.blockVolts.first(), .0001f)
        assertEquals(14, result.battery!!.blockVolts.size)
    }

    @Test fun unavailableHvFieldsDoNotWasteEveryPollCycleAndCanRecover() {
        val script = script()
        script["7E2/2198"] = "NO DATA"
        val replay = ReplayTransport(script)
        var now = 1000L
        val reader = VehicleReader(ElmSession(replay)) { now }
        awaitBlocking {
            reader.initialize()
            readFullCycle(reader)
        }
        assertEquals(1, replay.sent.count { it == "2198" })
        now = 2000
        val unavailable = awaitBlocking { readFullCycle(reader) }
        assertEquals(1, replay.sent.count { it == "2198" })
        assertTrue(unavailable.battery!!.currentAmps.isNaN())
        script["7E2/2198"] = "6198800099AA64"
        now = 17000
        val recovered = awaitBlocking { readFullCycle(reader) }
        assertTrue(replay.sent.count { it == "2198" } > 1)
        assertEquals(0f, recovered.battery!!.currentAmps, .0001f)
    }
}
