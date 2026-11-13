package com.example.netusage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Every 30 s: reads today's per-app totals from the system and stores only the part not yet recorded
 * (system total since midnight - what we already stored since midnight). So daily totals stay exact even if the
 * service was killed for a while; the missed bytes just land in the first bucket after it comes back.
 */
class SamplerService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private val tick = object : Runnable {
        override fun run() { try { sample() } catch (e: Exception) { /* keep running */ }; h.postDelayed(this, 30_000) }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("usage", "Usage monitoring", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "usage").setContentTitle("Data usage monitor is running")
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        thread = HandlerThread("sampler").also { it.start() }
        h = Handler(thread.looper)
        h.post { try { Backfill.run(this) } catch (e: Exception) { }; tick.run() }
    }

    private fun sample() {
        val now = System.currentTimeMillis() / 1000
        val mid = midnight(); val b = now / BS; val db = Db.get(this)
        for (net in intArrayOf(MOBILE, WIFI)) {
            val cur = Repo.query(this, net, mid, now)
            val stored = db.sums(net, mid)
            for ((pkg, v) in cur) {
                val s = stored[pkg]
                val dd = v[0] - (s?.get(0) ?: 0L); val du = v[1] - (s?.get(1) ?: 0L)
                if (dd > 0 || du > 0) db.add(b, net, pkg, maxOf(dd, 0), maxOf(du, 0))
            }
        }
        db.markAlive(b)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { h.removeCallbacksAndMessages(null); thread.quitSafely(); super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        try { c.startForegroundService(Intent(c, SamplerService::class.java)) } catch (e: Exception) { }
    }
}
