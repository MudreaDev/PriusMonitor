package org.betsy.monitor

import java.util.Locale

/** The floating view and notification use the same real readings and freshness rules. */
data class MiniMonitorReadings(
    val speed: String,
    val speedUnit: String,
    val average: String,
    val live: String,
    val liveUnit: String,
    val state: String,
    val source: String,
) {
    val notificationLine: String get() = "$speed $speedUnit · live $live $liveUnit · mediu $average l/100 km"

    companion object {
        fun from(
            sample: LiveSample,
            connected: Boolean,
            connecting: Boolean,
            imperial: Boolean,
            now: Long = System.currentTimeMillis(),
        ): MiniMonitorReadings {
            val fresh = connected && sample.time > 0 && now - sample.time in 0 until 20000
            val obd = if (fresh) sample.obd.copy(speed = sample.obd.speed?.takeIf { it.isFinite() && it >= 0 }) else ObdData()
            val fuel = Consumption.from(obd)
            val slow = obd.speed?.let { it.isFinite() && it >= 0 && it < 5 } == true
            val source =
                when {
                    !connected -> if (connecting) "Conectare…" else "Conexiune oprită"
                    !fresh -> "Aștept date OBD…"
                    fuel.estimated || sample.trip.estimated -> "Consum estimat MAF"
                    fuel.source == FuelSource.ECU -> "Consum ECU"
                    fuel.source == FuelSource.ENGINE_STOPPED -> "Motor termic oprit"
                    else -> "Consum indisponibil"
                }
            return MiniMonitorReadings(
                speed = format(obd.speed?.takeIf { it >= 0 }?.let { if (imperial) it / 1.609344 else it.toDouble() }, 0),
                speedUnit = if (imperial) "mph" else "km/h",
                average = format(sample.trip.average.takeIf { connected }),
                live = format(if (slow) fuel.litersHour else fuel.per100(obd.speed)),
                liveUnit = if (slow) "l/h" else "l/100 km",
                state =
                    if (connected) {
                        "LIVE"
                    } else if (connecting) {
                        "CONECTARE"
                    } else {
                        "OPRIT"
                    },
                source = source,
            )
        }

        private fun format(
            value: Double?,
            digits: Int = 1,
        ): String = if (value == null || !value.isFinite() || value < 0) "—" else String.format(Locale("ro"), "%.${digits}f", value)
    }
}
