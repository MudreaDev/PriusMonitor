package org.betsy.monitor

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File

/** Dependency-free device checks for the real Activity and its Demo/report/settings flows. */
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
        try {
            targetContext
                .getSharedPreferences("prius-monitor", 0)
                .edit()
                .clear()
                .putBoolean("first", false)
                .commit()
            activity = startActivitySync(Intent(targetContext, MonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            step("Cold start shows no invented readings") {
                check(texts().any { it == "—" })
                check(texts().contains("Prius Monitor"))
            }
            step("Connection screen offers a separate Demo") {
                click("Conexiune")
                check(texts().any { it.contains("Pornește modul Demo") })
            }
            step("Demo produces clearly labelled samples") {
                click("Pornește modul Demo")
                waitUntil { texts().any { it.contains("MOD DEMO") } && texts().any { it == "rpm" } }
                Thread.sleep(1200)
                screenshot("dashboard")
            }
            step("Consumption includes hourly fuel and stopped fuel") {
                click("Consum")
                check(texts().contains("Consum pe oră și la opriri"))
                check(texts().contains("Combustibil la viteză 0"))
                check(texts().any { it.contains("ESTIMAT") })
                screenshot("consum")
            }
            if (Build.VERSION.SDK_INT >= 29) {
                step("Trip export writes real contents and preserves the Demo session") {
                    val resolver = targetContext.contentResolver
                    val uri =
                        resolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            ContentValues().apply {
                                put(MediaStore.MediaColumns.DISPLAY_NAME, "prius-test-export.txt")
                                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                            },
                        ) ?: error("Cannot create export destination")
                    val monitor =
                        addMonitor(
                            IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                addDataType("text/plain")
                            },
                            ActivityResult(Activity.RESULT_OK, Intent().setData(uri)),
                            true,
                        )
                    try {
                        click("Exportă rezumatul călătoriei")
                        waitUntil {
                            runCatching {
                                resolver.openInputStream(uri)?.bufferedReader()?.use {
                                    it.readText().contains("PRIUS MONITOR — CĂLĂTORIE")
                                } == true
                            }.getOrDefault(false)
                        }
                        check(texts().any { it.contains("MOD DEMO") })
                    } finally {
                        removeMonitor(monitor)
                        resolver.delete(uri, null, null)
                    }
                }
            }
            step("Battery displays 14 voltage and resistance rows") {
                click("Baterie")
                check(texts().contains("Bloc 01"))
                check(texts().contains("Bloc 14"))
                check(texts().any { it.contains("mΩ") })
                screenshot("baterie")
            }
            step("SOH is not fabricated and battery observation records samples") {
                click("Sănătate baterie / SOH")
                check(texts().contains("Sănătate baterie / SOH"))
                check(texts().contains("Indisponibil"))
                click("Începe observația bateriei")
                waitUntil { texts().contains("Exportă observația bateriei") }
                screenshot("soh")
                click("Oprește observația bateriei")
                check(texts().contains("Exportă observația bateriei"))
            }
            step("Diagnostic scan saves and reloads a Demo report") {
                click("Erori")
                click("Citește codurile de eroare")
                waitUntil { texts().contains("P0A80") }
                val files = ReportStore(targetContext).list()
                check(files.isNotEmpty())
                check(ReportStore(targetContext).read(files.first()).demo)
                clickPrefix("Rapoarte salvate")
                check(texts().any { it.contains("DEMO ·") })
                screenshot("rapoarte")
                click("Deschide raportul")
                check(texts().contains("RAPORT DEMO"))
                check(texts().contains("P0A80"))
            }
            step("Settings persist chosen temperature units") {
                click("Setări")
                clickPrefix("Temperatură:")
                check(targetContext.getSharedPreferences("prius-monitor", 0).getBoolean("fahrenheit", false))
                check(texts().any { it == "Temperatură: °F" })
                clickPrefix("Temperatură:")
            }
            step("Disconnect removes simulated readings") {
                click("Conexiune")
                click("Deconectează")
                click("Acasă")
                check(texts().none { it.contains("MOD DEMO") })
                check(texts().any { it == "—" })
            }
            finish(0, Bundle().apply { putString("stream", "\nALL ${steps.size} DEVICE CHECKS PASSED\n") })
        } catch (e: Throwable) {
            finish(1, Bundle().apply { putString("stream", "\nFAILED after ${steps.size} checks: ${e.stackTraceToString()}\n") })
        }
    }

    private fun views(): List<View> {
        val all = mutableListOf<View>()
        runOnMainSync {
            fun collect(v: View) {
                all += v
                if (v is ViewGroup) (0 until v.childCount).forEach { collect(v.getChildAt(it)) }
            }
            collect(activity.window.decorView)
        }
        return all
    }

    private fun texts() = views().filterIsInstance<TextView>().map { it.text.toString() }

    private fun click(text: String) = clickMatching { it == text }

    private fun clickPrefix(text: String) = clickMatching { it.startsWith(text) }

    private fun clickMatching(predicate: (String) -> Boolean) {
        val view =
            views().firstOrNull {
                it.isClickable &&
                    (
                        predicate(it.contentDescription?.toString() ?: "") ||
                            (it is TextView && predicate(it.text.toString()))
                    )
            }
                ?: error("Control not found; visible text=${texts()}")
        runOnMainSync { view.performClick() }
        waitForIdleSync()
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + 12000
        while (!predicate() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(predicate()) { "Timed out waiting for app state" }
    }

    private fun screenshot(name: String) {
        // View hierarchy idle can precede the compositor's next frame.
        Thread.sleep(300)
        val bitmap = uiAutomation.takeScreenshot() ?: return
        val file = File(targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
