package org.betsy.monitor

import org.betsy.decode.DtcDecoder
import org.betsy.elm.ElmSession
import org.betsy.elm.Normalize

data class StoredCondition(
    val title: String,
    val value: Float,
    val unit: String,
)

data class FreezeFrame(
    val ecu: String,
    val index: Int,
    val code: String?,
    val conditions: List<StoredCondition>,
)

/** Mode 02 snapshots are stored conditions, never a substitute for mode 01 live values. */
object FreezeFrames {
    private val parameters =
        linkedMapOf(
            "04" to ("Sarcină motor" to "%"),
            "05" to ("Lichid de răcire" to "°C"),
            "06" to ("Corecție scurtă combustibil" to "%"),
            "07" to ("Corecție lungă combustibil" to "%"),
            "0B" to ("Presiune admisie" to "kPa"),
            "0C" to ("Turație motor" to "rpm"),
            "0D" to ("Viteză" to "km/h"),
            "0E" to ("Avans aprindere" to "°"),
            "0F" to ("Aer admisie" to "°C"),
            "10" to ("Debit aer MAF" to "g/s"),
            "11" to ("Poziție clapetă" to "%"),
            "1F" to ("Timp motor de la pornire" to "s"),
            "2F" to ("Nivel combustibil" to "%"),
            "42" to ("Tensiune modul" to "V"),
            "46" to ("Temperatură ambiantă" to "°C"),
        )

    fun decode(raw: Map<String, String>): List<FreezeFrame> {
        val groups = linkedMapOf<Pair<String, Int>, MutableMap<String, String>>()
        raw.forEach { (key, value) ->
            val parts = key.split('/')
            val cmd = parts.getOrNull(1) ?: return@forEach
            if (!cmd.matches(Regex("02[0-9A-F]{4}"))) return@forEach
            val pid = cmd.substring(2, 4)
            val fr = cmd.substring(4, 6)
            val response = Normalize.normalize(value)
            if (!response.startsWith("42$pid$fr")) return@forEach
            groups.getOrPut(parts[0] to fr.toInt(16)) { linkedMapOf() }[pid] = response.substring(6)
        }
        return groups
            .mapNotNull { (key, pids) ->
                val code =
                    pids["02"]
                        ?.takeIf { it.length >= 4 }
                        ?.substring(0, 4)
                        ?.toIntOrNull(16)
                        ?.takeIf { it != 0 }
                        ?.let(DtcDecoder::format)
                val conditions =
                    parameters.mapNotNull { (pid, title) ->
                        pids[pid]?.let { bytes ->
                            runCatching { StoredCondition(title.first, StandardPid.decode(pid, "41$pid$bytes"), title.second) }.getOrNull()
                        }
                    }
                if (code == null && conditions.isEmpty()) null else FreezeFrame(key.first, key.second, code, conditions)
            }.sortedWith(compareBy(FreezeFrame::ecu, FreezeFrame::index))
    }

    /** Two bounded engine frames; only low-range PIDs advertised by the ECU are requested. */
    suspend fun readEngine(
        elm: ElmSession,
        raw: MutableMap<String, String>,
        notes: MutableList<String>,
    ) {
        elm.withEcu("7E0") {
            for (frame in 0..1) {
                val fr = "%02X".format(frame)
                val mask = elm.rawCommand("0200$fr")
                raw["7E0/0200$fr"] = mask
                val normalized = Normalize.normalize(mask)
                if (!normalized.startsWith("4200$fr") || normalized.length < 14) {
                    if (frame == 0) notes += "Condițiile memorate de ECU motor (freeze frame) nu sunt disponibile."
                    break
                }
                val supported = StandardPid.supported("4100" + normalized.substring(6), 0)
                for (pid in listOf("02") + parameters.keys.filter { it.toInt(16) <= 0x20 && it in supported }) {
                    val cmd = "02$pid$fr"
                    raw["7E0/$cmd"] = elm.rawCommand(cmd)
                }
            }
        }
    }
}
