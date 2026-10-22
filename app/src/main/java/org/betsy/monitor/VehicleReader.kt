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
    private var tick = 0
    private var readiness: Pair<ObdReadiness, Long>? = null

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

    private suspend fun readPid(pid: String) {
        val now = clockMs()
        if (knownPids?.contains(pid) == false || (unavailableUntil[pid] ?: 0) > now) return
        val decoded = optional { StandardPid.decode(pid, elm.command("01$pid")) }
        if (decoded == null) {
            values.remove(pid)
            unavailableUntil[pid] = now + 15000
        } else {
            values[pid] = decoded to now
        }
    }

    private suspend fun readReadiness() {
        val now = clockMs()
        if (knownPids?.contains("01") == false || (unavailableUntil["01"] ?: 0) > now) return
        val decoded = optional { ObdReadiness.decode(elm.command("0101")) }
        readiness = decoded?.let { it to now }
        if (decoded == null) unavailableUntil["01"] = now + 15000
    }

    suspend fun read(): Triple<ObdData, BatteryModel?, List<String>> {
        elm.withEcu("7E0") {
            if (tick % 5 == 0) readReadiness()
            for (pid in listOf("0D", "0C", "05", "10", "5E", "42")) readPid(pid)
            val slow = listOf("0F", "46", "04", "11", "2F", "1F", "06", "07", "0E", "0B")
            if (tick == 0) {
                slow.forEach { readPid(it) }
            } else {
                slow.chunked(2)[tick % 5].forEach { readPid(it) }
            }
        }

        val errors = mutableListOf<String>()
        val battery =
            info?.let { vehicle ->
                val m = emptyBattery()
                val readAt = mutableMapOf<String, Long>()
                elm.withEcu(if (vehicle.model == VehicleModel.GEN3) "7E2" else "7E3") {
                    suspend fun read(
                        cmd: String,
                        decode: (String, BatteryModel) -> Unit,
                    ) {
                        val now = clockMs()
                        if ((hvUnavailableUntil[cmd] ?: 0) > now) {
                            errors += "$cmd : temporar indisponibil · reîncercare automată"
                            return
                        }
                        // Commit only a complete field group, so a truncated response cannot leave
                        // partially decoded defaults masquerading as fresh measurements.
                        val candidate = m.copy()
                        val success =
                            optional {
                                decode(elm.command(cmd), candidate)
                                true
                            } == true
                        if (success) {
                            readAt[cmd] = clockMs()
                            hvUnavailableUntil.remove(cmd)
                            m.soc = candidate.soc
                            m.currentAmps = candidate.currentAmps
                            m.aux12V = candidate.aux12V
                            m.maxChargeHp = candidate.maxChargeHp
                            m.maxDischargeHp = candidate.maxDischargeHp
                            m.deltaSoc = candidate.deltaSoc
                            m.blockVolts = candidate.blockVolts
                            m.temps = candidate.temps
                            m.internalResistance = candidate.internalResistance
                        } else {
                            hvUnavailableUntil[cmd] = now + 15000
                            errors += "$cmd : parametru indisponibil / răspuns incomplet"
                        }
                    }
                    if (vehicle.model == VehicleModel.GEN3) {
                        read("2181", Gen3Decoder::decodeBlocks)
                        read("2101", Gen3Decoder::decodeSoc)
                        read("2198", Gen3Decoder::decodeScalars)
                        read("2187", Gen3Decoder::decodeTemps)
                        read("2195", Gen3Decoder::decodeIr)
                    } else {
                        read("21CE", Gen2Decoder::decodeCe)
                        read("21CF") { r, b -> Gen2Decoder.decodeCf(r, b, false) }
                        read("21D0", Gen2Decoder::decodeD0)
                    }
                }
                readAt.forEach { (cmd, at) ->
                    if (clockMs() - at >= 15000) {
                        clearExpiredGroup(m, cmd)
                        errors += "$cmd : citire expirată în timpul ciclului OBD"
                    }
                }
                m
            }

        // Hybrid reads can be slow. Check age at publication, after all requests completed.
        fun get(pid: String): Float? = values[pid]?.takeIf { clockMs() - it.second < 15000 }?.first
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
                readiness = readiness?.takeIf { clockMs() - it.second < 15000 }?.first,
            )
        tick++
        return Triple(d, battery, errors)
    }

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
