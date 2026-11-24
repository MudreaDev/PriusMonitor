package org.betsy.vag

import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.json.JSONObject

/** Physical addressing only: never merge responses from multiple ECUs. */
object VagProtocol {
    private val configured = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ElmSession, String>())

    fun payload(
        raw: String,
        command: String,
        prefix: String,
    ): String {
        val lines =
            raw
                .uppercase()
                .replace(">", "")
                .split('\r', '\n')
                .map { it.trim().replace(" ", "") }
                .filter { it.isNotEmpty() && it != command }
        if (lines.any { it.contains("NODATA") || it.contains("ERROR") || it.contains("STOPPED") || it == "?" }) {
            throw NoDataException(raw.trim())
        }
        val lengths = lines.filter { it.matches(Regex("[0-9A-F]{3}")) }
        val frames = lines.filterNot { it == "SEARCHING..." || it in lengths }
        if (lengths.size > 1) throw NoDataException("Mai multe răspunsuri ECU")
        if (frames.any { ':' in it }) {
            frames.forEachIndexed { index, frame ->
                if (!frame.startsWith("${index.toString(16).uppercase()}:")) {
                    throw NoDataException("Cadru lipsă sau ordine invalidă")
                }
            }
        }
        val data = frames.joinToString("") { it.replace(Regex("^[0-9A-F]+:"), "") }
        if (lengths.isNotEmpty() && data.length != lengths.single().toInt(16) * 2) {
            throw NoDataException("Lungime ISO-TP incompletă")
        }
        if (data.startsWith("7F")) throw NoDataException("ECU refuză $command: $data")
        if (!data.matches(Regex("[0-9A-F]+")) || data.length % 2 != 0 || !data.startsWith(prefix)) {
            throw NoDataException("Răspuns invalid pentru $command: $raw")
        }
        return data.drop(prefix.length)
    }

    suspend fun request(
        elm: ElmSession,
        header: String,
        response: String,
        command: String,
        prefix: String,
    ): String =
        elm.withEcu(header) {
            val address = "$header/$response"
            if (configured[elm] != address) {
                configured.remove(elm)
                val setup =
                    if (header in listOf("7E0", "7E1")) {
                        listOf("ATCRA$response", "ATFCSM0")
                    } else {
                        listOf("ATCRA$response", "ATFCSH$header", "ATFCSD300000", "ATFCSM1")
                    }
                for (at in setup) {
                    check(elm.rawCommand(at).contains("OK")) { "Adaptorul nu acceptă $at" }
                }
                configured[elm] = address
            }
            payload(elm.rawCommand(command), command, prefix)
        }

    fun supported(
        data: String,
        base: Int,
    ): Set<Int> {
        require(data.length == 8) { "Bitmap PID trunchiat" }
        val mask = data.toLong(16)
        return (1..32).filter { mask and (1L shl (32 - it)) != 0L }.map { base + it }.toSet()
    }

    /** CAN SAE mode 03/07/0A carries a count byte; reject incomplete replies. */
    fun dtcs(data: String): List<String> {
        require(data.length >= 2)
        val count = data.take(2).toInt(16)
        require(data.length >= 2 + count * 4) { "Listă DTC incompletă" }
        return (0 until count).map {
            org.betsy.decode.DtcDecoder
                .format(data.substring(2 + it * 4, 6 + it * 4).toInt(16))
        }
    }

    fun udsDtcs(data: String): List<String> {
        require(data.length >= 2 && (data.length - 2) % 8 == 0) { "Listă UDS incompletă" }
        return data.drop(2).chunked(8).map { "${it.take(6)} · status ${it.takeLast(2)}" }
    }
}

data class VagSignal(
    val id: String,
    val name: String,
    val unit: String,
    val format: JSONObject,
) {
    fun decode(hex: String): Double? {
        val start = format.optInt("bix", 0)
        val size = format.getInt("len")
        if (size !in 1..32 || start < 0 || start + size > hex.length * 4) return null
        var bits = hex.chunked(2).joinToString("") { it.toInt(16).toString(2).padStart(8, '0') }.substring(start, start + size)
        if (format.optBoolean("blsb")) {
            if (size % 8 != 0 || start % 8 != 0) return null
            bits = bits.chunked(8).reversed().joinToString("")
        }
        var value = bits.toLong(2)
        if (format.optBoolean("sign") && bits.first() == '1') value -= 1L shl size
        val number = value * format.optDouble("mul", 1.0) / format.optDouble("div", 1.0) + format.optDouble("add", 0.0)
        if (!number.isFinite() ||
            number < format.optDouble("min", Double.NEGATIVE_INFINITY) ||
            number > format.optDouble("max", Double.POSITIVE_INFINITY) ||
            number <= format.optDouble("nullmin", Double.NEGATIVE_INFINITY) ||
            number >= format.optDouble("nullmax", Double.POSITIVE_INFINITY)
        ) {
            return null
        }
        return number
    }
}

data class VagQuery(
    val header: String,
    val response: String,
    val command: String,
    val signals: List<VagSignal>,
    val experimental: Boolean = false,
) {
    val key get() = "$header/$command"
    val prefix get() = if (command.startsWith("22")) "62${command.drop(2)}" else "41${command.drop(2)}"
}

object VagCatalog {
    /** No diagnostic sessions, writes, 29-bit addressing, or unknown schema features are inferred. */
    fun passat(
        json: String,
        year: Int,
    ): List<VagQuery> {
        require(year in 2014..2023)
        val commands = JSONObject(json).getJSONArray("commands")
        return (0 until commands.length())
            .mapNotNull { index ->
                val c = commands.getJSONObject(index)
                val filter = c.optJSONObject("filter")
                if (filter != null) {
                    val from = filter.optInt("from", Int.MIN_VALUE)
                    val to = filter.optInt("to", Int.MAX_VALUE)
                    val years = filter.optJSONArray("years")
                    val listed = years != null && (0 until years.length()).any { years.getInt(it) == year }
                    val range = if (from > to) year >= from || year <= to else year in from..to
                    if (!listed && !(range && (years == null || filter.has("from") || filter.has("to")))) return@mapNotNull null
                }
                val header = c.getString("hdr")
                val response = c.optString("rax", if (header == "7E0") "7E8" else "")
                if (c.has("din") ||
                    c.has("pri") ||
                    !header.matches(Regex("[0-7][0-9A-F]{2}")) ||
                    !response.matches(Regex("[0-7][0-9A-F]{2}"))
                ) {
                    return@mapNotNull null
                }
                val did = c.getJSONObject("cmd").optString("22")
                if (!did.matches(Regex("[0-9A-F]{4}"))) return@mapNotNull null
                val array = c.getJSONArray("signals")
                val signals =
                    (0 until array.length()).map { array.getJSONObject(it) }.map {
                        VagSignal(
                            it.getString("id"),
                            it.getString("name"),
                            it.getJSONObject("fmt").optString("unit", ""),
                            it.getJSONObject("fmt"),
                        )
                    }
                VagQuery(header, response, "22$did", signals, true)
            }.groupBy { it.key }
            .values
            .filter { it.size == 1 }
            .map { it.single() }
    }

    val standard: List<VagQuery> =
        buildList {
            fun add(
                pid: String,
                name: String,
                unit: String,
                len: Int = 8,
                div: Double = 1.0,
                add: Double = 0.0,
                mul: Double = 1.0,
            ) {
                val fmt =
                    JSONObject()
                        .put("len", len)
                        .put("div", div)
                        .put("add", add)
                        .put("mul", mul)
                add(VagQuery("7E0", "7E8", "01$pid", listOf(VagSignal("OBD_$pid", name, unit, fmt))))
            }
            add("0C", "Turație motor", "rpm", 16, 4.0)
            add("0D", "Viteză", "km/h")
            add("5C", "Temperatură ulei motor", "°C", add = -40.0)
            add("05", "Temperatură antigel", "°C", add = -40.0)
            add("0F", "Temperatură admisie", "°C", add = -40.0)
            add("46", "Temperatură exterioară", "°C", add = -40.0)
            add("42", "Tensiune ECU", "V", 16, 1000.0)
            add("04", "Sarcină motor", "%", div = 255.0, mul = 100.0)
            add("11", "Clapetă accelerație", "%", div = 255.0, mul = 100.0)
            add("2F", "Nivel combustibil", "%", div = 255.0, mul = 100.0)
            add("0B", "Presiune absolută admisie", "kPa")
            add("33", "Presiune atmosferică", "kPa")
            add("10", "Debit aer MAF", "g/s", 16, 100.0)
            add("23", "Presiune rampă diesel", "kPa", 16, mul = 10.0)
            add("5E", "Consum direct ECU", "l/h", 16, 20.0)
            add("1F", "Timp motor pornit", "s", 16)
            add("31", "Distanță după ștergerea DTC", "km", 16)
            add("21", "Distanță cu MIL aprins", "km", 16)
            add("30", "Încălziri după ștergerea DTC", "")
            add("2C", "EGR comandat", "%", div = 255.0, mul = 100.0)
            add("2D", "Eroare EGR", "%", div = 128.0, mul = 100.0, add = -100.0)
            add("3C", "Temperatură catalizator B1S1", "°C", 16, 10.0, -40.0)
            add("3D", "Temperatură catalizator B2S1", "°C", 16, 10.0, -40.0)
            add("3E", "Temperatură catalizator B1S2", "°C", 16, 10.0, -40.0)
            add("3F", "Temperatură catalizator B2S2", "°C", 16, 10.0, -40.0)
            add("61", "Cuplu cerut", "%", add = -125.0)
            add("62", "Cuplu efectiv", "%", add = -125.0)
            add("63", "Cuplu de referință", "Nm", 16)
        }
}
