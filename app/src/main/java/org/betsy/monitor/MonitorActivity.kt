package org.betsy.monitor

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native Android UI: all displayed live values come from MonitorEngine, never preview defaults. */
class MonitorActivity : Activity() {
    private val app by lazy { application as MonitorApplication }
    private val engine by lazy { app.engine }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("prius-monitor", MODE_PRIVATE) }
    private val store by lazy { ReportStore(this) }
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var compact: CompactDashboard
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var connection: TextView
    private lateinit var nav: LinearLayout
    private var page = "Live"
    private var renderedPage = ""
    private var navPage = ""
    private var navigationVersion = 0
    private var tripLoadVersion = 0
    private var loadedReport: ScanReport? = null
    private val pendingExports by lazy { PendingExport(this) }
    private val bleDiscovery by lazy { BleDiscovery(this) { if (uiVisible && page == "Conexiune") refresh() } }
    private var exportToken: String? = null
    private var documentPickerOpen = false
    private var exportPreparing = false
    private var touching = false
    private var uiVisible = true
    private var renderPending = false
    private var wasConnected = false
    private var lastAutoSave = 0L
    private var tripRows = emptyList<RecordedTrip>()
    private var historyLoading = false
    private var historyLoadedAt = 0L
    private var selectedTrip: RecordedTrip? = null
    private var selectedSamples = emptyList<LiveSample>()
    private var pendingTripCsv: Long? = null
    private var imperial = false
    private var fahrenheit = false
    private val bg = Color.rgb(8, 15, 25)
    private val panel = Color.rgb(17, 30, 44)
    private val line = Color.rgb(35, 52, 69)
    private val ink = Color.rgb(237, 245, 251)
    private val muted = Color.rgb(147, 168, 186)
    private val accent = Color.rgb(69, 229, 193)
    private val amber = Color.rgb(255, 196, 107)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        imperial = prefs.getBoolean("imperial", false)
        fahrenheit = prefs.getBoolean("fahrenheit", false)
        engine.intervalMs = prefs.getLong("interval", 1000)
        buildShell()
        engine.onChanged = {
            if (wasConnected && !engine.connected && engine.sample.time > 0) saveSummary(engine.status)
            if (engine.connected && engine.sample.time - lastAutoSave >= 30000) {
                saveSummary("Salvare automată în timpul călătoriei")
                lastAutoSave = engine.sample.time
            }
            wasConnected = engine.connected
            if (uiVisible) refresh()
        }
        engine.onReport = {
            if (uiVisible) {
                toast("Scanare terminată · raport disponibil")
                if (page == "Erori") render()
            }
        }
        if (savedInstanceState == null && prefs.getBoolean("first", true)) {
            prefs.edit().putBoolean("first", false).apply()
            AlertDialog
                .Builder(this)
                .setTitle("Bun venit în Prius Monitor")
                .setMessage(
                    "Asociază adaptorul VIECAR în setările Bluetooth ale telefonului, apoi selectează-l în Conexiune. Mașina trebuie să permită citirea ECU (de regulă în READY). Citirile Prius 30 necesită validare pe mașina ta.",
                ).setPositiveButton("Am înțeles", null)
                .show()
        }
        page = savedInstanceState?.getString("page") ?: "Live"
        exportToken = savedInstanceState?.getString("exportToken")
        pendingTripCsv = savedInstanceState?.getLong("csvTrip", -1L)?.takeIf { it >= 0 }
        documentPickerOpen = savedInstanceState?.getBoolean("documentPickerOpen", false) ?: false
        render()
        val savedY = savedInstanceState?.getInt("scrollY", 0) ?: 0
        val tripId = savedInstanceState?.getLong("selectedTrip", -1L) ?: -1L
        val reportTime = savedInstanceState?.getLong("loadedReport", -1L) ?: -1L
        if (page == "Călătorie" && tripId >= 0) {
            openTrip(tripId, savedY)
        } else if (page == "Raport" && reportTime >= 0) {
            app.query({ store.list().firstOrNull { it.name == "scan-$reportTime.json" }?.let { store.read(it) } }) { result ->
                if (!isDestroyed && page == "Raport") {
                    loadedReport = result.getOrNull()
                    render()
                    scroll.post { scroll.scrollTo(0, savedY) }
                }
            }
        } else {
            scroll.post { scroll.scrollTo(0, savedY) }
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("page", page)
        out.putInt("scrollY", scroll.scrollY)
        selectedTrip?.let { out.putLong("selectedTrip", it.id) }
        loadedReport?.let { out.putLong("loadedReport", it.time) }
        exportToken?.let { out.putString("exportToken", it) }
        pendingTripCsv?.let { out.putLong("csvTrip", it) }
        out.putBoolean("documentPickerOpen", documentPickerOpen)
        super.onSaveInstanceState(out)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun background(
        color: Int,
        radius: Int = 20,
        stroke: Int? = null,
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun label(
        value: String,
        size: Float = 14f,
        color: Int = ink,
        bold: Boolean = false,
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun gap(
        parent: LinearLayout,
        size: Int = 12,
    ) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(size)))
    }

    private fun button(
        text: String,
        primary: Boolean = false,
        action: () -> Unit,
    ) = MonitorButton(this, action, primary).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(if (primary) bg else ink)
        background = background(if (primary) accent else Color.rgb(27, 44, 60), 13)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(12), dp(6), dp(12), dp(6))
        contentDescription = text
    }

    private fun action(
        parent: LinearLayout,
        title: String,
        primary: Boolean = false,
        click: () -> Unit,
    ) {
        parent.addView(button(title, primary, click), LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
    }

    private fun card(title: String? = null): LinearLayout {
        val card =
            column().apply {
                background = background(panel, 20, line)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
        body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        if (title != null) {
            card.addView(label(title, 15f, ink, true))
            gap(card, 12)
        }
        return card
    }

    private fun note(
        text: String,
        color: Int = muted,
    ) {
        card().addView(label(text, 13f, color))
    }

    private fun metric(
        parent: LinearLayout,
        title: String,
        value: String,
        unit: String = "",
        tint: Int = ink,
    ) {
        val row =
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(9), 0, dp(9))
            }
        row.addView(label(title, 13f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            label(value + if (unit.isBlank()) "" else " $unit", 17f, tint, true).apply {
                setPadding(dp(12), 0, 0, 0)
                maxWidth = dp(190)
                gravity = Gravity.END
            },
        )
        parent.addView(row)
    }

    private fun twoMetrics(
        parent: LinearLayout,
        first: Triple<String, String, String>,
        second: Triple<String, String, String>,
    ) {
        val row = LinearLayout(this)
        listOf(first, second).forEachIndexed { i, m ->
            val c =
                column().apply {
                    background = background(Color.rgb(12, 23, 36), 14)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                }
            c.addView(label(m.first, 12f, muted))
            gap(c, 6)
            c.addView(label(m.second, 27f, ink, true))
            c.addView(label(m.third, 12f, muted))
            row.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i == 0) rightMargin = dp(8) else leftMargin = dp(8) })
        }
        parent.addView(row)
    }

    private fun buildShell() {
        root = column().apply { setBackgroundColor(bg) }
        header = column().apply { setPadding(dp(22), dp(18), dp(22), dp(14)) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = column()
        title.addView(label("TOYOTA HYBRID", 10f, accent, true).apply { letterSpacing = .18f })
        title.addView(label("Prius Monitor", 27f, ink, true))
        top.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("Setări") { navigate("Setări") }, LinearLayout.LayoutParams(dp(80), dp(46)))
        header.addView(top)
        connection = label("", 11f, muted).apply { setPadding(0, dp(9), 0, 0) }
        header.addView(connection)
        root.addView(header)
        scroll =
            ScrollView(this).apply {
                isFillViewport = true
                clipToPadding = false
            }
        body = column().apply { setPadding(dp(18), dp(4), dp(18), dp(22)) }
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        compact = CompactDashboard(this) { navigate("Conexiune") }.apply { visibility = View.GONE }
        root.addView(compact, LinearLayout.LayoutParams(-1, 0, 1f))
        nav =
            LinearLayout(this).apply {
                setPadding(dp(6), dp(7), dp(6), dp(9))
                setBackgroundColor(Color.rgb(12, 22, 34))
            }
        root.addView(nav)
        setContentView(root)
    }

    private fun navigate(next: String) {
        navigationVersion++
        if (page == "Conexiune" && next != page) bleDiscovery.stop(false)
        page = next
        loadedReport = null
        scroll.scrollTo(0, 0)
        render()
    }

    private fun refresh() {
        if (touching) renderPending = true else render()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touching = true
        val result = super.dispatchTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            if (renderPending) {
                renderPending = false
                handler.post { if (!touching) render() }
            }
        }
        return result
    }

    private fun render() {
        if (isFinishing || isDestroyed) return
        val live = page == "Live"
        header.visibility = if (live) View.GONE else View.VISIBLE
        scroll.visibility = if (live) View.GONE else View.VISIBLE
        compact.visibility = if (live) View.VISIBLE else View.GONE
        if (live) {
            if (navPage != page) renderNavigation()
            renderCompact()
            return
        }
        val y = scroll.scrollY
        val attachedBody = body
        body = column()
        connection.text =
            if (engine.demo) {
                "●  DEMO · DATE SIMULATE"
            } else if (engine.connected) {
                "●  ${engine.adapter} · CONECTAT"
            } else {
                "○  ${engine.status}"
            }
        connection.setTextColor(
            if (engine.demo) {
                amber
            } else if (engine.connected) {
                accent
            } else {
                muted
            },
        )
        if (navPage != page) renderNavigation()
        body.addView(label(page, 25f, ink, true))
        gap(body, 4)
        body.addView(label(subtitle(), 12f, muted))
        gap(body, 18)
        if (app.storageError.isNotBlank()) note(app.storageError, amber)
        if (engine.demo && page != "Conexiune" && page != "Setări") {
            card().addView(label("MOD DEMO · Toate valorile sunt simulate.", 12f, amber, true))
        }
        when (page) {
            "Acasă" -> dashboard()
            "Consum" -> consumption()
            "Baterie" -> battery()
            "Sănătate baterie" -> batteryHealth()
            "Erori" -> diagnostics()
            "Conexiune" -> connectPage()
            "Setări" -> settingsPage()
            "Parametri OBD" -> sensorsPage()
            "Emisii OBD" -> emissionsPage()
            "Istoric călătorii" -> tripHistory()
            "Călătorie" -> tripDetails()
            "Rapoarte" -> reportsPage()
            "Raport" -> loadedReport?.let { showReport(it) }
        }
        val fresh = body
        body = attachedBody
        if (renderedPage == page && LiveViews.compatible(body, fresh)) {
            LiveViews.update(body, fresh)
        } else {
            body.removeAllViews()
            while (fresh.childCount > 0) {
                val child = fresh.getChildAt(0)
                fresh.removeViewAt(0)
                body.addView(child)
            }
            scroll.post { scroll.scrollTo(0, y) }
        }
        renderedPage = page
    }

    private fun renderNavigation() {
        navPage = page
        nav.removeAllViews()
        val active =
            when (page) {
                "Sănătate baterie" -> "Baterie"
                "Istoric călătorii", "Călătorie" -> "Consum"
                "Parametri OBD" -> "Acasă"
                "Rapoarte", "Raport", "Emisii OBD" -> "Erori"
                else -> page
            }
        listOf("Acasă", "Live", "Consum", "Baterie", "Erori", "Conexiune").forEach { item ->
            val selected = active == item
            val tint = if (selected) accent else muted
            val destination =
                column().apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(4))
                    minimumHeight = dp(56)
                    contentDescription = item
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { navigate(item) }
                    val icon =
                        when (item) {
                            "Acasă" -> 0
                            "Live" -> 5
                            "Consum" -> 1
                            "Baterie" -> 2
                            "Erori" -> 3
                            else -> 4
                        }
                    addView(NavIcon(this@MonitorActivity, icon, tint), LinearLayout.LayoutParams(dp(24), dp(24)))
                    addView(label(item, 10f, tint).apply { gravity = Gravity.CENTER })
                }
            nav.addView(destination, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun renderCompact() {
        val d = obd()
        val battery = hv()
        val trip = engine.sample.trip
        val fuel = Consumption.from(d)
        val average = trip.average
        val kmPerLiter = average?.takeIf { it > 0 }?.let { 100 / it }
        val pack = battery?.takeIf { it.blockVolts.size == 14 }?.packVolts()
        val stopped =
            trip.stoppedLiters.takeIf {
                trip.stoppedCoveredSeconds > 0 || trip.stoppedSeconds == 0.0 && trip.coveredSeconds > 0
            }
        val distance = trip.distanceKm.takeIf { it > 0 || trip.stoppedSeconds > 0 || trip.seconds > 0 && d.speed != null }
        val metrics =
            listOf(
                CompactMetric("Viteză", speed(d.speed), speedUnit()),
                CompactMetric("Consum instant", f(fuel.per100(d.speed)), "l/100 km"),
                CompactMetric("Consum mediu", f(average), "l/100 km · ${f(kmPerLiter)} km/l"),
                CompactMetric("Consum pe oră", f(fuel.litersHour, 2), "l/h"),
                CompactMetric("Baterie HV", f(battery?.soc), "% SOC · ${f(pack, 0)} V"),
                CompactMetric("Turație motor", f(d.rpm, 0), "rpm"),
                CompactMetric("Răcire motor", temp(d.coolant), tempUnit()),
                CompactMetric("Curent HV", f(battery?.currentAmps, 1), "A · semn ECU"),
                CompactMetric("Temperatură HV", temp(battery?.temps?.filter(Float::isFinite)?.maxOrNull()), tempUnit()),
                CompactMetric("Diferență blocuri", f(battery?.takeIf { it.blockVolts.size == 14 }?.voltDiff(), 3), "V · max–min"),
                CompactMetric("Baterie 12 V", f(d.voltage ?: battery?.aux12V, 2), "V"),
                CompactMetric("Distanță", f(distance?.let { if (imperial) it / 1.609344 else it }, 2), if (imperial) "mile" else "km"),
                CompactMetric("Combustibil total", f(trip.liters.takeIf { trip.coveredSeconds > 0 }, 3), "litri calculați"),
                CompactMetric("La viteză 0", f(stopped, 3), "litri · călătoria continuă"),
                CompactMetric("Durată călătorie", duration(trip.seconds), "h:min:sec"),
            )
        val state =
            if (engine.connected) {
                "● ${engine.adapter}"
            } else if (engine.connecting) {
                "Conectare în curs…"
            } else {
                "Neconectat · apasă OBD"
            }
        val source =
            when (fuel.source) {
                FuelSource.MAF -> "Consum estimat MAF"
                FuelSource.ECU -> "Consum citit ECU"
                FuelSource.ENGINE_STOPPED -> "Motor termic oprit"
                FuelSource.UNAVAILABLE -> "Consum indisponibil"
            }
        compact.update(metrics, state, "$source · acoperire ${f(trip.coverage, 0)} % · SOH indisponibil")
    }

    private fun subtitle(): String =
        when (page) {
            "Acasă" -> "Toate informațiile esențiale, într-un singur loc."
            "Consum" -> "Călătoria continuă și când te oprești în trafic."
            "Baterie" -> "14 blocuri HV · tensiuni, temperaturi și curent."
            "Erori" -> "Citire ECU motor și hibrid · fără ștergerea codurilor."
            "Conexiune" -> "VIECAR / ELM327 · Bluetooth Classic și BLE."
            "Setări" -> "Preferințele tale, salvate pe telefon."
            "Sănătate baterie" -> "Observații măsurate · SOC și SOH explicate corect."
            "Parametri OBD" -> "Valorile disponibile de la ECU-ul mașinii."
            "Emisii OBD" -> "Starea MIL și testele raportate de ECU-ul motorului."
            "Istoric călătorii" -> "Datele drumurilor tale, păstrate pe telefon."
            "Călătorie" -> "Rezumat, grafice și exportul eșantioanelor OBD."
            else -> "Rapoarte păstrate local pe telefon."
        }

    private fun f(
        value: Number?,
        digits: Int = 1,
    ): String =
        if (value == null ||
            !value.toDouble().isFinite()
        ) {
            "—"
        } else {
            String.format(Locale("ro"), "%.${digits}f", value.toDouble())
        }

    private fun temp(value: Float?) = f(value?.let { if (fahrenheit) it * 1.8f + 32 else it })

    private fun speed(value: Float?) = f(value?.let { if (imperial) it / 1.609344f else it }, 0)

    private fun speedUnit() = if (imperial) "mph" else "km/h"

    private fun tempUnit() = if (fahrenheit) "°F" else "°C"

    private fun duration(seconds: Double): String {
        val m =
            seconds.toInt() / 60
        return "%d:%02d:%02d".format(m / 60, m % 60, seconds.toInt() % 60)
    }

    private fun isFresh() = engine.connected && engine.sample.time > 0 && System.currentTimeMillis() - engine.sample.time < 20000

    private fun obd() = if (isFresh()) engine.sample.obd else ObdData()

    private fun hv() = if (isFresh()) engine.sample.battery else null

    private fun idleState(d: ObdData): String =
        when {
            d.speed == null -> "Stare deplasare indisponibilă"
            d.speed > 0 -> "În mers"
            else -> "Viteză 0 · călătoria continuă"
        }

    private fun engineState(d: ObdData) =
        when {
            d.rpm == null -> "Motor: date indisponibile"
            d.rpm == 0f -> "Motor termic oprit"
            else -> "Motor termic pornit"
        }

    private fun dashboard() {
        val d = obd()
        val m = hv()
        val hero = card()
        hero.addView(label("VITEZĂ", 11f, accent, true).apply { letterSpacing = .15f })
        hero.addView(label(speed(d.speed), 70f, ink, true))
        hero.addView(label(speedUnit(), 14f, muted))
        gap(hero)
        hero.addView(label(idleState(d) + "\n" + engineState(d), 12f, muted))
        gap(hero, 18)
        twoMetrics(hero, Triple("Motor termic", f(d.rpm, 0), "rpm"), Triple("Baterie HV", f(m?.soc, 0), "% SOC"))
        val vital = card("Starea sistemelor")
        metric(vital, "Lichid de răcire", temp(d.coolant), tempUnit(), if ((d.coolant ?: 0f) > 105) amber else ink)
        metric(vital, "Tensiune 12 V · ECU", f(d.voltage, 2), "V")
        metric(vital, "Curent baterie HV", f(m?.currentAmps), "A")
        action(vital, "Toți parametrii OBD") { navigate("Parametri OBD") }
        val trend = card("Viteza în timp")
        trend.addView(
            LineChart(
                this,
                engine.history.map {
                    it.obd.speed?.let { v ->
                        if (imperial) v / 1.609344f else v
                    }
                },
                accent,
                "Ultimele ${engine.history.size} eșantioane · ${speedUnit()}",
            ),
            LinearLayout.LayoutParams(-1, dp(150)),
        )
        val c = Consumption.from(d)
        val fuel = card("Consumul călătoriei")
        metric(fuel, "Instantaneu", f(c.per100(d.speed)), "l/100 km", accent)
        metric(fuel, "Pe oră", f(c.litersHour, 2), "l/h")
        metric(fuel, "Mediu", f(engine.sample.trip.average), "l/100 km")
        fuel.addView(label(fuelSourceLabel(c), 11f, amber))
        action(fuel, "Deschide ecranul Consum") { navigate("Consum") }
        if (!engine.connected) {
            val start = card("Pregătit pentru Prius-ul tău")
            start.addView(
                label("Conectează adaptorul pentru citiri reale. Valorile fără răspuns sunt afișate cu —.", 13f, muted),
            )
            action(start, "Conectează VIECAR", true) { navigate("Conexiune") }
        }
        note(
            "${engine.layout}\n" +
                if (engine.sample.time >
                    0
                ) {
                    "Ultima citire: ${time(engine.sample.time)} · ${if (isFresh()) "date recente" else "date expirate"}"
                } else {
                    "Aștept o conexiune OBD."
                },
        )
    }

    private fun consumption() {
        val d = obd()
        val c = Consumption.from(d)
        val t = engine.sample.trip
        val hero = card()
        hero.addView(label("CONSUM INSTANTANEU", 11f, accent, true))
        hero.addView(label(f(c.per100(d.speed)), 58f, ink, true))
        hero.addView(label("l/100 km · de la 5 km/h", 12f, muted))
        gap(hero, 18)
        twoMetrics(hero, Triple("Consumul mediu", f(t.average), "l/100 km"), Triple("Viteza", speed(d.speed), speedUnit()))
        gap(hero)
        hero.addView(
            label(fuelSourceLabel(c), 11f, amber),
        )
        if (t.seconds > 0 && t.coverage < 99.5) hero.addView(label("Medie parțială · acoperire ${f(t.coverage, 0)} %", 11f, amber))
        val hourly = card("Consum pe oră și la opriri")
        metric(hourly, "Acum · inclusiv în mers", f(c.litersHour, 2), "l/h", accent)
        // Stopped fuel: only show a litre figure when we actually had fuel-rate readings
        // during the stopped interval. If coverage is zero, 0,000 l would be misleading.
        val stoppedCov = t.stoppedCoverage // 0..100
        val stoppedLabel =
            when {
                t.stoppedSeconds < 1 -> f(null) // no stops yet — show dash
                stoppedCov < 0.5 -> "date lipsă" // no readings during any stop
                else -> f(t.stoppedLiters, 3)
            }
        val stoppedUnit = if (t.stoppedSeconds < 1 || stoppedCov < 0.5) "" else "litri"
        metric(hourly, "Combustibil la viteză 0", stoppedLabel, stoppedUnit)
        metric(hourly, "Timp la viteză 0", duration(t.stoppedSeconds))
        if (t.stoppedSeconds >= 1 && stoppedCov < 99.5) {
            val covText =
                if (stoppedCov < 0.5) {
                    "Nicio citire de consum nu a coincis cu opriri — totalul de 0 nu confirmă consumul zero."
                } else {
                    "Acoperire opriri: ${f(stoppedCov, 0)} % (${duration(t.stoppedCoveredSeconds)} din ${duration(t.stoppedSeconds)}). " +
                        "Restul intervalului nu a putut fi calculat."
                }
            hourly.addView(label(covText, 12f, amber))
        }
        hourly.addView(
            label("Viteză 0 include semafoarele și alte opriri. Nu înseamnă poziția P. Călătoria și media continuă.", 12f, muted),
        )
        val trip = card("Călătoria curentă")
        metric(trip, "Distanță măsurată", f(if (imperial) t.distanceKm / 1.609344 else t.distanceKm, 2), if (imperial) "mile" else "km")
        metric(trip, "Combustibil calculat", f(t.liters, 3), "litri")
        metric(trip, "Timp cu date disponibile", duration(t.seconds))
        metric(trip, "Acoperire calcul consum", f(t.coverage, 0), "%")
        trip.addView(
            label(
                "Media apare după 100 m cu date de consum. Intervalele fără date sunt excluse; o acoperire mică face media incompletă.",
                12f,
                muted,
            ),
        )
        action(trip, "Exportă rezumatul călătoriei") { exportTrip() }
        action(trip, "Istoricul călătoriilor") { navigate("Istoric călătorii") }
        action(trip, "Încheie și începe o călătorie nouă") {
            AlertDialog
                .Builder(
                    this,
                ).setTitle(
                    "Călătorie nouă?",
                ).setMessage("Poți exporta rezumatul înainte de resetare. Distanța, timpul și consumul curent vor porni de la zero.")
                .setNegativeButton("Renunță", null)
                .setPositiveButton("Resetează") { _, _ -> engine.resetTrip() }
                .show()
        }
        val chart = card("Evoluția consumului")
        chart.addView(
            LineChart(
                this,
                engine.history.map {
                    Consumption.from(it.obd).per100(it.obd.speed)?.toFloat()
                },
                accent,
                "l/100 km · golurile indică lipsa datelor / sub 5 km/h",
            ),
            LinearLayout.LayoutParams(-1, dp(160)),
        )
        val coach = card("Pentru un consum mai bun")
        coach.addView(label(coaching(d), 14f, ink))
        gap(coach)
        coach.addView(
            label(
                "Accelerează progresiv, anticipează traficul și păstrează o viteză constantă. Evită încălzirea prelungită pe loc. Verifică presiunea anvelopelor conform etichetei mașinii. Nu urmări ecranul în timp ce conduci.",
                13f,
                muted,
            ),
        )
        val technical = card("Datele folosite în calcul")
        metric(technical, "Debit de aer MAF", f(d.maf, 2), "g/s")
        metric(technical, "Debit combustibil ECU", f(d.fuelRate, 2), "l/h")
        metric(technical, "Turație", f(d.rpm, 0), "rpm")
        metric(technical, "Sarcina motorului", f(d.load, 0), "%")
        metric(technical, "Lichid de răcire", temp(d.coolant), tempUnit())
        note(
            "Estimarea MAF presupune benzină, raport aer/combustibil 14,7:1 și densitate 745 g/l. Nu identifică sigur tăierea injecției și poate supraestima în decelerare. La rpm = 0, consumul de benzină este considerat 0. Nu include energia electrică din bateria HV.",
        )
    }

    private fun coaching(d: ObdData): String =
        when {
            !isFresh() -> "Conectează mașina pentru sugestii bazate pe citiri reale."
            d.coolant != null && d.coolant < 65 -> "Motorul este încă rece. Consumul poate fi temporar mai mare în faza de încălzire."
            d.speed == 0f && (d.rpm ?: 0f) > 0 ->
                "Viteza este 0, iar motorul termic funcționează. Vezi separat l/h și litrii acumulați la opriri; " +
                    "încălzirea sau climatizarea pot solicita motorul."
            d.load != null && d.load > 75 ->
                "Sarcina motorului este ridicată. Când traficul și drumul permit, " +
                    "o accelerație mai progresivă poate reduce vârfurile de consum."
            d.speed != null && d.speed > 110 -> "Viteza este ridicată. O viteză moderată și constantă poate reduce consumul."
            d.rpm == 0f -> "Motorul termic este oprit. Mașina poate utiliza energie electrică; aceasta nu este inclusă în l/100 km."
            else -> "Urmărește media pe o distanță mai lungă. O singură valoare instantanee nu descrie eficiența întregii călătorii."
        }

    private fun fuelSourceLabel(c: Consumption): String =
        when (c.source) {
            FuelSource.ECU -> "Debit combustibil citit direct de la ECU"
            FuelSource.MAF -> "ESTIMAT · calcul din MAF și turație"
            FuelSource.ENGINE_STOPPED -> "Motor termic oprit · 0 l/h determinat din turația citită"
            FuelSource.UNAVAILABLE -> "Consum indisponibil · lipsesc citirile necesare"
        }

    private fun battery() {
        val m = hv()
        val overview = card("Baterie de înaltă tensiune")
        twoMetrics(
            overview,
            Triple("Nivel încărcare", f(m?.soc), "% SOC"),
            Triple(
                "Diferență blocuri",
                f(
                    m
                        ?.takeIf {
                            it.blockVolts.isNotEmpty()
                        }?.voltDiff(),
                    3,
                ),
                "V · max − min",
            ),
        )
        gap(overview)
        metric(overview, "Tensiune totală blocuri", f(m?.takeIf { it.blockVolts.isNotEmpty() }?.packVolts(), 2), "V")
        metric(overview, "Curent baterie · semn ECU", f(m?.currentAmps, 2), "A")
        val power = m?.takeIf { it.blockVolts.isNotEmpty() && it.currentAmps.isFinite() }?.let { it.packVolts() * it.currentAmps / 1000 }
        metric(overview, "Putere calculată · semn ECU", f(power, 2), "kW")
        metric(overview, "Baterie auxiliară · ECU HV", f(m?.aux12V, 2), "V")
        overview.addView(
            label("Semnul curentului este păstrat din protocol. Sensul încărcare/descărcare necesită validare pe mașină.", 11f, muted),
        )
        action(overview, "Sănătate baterie / SOH") { navigate("Sănătate baterie") }
        val chart = card("Echilibrul celor 14 blocuri")
        if (m?.blockVolts?.isNotEmpty() == true) {
            chart.addView(HvChart(this, m.blockVolts), LinearLayout.LayoutParams(-1, dp(185)))
            chart.addView(
                label(
                    "Scală relativă · chihlimbar = cel mai mic bloc\nGraficul nu reprezintă un test de sănătate sau capacitate.",
                    11f,
                    muted,
                ),
            )
        } else {
            chart.addView(label("Tensiunile blocurilor sunt indisponibile. Conectează mașina pentru citirea tensiunilor.", 13f, muted))
        }
        val temps = card("Temperaturi baterie")
        (0..2).forEach { metric(temps, "Senzor TB${it + 1}", temp(m?.temps?.getOrNull(it)), tempUnit()) }
        val blocks = card("Detalii pe bloc")
        (0 until 14).forEach { i ->
            metric(
                blocks,
                "Bloc %02d".format(i + 1),
                "${f(m?.blockVolts?.getOrNull(i), 3)} V  ·  ${m?.internalResistance?.getOrNull(i)?.toString() ?: "—"} mΩ",
            )
        }
        if (engine.sample.batteryErrors.isNotEmpty()) {
            note(
                "Citiri lipsă în acest ciclu:\n" + engine.sample.batteryErrors.joinToString("\n"),
                amber,
            )
        }
        note(
            "${engine.layout}\nSuportul Prius Gen 3 este bazat pe protocolul BETSY și teste cu răspunsuri înregistrate/simulate. Necesită validare pe mașina ta. Nivelul SOC nu este procentul de sănătate al bateriei.",
        )
    }

    private fun batteryHealth() {
        val health = card("Sănătate baterie / SOH")
        metric(health, "SOH · capacitate rămasă", "Indisponibil", tint = amber)
        health.addView(
            label(
                "SOC este nivelul de încărcare, nu sănătatea bateriei. ECU-ul nu oferă aici un SOH documentat. " +
                    "Tensiunile și rezistențele nu pot fi transformate corect într-un procent de sănătate.",
                13f,
                muted,
            ),
        )
        gap(health)
        val observation = engine.observation
        metric(health, "Observație pasivă", if (observation.active) "Înregistrare activă" else "Oprită", tint = accent)
        metric(health, "Eșantioane înregistrate", "${observation.count}")
        metric(health, "Diferență maximă observată", f(observation.maxDelta, 3), "V")
        metric(health, "Temperatură maximă observată", temp(observation.hottest), tempUnit())
        metric(health, "SOC observat min / max", "${f(observation.minSoc)} / ${f(observation.maxSoc)}", "%")
        metric(health, "Curent maxim în modul", f(observation.maxAbsCurrent), "A")
        metric(health, "Flux electric total |I|", f(observation.absoluteAh, 3), "Ah")
        metric(health, "Energie totală tranzitată |P|", f(observation.absoluteKwh, 4), "kWh")
        metric(health, "Timp cu date pentru energie", duration(observation.powerCoveredSeconds))
        health.addView(
            label(
                "Valorile cumulează ambele sensuri în modul, prin integrare aproximativă între citiri. Nu reprezintă consum net, capacitate sau SOH.",
                12f,
                muted,
            ),
        )
        if (observation.count > 0 && observation.demo) health.addView(label("Observație DEMO · date simulate", 12f, amber))
        action(health, if (observation.active) "Oprește observația bateriei" else "Începe observația bateriei", true) {
            if (observation.active) {
                observation.stop()
            } else if (!engine.connected) {
                toast("Conectează mașina")
                return@action
            } else {
                observation.start(engine.demo)
            }
            render()
        }
        if (observation.count > 0) {
            action(health, "Exportă observația bateriei") {
                export(observation.report(), "prius-baterie-${System.currentTimeMillis()}.txt", "text/plain")
            }
        }
        gap(health)
        health.addView(
            label(
                "Înregistrează până la 3.000 de eșantioane, fără comenzi de test sau încărcare forțată. " +
                    "Diferențele depind de sarcină, SOC și temperatură. O diferență mică pe loc nu confirmă capacitatea bateriei. " +
                    "Un SOH credibil necesită o metodă de testare validată.",
                12f,
                muted,
            ),
        )
        if (observation.count > 0) {
            val ranges = card("Tensiuni minime și maxime observate")
            observation.maxDeltaRow?.let { row ->
                val context = card("Contextul diferenței maxime")
                context.addView(label(time(row.timestamp), 12f, muted))
                metric(context, "Diferență simultană între blocuri", f(row.delta, 3), "V")
                metric(context, "SOC în acel moment", f(row.soc), "%")
                metric(context, "Curent în acel moment", f(row.current, 2), "A")
            }
            observation.blockRanges.forEach { metric(ranges, "Bloc %02d".format(it.index), "${f(it.min, 3)} – ${f(it.max, 3)}", "V") }
            ranges.addView(
                label(
                    "Extreme observate la momente diferite. Nu sunt diferențe măsurate simultan și nu identifică singure un bloc defect.",
                    12f,
                    muted,
                ),
            )
            val currents = card("Curentul în timpul observației")
            currents.addView(
                LineChart(
                    this,
                    observation.recent().map {
                        it.current
                    },
                    accent,
                    "A · semn păstrat din ECU · ultimele 180 de eșantioane",
                ),
                LinearLayout.LayoutParams(-1, dp(160)),
            )
        }
    }

    private fun diagnostics() {
        val controls = card("Diagnosticare OBD și Toyota")
        val readiness = obd().readiness
        metric(
            controls,
            "Martor MIL · ECU motor",
            readiness?.let { if (it.milOn) "Aprins" else "Stins" } ?: "—",
            tint = if (readiness?.milOn == true) amber else ink,
        )
        metric(controls, "Coduri emisii raportate", readiness?.codeCount?.toString() ?: "—")
        action(controls, "Monitoarele de emisii") { navigate("Emisii OBD") }
        controls.addView(
            label(
                "Se citesc codurile motorului și, când sunt disponibile, codurile hibride și paginile INF. Citirile care nu răspund rămân în raport.",
                13f,
                muted,
            ),
        )
        action(controls, if (engine.scanning) "Scanare în curs…" else "Citește codurile de eroare", true) {
            if (engine.connected) {
                engine.scan()
            } else {
                toast("Conectează adaptorul")
                navigate("Conexiune")
            }
        }
        controls.addView(label(engine.scanProgress, 12f, accent))
        action(controls, "Rapoarte salvate (${store.list().size})") { navigate("Rapoarte") }
        engine.report?.let { showReport(it) }
        note(
            "Codurile indică sisteme sau condiții detectate de ECU, nu confirmă singure piesa defectă. Funcțiile de ștergere, codare și programare nu sunt incluse.",
        )
    }

    private fun showReport(r: ScanReport) {
        val summary = card(if (r.demo) "RAPORT DEMO" else "Raport diagnostic")
        summary.addView(label("${time(r.time)}\n${r.layout}", 12f, muted))
        metric(summary, "Coduri observate", "${r.faults.size}")
        metric(summary, "Citiri cu note", "${r.notes.size}")
        action(summary, "Exportă raportul complet", true) { export(store.text(r), "prius-diagnostic-${r.time}.txt", "text/plain") }
        if (r.faults.isEmpty()) {
            note(
                "Niciun cod în răspunsurile citite. Acest rezultat nu certifică toate ECU-urile; consultă notele privind citirile indisponibile.",
                accent,
            )
        }
        r.faults.forEach { fault ->
            val c = card(fault.code)
            c.addView(label("${fault.source} · ${fault.status}", 11f, amber))
            gap(c)
            c.addView(label(RomanianFaults.description(fault.code), 14f))
        }
        if (r.inf.isNotEmpty()) card("Detalii INF Toyota").addView(label(r.inf.joinToString("\n"), 13f, muted))
        FreezeFrames.decode(r.raw).forEach { frame ->
            val c = card("Condiții memorate · ECU ${frame.ecu} · cadru ${frame.index}")
            c.addView(
                label("La apariția erorii · ${frame.code ?: "DTC indisponibil"}\nSnapshot memorat de ECU, nu valori live.", 12f, amber),
            )
            frame.conditions.forEach { metric(c, it.title, f(it.value), it.unit) }
        }
        if (r.notes.isNotEmpty()) {
            val c = card("Acoperirea scanării / citiri lipsă")
            c.addView(label("Unele mesaje tehnice ECU sunt păstrate în forma originală în raportul exportat.", 12f, muted))
            action(c, "Vezi ${r.notes.size} note tehnice") {
                AlertDialog
                    .Builder(
                        this,
                    ).setTitle("Notele scanării")
                    .setMessage(r.notes.joinToString("\n\n"))
                    .setPositiveButton("Închide", null)
                    .show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectPage() {
        val current = card("Conexiunea curentă")
        current.addView(label(engine.status, 14f, if (engine.connected) accent else muted))
        gap(current, 6)
        current.addView(label(engine.adapter + "\n" + engine.layout, 12f, muted))
        if (engine.connected || engine.connecting) action(current, "Deconectează") { disconnectWithSummary("Deconectare manuală") }
        val lastError = prefs.getString("last_connection_error", "").orEmpty()
        if (lastError.isNotBlank()) {
            val errors = card("Ultima eroare de conexiune")
            errors.addView(label(time(prefs.getLong("last_connection_error_time", 0)), 12f, muted))
            errors.addView(label(lastError, 13f, amber))
            action(errors, "Copiază eroarea") {
                val details = "Prius Monitor ${org.betsy.BuildConfig.BUILD_LABEL}\nAndroid ${Build.VERSION.RELEASE}\n$lastError"
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Eroare OBD", details))
                toast("Eroare copiată")
            }
        }
        val adapters = card("Adaptoare asociate")
        val bluetooth = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            bluetooth == null -> adapters.addView(label("Acest dispozitiv nu are Bluetooth.", 13f, muted))
            !hasBluetoothPermission() -> {
                action(adapters, "Permite accesul Bluetooth", true) { requestBluetoothPermission() }
                action(adapters, "Setările permisiunilor aplicației") {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                }
            }
            !bluetooth.isEnabled ->
                action(
                    adapters,
                    "Pornește Bluetooth",
                    true,
                ) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            else -> {
                val devices = bluetooth.bondedDevices.sortedBy { it.name ?: it.address }
                if (devices.isEmpty()) {
                    adapters.addView(
                        label("Nu există adaptoare asociate. Asociază mai întâi VIECAR în setările Bluetooth ale telefonului.", 13f, muted),
                    )
                }
                devices.forEach { device ->
                    val name = device.name ?: "Adaptor Bluetooth"
                    action(adapters, "$name\n${device.address}", name.contains("VIECAR", true) || name.contains("OBD", true)) {
                        if (device.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                            AlertDialog
                                .Builder(this)
                                .setTitle("Tipul conexiunii pentru $name")
                                .setItems(arrayOf("Bluetooth Classic SPP", "Bluetooth Low Energy (BLE)")) { _, index ->
                                    connectDevice(device, name, index == 1)
                                }.show()
                        } else {
                            connectDevice(device, name, device.type == BluetoothDevice.DEVICE_TYPE_LE)
                        }
                    }
                }
            }
        }
        action(adapters, "Deschide setările Bluetooth") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        action(adapters, "Actualizează lista") { render() }
        val ble = card("Adaptoare BLE din apropiere")
        ble.addView(label(bleDiscovery.message, 13f, muted))
        if (bleDiscovery.active) {
            action(ble, "Oprește căutarea BLE") { bleDiscovery.stop() }
        } else {
            action(ble, "Caută adaptor BLE (15 secunde)") {
                if (!hasBluetoothPermission()) {
                    requestBluetoothPermission()
                } else if (!hasBleScanPermission()) {
                    requestBleScanPermission()
                } else {
                    bleDiscovery.start()
                }
            }
        }
        bleDiscovery.candidates.forEach { candidate ->
            action(
                ble,
                "${candidate.name}\nBLE · ${candidate.device.address} · ${candidate.rssi} dBm",
                BleDiscovery.looksLikeObd(candidate.name),
            ) {
                connectDevice(candidate.device, candidate.name, true)
            }
        }
        ble.addView(
            label(
                "Selectează doar adaptorul tău OBD. Profiluri acceptate: FFF0, FFE0, 18F0 și Nordic UART. " +
                    "Compatibilitatea necesită verificare pe adaptor. " +
                    if (Build.VERSION.SDK_INT <
                        31
                    ) {
                        "Android 8–11 cere permisiunea Locație și serviciile de localizare active pentru căutarea BLE. Aplicația nu citește poziția GPS."
                    } else {
                        "Căutarea folosește permisiunea Dispozitive din apropiere, fără localizare GPS."
                    },
                12f,
                muted,
            ),
        )
        note(
            "1. Introdu adaptorul în portul OBD și pregătește mașina pentru citire.\n2. Pentru Classic, asociază VIECAR în setările Bluetooth. Pentru BLE, folosește căutarea din aplicație.\n3. Închide Car Scanner și alte aplicații OBD înainte de conectare.\n\nMonitorizarea în fundal poate continua cu o notificare permanentă. Oprește conexiunea din aplicație sau din notificare.",
        )
    }

    private fun settingsPage() {
        val units = card("Unități și actualizare")
        action(units, "Viteză și distanță: ${if (imperial) "mph / mile" else "km/h / km"}") {
            AlertDialog
                .Builder(
                    this,
                ).setTitle(
                    "Viteză și distanță",
                ).setSingleChoiceItems(arrayOf("km/h și kilometri", "mph și mile"), if (imperial) 1 else 0) { dialog, which ->
                    imperial =
                        which == 1
                    ; prefs.edit().putBoolean("imperial", imperial).apply()
                    dialog.dismiss()
                    render()
                }.show()
        }
        action(units, "Temperatură: ${tempUnit()}") {
            fahrenheit = !fahrenheit
            prefs.edit().putBoolean("fahrenheit", fahrenheit).apply()
            render()
        }
        action(units, "Interval: ${engine.intervalMs / 1000.0} s") {
            val times = longArrayOf(500, 1000, 2000, 5000)
            AlertDialog
                .Builder(
                    this,
                ).setTitle("Pauză între ciclurile OBD")
                .setItems(arrayOf("0,5 secunde", "1 secundă", "2 secunde", "5 secunde")) { _, i ->
                    engine.intervalMs =
                        times[i]
                    ; prefs.edit().putLong("interval", times[i]).apply()
                    render()
                }.show()
        }
        metric(units, "Limbă", "Română")
        action(units, "Monitorizare în fundal: ${if (prefs.getBoolean("background_monitoring", true)) "pornită" else "oprită"}") {
            val enabled = !prefs.getBoolean("background_monitoring", true)
            prefs.edit().putBoolean("background_monitoring", enabled).apply()
            if (enabled &&
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 93)
            }
            render()
        }
        units.addView(
            label(
                "Monitorizarea în fundal folosește o notificare permanentă cu buton de oprire și poate crește consumul telefonului. " +
                    "Setarea se aplică la următoarea conexiune.",
                12f,
                muted,
            ),
        )
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            action(units, "Permite notificarea conexiunii") { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 93) }
        }
        units.addView(
            label(
                "Intervalul este pauza dintre cicluri. Citirea efectivă depinde de adaptor și numărul parametrilor disponibili. Consumul rămâne în litri.",
                12f,
                muted,
            ),
        )
        val data = card("Date și confidențialitate")
        data.addView(
            label(
                "Datele și rapoartele sunt păstrate pe telefon. Aplicația nu trimite automat date, VIN sau scanări. Exportul folosește destinația aleasă de tine.",
                13f,
                muted,
            ),
        )
        action(data, "Rapoarte de diagnostic") { navigate("Rapoarte") }
        action(data, "Istoricul călătoriilor") { navigate("Istoric călătorii") }
        val lastTrip = java.io.File(filesDir, "ultima-calatorie.txt")
        if (lastTrip.exists()) {
            action(data, "Exportă ultima călătorie încheiată") {
                export(lastTrip.readText(), "prius-ultima-calatorie.txt", "text/plain")
            }
        }
        val lastBattery = java.io.File(filesDir, "ultima-observatie-baterie.txt")
        if (lastBattery.exists()) {
            action(data, "Exportă ultima observație a bateriei") {
                export(lastBattery.readText(), "prius-ultima-observatie-baterie.txt", "text/plain")
            }
        }
        action(data, "Despre Prius Monitor") {
            AlertDialog
                .Builder(this)
                .setTitle("Prius Monitor 1.0")
                .setMessage(
                    "Pentru Toyota Prius 30, cu adaptor ELM327 Bluetooth Classic sau BLE UART acceptat.\n\nBazat pe BETSY de alrighdee și colaboratori, licență MIT. Codul și protocolul original sunt păstrate în proiect.\n\nGen 3 și BLE: implementare testată software, necesită verificare pe mașină și adaptor.\n\nAplicația nu codifică ECU-uri, nu șterge erori și nu certifică sănătatea bateriei.",
                ).setPositiveButton("Închide", null)
                .show()
        }
        action(data, "Licențe open-source") {
            val license = assets.open("licenses/BETSY-MIT.txt").bufferedReader().use { it.readText() }
            val notice = assets.open("licenses/NOTICE.txt").bufferedReader().use { it.readText() }
            AlertDialog
                .Builder(this)
                .setTitle("BETSY · MIT")
                .setMessage(license + "\n\n" + notice)
                .setPositiveButton("Închide", null)
                .show()
        }
        val trace = card("Jurnal tehnic ELM")
        action(trace, "Înregistrare tehnică: ${if (prefs.getBoolean("technical_capture", false)) "pornită" else "oprită"}") {
            prefs.edit().putBoolean("technical_capture", !prefs.getBoolean("technical_capture", false)).apply()
            render()
        }
        trace.addView(
            label(
                "Opțional, pentru verificarea adaptorului și a citirilor lipsă. " +
                    "Păstrează local comenzile și răspunsurile ELM, fără trimitere automată. " +
                    "Se aplică la următoarea conexiune reală. Se păstrează cel mult ultimele 10 sesiuni, maximum 4 MB fiecare. Un export în timpul conexiunii conține datele disponibile la acel moment.",
                12f,
                muted,
            ),
        )
        if (app.captures.lastError.isNotBlank()) trace.addView(label(app.captures.lastError, 12f, amber))
        app.captures.list().forEach { file ->
            action(trace, "Exportă ${file.name}\n${f(file.length() / 1024.0, 1)} KB") {
                exportFile(file, "prius-${file.name}")
            }
        }
    }

    private fun sensorsPage() {
        val extra = card("Mai multe informații OBD")
        val d = obd()
        metric(extra, "Aer admisie", temp(d.intake), tempUnit())
        metric(extra, "Temperatură ambiantă", temp(d.ambient), tempUnit())
        metric(extra, "Accelerație / clapetă", f(d.throttle, 0), "%")
        metric(extra, "Nivel combustibil ECU", f(d.fuelLevel, 0), "%")
        metric(extra, "Corecție scurtă combustibil", f(d.shortTrim), "%")
        metric(extra, "Corecție lungă combustibil", f(d.longTrim), "%")
        metric(extra, "Avans aprindere", f(d.timing), "°")
        metric(extra, "Presiune admisie", f(d.pressure, 0), "kPa")
        metric(extra, "Timp motor de la pornire", d.runtime?.let { duration(it.toDouble()) } ?: "—")
        note("— înseamnă date indisponibile sau expirate, niciodată valoare zero presupusă.")
    }

    private fun emissionsPage() {
        val r = obd().readiness
        val status = card("ECU motor · monitorizare emisii")
        metric(status, "Martor MIL", r?.let { if (it.milOn) "Aprins" else "Stins" } ?: "—", tint = if (r?.milOn == true) amber else ink)
        metric(status, "Coduri emisii raportate", r?.codeCount?.toString() ?: "—")
        if (r == null) {
            status.addView(label("Date indisponibile. ECU-ul poate să nu răspundă acestui parametru sau citirea a expirat.", 13f, muted))
            return
        }
        metric(status, "Monitoare finalizate / suportate", "${r.completeCount} / ${r.supportedCount}")
        status.addView(
            label(
                if (r.sparkIgnition) {
                    "Aprindere prin scânteie · profil benzină"
                } else {
                    "Aprindere prin compresie · detaliile extinse nu sunt implementate pentru acest profil"
                },
                12f,
                muted,
            ),
        )
        val monitors = card("De la ultima ștergere a codurilor")
        r.monitors.forEach { m ->
            val value =
                when {
                    !m.supported -> "Nesuportat"
                    m.complete == true -> "Finalizat"
                    else -> "Neîncheiat"
                }
            metric(monitors, m.title, value, tint = if (m.supported && m.complete != true) amber else muted)
        }
        note(
            "Finalizat înseamnă că ECU-ul a efectuat monitorul, nu că toate sistemele mașinii sunt sănătoase. " +
                "Neîncheiat nu înseamnă automat defecțiune; unele teste cer anumite condiții de rulare. " +
                "Aplicația nu șterge coduri și nu resetează monitoarele.",
        )
    }

    private fun reportsPage() {
        val files = store.list()
        if (files.isEmpty()) note("Nu există rapoarte salvate. Rulează o scanare în Erori, apoi revino aici.")
        files.forEach { file ->
            try {
                val r = store.read(file)
                val c = card((if (r.demo) "DEMO · " else "") + time(r.time))
                c.addView(label("${r.faults.size} coduri · ${r.notes.size} note\n${r.layout}", 12f, muted))
                action(c, "Deschide raportul") {
                    loadedReport = r
                    page = "Raport"
                    scroll.scrollTo(0, 0)
                    render()
                }
            } catch (_: Exception) {
                note("Un raport nu a putut fi citit: ${file.name}", amber)
            }
        }
    }

    private fun reloadTrips() {
        if (historyLoading) return
        historyLoading = true
        app.query({ app.trips.list() }) { result ->
            if (isDestroyed || isFinishing) return@query
            historyLoading = false
            historyLoadedAt = System.currentTimeMillis()
            result.onSuccess { tripRows = it }.onFailure { toast("Istoricul nu a putut fi citit") }
            if (page == "Istoric călătorii") refresh()
        }
    }

    private fun tripHistory() {
        if (!historyLoading && System.currentTimeMillis() - historyLoadedAt > 5000) reloadTrips()
        val controls = card("Jurnalul călătoriilor")
        controls.addView(
            label(
                "Valorile sunt înregistrate automat cât timp există o sesiune activă. " +
                    "Datele Demo sunt etichetate și rămân separate de citirile mașinii.",
                13f,
                muted,
            ),
        )
        action(controls, if (historyLoading) "Se încarcă…" else "Actualizează istoricul") { reloadTrips() }
        if (tripRows.isEmpty() && !historyLoading) note("Nu există încă drumuri înregistrate. Conectează adaptorul.")
        tripRows.forEach { r ->
            val c = card((if (r.demo) "DEMO · " else "") + time(r.started))
            c.addView(label(if (r.ended == null) "Sesiune activă" else r.reason, 11f, if (r.ended == null) accent else muted))
            metric(
                c,
                "Distanță",
                f(if (imperial) r.snapshot.distanceKm / 1.609344 else r.snapshot.distanceKm, 2),
                if (imperial) "mile" else "km",
            )
            metric(c, "Consum mediu", f(r.snapshot.average), "l/100 km")
            metric(c, "Eșantioane", "${r.count}")
            action(c, "Deschide călătoria") { openTrip(r.id) }
        }
    }

    private fun openTrip(
        id: Long,
        restoredY: Int = 0,
    ) {
        val navigation = navigationVersion
        val request = ++tripLoadVersion
        app.query({ app.trips.get(id) to app.trips.samples(id) }) { result ->
            if (isDestroyed || isFinishing || navigation != navigationVersion || request != tripLoadVersion) return@query
            result
                .onSuccess { (trip, samples) ->
                    if (trip == null) {
                        toast("Călătoria nu mai există")
                        return@onSuccess
                    }
                    selectedTrip = trip
                    selectedSamples = samples
                    navigate("Călătorie")
                    scroll.post { scroll.scrollTo(0, restoredY) }
                }.onFailure { toast("Datele călătoriei nu au putut fi citite") }
        }
    }

    private fun tripDetails() {
        val r = selectedTrip ?: return
        val t = r.snapshot
        val summary = card(if (r.demo) "CĂLĂTORIE DEMO" else "Călătorie OBD")
        summary.addView(label("${time(r.started)}\n${r.adapter}\n${r.layout}", 12f, muted))
        metric(summary, "Încheiată", r.ended?.let { time(it) } ?: "În curs")
        metric(summary, "Distanță măsurată", f(if (imperial) t.distanceKm / 1.609344 else t.distanceKm, 2), if (imperial) "mile" else "km")
        metric(summary, "Consum mediu", f(t.average), "l/100 km", accent)
        metric(summary, "Combustibil calculat", f(t.liters, 3), "litri")
        metric(summary, "Timp cu date", duration(t.seconds))
        metric(summary, "Acoperire consum", f(t.coverage, 0), "%")
        run {
            val stCov = t.stoppedCoverage
            val stLabel =
                when {
                    t.stoppedSeconds < 1 -> f(null)
                    stCov < 0.5 -> "date lipsă"
                    else -> f(t.stoppedLiters, 3)
                }
            val stUnit = if (t.stoppedSeconds < 1 || stCov < 0.5) "" else "litri"
            metric(summary, "La viteză 0", stLabel, stUnit)
        }
        metric(summary, "Timp la viteză 0", duration(t.stoppedSeconds))
        if (t.stoppedSeconds >= 1 && t.stoppedCoverage < 99.5) {
            summary.addView(
                label(
                    if (t.stoppedCoverage < 0.5) {
                        "Date de consum lipsă la opriri — 0 l/h neconfirmat."
                    } else {
                        "Acoperire opriri: ${f(
                            t.stoppedCoverage,
                            0,
                        )} % (${duration(t.stoppedCoveredSeconds)} / ${duration(t.stoppedSeconds)})."
                    },
                    12f,
                    amber,
                ),
            )
        }
        metric(summary, "Eșantioane păstrate", "${r.count}")
        summary.addView(
            label(
                if (t.estimated) "Consumul include estimare din MAF." else "Debit ECU și/sau motor termic oprit, conform citirilor.",
                12f,
                amber,
            ),
        )
        action(summary, "Exportă toate eșantioanele CSV", true) {
            export("", "prius-calatorie-${r.id}.csv", "text/csv", r.id)
        }
        action(summary, "Exportă rezumatul în text") { export(recordedTripText(r), "prius-calatorie-${r.id}.txt", "text/plain") }
        action(summary, "Actualizează datele călătoriei") { openTrip(r.id) }
        val speedChart = card("Viteza înregistrată")
        speedChart.addView(
            LineChart(
                this,
                selectedSamples.map { it.obd.speed?.let { v -> if (imperial) v / 1.609344f else v } },
                accent,
                "Ultimele ${selectedSamples.size} eșantioane · ${speedUnit()}",
            ),
            LinearLayout.LayoutParams(-1, dp(170)),
        )
        val fuelChart = card("Consumul înregistrat")
        fuelChart.addView(
            LineChart(
                this,
                selectedSamples.map { Consumption.from(it.obd).per100(it.obd.speed)?.toFloat() },
                amber,
                "l/100 km · goluri = lipsă date / viteză sub 5 km/h",
            ),
            LinearLayout.LayoutParams(-1, dp(170)),
        )
        val socChart = card("Nivelul bateriei HV")
        socChart.addView(
            LineChart(
                this,
                selectedSamples.map { it.battery?.soc?.takeIf { v -> v.isFinite() } },
                accent,
                "% SOC · nivel de încărcare, nu SOH",
            ),
            LinearLayout.LayoutParams(-1, dp(170)),
        )
        val end = card("Despre această înregistrare")
        end.addView(
            label(
                "${r.reason.ifBlank { "Călătorie în curs" }}\nGraficele folosesc cel mult ultimele 1.800 de eșantioane. " +
                    "Exportul CSV conține toate eșantioanele păstrate, cu unități explicite, marcaj Demo și celule goale pentru parametrii indisponibili.",
                12f,
                muted,
            ),
        )
        if (r.ended != null) {
            action(end, "Șterge această călătorie") {
                AlertDialog
                    .Builder(this)
                    .setTitle("Ștergi călătoria?")
                    .setMessage(
                        "Se vor șterge rezumatul și toate eșantioanele acestei călătorii din telefon. Fișierele exportate rămân la destinația aleasă.",
                    ).setNegativeButton("Renunță", null)
                    .setPositiveButton("Șterge") { _, _ ->
                        app.query({ app.trips.delete(r.id) }) { result ->
                            if (isDestroyed) return@query
                            result
                                .onSuccess {
                                    historyLoadedAt = 0
                                    selectedTrip = null
                                    navigate("Istoric călătorii")
                                }.onFailure { toast("Ștergerea a eșuat") }
                        }
                    }.show()
            }
        }
    }

    private fun recordedTripText(r: RecordedTrip): String =
        buildString {
            val t = r.snapshot
            appendLine("PRIUS MONITOR — CĂLĂTORIE ${r.id}")
            appendLine(if (r.demo) "DEMO · DATE SIMULATE" else "Citiri OBD / calcule")
            appendLine("Pornire: ${time(r.started)}")
            appendLine("Sfârșit: ${r.ended?.let { time(it) } ?: "în curs"}")
            appendLine(r.layout)
            appendLine("Distanță: ${f(t.distanceKm, 3)} km")
            appendLine("Combustibil: ${f(t.liters, 3)} l; mediu: ${f(t.average, 2)} l/100 km")
            val stTxt =
                when {
                    t.stoppedSeconds < 1 -> "nicio oprire"
                    t.stoppedCoverage < 0.5 -> "date lipsă (acoperire opriri 0 %)"
                    t.stoppedCoverage < 99.5 ->
                        "${f(t.stoppedLiters, 3)} l (acoperire opriri ${f(t.stoppedCoverage, 0)} %)"
                    else -> "${f(t.stoppedLiters, 3)} l"
                }
            appendLine("La viteză 0: $stTxt în ${duration(t.stoppedSeconds)}")
            appendLine("Acoperire consum: ${f(t.coverage, 1)} %; eșantioane: ${r.count}")
            appendLine(if (t.estimated) "Include estimări MAF." else "Debit ECU / motor termic oprit.")
            appendLine("Motiv încheiere: ${r.reason}")
        }

    private fun hasBluetoothPermission() =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasBleScanPermission() =
        checkSelfPermission(
            if (Build.VERSION.SDK_INT >=
                31
            ) {
                Manifest.permission.BLUETOOTH_SCAN
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            },
        ) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestBleScanPermission() {
        val permissions =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        requestPermissions(permissions, 94)
    }

    @SuppressLint("MissingPermission")
    private fun connectDevice(
        device: BluetoothDevice,
        name: String,
        ble: Boolean,
    ) {
        if (engine.connecting) return
        bleDiscovery.stop(false)
        if (Build.VERSION.SDK_INT >= 33 &&
            prefs.getBoolean("background_monitoring", true) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_asked", false)
        ) {
            prefs.edit().putBoolean("notification_asked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 93)
        }
        app.connect(device, name, ble)?.let { toast(it) }
        prefs
            .edit()
            .putString("adapter", device.address)
            .putBoolean("adapter_ble", ble)
            .apply()
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >=
            31
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 91)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        render()
    }

    private fun time(ms: Long) = SimpleDateFormat("dd.MM.yyyy · HH:mm:ss", Locale("ro")).format(Date(ms))

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun tripText(): String =
        buildString {
            val t = engine.sample.trip
            appendLine("PRIUS MONITOR — CĂLĂTORIE")
            appendLine(if (engine.demo) "DEMO · DATE SIMULATE" else "Date OBD / calcule")
            appendLine(time(System.currentTimeMillis()))
            appendLine("Distanță: ${f(t.distanceKm, 3)} km")
            appendLine("Combustibil: ${f(t.liters, 3)} l")
            appendLine("Mediu: ${f(t.average, 2)} l/100 km")
            appendLine("Durată cu date: ${duration(t.seconds)}")
            appendLine("Acoperire consum: ${f(t.coverage, 1)} %")
            appendLine("La viteză 0: ${f(t.stoppedLiters, 3)} l / ${duration(t.stoppedSeconds)}")
            appendLine("Sursă consum: ${if (t.estimated) "include estimare din MAF" else "debit ECU / motor termic oprit"}")
            appendLine("Lipsa datelor exclude intervalele din calcul. Nu este o măsurare certificată.")
        }

    private fun exportTrip() {
        export(tripText(), "prius-calatorie-${System.currentTimeMillis()}.txt", "text/plain")
    }

    private fun export(
        text: String,
        name: String,
        mime: String,
        csvTrip: Long? = null,
    ) {
        if (documentPickerOpen || exportPreparing) return
        val token =
            try {
                pendingExports.stage(text)
            } catch (_: Exception) {
                toast("Exportul nu a putut fi pregătit. Verifică spațiul liber.")
                return
            }
        launchPreparedExport(token, name, mime, csvTrip)
    }

    private fun exportFile(
        file: java.io.File,
        name: String,
    ) {
        if (documentPickerOpen || exportPreparing) return
        exportPreparing = true
        toast("Pregătesc exportul jurnalului…")
        app.query({ pendingExports.stageFile(file) }) { result ->
            exportPreparing = false
            if (isDestroyed || isFinishing || !uiVisible) {
                result.getOrNull()?.let { pendingExports.discard(it) }
                return@query
            }
            result
                .onSuccess { launchPreparedExport(it, name, "text/plain") }
                .onFailure { toast("Jurnalul nu a putut fi pregătit pentru export") }
        }
    }

    private fun launchPreparedExport(
        token: String,
        name: String,
        mime: String,
        csvTrip: Long? = null,
    ) {
        exportToken = token
        pendingTripCsv = csvTrip
        documentPickerOpen = true
        try {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = mime
                    putExtra(Intent.EXTRA_TITLE, name)
                },
                92,
            )
        } catch (_: Exception) {
            documentPickerOpen = false
            pendingTripCsv = null
            exportToken = null
            pendingExports.discard(token)
            toast("Selectorul de fișiere nu este disponibil pe acest telefon.")
        }
    }

    override fun onActivityResult(
        request: Int,
        result: Int,
        intent: Intent?,
    ) {
        super.onActivityResult(request, result, intent)
        if (request == 92) documentPickerOpen = false
        val csvTrip = if (request == 92) pendingTripCsv else null
        val token = if (request == 92) exportToken else null
        if (request == 92) pendingTripCsv = null
        if (request == 92) exportToken = null
        if (request == 92 && result == RESULT_OK && intent?.data != null) {
            intent?.data?.let { uri ->
                app.query({
                    try {
                        val text =
                            if (csvTrip ==
                                null
                            ) {
                                pendingExports.read(token ?: error("Conținutul exportului nu mai este disponibil"))
                            } else {
                                null
                            }
                        val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Destinație indisponibilă")
                        stream.bufferedWriter().use { writer ->
                            if (csvTrip != null) app.trips.exportCsv(csvTrip, writer) else writer.write(text!!)
                        }
                    } finally {
                        token?.let { pendingExports.discard(it) }
                    }
                }) { outcome ->
                    if (isDestroyed) return@query
                    outcome
                        .onSuccess { toast("Fișier exportat") }
                        .onFailure { toast("Exportul a eșuat. Alege o altă destinație.") }
                }
            }
        } else if (request == 92) {
            token?.let { pendingExports.discard(it) }
        }
    }

    private fun disconnectWithSummary(reason: String) {
        saveSummary(reason)
        engine.disconnect(reason)
    }

    private fun saveSummary(reason: String) {
        if (engine.observation.count > 0) {
            try {
                java.io.File(filesDir, "ultima-observatie-baterie.txt").writeText(engine.observation.report())
            } catch (
                _: Exception,
            ) {
                toast("Observația bateriei nu a putut fi salvată")
            }
        }
        if (engine.sample.time > 0) {
            try {
                java.io.File(filesDir, "ultima-calatorie.txt").writeText(tripText() + "Context salvare: $reason\n")
            } catch (
                _: Exception,
            ) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        uiVisible = true
        if (::body.isInitialized) render()
    }

    override fun onStop() {
        super.onStop()
        uiVisible = false
        bleDiscovery.stop(false)
        // The document picker temporarily backgrounds this activity; preserve the trip text
        // before closing Bluetooth, then let the export complete on return.
        val canContinue = !engine.demo && app.backgroundRequested
        if (!documentPickerOpen && !canContinue && (engine.connected || engine.connecting)) {
            disconnectWithSummary("Aplicația a trecut în fundal")
        } else if (canContinue && engine.sample.time > 0) {
            saveSummary("Monitorizare activă în fundal")
        }
    }

    override fun onDestroy() {
        engine.onChanged = null
        engine.onReport = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        when (page) {
            "Sănătate baterie" -> navigate("Baterie")
            "Raport", "Rapoarte" -> navigate("Erori")
            "Călătorie" -> navigate("Istoric călătorii")
            "Istoric călătorii" -> navigate("Consum")
            "Emisii OBD" -> navigate("Erori")
            "Acasă" -> super.onBackPressed()
            else -> navigate("Acasă")
        }
    }
}
