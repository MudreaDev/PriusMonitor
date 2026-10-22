package org.betsy.monitor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.betsy.detect.VehicleInfo
import org.betsy.elm.ElmSession
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

    @Volatile private var transport: ConnectableTransport? = null
    private var session: ElmSession? = null
    private var info: VehicleInfo? = null
    private var polling: ScheduledFuture<*>? = null
    private var reader: VehicleReader? = null

    private var tick = 0
    private val meter = TripMeter()

    @Volatile var intervalMs = 1000L
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
        name: String,
        transportFactory: () -> ConnectableTransport,
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
                val link = transportFactory()
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
                val result =
                    if (demo) {
                        Thread.sleep(800)
                        ScanReport(
                            System.currentTimeMillis(),
                            true,
                            listOf(Fault("P0A80", "ECU hibrid · 7E2", "Exemplu demonstrativ")),
                            listOf("Raport DEMO. Nu provine de la o mașină și nu reprezintă un diagnostic."),
                            emptyMap(),
                            layout,
                        )
                    } else {
                        val elm = session ?: error("Sesiune închisă")
                        awaitBlocking {
                            ReadOnlyDiagnostics.read(elm, info, layout, progress = { text ->
                                scanProgress = text
                                notifyUi(id)
                            })
                        }
                    }
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
