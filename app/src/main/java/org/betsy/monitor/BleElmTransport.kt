package org.betsy.monitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import org.betsy.transport.TransportException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One outstanding GATT operation, bounded waits and prompt-framed ASCII ELM replies. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleElmTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : ConnectableTransport {
    private data class Event(
        val kind: String,
        val status: Int,
        val detail: String = "",
    )

    private val events = ArrayBlockingQueue<Event>(16)
    private val inbox = ElmReplyInbox()

    @Volatile private var closed = false

    @Volatile private var gatt: BluetoothGatt? = null

    @Volatile private var notify: BluetoothGattCharacteristic? = null

    @Volatile private var write: BluetoothGattCharacteristic? = null
    override var readTimeoutMs = 2500
    var profileLabel = "BLE UART"
        private set

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                g: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (closed) return
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    event(Event("connected", status))
                } else if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                    abort("Conexiunea BLE s-a închis (cod $status)")
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                status: Int,
            ) {
                event(Event("services", status))
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int,
            ) {
                if (d.uuid == BleUartProfiles.cccd) event(Event("descriptor", status))
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (c.uuid == write?.uuid) event(Event("write", status))
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < 33 && c.uuid == notify?.uuid) inbox.receive(c.value?.copyOf() ?: byteArrayOf())
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (c.uuid == notify?.uuid) inbox.receive(value)
            }
        }

    private fun event(value: Event) {
        if (!closed && !events.offer(value)) abort("Prea multe evenimente BLE; reconectează adaptorul")
    }

    private fun abort(reason: String) {
        inbox.fail(reason)
        events.clear()
        events.offer(Event("error", -1, reason))
        close()
    }

    private fun await(
        kind: String,
        timeoutMs: Long,
        allowLegacyCccd: Boolean = false,
    ) {
        val event =
            events.poll(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw TransportException("Timp depășit la etapa BLE: $kind")
        if (event.kind == kind && allowLegacyCccd && BleNotificationPolicy.canVerifyWithoutCccd(event.status)) {
            // Some UART clones reject the CCCD write but already emit notifications.
            // Local routing is enabled; ATZ/configure must still succeed before connecting.
            profileLabel += " · CCCD compatibil"
            return
        }
        if (event.kind != kind || event.status != BluetoothGatt.GATT_SUCCESS) {
            throw TransportException(event.detail.ifBlank { "Etapa BLE $kind a eșuat (cod ${event.status})" })
        }
    }

    override fun connect() {
        try {
            check(!closed) { "Conexiune închisă" }
            val link =
                device.connectGatt(context.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw TransportException("Android nu a putut crea legătura BLE")
            synchronized(this) {
                if (closed) {
                    link.close()
                    throw TransportException("Conexiune anulată")
                }
                gatt = link
            }
            await("connected", 18000)
            if (!link.discoverServices()) throw TransportException("Descoperirea serviciilor BLE a fost refuzată")
            await("services", 8000)
            val match =
                BleUartProfiles.all.firstNotNullOfOrNull { p ->
                    val service = link.getService(p.service) ?: return@firstNotNullOfOrNull null
                    val rx = service.getCharacteristic(p.notify) ?: return@firstNotNullOfOrNull null
                    val tx = service.getCharacteristic(p.write) ?: return@firstNotNullOfOrNull null
                    val readable =
                        rx.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) !=
                            0
                    val writable =
                        tx.properties and
                            (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) !=
                            0
                    if (readable && writable) Triple(p, rx, tx) else null
                }
                    ?: throw TransportException(
                        "Adaptorul nu oferă un profil BLE UART acceptat (FFF0 / FFE0 / 18F0 / Nordic). Folosește SPP dacă există.",
                    )
            profileLabel = match.first.label
            notify = match.second
            write = match.third
            if (!link.setCharacteristicNotification(
                    match.second,
                    true,
                )
            ) {
                throw TransportException("Notificările BLE nu au putut fi activate")
            }
            val descriptor =
                match.second.getDescriptor(BleUartProfiles.cccd)
                    ?: throw TransportException("Profilul BLE nu are descriptorul pentru notificări")
            val value =
                if (match.second.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
            val started =
                if (Build.VERSION.SDK_INT >= 33) {
                    link.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    descriptor.value = value
                    link.writeDescriptor(descriptor)
                }
            if (!started) throw TransportException("Activarea notificărilor BLE a fost refuzată")
            await("descriptor", 6000, allowLegacyCccd = true)
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun send(cmd: String): String {
        check(!closed) { "Conexiune BLE închisă" }
        val link = gatt ?: throw TransportException("Legătură BLE indisponibilă")
        val characteristic = write ?: throw TransportException("Canal BLE indisponibil")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(readTimeoutMs.toLong())

        fun remaining() =
            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).takeIf { it > 0 }
                ?: throw TransportException("Adaptorul BLE nu a răspuns la $cmd")
        try {
            inbox.begin(cmd)
            val type =
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                }
            // Default ATT MTU is 23, leaving 20 data bytes. No MTU negotiation assumption.
            for (chunk in (cmd + "\r").toByteArray(Charsets.US_ASCII).toList().chunked(20)) {
                val bytes = chunk.toByteArray()
                val started =
                    if (Build.VERSION.SDK_INT >= 33) {
                        link.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
                    } else {
                        characteristic.writeType = type
                        characteristic.value = bytes
                        link.writeCharacteristic(characteristic)
                    }
                if (!started) throw TransportException("Scrierea BLE a fost refuzată")
                await("write", remaining())
            }
            return inbox.await(remaining())
        } catch (e: Exception) {
            // Late notifications must never become the next PID's response.
            close()
            throw e
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        inbox.fail("Conexiune BLE închisă")
        events.offer(Event("error", -1, "Conexiune BLE închisă"))
        val link = gatt
        gatt = null
        runCatching { link?.disconnect() }
        runCatching { link?.close() }
    }
}
