package org.betsy.monitor

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** The process owns the session and the journal; an Activity is only a view of them. */
class MonitorApplication : Application() {
    val engine = MonitorEngine()
    lateinit var trips: TripStore
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var trackedKey = -1
    private var trackedOpen = false
    private var lastSample: LiveSample? = null
    private var lastReportTime = 0L

    // Accessed only on io, with all record/start/finish operations in queue order.
    private var activeId: Long? = null

    @Volatile var backgroundRequested = false

    @Volatile var backgroundRunning = false

    @Volatile var lastServiceStart = 0L

    @Volatile var storageError = ""
        private set

    override fun onCreate() {
        super.onCreate()
        trips = TripStore(this)
        write { trips.recoverInterrupted() }
        engine.addObserver { journalChanged() }
    }

    fun connect(
        device: BluetoothDevice,
        name: String,
        ble: Boolean = false,
    ): String? {
        val background = getSharedPreferences("prius-monitor", MODE_PRIVATE).getBoolean("background_monitoring", true)
        backgroundRequested = background
        var warning: String? = null
        if (background) {
            try {
                startForegroundService(Intent(this, MonitorService::class.java))
            } catch (_: Exception) {
                backgroundRequested = false
                warning = "Monitorizarea în fundal nu a putut porni. Păstrează aplicația deschisă."
            }
        }
        engine.connect("$name · ${if (ble) "BLE" else "SPP"}") {
            if (ble) BleElmTransport(this, device) else DeadlineBluetoothTransport(device)
        }
        return warning
    }

    private fun write(block: () -> Unit) {
        io.execute {
            try {
                block()
                if (storageError.isNotBlank()) {
                    storageError = ""
                    main.post { engine.requestRefresh() }
                }
            } catch (e: Exception) {
                storageError = "Jurnalul nu a putut fi actualizat: ${e.message ?: e.javaClass.simpleName}"
                main.post { engine.requestRefresh() }
            }
        }
    }

    fun <T> query(
        block: () -> T,
        result: (Result<T>) -> Unit,
    ) {
        io.execute {
            val value = runCatching(block)
            main.post { result(value) }
        }
    }

    private fun finish(reason: String) {
        write {
            activeId?.let { trips.finish(it, reason) }
            activeId = null
        }
        trackedOpen = false
        lastSample = null
    }

    private fun journalChanged() {
        val key = engine.journeyKey
        if (trackedKey != key) {
            if (trackedOpen) finish(engine.lastEndReason)
            trackedKey = key
        }
        if (engine.connected && !trackedOpen) {
            trackedOpen = true
            val demo = engine.demo
            val adapter = engine.adapter
            val layout = engine.layout
            write { activeId = trips.start(demo, adapter, layout) }
        }
        val sample = engine.sample
        if (trackedOpen && engine.connected && sample.time > 0 && sample !== lastSample) {
            lastSample = sample
            write { activeId?.let { trips.record(it, sample) } }
        }
        if (trackedOpen && !engine.connected && !engine.connecting) finish(engine.status)
        engine.report?.let { report ->
            if (report.time != lastReportTime) {
                lastReportTime = report.time
                write { ReportStore(this).save(report) }
            }
        }
    }
}
