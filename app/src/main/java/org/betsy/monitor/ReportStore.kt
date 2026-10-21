package org.betsy.monitor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportStore(
    private val context: Context,
) {
    private val folder get() = File(context.filesDir, "reports").apply { mkdirs() }

    fun save(report: ScanReport): File {
        val json = JSONObject().put("time", report.time).put("demo", report.demo).put("layout", report.layout)
        json.put(
            "faults",
            JSONArray().apply {
                report.faults.forEach { put(JSONObject().put("code", it.code).put("source", it.source).put("status", it.status)) }
            },
        )
        json.put("notes", JSONArray(report.notes)).put("inf", JSONArray(report.inf)).put("raw", JSONObject(report.raw))
        return File(folder, "scan-${report.time}.json").apply { writeText(json.toString(2)) }
    }

    fun list(): List<File> = folder.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.name } ?: emptyList()

    fun read(file: File): ScanReport {
        val j = JSONObject(file.readText())

        fun strings(key: String): List<String> =
            j.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val codes = j.getJSONArray("faults")
        val raw = j.getJSONObject("raw")
        return ScanReport(
            j.getLong("time"),
            j.getBoolean("demo"),
            (0 until codes.length()).map {
                val f = codes.getJSONObject(it)
                Fault(f.getString("code"), f.getString("source"), f.getString("status"))
            },
            strings("notes"),
            raw.keys().asSequence().associateWith { raw.getString(it) },
            j.getString("layout"),
            strings("inf"),
        )
    }

    fun text(r: ScanReport): String =
        buildString {
            appendLine("PRIUS MONITOR — RAPORT DIAGNOSTIC")
            appendLine(if (r.demo) "DEMO · DATE SIMULATE" else "Date citite de la ECU")
            appendLine(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("ro")).format(Date(r.time)))
            appendLine(r.layout)
            appendLine()
            if (r.faults.isEmpty()) appendLine("Niciun cod în răspunsurile citite. Verifică notele privind acoperirea scanării.")
            r.faults.forEach {
                appendLine("${it.code} | ${it.source} | ${it.status}")
                appendLine(RomanianFaults.description(it.code))
                appendLine()
            }
            r.inf.forEach { appendLine(it) }
            appendLine("NOTE / CITIRI INDISPONIBILE")
            r.notes.forEach { appendLine(it) }
            appendLine()
            appendLine("RĂSPUNSURI TEHNICE ECU")
            r.raw.forEach { (key, value) -> appendLine("$key = $value") }
            appendLine()
            appendLine("Aplicația citește date și nu șterge coduri. Raportul nu certifică starea completă a mașinii.")
            appendLine("Prius Monitor, bazat pe BETSY · licență MIT")
        }
}
