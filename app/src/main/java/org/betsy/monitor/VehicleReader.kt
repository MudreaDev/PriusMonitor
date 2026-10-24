package org.betsy.monitor

import org.betsy.decode.Gen2Decoder
import org.betsy.decode.Gen3Decoder
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmException
import org.betsy.elm.ElmSession
import org.betsy.elm.NegativeResponseException
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel

data class VehicleReading(
    val obd: ObdData,
    val battery: BatteryModel?,
    val errors: List<String>,
    val batteryUpdated: Boolean = false,
)

/** Testable real-vehicle path. Transport failures propagate; unsupported PIDs stay missing. */
class VehicleReader(
    private val elm: ElmSession,
    private val clockMs: () -> Long,
) {
    var info: VehicleInfo? = null
        private set
    private var knownPids: Set<String>? = null
    private val values = mutableMapOf<String, Pair<Float, Long>>()
    private val unavailableUntil = mutableMapOf<String, Long>()
    private val hvUnavailableUntil = mutableMapOf<String, Long>()
    private var readiness: Pair<ObdReadiness, Long>? = null
    private var cachedBattery = emptyBattery()
    private val hvReadAt = mutableMapOf<String, Long>()
    private val hvErrors = mutableMapOf<String, String>()
    private var standardSupplementaryIndex = 0
    private var hybridSupplementaryIndex = 0
    private var nextSupplementaryHybrid = false
    private val hvRotation = mutableSetOf<String>()
    private var hvRotationChanged = false
    private var batteryUpdatePending = false

    suspend fun initialize() {
        // The first physical OBD request can start automatic protocol detection on ELM clones.
        elm.commandTimeoutMs = 8000
        elm.withEcu("7E0") {
            val all = mutableSetOf<String>()
            var base = 0
            knownPids =
                optional {
                    while (base <= 0x40) {
                        val page = StandardPid.supported(elm.command("01%02X".format(base)), base)
                        all.addAll(page)
                        base += 0x20
                        if ("%02X".format(base) !in page) break
                    }
                    all.toSet()
                }
        }
        elm.commandTimeoutMs = 2500
        for ((header, cmd, model) in listOf(Triple("7E2", "2181", VehicleModel.GEN3), Triple("7E3", "21CE", VehicleModel.GEN2))) {
            val detected =
                optional {
                    val m = emptyBattery()
                    elm.withEcu(header) {
                        val raw = elm.command(cmd)
                        if (model == VehicleModel.GEN3) {
                            Gen3Decoder.decodeBlocks(raw, m)
                        } else {
                            Gen2Decoder.decodeCe(raw, m)
                        }
                    }
                    // A zero/weak block may be the very fault being diagnosed. Use the ECU's
                    // positive response and complete layout, not a "healthy voltage" gate.
                    m.blockVolts.size == 14 && m.blockVolts.all { it.isFinite() }
                }
            if (detected == true) {
                info = VehicleInfo(model, true, 14, 28)
                break
            }
        }
    }

    private fun pidAvailable(pid: String): Boolean = knownPids?.contains(pid) != false && (unavailableUntil[pid] ?: 0) <= clockMs()

    private suspend fun readPid(pid: String): Boolean {
        if (!pidAvailable(pid)) return false
        val decoded = optional { StandardPid.decode(pid, elm.command("01$pid")) }
        if (decoded == null) {
            values.remove(pid)
            unavailableUntil[pid] = clockMs() + 15000
        } else {
            values[pid] = decoded to clockMs()
            unavailableUntil.remove(pid)
        }
        return decoded != null
    }

    private suspend fun readReadiness() {
        if (!pidAvailable("01")) return
        val decoded = optional { ObdReadiness.decode(elm.command("0101")) }
        readiness = decoded?.let { it to clockMs() }
        if (decoded == null) unavailableUntil["01"] = clockMs() + 15000
    }

    /** Publish these driving values before spending any time on supplementary diagnostics. */
    suspend fun readLive(): VehicleReading {
        elm.withEcu("7E0") {
            readPid("0D")
            readPid("0C")
            // Most Prius ECUs need the MAF estimate. A supported direct fuel rate avoids that
            // extra request; an unavailable direct rate falls back during the same live pass.
            if (!readPid("5E")) readPid("10")
        }
        return snapshot()
    }

    private data class SupplementaryRequest(
        val command: String,
        val hybrid: Boolean = false,
    )

    private fun hybridCommands(): List<String> =
        when (info?.model) {
            VehicleModel.GEN3 -> listOf("2181", "2101", "2198", "2187", "2195")
            VehicleModel.GEN2 -> listOf("21CE", "21CF", "21D0")
            else -> emptyList()
        }

    /** At most one additional ECU query per live pass, including the first pass. */
    suspend fun readSupplementary() {
        val standard = listOf("05", "42", "0F", "46", "04", "11", "2F", "1F", "06", "07", "0E", "0B", "10", "01")
        val hybrid = hybridCommands()
        // Give HV its own alternating queue: five Gen3 groups refresh within ten live
        // passes instead of waiting for every optional engine sensor to take a turn.
        repeat(2 * (standard.size + hybrid.size)) {
            val hybridTurn = nextSupplementaryHybrid && hybrid.isNotEmpty()
            nextSupplementaryHybrid = !hybridTurn
            val request =
                if (hybridTurn) {
                    SupplementaryRequest(hybrid[hybridSupplementaryIndex], hybrid = true).also {
                        hybridSupplementaryIndex = (hybridSupplementaryIndex + 1) % hybrid.size
                    }
                } else {
                    SupplementaryRequest(standard[standardSupplementaryIndex]).also {
                        standardSupplementaryIndex = (standardSupplementaryIndex + 1) % standard.size
                    }
                }
            if (request.hybrid) {
                if ((hvUnavailableUntil[request.command] ?: 0) > clockMs()) {
                    markHybridVisited(request.command, changed = false)
                    return@repeat
                }
                elm.withEcu(if (info?.model == VehicleModel.GEN3) "7E2" else "7E3") {
                    readHybrid(request.command)
                }
                markHybridVisited(request.command, changed = true)
                return
            }
            if (!pidAvailable(request.command)) return@repeat
            // MAF already arrives in every live pass when the direct fuel rate is unavailable.
            if (request.command == "10" && values["5E"]?.let { clockMs() - it.second < 15000 } != true) return@repeat
            elm.withEcu("7E0") {
                if (request.command == "01") readReadiness() else readPid(request.command)
            }
            return
        }
    }

    private fun markHybridVisited(
        command: String,
        changed: Boolean,
    ) {
        hvRotation += command
        hvRotationChanged = hvRotationChanged || changed
        if (hvRotation.containsAll(hybridCommands())) {
            // Cached values may be displayed on every live pass, but only a new complete HV
            // rotation may enter observation/integration as another battery measurement.
            batteryUpdatePending = batteryUpdatePending || hvRotationChanged
            hvRotation.clear()
            hvRotationChanged = false
        }
    }

    private suspend fun readHybrid(command: String) {
        // Decode into a candidate so an incomplete response cannot partially overwrite a group.
        val candidate = batteryCopy(cachedBattery)
        val success =
            optional {
                val raw = elm.command(command)
                when (command) {
                    "2181" -> Gen3Decoder.decodeBlocks(raw, candidate)
                    "2101" -> Gen3Decoder.decodeSoc(raw, candidate)
                    "2198" -> Gen3Decoder.decodeScalars(raw, candidate)
                    "2187" -> Gen3Decoder.decodeTemps(raw, candidate)
                    "2195" -> Gen3Decoder.decodeIr(raw, candidate)
                    "21CE" -> Gen2Decoder.decodeCe(raw, candidate)
                    "21CF" -> {
                        Gen2Decoder.decodeCf(raw, candidate, false)
                        // This app detects the 14-block Prius layout. CF owns no block voltages;
                        // its decoder's placeholder blocks must not revive a missing CE response.
                        candidate.blockVolts = cachedBattery.blockVolts.toList()
                    }
                    "21D0" -> Gen2Decoder.decodeD0(raw, candidate)
                }
                true
            } == true
        if (success) {
            cachedBattery = candidate
            hvReadAt[command] = clockMs()
            hvUnavailableUntil.remove(command)
            hvErrors.remove(command)
        } else {
            clearExpiredGroup(cachedBattery, command)
            hvReadAt.remove(command)
            hvUnavailableUntil[command] = clockMs() + 15000
            hvErrors[command] = "$command : parametru indisponibil / răspuns incomplet"
        }
    }

    private fun snapshot(): VehicleReading {
        val now = clockMs()
        hvReadAt.toMap().forEach { (command, at) ->
            if (now - at >= 15000) {
                clearExpiredGroup(cachedBattery, command)
                hvReadAt.remove(command)
                hvErrors[command] = "$command : citire expirată · reîncercare automată"
            }
        }

        fun get(pid: String): Float? = values[pid]?.takeIf { now - it.second < 15000 }?.first
        val d =
            ObdData(
                get("0D"),
                get("0C"),
                get("05"),
                get("0F"),
                get("46"),
                get("04"),
                get("11"),
                get("10"),
                get("42"),
                get("2F"),
                get("5E"),
                get("1F"),
                get("06"),
                get("07"),
                get("0E"),
                get("0B"),
                readiness = readiness?.takeIf { now - it.second < 15000 }?.first,
            )
        val updated = batteryUpdatePending
        batteryUpdatePending = false
        return VehicleReading(d, info?.let { batteryCopy(cachedBattery) }, hvErrors.values.toList(), updated)
    }

    private fun batteryCopy(battery: BatteryModel): BatteryModel =
        battery.copy(
            blockVolts = battery.blockVolts.toList(),
            temps = battery.temps.toList(),
            internalResistance = battery.internalResistance.toList(),
        )

    private fun clearExpiredGroup(
        m: BatteryModel,
        cmd: String,
    ) {
        when (cmd) {
            "2181" -> {
                m.blockVolts = emptyList()
                m.aux12V = Float.NaN
            }
            "2101" -> m.soc = Float.NaN
            "2198" -> {
                m.currentAmps = Float.NaN
                m.maxChargeHp = Float.NaN
                m.maxDischargeHp = Float.NaN
                m.deltaSoc = Float.NaN
            }
            "2187" -> m.temps = emptyList()
            "2195", "21D0" -> m.internalResistance = emptyList()
            "21CE" -> {
                m.soc = Float.NaN
                m.currentAmps = Float.NaN
                m.blockVolts = emptyList()
            }
            "21CF" -> {
                m.aux12V = Float.NaN
                m.maxChargeHp = Float.NaN
                m.maxDischargeHp = Float.NaN
                m.deltaSoc = Float.NaN
                m.temps = emptyList()
            }
        }
    }

    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (
            _: NoDataException,
        ) {
            null
        } catch (
            _: NegativeResponseException,
        ) {
            null
        } catch (_: ElmException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun emptyBattery() =
        BatteryModel(
            soc = Float.NaN,
            currentAmps = Float.NaN,
            aux12V = Float.NaN,
            deltaSoc = Float.NaN,
            maxChargeHp = Float.NaN,
            maxDischargeHp = Float.NaN,
            blockCount = 14,
            cellCount = 28,
        )
}
