package org.betsy.vag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

class VagService : Service() {
    private val engine get() = (application as VagApplication).vag
    private val handler = Handler(Looper.getMainLooper())
    private var started = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val check =
        object : Runnable {
            override fun run() {
                if (!engine.connected && !engine.busy && android.os.SystemClock.elapsedRealtime() - started > 3000) {
                    stopSelf()
                } else {
                    handler.postDelayed(this, 1000)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("vag", "Conexiune VAG", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, VagActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, VagService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        startForeground(
            202,
            Notification
                .Builder(this, "vag")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("VAG Monitor")
                .setContentText("Conexiunea OBD rulează în fundal")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Oprește", stop).build())
                .build(),
        )
        started = android.os.SystemClock.elapsedRealtime()
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VAGMonitor:OBD")
                .apply {
                    setReferenceCounted(false)
                    acquire(6 * 60 * 60 * 1000L)
                }
        handler.post(check)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == "STOP") {
            engine.disconnect()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        if (engine.connected || engine.busy) engine.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
