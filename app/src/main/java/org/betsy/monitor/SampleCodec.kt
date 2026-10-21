package org.betsy.monitor

import org.betsy.model.BatteryModel
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Explicit units, nullable fields and source labels survive persistence and CSV export. */
object SampleCodec {
    private fun JSONObject.number(
        key: String,
        value: Number?,
    ) = put(key, if (value == null || !value.toDouble().isFinite()) JSONObject.NULL else value)

    private fun JSONObject.floatOrNull(key: String): Float? =
        if (isNull(key) || !has(key)) null else getDouble(key).toFloat().takeIf { it.isFinite() }

    fun tripJson(t: TripSnapshot): JSONObject =
        JSONObject()
            .put("distanceKm", t.distanceKm)
            .put("liters", t.liters)
            .put("seconds", t.seconds)
            .put("distanceCoveredKm", t.distanceCoveredKm)
            .put("coveredSeconds", t.coveredSeconds)
            .put("estimated", t.estimated)
            .put("stoppedLiters", t.stoppedLiters)
            .put("stoppedSeconds", t.stoppedSeconds)

    fun tripFrom(j: JSONObject): TripSnapshot =
        TripSnapshot(
            distanceKm = j.getDouble("distanceKm"),
            liters = j.getDouble("liters"),
            seconds = j.getDouble("seconds"),
            distanceCoveredKm = j.getDouble("distanceCoveredKm"),
            coveredSeconds = j.getDouble("coveredSeconds"),
            estimated = j.getBoolean("estimated"),
            stoppedLiters = j.getDouble("stoppedLiters"),
            stoppedSeconds = j.getDouble("stoppedSeconds"),
        )

    fun json(s: LiveSample): String {
        val d = s.obd
        val obd =
            JSONObject()
                .number("speed", d.speed)
                .number("rpm", d.rpm)
                .number("coolant", d.coolant)
                .number("intake", d.intake)
                .number("ambient", d.ambient)
                .number("load", d.load)
                .number("throttle", d.throttle)
                .number("maf", d.maf)
                .number("voltage", d.voltage)
                .number("fuelLevel", d.fuelLevel)
                .number("fuelRate", d.fuelRate)
                .number("runtime", d.runtime)
                .number("shortTrim", d.shortTrim)
                .number("longTrim", d.longTrim)
                .number("timing", d.timing)
                .number("pressure", d.pressure)
        val battery =
            s.battery?.let { b ->
                JSONObject()
                    .number("soc", b.soc)
                    .number("current", b.currentAmps)
                    .number("aux12V", b.aux12V)
                    .number("maxChargeHp", b.maxChargeHp)
                    .number("maxDischargeHp", b.maxDischargeHp)
                    .number("deltaSoc", b.deltaSoc)
                    .put("blockCount", b.blockCount)
                    .put("cellCount", b.cellCount)
                    .put("blocks", JSONArray(b.blockVolts))
                    .put("temps", JSONArray(b.temps))
                    .put("resistance", JSONArray(b.internalResistance))
            }
        return JSONObject()
            .put("schema", 1)
            .put("time", s.time)
            .put("obd", obd)
            .put("battery", battery ?: JSONObject.NULL)
            .put("batteryErrors", JSONArray(s.batteryErrors))
            .put("trip", tripJson(s.trip))
            .toString()
    }

    fun fromJson(text: String): LiveSample {
        val j = JSONObject(text)
        require(j.getInt("schema") == 1) { "Versiune necunoscută a înregistrării" }
        val d = j.getJSONObject("obd")
        val obd =
            ObdData(
                speed = d.floatOrNull("speed"),
                rpm = d.floatOrNull("rpm"),
                coolant = d.floatOrNull("coolant"),
                intake = d.floatOrNull("intake"),
                ambient = d.floatOrNull("ambient"),
                load = d.floatOrNull("load"),
                throttle = d.floatOrNull("throttle"),
                maf = d.floatOrNull("maf"),
                voltage = d.floatOrNull("voltage"),
                fuelLevel = d.floatOrNull("fuelLevel"),
                fuelRate = d.floatOrNull("fuelRate"),
                runtime = d.floatOrNull("runtime"),
                shortTrim = d.floatOrNull("shortTrim"),
                longTrim = d.floatOrNull("longTrim"),
                timing = d.floatOrNull("timing"),
                pressure = d.floatOrNull("pressure"),
            )
        val battery =
            j.optJSONObject("battery")?.let { b ->
                fun floats(key: String): List<Float> =
                    b.getJSONArray(key).let { a -> (0 until a.length()).map { a.getDouble(it).toFloat() } }
                val resistance = b.getJSONArray("resistance")
                BatteryModel(
                    soc = b.floatOrNull("soc") ?: Float.NaN,
                    currentAmps = b.floatOrNull("current") ?: Float.NaN,
                    aux12V = b.floatOrNull("aux12V") ?: Float.NaN,
                    maxChargeHp = b.floatOrNull("maxChargeHp") ?: Float.NaN,
                    maxDischargeHp = b.floatOrNull("maxDischargeHp") ?: Float.NaN,
                    deltaSoc = b.floatOrNull("deltaSoc") ?: Float.NaN,
                    blockCount = b.getInt("blockCount"),
                    cellCount = b.getInt("cellCount"),
                    blockVolts = floats("blocks"),
                    temps = floats("temps"),
                    internalResistance = (0 until resistance.length()).map { resistance.getInt(it) },
                )
            }
        val errors = j.getJSONArray("batteryErrors")
        return LiveSample(
            j.getLong("time"),
            obd,
            battery,
            (0 until errors.length()).map {
                errors.getString(it)
            },
            tripFrom(j.getJSONObject("trip")),
        )
    }

    val csvHeader: String =
        (
            listOf(
                "timestamp_utc",
                "speed_kmh",
                "rpm",
                "coolant_c",
                "intake_c",
                "ambient_c",
                "engine_load_pct",
                "throttle_pct",
                "maf_g_s",
                "ecu_voltage_v",
                "fuel_level_pct",
                "fuel_rate_ecu_l_h",
                "runtime_s",
                "short_trim_pct",
                "long_trim_pct",
                "ignition_advance_deg",
                "intake_pressure_kpa",
                "fuel_rate_used_l_h",
                "fuel_estimated",
                "instant_l_100km",
                "hv_soc_pct",
                "hv_current_a",
                "hv_aux_v",
                "hv_pack_v",
            ) +
                (1..3).map { "hv_temp${it}_c" } + (1..14).map { "block${it}_v" } + (1..14).map { "block${it}_mohm" }
        ).joinToString(";")

    fun csvRow(s: LiveSample): String {
        val d = s.obd
        val b = s.battery
        val fuel = Consumption.from(d)

        fun n(v: Number?): String = if (v == null || !v.toDouble().isFinite()) "" else v.toString()
        val row =
            listOf(
                Instant.ofEpochMilli(s.time).toString(),
                n(d.speed),
                n(d.rpm),
                n(d.coolant),
                n(d.intake),
                n(d.ambient),
                n(d.load),
                n(d.throttle),
                n(d.maf),
                n(d.voltage),
                n(d.fuelLevel),
                n(d.fuelRate),
                n(d.runtime),
                n(d.shortTrim),
                n(d.longTrim),
                n(d.timing),
                n(d.pressure),
                n(fuel.litersHour),
                if (fuel.litersHour == null) "" else fuel.estimated.toString(),
                n(fuel.per100(d.speed)),
                n(b?.soc),
                n(b?.currentAmps),
                n(b?.aux12V),
                n(b?.takeIf { it.blockVolts.isNotEmpty() }?.packVolts()),
            ) + (0..2).map { n(b?.temps?.getOrNull(it)) } + (0..13).map { n(b?.blockVolts?.getOrNull(it)) } +
                (0..13).map { n(b?.internalResistance?.getOrNull(it)) }
        return row.joinToString(";")
    }
}
