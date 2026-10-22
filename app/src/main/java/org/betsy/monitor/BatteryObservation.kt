package org.betsy.monitor

import org.betsy.model.BatteryModel
import java.util.Locale
import kotlin.math.abs

/** Passive measurements and bounded integration; never a capacity test or SOH estimate. */
class BatteryObservation {
    data class Row(
        val timestamp: Long,
        val soc: Float?,
        val current: Float?,
        val blocks: List<Float>,
        val temps: List<Float>,
        val resistance: List<Int>,
        val monotonicMs: Long = timestamp,
    ) {
        val delta: Float? get() = blocks.takeIf { it.size >= 2 && it.all(Float::isFinite) }?.let { it.max() - it.min() }
        val powerKw: Double? get() =
            if (blocks.size == 14 && blocks.all(Float::isFinite) && current != null && blocks.sum() >= 0) {
                blocks.sum() * current.toDouble() / 1000.0
            } else {
                null
            }
    }

    data class BlockRange(
        val index: Int,
        val min: Float?,
        val max: Float?,
        val samples: Int,
    )

    var active = false
        private set
    var demo = false
        private set
    private val samples = mutableListOf<Row>()
    val rows: List<Row> get() = samples.toList()

    fun recent(limit: Int = 180): List<Row> = samples.takeLast(limit.coerceIn(1, 3000))

    val count: Int get() = samples.size
    var maxDelta: Float? = null
        private set
    var maxDeltaRow: Row? = null
        private set
    var hottest: Float? = null
        private set
    var minSoc: Float? = null
        private set
    var maxSoc: Float? = null
        private set
    var maxAbsCurrent: Float? = null
        private set
    private var previous: Row? = null
    private var ampHours = 0.0
    private var kiloWattHours = 0.0
    var currentCoveredSeconds = 0.0
        private set
    var powerCoveredSeconds = 0.0
        private set
    val absoluteAh: Double? get() = ampHours.takeIf { currentCoveredSeconds > 0 }
    val absoluteKwh: Double? get() = kiloWattHours.takeIf { powerCoveredSeconds > 0 }
    private val low = arrayOfNulls<Float>(14)
    private val high = arrayOfNulls<Float>(14)
    private val blockCounts = IntArray(14)
    val blockRanges: List<BlockRange> get() = (0..13).map { BlockRange(it + 1, low[it], high[it], blockCounts[it]) }

    fun start(isDemo: Boolean) {
        samples.clear()
        demo = isDemo
        active = true
        maxDelta = null
        maxDeltaRow = null
        hottest = null
        minSoc = null
        maxSoc = null
        maxAbsCurrent = null
        previous = null
        ampHours = 0.0
        kiloWattHours = 0.0
        currentCoveredSeconds = 0.0
        powerCoveredSeconds = 0.0
        low.fill(null)
        high.fill(null)
        blockCounts.fill(0)
    }

    fun stop() {
        active = false
        previous = null
    }

    fun add(
        timestamp: Long,
        battery: BatteryModel?,
        monotonicMs: Long = timestamp,
    ) {
        if (!active) return
        if (battery == null) {
            previous = null
            return
        }
        val row =
            Row(
                timestamp,
                battery.soc.takeIf(Float::isFinite),
                battery.currentAmps.takeIf(Float::isFinite),
                battery.blockVolts.toList(),
                battery.temps.toList(),
                battery.internalResistance.toList(),
                monotonicMs,
            )
        if (row.soc == null &&
            row.current == null &&
            row.blocks.none(Float::isFinite) &&
            row.temps.none(Float::isFinite) &&
            row.resistance.isEmpty()
        ) {
            previous = null
            return
        }
        samples += row
        row.delta?.let { delta ->
            if (maxDelta == null || delta > maxDelta!!) {
                maxDelta = delta
                maxDeltaRow = row
            }
        }
        row.temps
            .filter(Float::isFinite)
            .maxOrNull()
            ?.let { hottest = maxOf(hottest ?: it, it) }
        row.soc?.let {
            minSoc = minOf(minSoc ?: it, it)
            maxSoc = maxOf(maxSoc ?: it, it)
        }
        row.current?.let { maxAbsCurrent = maxOf(maxAbsCurrent ?: abs(it), abs(it)) }
        (0..13).forEach { i ->
            row.blocks.getOrNull(i)?.takeIf(Float::isFinite)?.let { v ->
                low[i] = minOf(low[i] ?: v, v)
                high[i] = maxOf(high[i] ?: v, v)
                blockCounts[i]++
            }
        }
        previous?.let { old ->
            val dt = (monotonicMs - old.monotonicMs) / 1000.0
            if (dt > 0 && dt <= 15) {
                if (old.current != null && row.current != null) {
                    ampHours += absoluteLinearAverage(old.current.toDouble(), row.current.toDouble()) * dt / 3600
                    currentCoveredSeconds += dt
                }
                val a = old.powerKw
                val b = row.powerKw
                if (a != null && b != null) {
                    kiloWattHours += absoluteLinearAverage(a, b) * dt / 3600
                    powerCoveredSeconds += dt
                }
            }
        }
        previous = row
        if (samples.size >= 3000) stop()
    }

    fun report(): String =
        buildString {
            fun value(v: Number?) =
                v?.toDouble()?.takeIf(Double::isFinite)?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "indisponibil"
            appendLine("PRIUS MONITOR — OBSERVAȚIE BATERIE HV")
            appendLine(if (demo) "DEMO · DATE SIMULATE" else "Date citite de la ECU")
            appendLine("Eșantioane: $count")
            appendLine("SOH: INDISPONIBIL — fără PID documentat sau test de capacitate validat")
            appendLine("SOC min/max: ${value(minSoc)} / ${value(maxSoc)} %")
            appendLine("Diferență maximă observată între blocuri: ${value(maxDelta)} V")
            maxDeltaRow?.let {
                appendLine("Context diferență maximă: ${it.timestamp} Unix ms; SOC ${value(it.soc)} %; curent ${value(it.current)} A")
            }
            appendLine("Temperatură maximă observată: ${value(hottest)} °C")
            appendLine("Curent maxim în modul: ${value(maxAbsCurrent)} A")
            appendLine("Flux absolut integrat |I|: ${value(absoluteAh)} Ah în ${value(currentCoveredSeconds)} s cu date")
            appendLine("Energie absolută integrată |P|: ${value(absoluteKwh)} kWh în ${value(powerCoveredSeconds)} s cu date")
            appendLine(
                "Fluxurile însumează ambele sensuri în modul; nu sunt consum net, capacitate sau SOH. Integrare aproximativă între eșantioane, fără goluri >15 s.",
            )
            appendLine("Semnul curentului este păstrat din protocol. Sensul energetic necesită validare pe mașină.")
            appendLine("Valorile depind de SOC, sarcină și temperatură. O diferență mică pe loc nu confirmă capacitatea bateriei.")
            blockRanges.forEach { appendLine("Bloc ${it.index}: min ${value(it.min)} V, max ${value(it.max)} V, ${it.samples} eșantioane") }
            appendLine("Date înregistrate (%, A, V, °C, mΩ; timestamp Unix ms; pozițiile lipsă sunt păstrate):")
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

    companion object {
        /** Integral of |linear(a,b)| over a unit interval, including a zero crossing. */
        internal fun absoluteLinearAverage(
            a: Double,
            b: Double,
        ): Double = if (a * b < 0) (a * a + b * b) / (2 * (abs(a) + abs(b))) else (abs(a) + abs(b)) / 2
    }
}
