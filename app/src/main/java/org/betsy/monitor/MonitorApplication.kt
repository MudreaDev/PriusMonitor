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
    val captures by lazy { CaptureStore(this) }
    lateinit var trips: TripStore
        private set
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var trackedKey = -1
    private var trackedOpen = false
    private var lastSample: LiveSample? = null
    private var lastReportTime = 0L
    private var lastSeenStatus = ""

    // Accessed only on io, with all record/start/finish operations in queue order.
    private var activeId: Long? = null

    @Volatile var backgroundRequested = false
        private set

    @Volatile var backgroundSessionId = 0L
        private set

    @Volatile var backgroundRunning = false

    @Volatile var lastServiceStart = 0L

    @Volatile var storageError = ""
        private set

    override fun onCreate() {
        super.onCreate()
        trips = TripStore(this)
        write { trips.recoverInterrupted() }
        engine.addObserver {
            journalChanged()
            val status = engine.status
            if (status != lastSeenStatus) {
                lastSeenStatus = status
                if (status.startsWith("Conexiune nereușită") || status.startsWith("Conexiune întreruptă")) {
                    getSharedPreferences("prius-monitor", MODE_PRIVATE)
                        .edit()
                        .putString("last_connection_error", status)
                        .putLong("last_connection_error_time", System.currentTimeMillis())
                        .apply()
                    engine.requestRefresh()
                }
            }
        }
    }

    fun connect(
        device: BluetoothDevice,
        name: String,
        ble: Boolean = false,
    ): String? =
        connect("$name · ${if (ble) "BLE" else "SPP"}") {
            val link = if (ble) BleElmTransport(this, device) else DeadlineBluetoothTransport(device)
            val capture =
                if (getSharedPreferences("prius-monitor", MODE_PRIVATE).getBoolean("technical_capture", false)) {
                    captures.start(if (ble) "BLE" else "SPP")
                } else {
                    null
                }
            if (capture == null) link else RecordingTransport(link, capture)
        }

    internal fun connect(
        name: String,
        transportFactory: () -> ConnectableTransport,
    ): String? {
        // Background continuity belongs to every real connection, including users who had
        // disabled the old preference. Establish connecting state before service callbacks.
        val sessionId = ++backgroundSessionId
        backgroundRequested = true
        engine.connect(name, transportFactory)
        return try {
            startForegroundService(
                Intent(this, MonitorService::class.java).putExtra(MonitorService.EXTRA_SESSION_ID, sessionId),
            )
            null
        } catch (_: Exception) {
            finishBackgroundSession(sessionId)
            "Monitorizarea în fundal nu a putut porni. Păstrează aplicația deschisă."
        }
    }

    /** A late teardown of the previous service must never clear a newer connection request. */
    fun finishBackgroundSession(sessionId: Long) {
        if (backgroundSessionId == sessionId) backgroundRequested = false
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
