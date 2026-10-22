package org.betsy.monitor

import org.betsy.transport.TransportException
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Bounded standard connection attempts; cancelling one session prevents every fallback. */
internal class SocketConnector<T : Closeable>(
    private val factories: List<Pair<String, () -> T>>,
    private val connectSocket: (T) -> Unit,
    private val timeoutMs: Long = 15000,
) : Closeable {
    @Volatile private var closed = false

    @Volatile private var current: T? = null

    fun connect(): T {
        val failures = mutableListOf<String>()
        for ((label, factory) in factories) {
            if (closed) throw TransportException("Conexiune anulată")
            var candidate: T? = null
            var retained = false
            try {
                val socket = factory()
                candidate = socket
                synchronized(this) {
                    if (closed) {
                        socket.close()
                        throw TransportException("Conexiune anulată")
                    }
                    current = socket
                }
                val outcome = AtomicInteger(0) // pending / expired / connected
                val watchdog =
                    WATCHDOG.schedule({
                        if (outcome.compareAndSet(0, 1)) runCatching { socket.close() }
                    }, timeoutMs, TimeUnit.MILLISECONDS)
                try {
                    connectSocket(socket)
                    if (!outcome.compareAndSet(0, 2)) throw IOException("Timp de conectare depășit")
                    if (closed) throw TransportException("Conexiune anulată")
                    retained = true
                    return socket
                } finally {
                    watchdog.cancel(false)
                }
            } catch (e: IOException) {
                failures += "$label: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                if (candidate != null && (!retained || closed)) {
                    runCatching { candidate.close() }
                    synchronized(this) { if (current === candidate) current = null }
                }
            }
        }
        throw TransportException("Bluetooth SPP nu s-a conectat. ${failures.joinToString("; ")}")
    }

    @Synchronized override fun close() {
        closed = true
        val socket = current
        current = null
        runCatching { socket?.close() }
    }

    companion object {
        private val WATCHDOG =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "SPP-connect-timeout").apply { isDaemon = true }
            }
    }
}
