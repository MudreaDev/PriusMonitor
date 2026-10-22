package org.betsy.monitor

import org.betsy.elm.ElmSession
import org.betsy.transport.ReplayTransport
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FreezeFramesTest {
    @Test fun storedFramesKeepEcuIdentityAndRejectLiveMismatchedOrTruncatedData() {
        val frames =
            FreezeFrames.decode(
                mapOf(
                    "7E0/020201" to "42 02 01 03 00",
                    "7E0/020C01" to "42 0C 01 0F A0",
                    "7E0/020501" to "42 05 01 79",
                    "7E2/020C01" to "420C010000",
                    "7E0/020D01" to "420D020A",
                    "7E0/020F01" to "410F50",
                    "7E0/021001" to "42100100",
                    "7E0/010D" to "410D64",
                    "7E0/020000" to "42000000000000",
                ),
            )
        assertEquals(2, frames.size)
        assertEquals("P0300", frames[0].code)
        assertEquals(1000f, frames[0].conditions.single { it.unit == "rpm" }.value)
        assertEquals(81f, frames[0].conditions.single { it.unit == "°C" }.value)
        assertEquals(2, frames[0].conditions.size)
        assertEquals("7E2", frames[1].ecu)
        assertEquals(0f, frames[1].conditions.single().value)
    }

    @Test fun readsOnlySupportedEngineParametersAndStopsAtFirstAbsentFrame() {
        val replay = ReplayTransport(mapOf("7E0/020000" to "42000000100000", "7E0/020C00" to "420C000FA0", "7E0/020200" to "4202000300"))
        val raw = linkedMapOf<String, String>()
        awaitBlocking { FreezeFrames.readEngine(ElmSession(replay), raw, mutableListOf()) }
        assertEquals(listOf("020000", "020200", "020C00", "020001"), replay.sent.filter { !it.startsWith("AT") })
        assertEquals(
            1000f,
            FreezeFrames
                .decode(raw)
                .single()
                .conditions
                .single()
                .value,
        )
    }

    @Test fun absentFramesDoNotProduceAZeroSnapshot() {
        val notes = mutableListOf<String>()
        val raw = linkedMapOf<String, String>()
        awaitBlocking { FreezeFrames.readEngine(ElmSession(ReplayTransport(emptyMap())), raw, notes) }
        assertTrue(FreezeFrames.decode(raw).isEmpty())
        assertTrue(notes.single().contains("nu sunt disponibile"))
    }
}
