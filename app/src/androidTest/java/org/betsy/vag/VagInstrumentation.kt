package org.betsy.vag

import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.betsy.monitor.ConnectableTransport
import java.util.Collections

/** Synthetic ECU only in the test APK; production has no simulated-value path. */
class VagInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun waitFor(
        message: String,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + 12000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        check(condition()) { message }
    }

    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) {
            (0 until view.childCount).flatMap {
                texts(view.getChildAt(it))
            }
        } else {
            listOfNotNull(view as? TextView)
        }

    override fun onStart() {
        var activity: Activity? = null
        val app = targetContext.applicationContext as VagApplication
        val passed = mutableListOf<String>()

        fun step(
            name: String,
            action: () -> Unit,
        ) {
            action()
            passed += name
            sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
            }
            targetContext
                .getSharedPreferences("vag", 0)
                .edit()
                .clear()
                .commit()
            activity = startActivitySync(Intent(targetContext, VagActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            step("Independent VAG launcher and package") {
                check(targetContext.packageName == "ro.vagmonitor.app")
                runOnMainSync { check(texts(activity!!.window.decorView).any { it.text.toString() == "VAG MONITOR" }) }
            }
            step("All four pages open without vehicle") {
                for (page in listOf("Senzori", "Erori", "Service", "Live")) {
                    runOnMainSync { texts(activity!!.window.decorView).first { it.text.toString() == page }.performClick() }
                    waitForIdleSync()
                }
            }
            val sent = Collections.synchronizedList(mutableListOf<String>())
            val moving =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val rejectClear =
                java.util.concurrent.atomic
                    .AtomicBoolean(false)
            val link =
                object : ConnectableTransport {
                    override var readTimeoutMs = 2500

                    override fun connect() {}

                    override fun close() {}

                    override suspend fun send(cmd: String): String {
                        sent += cmd
                        return when {
                            cmd == "ATZ" -> "ELM327 v1.5\r>"
                            cmd.startsWith("AT") -> "OK\r>"
                            cmd == "0100" -> "410000180001\r>"
                            cmd == "0120" -> "412000000001\r>"
                            cmd == "0140" -> "414000000010\r>"
                            cmd == "010C" -> if (moving.get()) "410C1F40\r>" else "410C0000\r>"
                            cmd == "010D" -> if (moving.get()) "410D32\r>" else "410D00\r>"
                            cmd == "015C" -> "415C82\r>"
                            cmd == "03" -> "43010133\r>"
                            cmd == "07" -> "4700\r>"
                            cmd == "0A" -> "4A00\r>"
                            cmd == "1902FF" -> "5902FF12345608\r>"
                            cmd == "04" -> if (rejectClear.get()) "7F0422\r>" else "44\r>"
                            cmd == "14FFFFFF" -> "54\r>"
                            else -> "NO DATA\r>"
                        }
                    }
                }
            step("Real engine path discovers PIDs and decodes oil") {
                app.vag.connect { link }
                waitFor("Connection failed: ${app.vag.status}") { app.vag.connected }
                waitFor("No oil sample") { app.vag.values.any { it.signal.id == "OBD_5C" && it.value == 90.0 } }
            }
            step("Clearing is blocked while speed or RPM are nonzero") {
                app.vag.clearEngine()
                waitFor("Clear check did not finish") { !app.vag.busy }
                check("04" !in sent)
                check(app.vag.report.contains("zero"))
            }
            step("Stored and UDS codes are read from the transport") {
                app.vag.scan()
                waitFor("Scan not finished") { !app.vag.busy }
                check(app.vag.report.contains("P0133"))
                check(app.vag.report.contains("123456"))
            }
            step("Stationary engine-off OBD clear requires ECU acknowledgement") {
                moving.set(false)
                app.vag.clearEngine()
                waitFor("Clear not finished") { !app.vag.busy }
                check("04" in sent)
                check(app.vag.report.contains("confirmat"))
            }
            step("ECU refusal is never reported as successful clearing") {
                rejectClear.set(true)
                app.vag.clearEngine()
                waitFor("Refused clear not finished") { !app.vag.busy }
                check(app.vag.report.contains("nereușită"))
            }
            step("Foreground service is visible and stops the connection") {
                runOnMainSync { activity!!.startForegroundService(Intent(targetContext, VagService::class.java)) }
                waitFor("Missing foreground notification") {
                    targetContext.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 202 }
                }
                check(app.vag.connected)
                runOnMainSync { activity!!.stopService(Intent(targetContext, VagService::class.java)) }
                waitFor("Service failed to close connection") { !app.vag.connected }
            }
            step("Disconnect removes live samples") {
                app.vag.disconnect()
                check(app.vag.values.isEmpty())
            }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "${passed.size} VAG checks passed\n") })
        } catch (e: Throwable) {
            finish(
                Activity.RESULT_CANCELED,
                Bundle().apply { putString("stream", "FAILED after ${passed.size}: ${e.stackTraceToString()}\n") },
            )
        } finally {
            app.vag.disconnect()
            activity?.let { runOnMainSync { it.finish() } }
        }
    }
}
