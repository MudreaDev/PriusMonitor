package org.betsy.monitor

import org.betsy.transport.TransportException
import java.io.InputStream
import java.io.OutputStream

/** Interrupt a previous read/monitor with a space, never repeat an unknown last command with CR.
 * Discard startup traffic until the link is quiet before sending the first full ATZ command.
 */
internal object ElmStartupDrain {
    fun run(
        input: InputStream,
        output: OutputStream,
        closed: () -> Boolean,
        nowMs: () -> Long = { System.nanoTime() / 1000000 },
        pause: () -> Unit = { Thread.sleep(8) },
    ) {
        output.write(' '.code)
        output.flush()
        val started = nowMs()
        var lastByte = started
        var count = 0
        while (!closed() && nowMs() - started < 1800) {
            if (input.available() > 0) {
                if (input.read() < 0) throw TransportException("Adaptorul a închis conexiunea")
                if (++count > 32768) throw TransportException("VIECAR transmite continuu; închide celelalte aplicații OBD")
                lastByte = nowMs()
            } else {
                if (nowMs() - lastByte >= 300) return
                pause()
            }
        }
        throw TransportException("VIECAR nu este liber; închide celelalte aplicații OBD și reconectează adaptorul")
    }
}
