package org.betsy.monitor

import org.betsy.transport.TransportException

/** A prompt ends a frame, but an old ECU frame is not an answer to an AT setup command. */
internal class ElmResponseCollector(
    command: String,
    private val maxChars: Int = 32768,
) {
    private val command = command.uppercase().replace(" ", "")
    private val buffer = StringBuilder()
    private var received = 0
    var lastIgnoredFrame = ""
        private set

    fun receive(byte: Int): String? {
        if (++received > maxChars) throw TransportException("Răspuns ELM327 prea lung; reconectează adaptorul")
        if (byte != '>'.code) {
            buffer.append((byte and 255).toChar())
            return null
        }
        val frame = buffer.toString()
        buffer.setLength(0)
        if (frame.isBlank()) return null
        val lines = frame.uppercase().split('\r', '\n').map { it.trim() }
        val accepted =
            when {
                command == "ATZ" || command == "ATWS" -> lines.any { it.contains("ELM327") || it == "?" }
                SETUP.matches(command) -> lines.any { it == "OK" || it == "?" }
                else -> true
            }
        if (!accepted) lastIgnoredFrame = frame.trim().take(220)
        return frame.takeIf { accepted }
    }

    companion object {
        private val SETUP = Regex("AT(?:E[01]|L[01]|S[01]|H[01]|SP[0-9A-C]|ST[0-9A-F]{2}|SH[0-9A-F]{3,8})")
    }
}
