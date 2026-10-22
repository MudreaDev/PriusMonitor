package org.betsy.monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.betsy.transport.TransportException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A stalled clone must not strand the worker or feed a late response into the next PID. */
@SuppressLint("MissingPermission")
class DeadlineBluetoothTransport(
    device: BluetoothDevice,
) : ConnectableTransport {
    private val connector =
        SocketConnector(
            listOf(
                "SPP securizat" to { device.createRfcommSocketToServiceRecord(SPP) },
                "SPP compatibil" to { device.createInsecureRfcommSocketToServiceRecord(SPP) },
            ),
            BluetoothSocket::connect,
        )

    @Volatile private var socket: BluetoothSocket? = null

    @Volatile private var closed = false
    override var readTimeoutMs = 2500

    override fun connect() {
        val active = connector.connect()
        socket = active
        val watchdog = WATCHDOG.schedule({ close() }, 2500, TimeUnit.MILLISECONDS)
        try {
            ElmStartupDrain.run(active.inputStream, active.outputStream, { closed })
        } catch (e: Exception) {
            close()
            throw e
        } finally {
            watchdog.cancel(false)
        }
    }

    override suspend fun send(cmd: String): String {
        check(!closed) { "Conexiune închisă" }
        val active = socket ?: throw TransportException("Conexiune Bluetooth indisponibilă")
        val input = active.inputStream
        val output = active.outputStream
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs.toLong())
        // Also covers a blocked write, not just waiting for a reply.
        val watchdog = WATCHDOG.schedule({ close() }, readTimeoutMs.toLong() + 500, TimeUnit.MILLISECONDS)
        try {
            output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            output.flush()
            val reply = ElmResponseCollector(cmd)
            while (!closed && System.nanoTime() < deadline) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) throw TransportException("Adaptorul a închis conexiunea")
                    reply.receive(b)?.let { return it }
                } else {
                    Thread.sleep(8)
                }
            }
            close()
            throw TransportException(
                "Adaptorul nu a oferit răspunsul așteptat la $cmd. " +
                    "Închide Car Scanner și celelalte aplicații OBD, scoate și reintrodu VIECAR, apoi reconectează-l. " +
                    "Ultimul răspuns ignorat: ${reply.lastIgnoredFrame.ifBlank { "niciunul" }}",
            )
        } finally {
            watchdog.cancel(false)
        }
    }

    override fun close() {
        closed = true
        try {
            connector.close()
            socket = null
        } catch (_: Exception) {
        }
    }

    companion object {
        private val SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "OBD-timeout").apply { isDaemon = true } }
    }
}
