package org.betsy.monitor

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.transport.ReplayTransport
import java.io.File

/** Device-only fixtures stay in the test APK. Production has no Demo or synthetic data source. */
class MonitorInstrumentation : Instrumentation() {
    private lateinit var activity: Activity
    private val steps = mutableListOf<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun step(
        name: String,
        action: () -> Unit,
    ) {
        action()
        steps += name
        sendStatus(0, Bundle().apply { putString("stream", "PASS: $name\n") })
    }

    override fun onStart() {
        val app = targetContext.applicationContext as MonitorApplication
        try {
            targetContext
                .getSharedPreferences("prius-monitor", 0)
                .edit()
                .clear()
                .putBoolean("first", false)
                .commit()
            activity = startActivitySync(Intent(targetContext, MonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            step("Compact Live opens without scrolling or invented telemetry") {
                check(texts().contains("LIVE · Prius Monitor"))
                check(texts().contains("Consum instant"))
                check(texts().contains("Consum mediu"))
                check(texts().contains("Baterie HV"))
                check(texts().count { it == "—" } >= 10)
                assertCompactFits()
                screenshot("live-neconectat")
            }
            step("Every main page has no Demo launch control") {
                for (page in listOf("Acasă", "Consum", "Baterie", "Erori", "Conexiune")) {
                    click(page)
                    check(
                        texts().none {
                            it.contains("pornește Demo", true) ||
                                it.contains("modul Demo", true) ||
                                it.contains("Explorează modul", true)
                        },
                    )
                }
                check(texts().contains("Caută adaptor BLE (15 secunde)"))
                click("Live")
            }
            step("Compact values use the real decoder path with a test-only transport fixture") {
                val replay =
                    ReplayTransport(
                        mapOf(
                            "ATZ" to "ELM327 v1.5",
                            "0100" to "4100FFFFFFFF",
                            "0120" to "4120FFFFFFFF",
                            "0140" to "4140FFFFFFFF",
                            "7E0/010D" to "410D3C",
                            "7E0/010C" to "410C1770",
                            "7E0/0105" to "41057A",
                            "7E0/0110" to "411003E8",
                            "7E0/015E" to "415E003C",
                            "7E0/0142" to "41423714",
                            "7E2/2181" to "6181" + "2E1B".repeat(14) + "AF24",
                            "7E2/2101" to "6101" + "00".repeat(21) + "99",
                            "7E2/2198" to "619883E899AA64",
                            "7E2/2187" to "618700005A005A005A00",
                            "7E2/2195" to "6195" + "19".repeat(14),
                        ),
                    )
                runOnMainSync {
                    app.engine.intervalMs = 500
                    app.engine.connect("TEST · decoder fixture") {
                        object : ConnectableTransport {
                            override var readTimeoutMs = 2500

                            override fun connect() {}

                            override suspend fun send(cmd: String) = replay.send(cmd)

                            override fun close() {
                                replay.close()
                            }
                        }
                    }
                }
                waitUntil { app.engine.sample.trip.average != null }
                check(!app.engine.demo)
                check(texts().contains("60"))
                check(texts().contains("5,0"))
                check(texts().contains("l/100 km · 20,0 km/l"))
                check(texts().contains("1500"))
                check(texts().contains("82,0"))
                check(texts().contains("14,10"))
                assertCompactFits()
                screenshot("live-valori-test")
            }
            step("Live updates preserve the attached compact dashboard") {
                val before = views().filterIsInstance<CompactDashboard>().single()
                val timestamp = app.engine.sample.time
                waitUntil { app.engine.sample.time > timestamp + 1000 }
                check(views().any { it === before })
                assertCompactFits()
            }
            step("Connection error is remembered and can be copied") {
                runOnMainSync {
                    app.engine.connect("TEST · connection failure") {
                        object : ConnectableTransport {
                            override var readTimeoutMs = 2500

                            override fun connect(): Unit = throw java.io.IOException("test socket failure")

                            override suspend fun send(cmd: String): String = error("must not send")

                            override fun close() {}
                        }
                    }
                }
                waitUntil {
                    targetContext
                        .getSharedPreferences(
                            "prius-monitor",
                            0,
                        ).getString("last_connection_error", "")!!
                        .contains("test socket failure")
                }
                click("Conexiune")
                waitUntil { texts().contains("Copiază eroarea") }
                check(texts().any { it.contains("Bluetooth: test socket failure") })
                click("Copiază eroarea")
                click("Live")
                check(texts().count { it == "—" } >= 10)
            }
            step("Pending file exports preserve a private snapshot") {
                val pending = PendingExport(targetContext)
                val source = File(targetContext.cacheDir, "capture-check.log").apply { writeText("TX 010D\nRX 410D32\n") }
                try {
                    val token = pending.stageFile(source)
                    source.writeText("changed")
                    check(pending.read(token) == "TX 010D\nRX 410D32\n")
                    check(runCatching { pending.read("../private") }.isFailure)
                    pending.discard(token)
                } finally {
                    source.delete()
                }
            }
            step("Recreation restores the compact page without reconnecting") {
                val monitor = addMonitor(MonitorActivity::class.java.name, null, false)
                try {
                    runOnMainSync { activity.recreate() }
                    activity = waitForMonitorWithTimeout(monitor, 10000) ?: error("Activity did not recreate")
                    waitUntil { texts().contains("LIVE · Prius Monitor") }
                    assertCompactFits()
                    check(!app.engine.connected)
                } finally {
                    removeMonitor(monitor)
                }
            }
            finish(0, Bundle().apply { putString("stream", "\nALL ${steps.size} DEVICE CHECKS PASSED\n") })
        } catch (e: Throwable) {
            finish(1, Bundle().apply { putString("stream", "\nFAILED after ${steps.size} checks: ${e.stackTraceToString()}\n") })
        } finally {
            runOnMainSync { app.engine.disconnect("Sfârșit verificare software") }
        }
    }

    private fun assertCompactFits() {
        check(views().none { it is ScrollView && it.isShown })
        val dashboard = views().filterIsInstance<CompactDashboard>().single()
        runOnMainSync {
            val bounds = Rect()
            check(dashboard.getGlobalVisibleRect(bounds))

            fun inspect(view: View) {
                if (view is TextView && view.isShown && view.text.isNotBlank()) {
                    val actual = Rect()
                    check(view.getGlobalVisibleRect(actual)) { "Hidden reading: ${view.text}" }
                    check(bounds.contains(actual)) { "Reading outside compact viewport: ${view.text}" }
                    check(actual.height() == view.height) { "Vertically clipped control: ${view.text}" }
                }
                if (view is ViewGroup) (0 until view.childCount).forEach { inspect(view.getChildAt(it)) }
            }
            inspect(dashboard)
        }
    }

    private fun views(): List<View> {
        val all = mutableListOf<View>()
        runOnMainSync {
            fun collect(view: View) {
                all += view
                if (view is ViewGroup) (0 until view.childCount).forEach { collect(view.getChildAt(it)) }
            }
            collect(activity.window.decorView)
        }
        return all
    }

    private fun texts() = views().filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }

    private fun click(text: String) {
        val view =
            views().firstOrNull {
                it.isShown &&
                    it.isClickable &&
                    (it.contentDescription?.toString() == text || it is TextView && it.text.toString() == text)
            }
                ?: error("Control not found: $text; ${texts()}")
        runOnMainSync { view.performClick() }
        waitForIdleSync()
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20000
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        check(predicate()) { "Timed out waiting for app state" }
    }

    private fun screenshot(name: String) {
        Thread.sleep(500)
        val bitmap = uiAutomation.takeScreenshot() ?: return
        File(targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
