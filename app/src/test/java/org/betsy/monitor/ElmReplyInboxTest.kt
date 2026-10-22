package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ElmReplyInboxTest {
    @Test fun assemblesFragmentedRepliesOnlyAfterPromptAndIgnoresStartupNoise() {
        val inbox = ElmReplyInbox()
        inbox.receive("old startup>".toByteArray())
        inbox.begin()
        inbox.receive("41 0C\r".toByteArray())
        inbox.receive("0F A0".toByteArray())
        inbox.receive("\r>\r".toByteArray())
        assertEquals("41 0C\r0F A0\r", inbox.await(50))
        inbox.begin()
        inbox.receive("NO DATA>".toByteArray())
        assertEquals("NO DATA", inbox.await(50))
    }

    @Test fun aClosedOrOversizedChannelCannotReturnStaleValues() {
        val inbox = ElmReplyInbox(4)
        inbox.begin()
        inbox.receive("12345>".toByteArray())
        assertTrue(runCatching { inbox.await(50) }.exceptionOrNull()?.message?.contains("prea lung") == true)
        assertTrue(runCatching { inbox.begin() }.isFailure)
        val closed = ElmReplyInbox()
        closed.begin()
        closed.receive("4200>".toByteArray())
        closed.fail("Disconnected")
        assertTrue(runCatching { closed.await(50) }.isFailure)
    }

    @Test fun closeWakesAWaitingWorkerAndOverlappingCommandsAreRejected() {
        val inbox = ElmReplyInbox()
        inbox.begin()
        assertTrue(runCatching { inbox.begin() }.isFailure)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting = executor.submit<Boolean> { runCatching { inbox.await(5000) }.isFailure }
            inbox.fail("Closed by user")
            assertTrue(waiting.get(1, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun knownProfilesHaveExplicitDirectionAndNoArbitraryFallback() {
        assertEquals(4, BleUartProfiles.all.size)
        val nordic = BleUartProfiles.all.single { it.label == "Nordic UART" }
        assertTrue(nordic.write.toString().startsWith("6e400002"))
        assertTrue(nordic.notify.toString().startsWith("6e400003"))
        val shared = BleUartProfiles.all.single { it.label == "UART FFE0" }
        assertEquals(shared.write, shared.notify)
        assertEquals(
            4,
            BleUartProfiles.all
                .map { it.service }
                .toSet()
                .size,
        )
    }
}
