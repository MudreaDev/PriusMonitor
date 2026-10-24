package org.betsy.monitor

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
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
                .putBoolean("background_monitoring", false)
                .commit()
            if (Build.VERSION.SDK_INT >= 31) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                uiAutomation.grantRuntimePermission(targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
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
                runOnMainSync {
                    app.engine.intervalMs = 500
                    check(app.connect("TEST · decoder fixture", ::replayTransport) == null)
                }
                waitUntil {
                    val sample = app.engine.sample
                    sample.trip.average != null &&
                        app.backgroundRunning &&
                        sample.obd.coolant == 82f &&
                        sample.obd.voltage == 14.1f &&
                        sample.battery?.blockVolts?.size == 14
                }
                check(!app.engine.demo)
                check(app.backgroundRequested)
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
            step("Home keeps the connection alive and shows three live readings when PiP is supported") {
                val journey = app.engine.journeyKey
                val timestamp = app.engine.sample.time
                pressHome()
                if (supportsPictureInPicture()) {
                    waitUntil { activity.isInPictureInPictureMode }
                    assertMiniFits()
                    screenshot("mini-home-valori-test")
                } else {
                    waitUntil { !activity.hasWindowFocus() }
                }
                waitUntil { app.engine.sample.time > timestamp + 1000 }
                check(app.engine.connected && app.backgroundRunning)
                check(app.engine.journeyKey == journey)
                if (supportsPictureInPicture()) assertMiniFits()
            }
            step("Closing the minimized activity preserves the connection and trip on return") {
                val journey = app.engine.journeyKey
                val timestamp = app.engine.sample.time
                val distance = app.engine.sample.trip.distanceKm
                runOnMainSync { activity.finish() }
                waitUntil { activity.isDestroyed }
                waitUntil { app.engine.sample.time > timestamp + 1000 }
                check(app.engine.connected && app.backgroundRunning)
                check(app.engine.journeyKey == journey)
                activity = startActivitySync(Intent(targetContext, MonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitUntil { texts().contains("LIVE · Prius Monitor") }
                check(app.engine.journeyKey == journey)
                check(app.engine.sample.trip.distanceKm >= distance)
                assertCompactFits()
            }
            step("Disabling the mini window keeps background monitoring active") {
                click("Acasă")
                click("Setări")
                click("Fereastră mică la ieșire: pornită")
                check(!targetContext.getSharedPreferences("prius-monitor", 0).getBoolean("mini_window", true))
                val journey = app.engine.journeyKey
                val timestamp = app.engine.sample.time
                pressHome()
                waitUntil { !activity.hasWindowFocus() }
                waitUntil { app.engine.sample.time > timestamp + 1000 }
                check(!activity.isInPictureInPictureMode)
                check(app.engine.connected && app.backgroundRunning)
                check(app.engine.journeyKey == journey)
                restoreActivity()
                check(app.engine.journeyKey == journey)
                click("Fereastră mică la ieșire: oprită")
                click("Live")
                assertCompactFits()
            }
            step("Back from Live minimizes the active session without resetting its trip") {
                val journey = app.engine.journeyKey
                val timestamp = app.engine.sample.time
                runOnMainSync { activity.onBackPressed() }
                if (supportsPictureInPicture()) {
                    waitUntil { activity.isInPictureInPictureMode }
                    assertMiniFits()
                    screenshot("mini-back-valori-test")
                } else {
                    waitUntil { !activity.hasWindowFocus() }
                }
                waitUntil { app.engine.sample.time > timestamp + 1000 }
                check(app.engine.connected && app.backgroundRunning)
                check(app.engine.journeyKey == journey)
            }
            step("The notification Stop action ends the connection and background service") {
                val stop =
                    targetContext
                        .getSystemService(NotificationManager::class.java)
                        .activeNotifications
                        .flatMap {
                            it.notification.actions
                                ?.toList()
                                .orEmpty()
                        }.single { it.title.toString() == "Oprește conexiunea" }
                runOnMainSync { stop.actionIntent.send() }
                waitUntil { !app.engine.connected && !app.engine.connecting && !app.backgroundRunning }
                check(app.engine.status == "Oprit din notificare")
                check(!app.backgroundRequested)
                if (!activity.isDestroyed) {
                    runOnMainSync { activity.finish() }
                    waitUntil { activity.isDestroyed }
                }
                activity = startActivitySync(Intent(targetContext, MonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                waitUntil { texts().contains("LIVE · Prius Monitor") }
                check(texts().count { it == "—" } >= 10)
            }
            step("Connection error is remembered and can be copied") {
                runOnMainSync {
                    app.connect("TEST · connection failure") {
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

    private fun supportsPictureInPicture() = targetContext.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun pressHome() {
        check(uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
    }

    private fun restoreActivity() {
        runOnMainSync {
            targetContext.startActivity(
                Intent(targetContext, MonitorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        waitUntil { activity.hasWindowFocus() && !activity.isInPictureInPictureMode }
        waitForIdleSync()
    }

    private fun assertMiniFits() {
        waitUntilMiniReady()
        val dashboard = views().filterIsInstance<MiniDashboardView>().single { it.isShown }
        check(views().filterIsInstance<CompactDashboard>().none { it.isShown })
        val visible = texts()
        check(visible.containsAll(listOf("VITEZĂ", "CONSUM LIVE", "CONSUM MEDIU", "60", "km/h")))
        check(visible.count { it == "5,0" } == 2)
        assertReadingsFit(dashboard)
    }

    private fun waitUntilMiniReady() {
        fun ready(): Boolean {
            val dashboard = views().filterIsInstance<MiniDashboardView>().singleOrNull() ?: return false
            var settled = false
            runOnMainSync {
                val visible = Rect()
                val aspect = if (dashboard.height > 0) dashboard.width.toDouble() / dashboard.height else 0.0
                settled =
                    activity.isInPictureInPictureMode &&
                    dashboard.isShown &&
                    aspect in 1.4..2.1 &&
                    dashboard.getGlobalVisibleRect(visible) &&
                    visible.width() == dashboard.width &&
                    visible.height() == dashboard.height
            }
            return settled
        }
        waitUntil(::ready)
        Thread.sleep(500)
        waitUntil(::ready)
    }

    private fun replayTransport(): ConnectableTransport {
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
        return object : ConnectableTransport {
            override var readTimeoutMs = 2500

            override fun connect() {}

            override suspend fun send(cmd: String) = replay.send(cmd)

            override fun close() {
                replay.close()
            }
        }
    }

    private fun assertCompactFits() {
        check(views().none { it is ScrollView && it.isShown })
        val dashboard = views().filterIsInstance<CompactDashboard>().single()
        assertReadingsFit(dashboard)
    }

    private fun assertReadingsFit(dashboard: View) {
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
        Thread.sleep(if (activity.isInPictureInPictureMode) 2000 else 500)
        if (activity.isInPictureInPictureMode) waitUntilMiniReady()
        val bitmap = uiAutomation.takeScreenshot() ?: return
        File(targetContext.getExternalFilesDir(null), "$name.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}
