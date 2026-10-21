package org.betsy.monitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.betsy.R

/** User-visible connected-device service; never reconnects or starts itself after process death. */
class MonitorService : Service() {
    private val app get() = application as MonitorApplication
    private val engine get() = app.engine
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    private var lastState = ""
    private val observer: () -> Unit = {
        if (!app.backgroundRequested || engine.demo || (!engine.connected && !engine.connecting)) {
            stopSelf()
        } else {
            updateNotification()
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Conexiune OBD activă", NotificationManager.IMPORTANCE_LOW))
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, notification)
            }
            app.backgroundRequested = true
            app.backgroundRunning = true
            app.lastServiceStart = SystemClock.elapsedRealtime()
            wakeLock =
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PriusMonitor:OBD").apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            engine.addObserver(observer)
        } catch (e: Exception) {
            app.backgroundRequested = false
            engine.disconnect("Monitorizarea în fundal nu a putut porni: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_STOP) {
            engine.disconnect("Oprit din notificare")
            stopSelf()
        } else if (engine.demo || (!engine.connected && !engine.connecting)) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MonitorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                2,
                Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_monitor_notification)
            .setContentTitle(if (engine.connected) "Prius Monitor · conectat" else "Prius Monitor · conectare")
            .setContentText(
                if (engine.scanning) "Diagnosticare în curs. Atinge pentru detalii." else "Monitorizare OBD și înregistrare a călătoriei.",
            ).setSubText(engine.adapter)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Oprește conexiunea", stop).build())
            .build()
    }

    @SuppressLint("MissingPermission") // Notification permission is optional for an FGS; see Android's FGS contract.
    private fun updateNotification() {
        val now = SystemClock.elapsedRealtime()
        val state = "${engine.connected}/${engine.connecting}/${engine.scanning}"
        if (state == lastState && now - lastNotification < 15000) return
        lastState = state
        lastNotification = now
        try {
            getSystemService(NotificationManager::class.java).notify(ID, notification())
        } catch (
            _: SecurityException,
        ) {
            // The user may revoke notification permission during a connection.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine.removeObserver(observer)
        val shouldCloseConnection = app.backgroundRequested
        app.backgroundRunning = false
        app.backgroundRequested = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (shouldCloseConnection && !engine.demo && (engine.connected || engine.connecting)) {
            engine.disconnect("Serviciul de monitorizare a fost oprit")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "ro.priusmonitor.app.STOP_OBD"
        private const val CHANNEL = "prius-obd-monitoring"
        private const val ID = 3001
    }
}
