package org.betsy.monitor

import org.betsy.transport.TransportException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** BLE splits one ASCII reply over arbitrary notifications; prompt is the only delimiter. */
class ElmReplyInbox(
    private val maxChars: Int = 32768,
) {
    private val completed = ArrayBlockingQueue<Result<String>>(1)
    private val buffer = StringBuilder()
    private var active = false
    private var failure: String? = null

    @Synchronized fun begin() {
        failure?.let { throw TransportException(it) }
        check(!active && completed.isEmpty()) { "Cerere OBD suprapusă" }
        buffer.setLength(0)
        active = true
    }

    @Synchronized fun receive(bytes: ByteArray) {
        if (!active || failure != null) return
        for (byte in bytes) {
            if (byte.toInt() == '>'.code) {
                active = false
                completed.offer(Result.success(buffer.toString()))
                return
            }
            buffer.append((byte.toInt() and 255).toChar())
            if (buffer.length > maxChars) {
                fail("Răspuns BLE prea lung")
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
