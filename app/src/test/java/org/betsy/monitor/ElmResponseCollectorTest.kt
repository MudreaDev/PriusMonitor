package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmResponseCollectorTest {
    private fun frames(
        command: String,
        bytes: String,
    ): List<String> {
        val collector = ElmResponseCollector(command)
        return bytes.mapNotNull { collector.receive(it.code) }
    }

    @Test fun photoAtzFailureDoesNotConsumeTheRealResetBanner() {
        assertEquals(
            listOf("\rATZ\rELM327 v1.5\r"),
            frames("ATZ", "410C00000D0F>\r>\rATZ\rELM327 v1.5\r>"),
        )
    }

    @Test fun photoAte0FailureWaitsForItsOwnAcknowledgementWithoutResending() {
        val inbox = ElmReplyInbox()
        inbox.begin("ATE0")
        inbox.receive("191717171719\rA:161717188748D1\rB:542E56E3534298>".toByteArray())
        inbox.receive("\r>\rATE0\rO".toByteArray())
        inbox.receive("K\r>".toByteArray())
        assertEquals("\rATE0\rOK\r", inbox.await(50))
    }

    @Test fun setupRejectionIsNotHiddenAndSubstringOkIsNotAnAcknowledgement() {
        assertEquals(listOf("?\r"), frames("ATSH7E2", "NOT OK>?\r>"))
        assertEquals(emptyList<String>(), frames("ATZ", "OK>410C00000D0F>"))
        assertEquals(listOf("ELM327 v1.5\r"), frames("ATZ", "ELM327 v1.5\r>"))
    }

    @Test fun ordinaryEcuErrorsAndResponsesRemainVisibleToTheirDecoder() {
        assertEquals(listOf("NO DATA"), frames("010C", ">\r>NO DATA>"))
        assertEquals(listOf("410C00000D0F"), frames("010C", "410C00000D0F>"))
        assertEquals(listOf("14.1V"), frames("ATRV", "14.1V>"))
    }

    @Test fun discardedTrafficIsBoundedAndPreservedForTroubleshooting() {
        val collector = ElmResponseCollector("ATZ", 24)
        "410C00000D0F>".forEach { collector.receive(it.code) }
        assertEquals("410C00000D0F", collector.lastIgnoredFrame)
        assertTrue(runCatching { "410C00000D0F>".forEach { collector.receive(it.code) } }.isFailure)
    }

    @Test fun cccdCompatibilityDoesNotBypassSecurityFailuresOrTimeouts() {
        for (code in listOf(3, 4, 6)) assertTrue(BleNotificationPolicy.canVerifyWithoutCccd(code))
        for (code in listOf(-1, 0, 5, 8, 15, 133, 257)) assertFalse(BleNotificationPolicy.canVerifyWithoutCccd(code))
    }
}
