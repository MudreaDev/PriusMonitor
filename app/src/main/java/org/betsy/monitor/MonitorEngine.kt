package org.betsy.monitor

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.betsy.decode.DtcDecoder
import org.betsy.detect.VehicleInfo
import org.betsy.dtc.DtcReader
import org.betsy.elm.ElmException
import org.betsy.elm.ElmSession
import org.betsy.elm.NegativeResponseException
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.transport.awaitBlocking
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sin

/** One worker owns all ECU traffic. UI never blocks on Bluetooth, scans or polling. */
class MonitorEngine {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var generation = 0
    private var journey = 0
    val journeyKey: Int get() = journey
    var lastEndReason = "Sesiune nouă"
        private set
    private val observers = linkedSetOf<() -> Unit>()

    @Volatile private var transport: DeadlineBluetoothTransport? = null
    private var session: ElmSession? = null
    private var info: VehicleInfo? = null
    private var polling: ScheduledFuture<*>? = null
    private var reader: VehicleReader? = null

    private var tick = 0
    private val meter = TripMeter()
    var intervalMs = 1000L
    var onChanged: (() -> Unit)? = null
    var onReport: ((ScanReport) -> Unit)? = null
    var sample = LiveSample()
        private set
    val history = ArrayDeque<LiveSample>()
    val observation = BatteryObservation()
    var report: ScanReport? = null
        private set
    var connected = false
        private set
    var connecting = false
        private set
    var scanning = false
        private set
    var demo = false
        private set
    var status = "Neconectat"
        private set
    var adapter = "Niciun adaptor"
        private set
    var layout = "Prius 30 · detectare automată"
        private set
    var scanProgress = ""
        private set

    fun addObserver(observer: () -> Unit) {
        observers += observer
    }

    fun removeObserver(observer: () -> Unit) {
        observers -= observer
    }

    fun requestRefresh() {
        notifyUi()
    }

    private fun changed() {
        onChanged?.invoke()
        observers.toList().forEach { it() }
    }

    private fun notifyUi(id: Int = generation) {
        main.post { if (id == generation) changed() }
    }

    private fun publish(
        next: LiveSample,
        id: Int,
    ) {
        main.post {
            if (id == generation) {
                sample = next
                observation.add(next.time, next.battery)
                history.addLast(next)
                while (history.size > 180) history.removeFirst()
                changed()
            }
        }
    }

    fun connect(
        device: BluetoothDevice,
        name: String,
    ) {
        disconnect("Pregătire conexiune")
        val id = generation
        connecting = true
        adapter = name
        status = "Conectare Bluetooth…"
        notifyUi(id)
        worker.execute {
            try {
                if (id != generation) return@execute
                val link = DeadlineBluetoothTransport(device)
                transport = link
                if (id != generation) {
                    link.close()
                    return@execute
                }
                link.connect()
                val elm = ElmSession(link)
                elm.commandTimeoutMs = 6000
                status = "Inițializare ELM327…"
                notifyUi(id)
                awaitBlocking { elm.initialize() }
                // The first OBD request may trigger ELM automatic protocol search.
                elm.commandTimeoutMs = 8000
                status = "Citesc parametrii disponibili…"
                notifyUi(id)
                val vehicleReader = VehicleReader(elm) { SystemClock.elapsedRealtime() }
                awaitBlocking { vehicleReader.initialize() }
                reader = vehicleReader
                info = vehicleReader.info
                if (id != generation) {
                    link.close()
                    return@execute
                }
                session = elm
                layout = info?.let { "Toyota ${it.model.label} · ${it.blockCount} blocuri HV" } ?: "OBD standard · bateria HV indisponibilă"
                connected = true
                connecting = false
                status = "Conectat · ${elm.adapterBanner}"
                notifyUi(id)
                schedule(id)
            } catch (e: Exception) {
                fail(id, "Conexiune nereușită: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun startDemo() {
        disconnect("Mod demonstrativ")
        val id = generation
        demo = true
        connected = true
        adapter = "DEMO · date simulate"
        layout = "Prius 30 · simulare cu 14 blocuri"
        status = "DEMO · fără conexiune la mașină"
        notifyUi(id)
        schedule(id)
    }

    private fun schedule(id: Int) {
        if (id != generation || !connected) return
        polling =
            worker.schedule({
                if (id == generation && connected) {
                    try {
                        val (d, battery, errors) = if (demo) demoSample() else readSample()
                        meter.update(SystemClock.elapsedRealtime(), d)
                        publish(LiveSample(System.currentTimeMillis(), d, battery, errors, meter.snapshot), id)
                    } catch (e: Exception) {
                        fail(id, "Conexiune întreruptă: ${e.message ?: e.javaClass.simpleName}")
                    }
                    schedule(id)
                }
            }, if (tick == 0) 0 else intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun readSample(): Triple<ObdData, BatteryModel?, List<String>> {
        val result = awaitBlocking { (reader ?: error("Sesiune închisă")).read() }
        tick++
        return result
    }

    private fun demoSample(): Triple<ObdData, BatteryModel?, List<String>> {
        val t = tick++
        val speed = (48 + sin(t / 13.0) * 22).toFloat()
        val rpm = (1400 + sin(t / 8.0) * 700).toFloat()
        val d =
            ObdData(
                speed,
                rpm,
                84f,
                27f,
                20f,
                31f,
                18f,
                (8 + sin(t / 9.0) * 4).toFloat(),
                14.1f,
                55f,
                null,
                t.toFloat(),
                1.5f,
                -2f,
                12f,
                42f,
            )
        val m =
            BatteryModel(
                soc = (61 + sin(t / 25.0) * 3).toFloat(),
                currentAmps = (sin(t / 10.0) * 24).toFloat(),
                aux12V = 14.1f,
                blockCount = 14,
                cellCount = 28,
                blockVolts = (0 until 14).map { (15.1 + sin(t / 10.0 + it) * 0.035).toFloat() },
                internalResistance = (0 until 14).map { 24 + it % 3 },
                temps = listOf(31.5f, 32.4f, 31.8f),
            )
        return Triple(d, m, emptyList())
    }

    fun scan() {
        if (!connected || scanning) return
        scanning = true
        scanProgress = "Pregătire scanare…"
        val id = generation
        notifyUi(id)
        worker.execute {
            try {
                val notes = mutableListOf<String>()
                val raw = linkedMapOf<String, String>()
                val faults = mutableListOf<Fault>()
                val inf = mutableListOf<String>()
                if (demo) {
                    Thread.sleep(800)
                    faults += Fault("P0A80", "ECU hibrid · 7E2", "Exemplu demonstrativ")
                    notes += "Raport DEMO. Nu provine de la o mașină și nu reprezintă un diagnostic."
                } else {
                    val elm = session ?: error("Sesiune închisă")
                    awaitBlocking {
                        elm.withEcu("7E0") {
                            for ((cmd, label) in listOf("03" to "Memorat", "07" to "În așteptare", "0A" to "Permanent")) {
                                scanProgress = "ECU motor · $label"
                                notifyUi(id)
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
                                } catch (e: ElmException) {
                                    notes += "ECU motor · $label : adaptorul a refuzat cererea (${e.message})"
                                }
                            }
                        }
                        val vehicle = info
                        if (vehicle != null) {
                            val result =
                                DtcReader(elm, vehicle).read { step ->
                                    scanProgress = "Toyota · ${step.totalStep}/${step.totalSteps} · ${step.label}"
                                    notifyUi(id)
                                }
                            faults +=
                                result.groups.flatMap { g -> g.codes.filter { it.raw != 0 }.map { Fault(it.code, g.label, "Toyota ECU") } }
                            raw.putAll(result.rawResponses)
                            notes += result.notes
                            inf +=
                                result.infCodes.map {
                                    "INF ${it.code} · pagina %02X".format(it.table) +
                                        " · asocierea cu un DTC necesită verificare"
                                }
                        } else {
                            notes += "ECU hibrid indisponibil. Raportul acoperă numai citirile ECU motor."
                        }
                    }
                }
                val result = ScanReport(System.currentTimeMillis(), demo, faults.distinct(), notes, raw, layout, inf)
                main.post {
                    if (id == generation) {
                        report = result
                        scanning = false
                        scanProgress = "Scanare terminată"
                        onReport?.invoke(result)
                        changed()
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (id ==
                        generation
                    ) {
                        scanning = false
                        scanProgress = "Scanare incompletă: ${e.message}"
                        changed()
                    }
                }
            }
        }
    }

    fun resetTrip() {
        val id = generation
        worker.execute {
            meter.reset()
            main.post {
                if (id == generation) {
                    lastEndReason = "Călătorie încheiată manual"
                    journey++
                    history.clear()
                    sample = LiveSample()
                    changed()
                }
            }
        }
    }

    private fun fail(
        id: Int,
        message: String,
    ) {
        if (id != generation) return
        transport?.close()
        main.post {
            if (id ==
                generation
            ) {
                connected = false
                connecting = false
                scanning = false
                status = message
                observation.stop()
                changed()
            }
        }
    }

    fun disconnect(reason: String = "Deconectat") {
        lastEndReason = reason
        generation++
        journey++
        polling?.cancel(false)
        polling = null
        transport?.close()
        transport = null
        connected = false
        connecting = false
        scanning = false
        demo = false
        observation.stop()
        status = reason
        report = null
        sample = LiveSample()
        history.clear()
        worker.execute {
            session = null
            info = null
            reader = null

            meter.reset()
            tick = 0
        }
        notifyUi()
    }

    fun destroy() {
        disconnect()
        worker.shutdownNow()
        onChanged = null
        onReport = null
    }

    companion object {
        private fun missingBattery() =
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
}
