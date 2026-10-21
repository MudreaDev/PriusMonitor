package org.betsy.monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import org.betsy.transport.ElmTransport
import org.betsy.transport.TransportException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A stalled clone must not strand the worker or feed a late response into the next PID. */
@SuppressLint("MissingPermission")
class DeadlineBluetoothTransport(
    device: BluetoothDevice,
) : ElmTransport {
    private val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))

    @Volatile private var closed = false
    override var readTimeoutMs = 2500

    fun connect() {
        val watchdog = WATCHDOG.schedule({ close() }, 18, TimeUnit.SECONDS)
        try {
            socket.connect()
        } finally {
            watchdog.cancel(false)
        }
    }

    override suspend fun send(cmd: String): String {
        check(!closed) { "Conexiune închisă" }
        val input = socket.inputStream
        val output = socket.outputStream
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs.toLong())
        // Also covers a blocked write, not just waiting for a reply.
        val watchdog = WATCHDOG.schedule({ close() }, readTimeoutMs.toLong() + 500, TimeUnit.MILLISECONDS)
        try {
            output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
            output.flush()
            val reply = StringBuilder()
            while (!closed && System.nanoTime() < deadline) {
                if (input.available() > 0) {
                    val b = input.read()
                    if (b < 0) throw TransportException("Adaptorul a închis conexiunea")
                    if (b == '>'.code) return reply.toString()
                    reply.append(b.toChar())
                    if (reply.length > 32768) throw TransportException("Răspuns prea lung de la adaptor")
                } else {
                    Thread.sleep(8)
                }
            }
            close()
            throw TransportException("Adaptorul nu a răspuns la $cmd. Reconectează-l.")
        } finally {
            watchdog.cancel(false)
        }
    }

    override fun close() {
        closed = true
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        private val WATCHDOG = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "OBD-timeout").apply { isDaemon = true } }
    }
}
