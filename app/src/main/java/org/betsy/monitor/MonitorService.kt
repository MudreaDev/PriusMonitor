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
    private var sessionId = 0L
    private var lastStartId = 0
    private var foregroundReady = false
    private val observer: () -> Unit = { reconcile() }

    override fun onCreate() {
        super.onCreate()
        sessionId = app.backgroundSessionId
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Conexiune OBD activă", NotificationManager.IMPORTANCE_LOW))
            val notification = notification()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(ID, notification)
            }
            foregroundReady = true
            app.backgroundRunning = true
            app.lastServiceStart = SystemClock.elapsedRealtime()
            wakeLock =
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PriusMonitor:OBD").apply {
                    setReferenceCounted(false)
                    acquire(12 * 60 * 60 * 1000L)
                }
            engine.addObserver(observer)
        } catch (e: Exception) {
            app.finishBackgroundSession(sessionId)
            engine.disconnect("Monitorizarea în fundal nu a putut porni: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        if (!foregroundReady) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val requestedSession = intent?.getLongExtra(EXTRA_SESSION_ID, app.backgroundSessionId) ?: 0L
        if (intent?.action == ACTION_STOP) {
            // A notification left over from an earlier session cannot stop a new connection.
            if (requestedSession == app.backgroundSessionId) {
                sessionId = requestedSession
                app.finishBackgroundSession(sessionId)
                engine.disconnect("Oprit din notificare")
                stopSelfResult(startId)
            }
        } else if (intent != null && requestedSession == app.backgroundSessionId) {
            sessionId = requestedSession
            reconcile(force = true)
        } else if (!app.backgroundRequested || (!engine.connected && !engine.connecting)) {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun reconcile(force: Boolean = false) {
        if (!foregroundReady || sessionId != app.backgroundSessionId) return
        if (!app.backgroundRequested || engine.demo || (!engine.connected && !engine.connecting)) {
            app.finishBackgroundSession(sessionId)
            // Do not cancel a connect request already queued by Android but not delivered yet.
            if (lastStartId > 0) stopSelfResult(lastStartId)
        } else {
            updateNotification(force)
        }
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
                Intent(this, MonitorService::class.java)
                    .setAction(ACTION_STOP)
                    .putExtra(EXTRA_SESSION_ID, sessionId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val readings =
            MiniMonitorReadings.from(
                engine.sample,
                engine.connected,
                engine.connecting,
                getSharedPreferences("prius-monitor", MODE_PRIVATE).getBoolean("imperial", false),
            )
        val text = if (engine.connected) readings.notificationLine else engine.status
        val details = if (engine.scanning) "$text\nDiagnosticare în curs." else text
        return Notification
            .Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_monitor_notification)
            .setContentTitle(if (engine.connected) "Prius Monitor · conectat" else "Prius Monitor · conectare")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(details))
            .setSubText(engine.adapter)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, "Oprește conexiunea", stop).build())
            .build()
    }

    @SuppressLint("MissingPermission") // Notification permission is optional for an FGS; see Android's FGS contract.
    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val state = "${engine.connected}/${engine.connecting}/${engine.scanning}/${engine.status}"
        if (!force && state == lastState && now - lastNotification < 2000) return
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
        foregroundReady = false
        app.backgroundRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // stopSelf is asynchronous. A new connect may arrive before this old instance dies.
        if (sessionId == app.backgroundSessionId) {
            val shouldCloseConnection = app.backgroundRequested
            app.finishBackgroundSession(sessionId)
            if (shouldCloseConnection && !engine.demo && (engine.connected || engine.connecting)) {
                engine.disconnect("Serviciul de monitorizare a fost oprit")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "ro.priusmonitor.app.STOP_OBD"
        const val EXTRA_SESSION_ID = "ro.priusmonitor.app.OBD_SESSION_ID"
        private const val CHANNEL = "prius-obd-monitoring"
        private const val ID = 3001
    }
}
