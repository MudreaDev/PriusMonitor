package org.betsy.monitor

import org.betsy.elm.Normalize
import org.json.JSONObject

data class EmissionMonitor(
    val id: String,
    val title: String,
    val supported: Boolean,
    val complete: Boolean?,
)

/** SAE mode 01 PID 01: test completion since DTC clearing, not a mechanical-health verdict. */
data class ObdReadiness(
    val milOn: Boolean,
    val codeCount: Int,
    val sparkIgnition: Boolean,
    val monitors: List<EmissionMonitor>,
    val raw: String,
) {
    val supportedCount: Int get() = monitors.count { it.supported }
    val completeCount: Int get() = monitors.count { it.supported && it.complete == true }

    fun json(): JSONObject = JSONObject().put("raw", raw)

    companion object {
        fun decode(raw: String): ObdReadiness {
            val r = Normalize.normalize(raw)
            require(r.startsWith("4101")) { "Răspuns neașteptat pentru monitorizare emisii" }
            Normalize.requireLength(r, 12, "0101")
            val a = Normalize.u8(r, 4)
            val b = Normalize.u8(r, 6)
            val c = Normalize.u8(r, 8)
            val d = Normalize.u8(r, 10)
            val monitors = mutableListOf<EmissionMonitor>()
            listOf("misfire" to "Rateuri de aprindere", "fuel" to "Sistem alimentare", "components" to "Componente generale")
                .forEachIndexed { bit, (id, title) ->
                    val supported = b and (1 shl bit) != 0
                    monitors += EmissionMonitor(id, title, supported, if (supported) b and (1 shl (bit + 4)) == 0 else null)
                }
            val spark = b and 8 == 0
            if (spark) {
                listOf(
                    "catalyst" to "Catalizator",
                    "heated_catalyst" to "Catalizator încălzit",
                    "evap" to "Vapori combustibil (EVAP)",
                    "secondary_air" to "Aer secundar",
                    "ac" to "Agent frigorific A/C",
                    "oxygen" to "Senzori oxigen",
                    "oxygen_heater" to "Încălzire senzori oxigen",
                    "egr" to "EGR / distribuție variabilă",
                ).forEachIndexed { bit, (id, title) ->
                    val supported = c and (1 shl bit) != 0
                    monitors += EmissionMonitor(id, title, supported, if (supported) d and (1 shl bit) == 0 else null)
                }
            }
            return ObdReadiness(a and 0x80 != 0, a and 0x7F, spark, monitors, r.take(12))
        }
    }
}
