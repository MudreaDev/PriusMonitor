package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Test

class LivePollingTest {
    @Test fun fastAdapterWaitsOnlyForTheRemainderOfTheTargetInterval() {
        assertEquals(250L, LivePolling.delayMs(250, 0))
        assertEquals(50L, LivePolling.delayMs(250, 200))
        assertEquals(1800L, LivePolling.delayMs(2000, 200))
    }

    @Test fun slowAdapterDoesNotPayAnAdditionalFullIntervalAfterReading() {
        assertEquals(25L, LivePolling.delayMs(250, 900))
        assertEquals(25L, LivePolling.delayMs(2000, 3000))
    }
}
