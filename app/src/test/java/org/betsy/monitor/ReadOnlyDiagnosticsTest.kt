package org.betsy.monitor

import org.betsy.elm.ElmSession
import org.betsy.transport.ReplayTransport
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyDiagnosticsTest {
    @Test fun missingLiveBatteryDataDoesNotHideAReadableHybridFault() {
        val replay =
            ReplayTransport(
                mapOf(
                    "7E0/03" to "4300",
                    "7E0/07" to "4700",
                    "7E0/0A" to "4A00",
                    "7E0/13B0" to "5300",
                    "7E2/0A" to "4A00",
                    "7E2/13B0" to "53010A80",
                ),
            )
        val result = awaitBlocking { ReadOnlyDiagnostics.read(ElmSession(replay), null, "Prius 30", now = { 1234 }) }
        assertEquals(1234L, result.time)
        assertFalse(result.demo)
        assertTrue(result.faults.any { it.code == "P0A80" && it.source.contains("7E2") })
        assertTrue(result.notes.any { it.contains("nu a confirmat profilul") })
        assertTrue(replay.sent.contains("ATSH7E2"))
        assertTrue(replay.sent.all { it.startsWith("AT") || it in setOf("03", "07", "0A", "13B0", "21C6", "21C7", "21C8", "21C9", "21CA") })
    }

    @Test fun rejectedOptionalServiceIsReportedAndDoesNotDropOtherCodes() {
        val replay = ReplayTransport(mapOf("7E0/03" to "43010300", "7E0/07" to "?", "7E0/0A" to "4A00"))
        val result = awaitBlocking { ReadOnlyDiagnostics.read(ElmSession(replay), null, "Prius 30") }
        assertTrue(result.faults.any { it.code == "P0300" })
        assertTrue(result.notes.any { it.contains("adaptorul a refuzat") })
    }
}
