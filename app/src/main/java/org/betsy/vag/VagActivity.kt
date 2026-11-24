package org.betsy.vag

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.monitor.BleDiscovery
import org.betsy.monitor.BleElmTransport
import org.betsy.monitor.DeadlineBluetoothTransport

@SuppressLint("MissingPermission", "SetTextI18n")
class VagActivity : Activity() {
    private val engine get() = (application as VagApplication).vag
    private val prefs by lazy { getSharedPreferences("vag", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var discovery: BleDiscovery
    private var tab = "Live"
    private var rows = mutableMapOf<String, TextView>()
    private var detail: TextView? = null
    private var catalogue = emptyList<VagQuery>()
    private val selected = linkedSetOf<String>()
    private var bleDialog: AlertDialog? = null
    private val refresh =
        object : Runnable {
            override fun run() {
                status.text = engine.status + if (engine.busy) " · operație în curs…" else ""
                if (tab == "Live") updateLive()
                if (tab == "Erori") detail?.text = engine.report
                handler.postDelayed(this, 750)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(12, 21, 31)
        window.navigationBarColor = Color.rgb(12, 21, 31)
        tab = savedInstanceState?.getString("tab") ?: "Live"
        discovery = BleDiscovery(this) { updateBleDialog() }
        loadProfile()
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 16, 20, 8)
                setBackgroundColor(Color.rgb(12, 21, 31))
            }
        setContentView(root)
        root.addView(label("VAG MONITOR", 26))
        root.addView(label("PASSAT B8 · 1.6 TDI", 14))
        status = label(engine.status, 13)
        root.addView(status)
        val connect = LinearLayout(this)
        connect.addView(button("Conectare") { connections() }, LinearLayout.LayoutParams(0, 52.dp(), 1f))
        connect.addView(
            button("Oprește") {
                engine.disconnect()
                stopService(Intent(this, VagService::class.java))
            },
            LinearLayout.LayoutParams(0, 52.dp(), 1f),
        )
        root.addView(connect)
        val tabs = LinearLayout(this)
        listOf("Live", "Senzori", "Erori", "Service").forEach { name ->
            tabs.addView(
                button(name) {
                    tab = name
                    render()
                },
                LinearLayout.LayoutParams(0, 50.dp(), 1f),
            )
        }
        root.addView(tabs)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        render()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun label(
        text: String,
        size: Int = 16,
    ) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(Color.rgb(220, 233, 244))
        setPadding(8, 12, 8, 12)
    }

    private fun button(
        text: String,
        action: () -> Unit,
    ) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        setOnClickListener { action() }
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        discovery.stop(false)
        bleDialog?.dismiss()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tab", tab)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        content.removeAllViews()
        rows.clear()
        detail = null
        when (tab) {
            "Live" -> {
                content.addView(
                    label("Date reale · valorile mai vechi de 15 secunde expiră. Selectează puțini senzori pentru actualizare rapidă.", 13),
                )
                updateLive()
            }
            "Senzori" -> sensors()
            "Erori" -> {
                content.addView(button("Citește erorile OBD + module VAG") { engine.scan() })
                content.addView(
                    button("Șterge erorile motorului OBD") {
                        confirm(
                            "Ștergere OBD motor",
                            "Se șterg codurile de emisii, freeze-frame și se resetează readiness. Codurile permanente rămân până la verificarea ECU. Contact pus, motor oprit.",
                        ) {
                            engine.clearEngine()
                        }
                    },
                )
                content.addView(
                    button("Șterge erorile unui modul UDS…") {
                        AlertDialog
                            .Builder(this)
                            .setTitle("Alege modulul")
                            .setItems(VagEngine.modules.map { it.third }.toTypedArray()) { _, i ->
                                val m = VagEngine.modules[i]
                                confirm(
                                    "Șterge DTC · ${m.third}",
                                    "Se solicită ștergerea tuturor DTC din acest modul. Defecțiunile active pot reveni. Contact pus, motor oprit.",
                                ) {
                                    engine.clearModule(m.first)
                                }
                            }.show()
                    },
                )
                content.addView(
                    button("Distribuie raportul") {
                        startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, engine.report)
                                },
                                "Raport VAG",
                            ),
                        )
                    },
                )
                detail =
                    label(engine.report, 14).also {
                        it.setTextIsSelectable(true)
                        content.addView(it)
                    }
            }
            else -> {
                content.addView(label("Inspecție și schimb ulei", 23))
                content.addView(
                    label(
                        "Resetarea service-ului nu este încă activată. Passat B8 folosește adaptări specifice calculatorului de bord; nu există o comandă universală pentru gama VAG.",
                    ),
                )
                content.addView(button("Identifică ECU motor + instrumente") { engine.identify() })
                content.addView(
                    button("Vezi identificarea / raportul") {
                        tab = "Erori"
                        render()
                    },
                )
                content.addView(
                    label(
                        "După citire, distribuie raportul cu numărul piesei și versiunea software. Pe baza lor se poate adăuga o procedură verificată pentru inspecție și ulei. Aplicația nu trimite adaptări sau rutine de resetare neverificate.",
                        14,
                    ),
                )
                content.addView(label("Compatibilitate", 22))
                content.addView(
                    label(
                        "OBD CAN pentru VW, Audi, Škoda, SEAT și CUPRA care oferă acești parametri. Catalogul extins inclus este pentru Passat; nu îl aplica altor modele. K-line, TP2.0, DoIP și deblocarea SFD nu sunt implementate.",
                        14,
                    ),
                )
                content.addView(
                    label(
                        "Android Auto: ecran experimental de telemetrie; vizibilitatea depinde de host și distribuție. Nu este certificat pentru Google Play. Conectarea și diagnoza se fac de pe telefon.",
                        14,
                    ),
                )
                content.addView(
                    label(
                        "Definiții: OBDb/Volkswagen-Passat, CC BY-SA 4.0. Interpretările comunitare necesită validare pe ECU-ul mașinii.",
                        13,
                    ),
                )
            }
        }
    }

    private fun updateLive() {
        engine.values.forEach { reading ->
            val row = rows.getOrPut(reading.signal.id) { label("", 18).also { content.addView(it) } }
            row.text = "${reading.signal.name}\n${reading.display()}"
        }
        val current = engine.values.map { it.signal.id }.toSet()
        rows.keys
            .filter { it !in current }
            .toList()
            .forEach { content.removeView(rows.remove(it)) }
        if (engine.values.isEmpty() &&
            detail == null
        ) {
            detail = label("Conectează adaptorul pentru a vedea parametrii disponibili.").also { content.addView(it) }
        }
        if (engine.values.isNotEmpty()) {
            detail?.let { content.removeView(it) }
            detail = null
        }
    }

    private fun loadProfile() {
        val year = prefs.getInt("year", 0)
        catalogue =
            if (year in
                2014..2023
            ) {
                VagCatalog.passat(assets.open("vag/passat.json").bufferedReader().use { it.readText() }, year)
            } else {
                emptyList()
            }
        selected.clear()
        selected.addAll(prefs.getStringSet("queries", emptySet()) ?: emptySet())
        selected.retainAll(catalogue.map { it.key }.toSet())
    }

    private fun sensors() {
        val year = prefs.getInt("year", 0)
        content.addView(
            button(if (year == 0) "Alege anul modelului Passat B8" else "Passat B8 · an model $year · schimbă") {
                val years = (2014..2023).toList()
                AlertDialog
                    .Builder(
                        this,
                    ).setTitle("Anul modelului (din acte)")
                    .setItems(years.map { it.toString() }.toTypedArray()) { _, index ->
                        prefs
                            .edit()
                            .putInt("year", years[index])
                            .remove("queries")
                            .apply()
                        loadProfile()
                        engine.select(emptyList())
                        render()
                    }.show()
            },
        )
        content.addView(
            label(
                "${engine.supportedPids.size} PID-uri OBD anunțate. ${catalogue.size} cereri Passat în catalog; prezența în catalog nu confirmă suportul ECU. Maximum 12 cereri extinse simultan.",
                13,
            ),
        )
        if (catalogue.isEmpty()) return
        content.addView(
            button("Preset ulei + DPF (experimental)") {
                selected.clear()
                selected +=
                    catalogue
                        .filter {
                            it.header == "7E0" &&
                                it.command in
                                listOf(
                                    "22179C",
                                    "221ABA",
                                    "221ABE",
                                    "222609",
                                    "221AD4",
                                )
                        }.map { it.key }
                saveSelection()
                render()
            },
        )
        val search =
            EditText(this).apply {
                hint = "Caută oil, DPF, temperature…"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                setSingleLine()
            }
        content.addView(search)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        fun filter(term: String) {
            list.removeAllViews()
            catalogue
                .filter { q ->
                    term.isBlank() || q.signals.any { it.name.contains(term, true) } || q.key.contains(term, true)
                }.take(100)
                .forEach { q ->
                    list.addView(
                        button("${if (q.key in selected) "✓ " else "+ "}${q.signals.first().name} · ${q.key}") {
                            if (q.key in selected) {
                                selected.remove(q.key)
                            } else if (selected.size < 12) {
                                selected.add(q.key)
                            } else {
                                AlertDialog
                                    .Builder(
                                        this,
                                    ).setMessage(
                                        "Selectează maximum 12 cereri pentru a păstra actualizarea utilă.",
                                    ).setPositiveButton("OK", null)
                                    .show()
                                return@button
                            }
                            saveSelection()
                            filter(term)
                        },
                    )
                }
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    filter(s.toString())
                }

                override fun afterTextChanged(s: Editable?) {}
            },
        )
        filter("")
    }

    private fun saveSelection() {
        prefs.edit().putStringSet("queries", selected.toSet()).apply()
        engine.select(catalogue.filter { it.key in selected })
    }

    private fun confirm(
        title: String,
        message: String,
        action: () -> Unit,
    ) {
        if (!engine.connected ||
            engine.busy
        ) {
            AlertDialog
                .Builder(
                    this,
                ).setMessage("Conectează adaptorul și așteaptă încheierea operației curente.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog
            .Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton("Anulează", null)
            .setPositiveButton("Confirm ștergerea") { _, _ -> action() }
            .show()
    }

    private fun connections() {
        val permissions =
            if (Build.VERSION.SDK_INT >=
                31
            ) {
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(permissions, 7)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter?.isEnabled != true) {
            startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val devices = adapter.bondedDevices.toList()
        AlertDialog
            .Builder(this)
            .setTitle("Același adaptor ca la Prius")
            .setItems((devices.map { "${it.name ?: "Bluetooth"} · ${it.address}" } + "Caută Bluetooth BLE…").toTypedArray()) { _, index ->
                if (index == devices.size) {
                    showBle()
                    discovery.start()
                } else {
                    connect(devices[index], false)
                }
            }.setNeutralButton(
                "Setări Bluetooth",
            ) { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .show()
    }

    private fun showBle() {
        bleDialog =
            AlertDialog
                .Builder(this)
                .setTitle("Adaptoare BLE")
                .setItems(emptyArray<String>()) { _, _ -> }
                .setNegativeButton("Închide") { _, _ -> discovery.stop(false) }
                .create()
        bleDialog?.show()
    }

    private fun updateBleDialog() {
        val dialog = bleDialog ?: return
        dialog.setTitle(discovery.message)
        val devices = discovery.candidates
        dialog.listView.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, devices.map { "${it.name} · ${it.device.address}" })
        dialog.listView.setOnItemClickListener { _, _, index, _ ->
            discovery.stop(false)
            dialog.dismiss()
            connect(devices[index].device, true)
        }
    }

    private fun connect(
        device: BluetoothDevice,
        ble: Boolean,
    ) {
        try {
            startForegroundService(Intent(this, VagService::class.java))
            engine.connect(catalogue.filter { it.key in selected }) {
                if (ble) BleElmTransport(applicationContext, device) else DeadlineBluetoothTransport(device)
            }
            tab = "Live"
            render()
        } catch (e: Exception) {
            engine.disconnect()
            stopService(Intent(this, VagService::class.java))
            AlertDialog
                .Builder(this)
                .setMessage("Conectarea nu a pornit: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
