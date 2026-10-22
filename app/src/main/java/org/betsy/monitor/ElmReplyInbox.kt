package org.betsy.monitor

import org.betsy.transport.TransportException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** BLE splits one ASCII reply over arbitrary notifications; prompt is the only delimiter. */
class ElmReplyInbox(
    private val maxChars: Int = 32768,
) {
    private val completed = ArrayBlockingQueue<Result<String>>(1)
    private var collector = ElmResponseCollector("", maxChars)
    private var active = false
    private var failure: String? = null

    @Synchronized fun begin(command: String = "") {
        failure?.let { throw TransportException(it) }
        check(!active && completed.isEmpty()) { "Cerere OBD suprapusă" }
        collector = ElmResponseCollector(command, maxChars)
        active = true
    }

    @Synchronized fun receive(bytes: ByteArray) {
        if (!active || failure != null) return
        for (byte in bytes) {
            val reply =
                try {
                    collector.receive(byte.toInt() and 255)
                } catch (e: TransportException) {
                    fail(e.message ?: "Răspuns BLE invalid")
                    return
                }
            if (reply != null) {
                active = false
                completed.offer(Result.success(reply))
                return
            }
        }
    }

    @Synchronized fun fail(reason: String) {
        if (failure != null) return
        failure = reason
        active = false
        completed.clear()
        completed.offer(Result.failure(TransportException(reason)))
    }

    fun await(timeoutMs: Long): String =
        (
            completed.poll(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw TransportException("Adaptorul BLE nu a răspuns la timp")
        ).getOrThrow()
}
