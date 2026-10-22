package org.betsy.monitor

import java.io.Closeable
import java.io.File
import java.time.Instant

/** Optional diagnostic evidence. A full disk must never break the OBD connection. */
class SessionCapture(
    val file: File,
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val onError: (String) -> Unit = {},
) : Closeable {
    private val writer =
        run {
            require(maxBytes >= 512)
            file.bufferedWriter(Charsets.UTF_8)
        }
    private val started = System.nanoTime()
    private var usedBytes = 0
    private var lines = 0
    private var closed = false

    init {
        append("START", "Prius Monitor · raw ELM traffic · private diagnostic log")
    }

    @Synchronized fun append(
        kind: String,
        message: String,
    ) {
        if (closed) return
        try {
            val escaped = message.take(65536).replace("\r", "\\r").replace("\n", "\\n")
            val suffix = if (message.length > 65536) " [message truncated]" else ""
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val line = "${Instant.now()} [+${elapsed}ms] $kind $escaped$suffix\n"
            val bytes = line.toByteArray(Charsets.UTF_8).size
            if (usedBytes + bytes > maxBytes - 128 || lines >= 50000) {
                end("Capture limit reached; later traffic is not recorded.")
                return
            }
            writer.write(line)
            writer.flush()
            usedBytes += bytes
            lines++
        } catch (e: Exception) {
            failed(e)
        }
    }

    private fun end(reason: String) {
        if (closed) return
        closed = true
        writer.append("END ").append(reason).append('\n')
        writer.close()
    }

    private fun failed(e: Exception) {
        closed = true
        runCatching { writer.close() }
        runCatching { onError("Jurnalul tehnic nu a putut fi scris: ${e.message ?: e.javaClass.simpleName}") }
    }

    @Synchronized override fun close() {
        try {
            end("Connection closed.")
        } catch (e: Exception) {
            failed(e)
        }
    }
}

class RecordingTransport(
    private val delegate: ConnectableTransport,
    private val capture: SessionCapture,
) : ConnectableTransport {
    override var readTimeoutMs: Int
        get() = delegate.readTimeoutMs
        set(value) {
            delegate.readTimeoutMs = value
        }

    override fun connect() {
        capture.append("CONNECT", "Opening Bluetooth transport")
        try {
            delegate.connect()
            capture.append("CONNECT", "Bluetooth transport opened; ELM initialization follows")
        } catch (e: Exception) {
            capture.append("ERROR", "${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override suspend fun send(cmd: String): String {
        capture.append("TX", cmd)
        val start = System.nanoTime()
        return try {
            delegate.send(cmd).also { capture.append("RX", "${(System.nanoTime() - start) / 1_000_000}ms $it") }
        } catch (e: Exception) {
            capture.append("ERROR", "$cmd: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    override fun close() {
        try {
            delegate.close()
        } finally {
            capture.close()
        }
    }
}
