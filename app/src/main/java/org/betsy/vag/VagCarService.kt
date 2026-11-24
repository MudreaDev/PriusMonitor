package org.betsy.vag

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.betsy.BuildConfig

/** Experimental connected-device telemetry. No diagnostics or writes on the head unit. */
class VagCarService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }

    override fun onCreateSession(): Session =
        object : Session() {
            override fun onCreateScreen(intent: Intent): Screen = VagCarScreen(carContext)
        }
}

private class VagCarScreen(
    context: CarContext,
) : Screen(context),
    DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val engine get() = (carContext.applicationContext as VagApplication).vag
    private val refresh =
        object : Runnable {
            override fun run() {
                invalidate()
                handler.postDelayed(this, 2000)
            }
        }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.post(refresh)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(refresh)
    }

    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
        if (!engine.connected) {
            pane.addRow(
                Row
                    .Builder()
                    .setTitle("Conectează adaptorul pe telefon")
                    .addText("Folosește telefonul numai când este sigur, cu mașina parcată.")
                    .build(),
            )
        } else {
            val values = engine.values
            val metrics = listOf("OBD_0C" to "Turație", "OBD_0D" to "Viteză", "OBD_5C" to "Ulei", "OBD_05" to "Antigel")
            metrics.forEach { (id, title) ->
                val reading =
                    values.firstOrNull { it.signal.id == id && it.value != null }
                        ?: if (id == "OBD_5C") values.firstOrNull { it.signal.id == "PASSAT_PQ35_ENGINE_OIL_T_2" } else null
                pane.addRow(
                    Row
                        .Builder()
                        .setTitle(title)
                        .addText(reading?.display() ?: "Indisponibil")
                        .build(),
                )
            }
        }
        return PaneTemplate
            .Builder(pane.build())
            .setTitle("VAG Monitor · live")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
