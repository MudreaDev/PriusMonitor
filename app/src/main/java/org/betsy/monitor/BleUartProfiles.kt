package org.betsy.monitor

import java.util.UUID

data class BleUartProfile(
    val label: String,
    val service: UUID,
    val notify: UUID,
    val write: UUID,
)

/** Explicit known serial profiles only. No heuristic writing to arbitrary characteristics. */
object BleUartProfiles {
    private fun short(id: String) = UUID.fromString("0000$id-0000-1000-8000-00805f9b34fb")

    val all =
        listOf(
            BleUartProfile("ELM UART FFF0", short("fff0"), short("fff1"), short("fff2")),
            BleUartProfile("UART FFE0", short("ffe0"), short("ffe1"), short("ffe1")),
            BleUartProfile("ELM UART 18F0", short("18f0"), short("2af0"), short("2af1")),
            BleUartProfile(
                "Nordic UART",
                UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"),
            ),
        )
    val cccd: UUID = short("2902")
}
