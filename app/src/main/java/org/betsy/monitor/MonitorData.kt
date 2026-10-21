package org.betsy.monitor

import org.betsy.elm.Normalize
import org.betsy.model.BatteryModel

/** Null means unavailable in this sample, never zero or the previous response. */
data class ObdData(
    val speed: Float? = null,
    val rpm: Float? = null,
    val coolant: Float? = null,
    val intake: Float? = null,
    val ambient: Float? = null,
    val load: Float? = null,
    val throttle: Float? = null,
    val maf: Float? = null,
    val voltage: Float? = null,
    val fuelLevel: Float? = null,
    val fuelRate: Float? = null,
    val runtime: Float? = null,
    val shortTrim: Float? = null,
    val longTrim: Float? = null,
    val timing: Float? = null,
    val pressure: Float? = null,
)

object StandardPid {
    /** SAE J1979 mode 01, physical ECM 7E0, headers and echo off. */
    fun decode(
        pid: String,
        raw: String,
    ): Float {
        val r = Normalize.normalize(raw)
        val prefix = "41$pid"
        require(r.startsWith(prefix)) { "Răspuns neașteptat pentru PID $pid" }
        val at = prefix.length
        val two = pid in setOf("0C", "10", "1F", "42", "5E")
        Normalize.requireLength(r, at + if (two) 4 else 2, "01$pid")
        val a = Normalize.u8(r, at)
        val ab = if (two) Normalize.u16(r, at) else 0
        return when (pid) {
            "0D", "0B" -> a.toFloat()
            "0C" -> ab / 4f
            "05", "0F", "46" -> a - 40f
            "04", "11", "2F" -> a * 100f / 255f
            "06", "07" -> (a - 128) * 100f / 128f
            "0E" -> a / 2f - 64f
            "10" -> ab / 100f
            "42" -> ab / 1000f
            "5E" -> ab / 20f
            "1F" -> ab.toFloat()
            else -> error("PID necunoscut: $pid")
        }
    }

    fun supported(
        raw: String,
        base: Int,
    ): Set<String> {
        val r = Normalize.normalize(raw)
        val tag = "41%02X".format(base)
        require(r.startsWith(tag) && r.length >= 12) { "Lista PID indisponibilă" }
        val mask = r.substring(4, 12).toLong(16)
        return (1..32)
            .filter { mask and (1L shl (32 - it)) != 0L }
            .map { "%02X".format(base + it) }
            .toSet()
    }
}

data class Consumption(
    val litersHour: Double?,
    val estimated: Boolean,
) {
    fun per100(speedKmh: Float?): Double? =
        if (litersHour != null && speedKmh != null && speedKmh >= 5f) litersHour * 100 / speedKmh else null

    companion object {
        fun from(d: ObdData): Consumption {
            d.fuelRate?.let { return Consumption(it.toDouble(), false) }
            // A stationary crankshaft on a hybrid consumes no petrol. All other MAF readings
            // are estimates: closed-loop stoichiometry and petrol density vary in real use.
            if (d.rpm == 0f) return Consumption(0.0, true)
            if (d.rpm == null || d.maf == null) return Consumption(null, true)
            return Consumption(d.maf * 3600.0 / (14.7 * 745.0), true)
        }
    }
}

data class TripSnapshot(
    val distanceKm: Double = 0.0,
    val liters: Double = 0.0,
    val seconds: Double = 0.0,
    val distanceCoveredKm: Double = 0.0,
    val coveredSeconds: Double = 0.0,
    val estimated: Boolean = false,
    val stoppedLiters: Double = 0.0,
    val stoppedSeconds: Double = 0.0,
) {
    val average: Double? get() = if (distanceCoveredKm >= 0.1) liters * 100 / distanceCoveredKm else null
    val coverage: Double get() = if (seconds > 0) coveredSeconds * 100 / seconds else 0.0
}

/** Trapezoidal integration on a monotonic clock. Missing/long intervals never add fuel or km. */
class TripMeter {
    private var previousTime: Long? = null
    private var previous: ObdData? = null
    var snapshot = TripSnapshot()
        private set

    fun reset() {
        previousTime = null
        previous = null
        snapshot = TripSnapshot()
    }

    fun update(
        nowMs: Long,
        data: ObdData,
    ) {
        val old = previous
        val dt = previousTime?.let { (nowMs - it) / 1000.0 } ?: 0.0
        if (old != null && dt > 0 && dt <= 15) {
            val a = Consumption.from(old)
            val b = Consumption.from(data)
            val distance = if (old.speed != null && data.speed != null) (old.speed + data.speed) / 2 * dt / 3600 else null
            val covered = distance != null && a.litersHour != null && b.litersHour != null
            snapshot =
                snapshot.copy(
                    seconds = snapshot.seconds + dt,
                    distanceKm = snapshot.distanceKm + (distance ?: 0.0),
                    liters = snapshot.liters + if (covered) (a.litersHour!! + b.litersHour!!) / 2 * dt / 3600 else 0.0,
                    distanceCoveredKm = snapshot.distanceCoveredKm + if (covered) distance!! else 0.0,
                    coveredSeconds = snapshot.coveredSeconds + if (covered) dt else 0.0,
                    estimated = snapshot.estimated || (covered && (a.estimated || b.estimated)),
                    stoppedLiters =
                        snapshot.stoppedLiters +
                            if (covered && old.speed == 0f && data.speed == 0f) (a.litersHour!! + b.litersHour!!) / 2 * dt / 3600 else 0.0,
                    stoppedSeconds = snapshot.stoppedSeconds + if (old.speed == 0f && data.speed == 0f) dt else 0.0,
                )
        }
        previousTime = nowMs
        previous = data
    }
}

data class LiveSample(
    val time: Long = 0,
    val obd: ObdData = ObdData(),
    val battery: BatteryModel? = null,
    val batteryErrors: List<String> = emptyList(),
    val trip: TripSnapshot = TripSnapshot(),
)

data class Fault(
    val code: String,
    val source: String,
    val status: String,
)

data class ScanReport(
    val time: Long,
    val demo: Boolean,
    val faults: List<Fault>,
    val notes: List<String>,
    val raw: Map<String, String>,
    val layout: String,
    val inf: List<String> = emptyList(),
)

object RomanianFaults {
    fun description(code: String): String {
        val block = code.removePrefix("P").toIntOrNull(16)?.minus(0x3010)
        if (code.startsWith("P") && block != null && block in 1..14) {
            return "Blocul HV $block este raportat ca slab. Verifică tensiunile sub sarcină și solicită diagnosticarea bateriei."
        }
        return when (code) {
            "P0A80" ->
                "ECU raportează degradarea bateriei hibride. Codul singur nu identifică modulul defect; este necesară verificarea bateriei."
            "P0A7F" -> "Degradarea bateriei hibride detectată de ECU. Verifică răcirea și comportamentul blocurilor sub sarcină."
            "P0AA6" ->
                "Defecțiune de izolație în sistemul de înaltă tensiune. Necesită diagnosticare specializată; nu atinge cablurile portocalii."
            "P3000" -> "Sistemul de control al bateriei raportează o defecțiune. Consultă și codurile ECU-ului bateriei."
            "P0A93" -> "Performanță insuficientă a răcirii invertorului. Solicită verificarea circuitului de răcire."
            "P0A82" -> "Ventilatorul bateriei HV nu atinge performanța cerută. Verifică admisia și sistemul de răcire."
            "P0300" -> "Rateuri de aprindere aleatorii sau multiple. Verifică sistemele de aprindere, alimentare și admisie."
            "P0301", "P0302", "P0303", "P0304" -> "Rateuri de aprindere la cilindrul ${code.last()}. Codul nu stabilește singur cauza."
            "P0420" -> "Eficiența catalizatorului sub prag. Verificarea senzorilor și a evacuării precedă înlocuirea pieselor."
            "P0171" -> "Amestec prea sărac, bancul 1. Posibile cauze în admisie, măsurarea aerului sau alimentarea cu combustibil."
            "P0172" -> "Amestec prea bogat, bancul 1. Necesită verificarea alimentării și a senzorilor."
            "P0571" -> "Circuitul comutatorului de frână A: semnal necorespunzător. Verifică și funcționarea stopurilor."
            "P0115", "P0117", "P0118" -> "Problemă la circuitul senzorului de temperatură a lichidului de răcire."
            "P0100", "P0101", "P0102", "P0103" -> "Problemă la măsurarea debitului de aer (MAF). Verifică senzorul, conexiunile și admisia."
            "P0128" -> "Temperatura lichidului de răcire rămâne sub valoarea așteptată de ECU."
            "P0441", "P0455", "P0456" -> "Defecțiune în sistemul de control al vaporilor de combustibil (EVAP)."
            "U0293" -> "Comunicație pierdută cu modulul de control hibrid. Verifică alimentarea de 12 V și rețeaua de comunicații."
            else ->
                "Explicație în română indisponibilă pentru acest cod. Păstrează codul și sursa ECU pentru verificarea în documentația Toyota."
        }
    }
}
