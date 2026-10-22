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
    val demo = false
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
                observation.add(next.time, next.battery, SystemClock.elapsedRealtime())
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
            var stage = "Bluetooth"
            try {
                if (id != generation) return@execute
                val link = transportFactory()
                transport = link
                if (id != generation) {
                    link.close()
                    return@execute
                }
                link.connect()
                stage = "inițializare ELM327"
                val elm = ElmSession(link)
                elm.commandTimeoutMs = 6000
                status = "Inițializare ELM327…"
                notifyUi(id)
                awaitBlocking { elm.initialize() }
                stage = "comunicație cu ECU"
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
                fail(id, "Conexiune nereușită · $stage: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun schedule(id: Int) {
        if (id != generation || !connected) return
        polling =
            worker.schedule({
                if (id == generation && connected) {
                    try {
                        val (d, battery, errors) = readSample()
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

    fun scan() {
        if (!connected || scanning) return
        scanning = true
        scanProgress = "Pregătire scanare…"
        val id = generation
        notifyUi(id)
        worker.execute {
            try {
                val elm = session ?: error("Sesiune închisă")
                val result =
                    awaitBlocking {
                        ReadOnlyDiagnostics.read(elm, info, layout, progress = { text ->
                            scanProgress = text
                            notifyUi(id)
                        })
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
