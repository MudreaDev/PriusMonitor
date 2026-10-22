package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SocketConnectorTest {
    private class Link : Closeable {
        val closed = CountDownLatch(1)

        override fun close() {
            closed.countDown()
        }
    }

    @Test fun secureFailureUsesFreshFallbackAndKeepsSuccessfulSocketOpen() {
        val secure = Link()
        val compatible = Link()
        val attempts = mutableListOf<Link>()
        val connector =
            SocketConnector<Link>(listOf("secure" to { secure }, "compatible" to { compatible }), {
                attempts += it
                if (it === secure) throw IOException("service discovery failed")
            })
        assertSame(compatible, connector.connect())
        assertEquals(listOf(secure, compatible), attempts)
        assertEquals(0L, secure.closed.count)
        assertEquals(1L, compatible.closed.count)
        connector.close()
        assertEquals(0L, compatible.closed.count)
    }

    @Test fun cancellationUnblocksPendingAttemptAndDoesNotStartFallback() {
        val link = Link()
        val entered = CountDownLatch(1)
        var fallback = false
        val connector =
            SocketConnector<Link>(
                listOf(
                    "secure" to { link },
                    "fallback" to {
                        fallback = true
                        Link()
                    },
                ),
                {
                    entered.countDown()
                    check(it.closed.await(2, TimeUnit.SECONDS))
                    throw IOException("socket closed")
                },
            )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val outcome = executor.submit<Boolean> { runCatching { connector.connect() }.isFailure }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            connector.close()
            assertTrue(outcome.get(1, TimeUnit.SECONDS))
            assertFalse(fallback)
        } finally {
            connector.close()
            executor.shutdownNow()
        }
    }

    @Test fun timedOutSocketCannotBeAcceptedAndMissingPermissionDoesNotRetry() {
        val link = Link()
        val connector =
            SocketConnector<Link>(listOf("timeout" to { link }), {
                check(it.closed.await(2, TimeUnit.SECONDS))
            }, 40)
        assertTrue(runCatching { connector.connect() }.isFailure)
        connector.close()
        var fallback = false
        val permission =
            SocketConnector<Link>(
                listOf(
                    "secure" to { throw SecurityException("denied") },
                    "fallback" to {
                        fallback = true
                        Link()
                    },
                ),
                { },
            )
        assertTrue(runCatching { permission.connect() }.exceptionOrNull() is SecurityException)
        assertFalse(fallback)
    }
}
