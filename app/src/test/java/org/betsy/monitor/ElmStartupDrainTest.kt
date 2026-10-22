package org.betsy.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ElmStartupDrainTest {
    @Test fun staleRpmAndPromptAreDrainedWithoutRepeatingTheLastCommand() {
        val input = ByteArrayInputStream("410C00000D0F>".toByteArray())
        val output = ByteArrayOutputStream()
        var clock = 0L
        ElmStartupDrain.run(input, output, { false }, { clock }, { clock += 10 })
        assertEquals(" ", output.toString("US-ASCII"))
        assertEquals(0, input.available())
        assertEquals(300L, clock)
    }

    @Test fun continuousTrafficAndCancellationCannotHangInitialization() {
        val input =
            object : InputStream() {
                override fun available() = 1

                override fun read() = 'A'.code
            }
        val output = ByteArrayOutputStream()
        var clock = 0L
        assertTrue(runCatching { ElmStartupDrain.run(input, output, { false }, { clock++ }, {}) }.isFailure)
        assertTrue(runCatching { ElmStartupDrain.run(input, output, { true }) }.isFailure)
    }
}
