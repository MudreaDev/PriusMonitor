package org.betsy.monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

data class BleCandidate(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int,
)

/** Explicit foreground-only 15-second scan. Results stay in memory and are never uploaded. */
@SuppressLint("MissingPermission")
class BleDiscovery(
    private val context: Context,
    private val changed: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val found = linkedMapOf<String, BleCandidate>()
    private var scanner: BluetoothLeScanner? = null
    private var callback: ScanCallback? = null
    private var generation = 0
    private val timeout = Runnable { stop() }
    private val refresh = Runnable { if (active) changed() }
    var active = false
        private set
    var message = "Căutare opțională pentru adaptoare Bluetooth Low Energy."
        private set
    val candidates: List<BleCandidate> get() =
        found.values.sortedWith(
            compareByDescending<BleCandidate> {
                looksLikeObd(it.name)
            }.thenByDescending { it.rssi },
        )

    fun start() {
        if (active) return
        found.clear()
        val id = ++generation
        try {
            val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
            val scan = bluetooth?.bluetoothLeScanner ?: error("Bluetooth BLE nu este disponibil sau este oprit")
            val listener =
                object : ScanCallback() {
                    override fun onScanResult(
                        callbackType: Int,
                        result: ScanResult,
                    ) {
                        accept(result)
                    }

                    override fun onBatchScanResults(results: MutableList<ScanResult>) {
                        results.forEach { accept(it) }
                    }

                    private fun accept(result: ScanResult) {
                        handler.post {
                            if (id != generation || !active) return@post
                            val device = result.device
                            val address = device.address
                            if (found.size >= 40 && address !in found) return@post
                            val name =
                                result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: "Dispozitiv BLE fără nume"
                            found[address] = BleCandidate(device, name, result.rssi)
                            handler.removeCallbacks(refresh)
                            handler.postDelayed(refresh, 250)
                        }
                    }

                    override fun onScanFailed(errorCode: Int) {
                        handler.post {
                            if (id == generation) {
                                stop(false)
                                message = "Căutarea BLE a eșuat (cod $errorCode). Așteaptă puțin și reîncearcă."
                                changed()
                            }
                        }
                    }
                }
            scanner = scan
            callback = listener
            active = true
            message = "Caut adaptoare BLE timp de 15 secunde…"
            scan.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), listener)
            handler.postDelayed(timeout, 15000)
        } catch (e: Exception) {
            stop(false)
            message = "Căutarea BLE nu a pornit: ${e.message ?: e.javaClass.simpleName}"
        }
        changed()
    }

    fun stop(notify: Boolean = true) {
        generation++
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(refresh)
        callback?.let { runCatching { scanner?.stopScan(it) } }
        callback = null
        scanner = null
        if (active) {
            message =
                if (found.isEmpty()) {
                    "Niciun dispozitiv găsit. Verifică adaptorul, permisiunile și Bluetooth."
                } else {
                    "Căutare încheiată · ${found.size} dispozitive"
                }
        }
        active = false
        if (notify) changed()
    }

    companion object {
        fun looksLikeObd(name: String) = listOf("viecar", "obd", "elm", "vgate", "vlink", "icar").any { name.contains(it, true) }
    }
}
