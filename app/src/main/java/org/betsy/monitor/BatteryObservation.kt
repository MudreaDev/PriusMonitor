package org.betsy.monitor

import org.betsy.model.BatteryModel
import java.util.Locale

/** Passive observations are measurements, not a capacity test or an invented SOH percentage. */
class BatteryObservation {
    data class Row(
        val timestamp: Long,
        val soc: Float?,
        val current: Float?,
        val blocks: List<Float>,
        val temps: List<Float>,
        val resistance: List<Int>,
    )

    var active = false
        private set
    var demo = false
        private set
    private val samples = mutableListOf<Row>()
    val rows: List<Row> get() = samples.toList()
    val count: Int get() = samples.size
    val maxDelta: Float? get() = samples.mapNotNull { if (it.blocks.isEmpty()) null else it.blocks.max() - it.blocks.min() }.maxOrNull()
    val hottest: Float? get() = samples.flatMap { it.temps }.maxOrNull()
    val minSoc: Float? get() = samples.mapNotNull { it.soc }.minOrNull()
    val maxSoc: Float? get() = samples.mapNotNull { it.soc }.maxOrNull()
    val maxAbsCurrent: Float? get() = samples.mapNotNull { it.current?.let { v -> kotlin.math.abs(v) } }.maxOrNull()

    fun start(isDemo: Boolean) {
        samples.clear()
        demo = isDemo
        active = true
    }

    fun stop() {
        active = false
    }

    fun add(
        timestamp: Long,
        battery: BatteryModel?,
    ) {
        if (!active || battery == null) return
        samples +=
            Row(
                timestamp,
                battery.soc.takeIf {
                    it.isFinite()
                },
                battery.currentAmps.takeIf {
                    it.isFinite()
                },
                battery.blockVolts.filter { it.isFinite() },
                battery.temps.filter { it.isFinite() },
                battery.internalResistance,
            )
        if (samples.size >= 3000) active = false
    }

    fun report(): String =
        buildString {
            fun value(v: Float?) = v?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "indisponibil"
            appendLine("PRIUS MONITOR — OBSERVAȚIE BATERIE HV")
            appendLine(if (demo) "DEMO · DATE SIMULATE" else "Date citite de la ECU")
            appendLine("Eșantioane: $count")
            appendLine("SOH: INDISPONIBIL — fără PID documentat sau test de capacitate validat")
            appendLine("SOC min/max: ${value(minSoc)} / ${value(maxSoc)} %")
            appendLine("Diferență maximă observată între blocuri: ${value(maxDelta)} V")
            appendLine("Temperatură maximă observată: ${value(hottest)} °C")
            appendLine("Curent maxim în modul: ${value(maxAbsCurrent)} A")
            appendLine("Valorile depind de SOC, sarcină și temperatură. O diferență mică pe loc nu confirmă capacitatea bateriei.")
            appendLine("Date înregistrate (unități: %, A, V, °C, mΩ; timestamp Unix ms):")
            appendLine("timestamp;soc;current;block1..14;temp1..3;resistance1..14")
            samples.forEach { row ->
                appendLine(
                    "${row.timestamp};${value(
                        row.soc,
                    )};${value(
                        row.current,
                    )};${row.blocks.joinToString(
                        ",",
                    ) { value(it) }};${row.temps.joinToString(",") { value(it) }};${row.resistance.joinToString(",")}",
                )
            }
        }
}
