package org.betsy.vag

import android.app.Application
import android.os.SystemClock
import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.betsy.monitor.ConnectableTransport
import org.betsy.transport.awaitBlocking
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class VagValue(
    val signal: VagSignal,
    val value: Double?,
    val time: Long,
    val state: String,
    val raw: String = "",
) {
    fun display(now: Long = SystemClock.elapsedRealtime()): String =
        if (value == null) {
            state
        } else if (now - time > 15000) {
            "Expirat"
        } else {
            "%.1f %s".format(value, signal.unit)
        }
}

class VagApplication : Application() {
    val vag = VagEngine()
}

/** One serial owner for polling, scans and confirmed writes. UI receives immutable snapshots. */
class VagEngine {
    private val worker = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var generation = 0

    @Volatile private var transport: ConnectableTransport? = null
    private var session: ElmSession? = null
    private var queue = emptyList<VagQuery>()
    private var cursor = 0
    private var tick = 0
    private val readings = linkedMapOf<String, VagValue>()
    private val retryAt = mutableMapOf<String, Long>()

    @Volatile var values: List<VagValue> = emptyList()
        private set

    @Volatile var status = "Neconectat · Passat B8 1.6 TDI"
        private set

    @Volatile var connected = false
        private set

    @Volatile var busy = false
        private set

    @Volatile var report = "Nicio scanare. Citirea erorilor nu depinde de catalogul de senzori."
        private set

    @Volatile var supportedPids = emptySet<Int>()
        private set

    init {
        worker.scheduleWithFixedDelay({
            val id = generation
            if (connected && !busy) {
                try {
                    awaitBlocking {
                        val fast = queue.filter { it.command in listOf("010C", "010D") }
                        val slow = queue.filterNot { it in fast }
                        val query =
                            if (tick++ % 2 == 0 && fast.isNotEmpty()) {
                                fast[(tick / 2) % fast.size]
                            } else {
                                slow.getOrNull(if (slow.isEmpty()) 0 else cursor++ % slow.size)
                            }
                        query?.let { read(it) }
                    }
                    publish(id)
                } catch (e: Exception) {
                    fail(id, e)
                }
            }
        }, 200, 150, TimeUnit.MILLISECONDS)
    }

    fun connect(
        initialQueries: List<VagQuery> = emptyList(),
        factory: () -> ConnectableTransport,
    ) {
        disconnect()
        val id = generation
        busy = true
        status = "Conectare adaptor…"
        worker.execute {
            try {
                if (id != generation) return@execute
                val link = factory()
                transport = link
                link.connect()
                if (id != generation) {
                    link.close()
                    return@execute
                }
                val elm = ElmSession(link)
                session = elm
                readings.clear()
                retryAt.clear()
                awaitBlocking {
                    elm.commandTimeoutMs = 8000
                    elm.initialize()
                    // This application explicitly targets ISO-TP CAN VAG vehicles, not K-line/TP2.0.
                    check(elm.rawCommand("ATSP6").contains("OK")) { "ISO 15765 CAN indisponibil" }
                    val supported = mutableSetOf<Int>()
                    for (base in 0..0xC0 step 0x20) {
                        val cmd = "01%02X".format(base)
                        val data = VagProtocol.request(elm, "7E0", "7E8", cmd, "41%02X".format(base))
                        val page = VagProtocol.supported(data, base)
                        supported += page
                        if (base + 32 !in page) break
                    }
                    if (id != generation) return@awaitBlocking
                    supportedPids = supported.toSet()
                    queue =
                        (
                            VagCatalog.standard.filter {
                                it.command
                                    .drop(
                                        2,
                                    ).toInt(16) in supported
                            } + initialQueries.take(12)
                        ).distinctBy { it.key }
                    elm.commandTimeoutMs = 2500
                }
                if (id == generation) {
                    connected = true
                    busy = false
                    status = "Conectat · ${queue.size} senzori OBD decodabili / ${supportedPids.size} PID-uri anunțate"
                }
            } catch (e: Exception) {
                fail(id, e)
            }
        }
    }

    fun disconnect() {
        generation++
        connected = false
        busy = false
        transport?.close()
        transport = null
        values = emptyList()
        supportedPids = emptySet()
        report = "Nicio scanare în această conexiune."
        status = "Deconectat"
    }

    private fun fail(
        id: Int,
        e: Exception,
    ) {
        if (id != generation) return
        disconnect()
        status = "Conexiune întreruptă: ${e.message}"
    }

    private fun publish(id: Int) {
        if (id == generation) values = readings.values.toList()
    }

    private suspend fun read(q: VagQuery) {
        if (SystemClock.elapsedRealtime() < (retryAt[q.key] ?: 0)) return
        val now = SystemClock.elapsedRealtime()
        try {
            val data = VagProtocol.request(session!!, q.header, q.response, q.command, q.prefix)
            q.signals.forEach { s ->
                val value = s.decode(data)
                readings[s.id] = VagValue(s, value, SystemClock.elapsedRealtime(), "Valoare invalidă / răspuns scurt", data)
            }
        } catch (e: NoDataException) {
            retryAt[q.key] = now + 60000
            q.signals.forEach { readings[it.id] = VagValue(it, null, now, "Indisponibil: ${e.message}") }
        }
    }

    fun select(queries: List<VagQuery>) {
        val id = generation
        worker.execute {
            if (id != generation || !connected) return@execute
            val standard = VagCatalog.standard.filter { it.command.drop(2).toInt(16) in supportedPids }
            queue = (standard + queries.take(12)).distinctBy { it.key }
            val ids = queue.flatMap { it.signals }.map { it.id }.toSet()
            readings.keys.retainAll(ids)
            retryAt.clear()
            cursor = 0
            status = "Conectat · ${queries.size} cereri VAG selectate (definiții comunitare)"
            publish(id)
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (!connected || busy) return
        busy = true
        val id = generation
        worker.execute {
            try {
                if (id != generation) return@execute
                awaitBlocking { block() }
            } catch (e: Exception) {
                if (id == generation) report = "Operație nereușită: ${e.message}"
            } finally {
                if (id == generation) busy = false
            }
        }
    }

    fun identify() =
        operation {
            val lines = mutableListOf("Identificare ECU · ${java.util.Date()}")
            for ((header, response, name) in modules.filter { it.first in listOf("7E0", "714") }) {
                for (did in listOf("F187", "F189", "F191")) {
                    try {
                        val raw = VagProtocol.request(session!!, header, response, "22$did", "62$did")
                        val ascii =
                            raw
                                .chunked(2)
                                .map { it.toInt(16) }
                                .filter { it in 32..126 }
                                .map { it.toChar() }
                                .joinToString("")
                        lines += "$name $did: $ascii [hex=$raw]"
                    } catch (e: NoDataException) {
                        lines += "$name $did: ${e.message}"
                    }
                }
            }
            report = lines.joinToString("\n")
        }

    fun scan() =
        operation {
            val lines = mutableListOf("Diagnoză · ${java.util.Date()} · citire fizică CAN")
            for ((mode, prefix, label) in listOf(
                Triple("03", "43", "Memorate"),
                Triple("07", "47", "În așteptare"),
                Triple("0A", "4A", "Permanente"),
            )) {
                try {
                    val raw = VagProtocol.request(session!!, "7E0", "7E8", mode, prefix)
                    val codes = VagProtocol.dtcs(raw)
                    lines += "Motor $label: ${codes.ifEmpty { listOf("0 coduri raportate") }.joinToString()} [raw=$raw]"
                } catch (e: NoDataException) {
                    lines += "Motor $label: indisponibil (${e.message})"
                }
            }
            for ((header, response, name) in modules) {
                try {
                    val raw = VagProtocol.request(session!!, header, response, "1902FF", "5902")
                    val codes = VagProtocol.udsDtcs(raw)
                    lines += "$name ($header) UDS: ${codes.ifEmpty { listOf("0 coduri raportate") }.joinToString()} [raw=$raw]"
                } catch (e: NoDataException) {
                    lines += "$name ($header): indisponibil (${e.message})"
                }
                report = lines.joinToString("\n\n")
            }
            report = lines.joinToString("\n\n") + "\n\nScanare limitată la modulele CAN listate; nu este un Auto-Scan complet VCDS."
        }

    /** Re-read speed and RPM in this same serialized operation immediately before a write. */
    private suspend fun requireStationaryEngineOff() {
        val elm = session!!
        val speed = VagProtocol.request(elm, "7E0", "7E8", "010D", "410D")
        val rpm = VagProtocol.request(elm, "7E0", "7E8", "010C", "410C")
        check(speed.length == 2 && speed.toInt(16) == 0 && rpm.length == 4 && rpm.toInt(16) == 0) {
            "Ștergerea necesită viteză și turație confirmate zero, contact pus și motor oprit."
        }
    }

    fun clearEngine() =
        operation {
            requireStationaryEngineOff()
            VagProtocol.request(session!!, "7E0", "7E8", "04", "44")
            report =
                "ECU motor a confirmat ștergerea OBD. Readiness și freeze-frame pot fi resetate. Codurile permanente nu se șterg prin această comandă. Scanează din nou."
        }

    fun clearModule(header: String) =
        operation {
            val module = modules.single { it.first == header }
            requireStationaryEngineOff()
            VagProtocol.request(session!!, module.first, module.second, "14FFFFFF", "54")
            report = "${module.third}: ECU a confirmat ștergerea DTC UDS. Scanează din nou pentru verificare."
        }

    companion object {
        val modules =
            listOf(
                Triple("7E0", "7E8", "Motor"),
                Triple("7E1", "7E9", "Transmisie"),
                Triple("713", "77D", "ABS"),
                Triple("714", "77E", "Instrumente"),
                Triple("710", "77A", "Gateway"),
                Triple("746", "7B0", "Climatizare"),
            )
    }
}
