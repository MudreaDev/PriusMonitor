package org.betsy.monitor

import org.betsy.decode.DtcDecoder
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.dtc.DtcReader
import org.betsy.elm.ElmException
import org.betsy.elm.ElmSession
import org.betsy.elm.NegativeResponseException
import org.betsy.elm.NoDataException

/** Fault reading is independent of whether a live HV voltage request happened to succeed. */
object ReadOnlyDiagnostics {
    suspend fun read(
        elm: ElmSession,
        detected: VehicleInfo?,
        layout: String,
        progress: (String) -> Unit = {},
        now: () -> Long = System::currentTimeMillis,
    ): ScanReport {
        val notes = mutableListOf<String>()
        val raw = linkedMapOf<String, String>()
        val faults = mutableListOf<Fault>()
        val inf = mutableListOf<String>()
        try {
            elm.withEcu("7E0") {
                for ((cmd, label) in listOf("03" to "Memorat", "07" to "În așteptare", "0A" to "Permanent")) {
                    progress("ECU motor · $label")
                    try {
                        val response = elm.command(cmd)
                        raw["7E0/$cmd"] = response
                        val codes =
                            when (cmd) {
                                "03" -> DtcDecoder.decodeMode03(response)
                                "07" -> DtcDecoder.decodeMode07(response)
                                else -> DtcDecoder.decodeMode0A(response)
                            }
                        faults += codes.filter { it.raw != 0 }.map { Fault(it.code, "ECU motor · 7E0", label) }
                    } catch (
                        e: NoDataException,
                    ) {
                        notes += "ECU motor · $label : citire indisponibilă (${e.message})"
                    } catch (
                        e: NegativeResponseException,
                    ) {
                        notes += "ECU motor · $label : cerere refuzată (${e.message})"
                    } catch (
                        e: ElmException,
                    ) {
                        notes += "ECU motor · $label : adaptorul a refuzat cererea (${e.message})"
                    }
                }
            }
            // The user selected a Prius 30 profile. Missing live telemetry must not hide a
            // readable hybrid fault; describe the assumption rather than claiming detection.
            val vehicle =
                detected ?: VehicleInfo(VehicleModel.GEN3, false, 14, 28).also {
                    notes += "Telemetria HV nu a confirmat profilul. Citirea codurilor Toyota folosește profilul Prius 30 / Gen 3 selectat."
                }
            val result = DtcReader(elm, vehicle).read { step -> progress("Toyota · ${step.totalStep}/${step.totalSteps} · ${step.label}") }
            faults += result.groups.flatMap { g -> g.codes.filter { it.raw != 0 }.map { Fault(it.code, g.label, "Toyota ECU") } }
            raw.putAll(result.rawResponses)
            notes += result.notes
            inf += result.infCodes.map { "INF ${it.code} · pagina %02X".format(it.table) + " · asocierea cu un DTC necesită verificare" }
        } catch (e: Exception) {
            notes += "Scanare întreruptă / incompletă: ${e.message ?: e.javaClass.simpleName}"
        }
        return ScanReport(now(), false, faults.distinct(), notes, raw, layout, inf)
    }
}
