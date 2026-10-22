package org.betsy.monitor

import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aFullCaptureDoesNotStopObdReads() {
        val file = temporary.newFile()
        val link =
            object : ConnectableTransport {
                override var readTimeoutMs = 2500

                override fun connect() {}

                override suspend fun send(cmd: String) = "410D32"

                override fun close() {}
            }
        val recording = RecordingTransport(link, SessionCapture(file, maxBytes = 512))
        repeat(100) { assertEquals("410D32", awaitBlocking { recording.send("010D") }) }
        recording.close()
        assertTrue(file.length() <= 512)
        assertTrue(file.readText().contains("Capture limit reached"))
    }

    @Test fun boundedUtf8CaptureStopsWithoutAffectingTheSession() {
        val file = temporary.newFile()
        val capture = SessionCapture(file, maxBytes = 1024)
        repeat(200) { capture.append("RX", "Tensiune și temperatură\r\n".repeat(8)) }
        capture.close()
        val text = file.readText()
        assertTrue(file.length() <= 1024)
        assertTrue(text.contains("Capture limit reached"))
        assertTrue(text.contains("\\r\\n"))
        val size = file.length()
        capture.append("RX", "must not reopen")
        assertEquals(size, file.length())
    }

    @Test fun wrapperPreservesRawRepliesTimeoutsAndTransportFailures() {
        val file = temporary.newFile()
        var timeout = 0
        var connected = false
        var closed = false
        val transport =
            RecordingTransport(
                object : ConnectableTransport {
                    override var readTimeoutMs: Int
                        get() = timeout
                        set(value) {
                            timeout = value
                        }

                    override fun connect() {
                        connected = true
                    }

                    override suspend fun send(cmd: String): String {
                        if (cmd == "fail") throw TransportException("radio lost")
                        return "41 0D 32\r\n"
                    }

                    override fun close() {
                        closed = true
                    }
                },
                SessionCapture(file),
            )
        transport.readTimeoutMs = 6000
        transport.connect()
        assertTrue(connected)
        assertEquals(6000, timeout)
        assertEquals("41 0D 32\r\n", awaitBlocking { transport.send("010D") })
        assertTrue(runCatching { awaitBlocking { transport.send("fail") } }.exceptionOrNull() is TransportException)
        transport.close()
        assertTrue(closed)
        val text = file.readText()
        assertTrue(text.contains("TX 010D"))
        assertTrue(text.contains("41 0D 32\\r\\n"))
        assertTrue(text.contains("radio lost"))
        assertTrue(text.endsWith("END Connection closed.\n"))
        assertFalse(text.contains("DEMO"))
    }
}
