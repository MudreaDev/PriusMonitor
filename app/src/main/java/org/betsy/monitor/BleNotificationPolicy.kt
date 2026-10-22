package org.betsy.monitor

/** ATT write-not-permitted / invalid-PDU / request-not-supported on known UART CCCDs only.
 * Authentication, encryption, disconnects and timeouts must never use this compatibility path.
 * A working ELM handshake over received notifications is still required by MonitorEngine.
 */
internal object BleNotificationPolicy {
    fun canVerifyWithoutCccd(status: Int) = status in setOf(3, 4, 6)
}
