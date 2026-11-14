package com.example.netusage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder

/**
 * Reads the system counters and stores what is new. Safe to call from anywhere, any time, any number of times:
 * the amount stored is always "system total since midnight minus what we already stored since midnight", so daily
 * totals stay exact even if nothing ran for a while. Bytes recorded after a pause are spread over the pause, and a
 * day that ended while nothing was running is settled first, so no traffic around midnight is lost.
 */
object Sampler {
    @Synchronized fun sample(c: Context) {
        try { Backfill.run(c) } catch (e: Exception) { }
        val db = Db.get(c)
        val now = System.currentTimeMillis() / 1000
        val mid = midnight(); val b = now / BS
        val lastB = db.lastAlive()

        // 1) settle finished days that were not fully recorded
        if (lastB >= 0) {
            var d = dayStart(lastB * BS); var guard = 0
            while (d < mid && guard++ < 40) {
                val e = nextMidnight(d)
                for (net in intArrayOf(MOBILE, WIFI)) {
                    val stored = db.sums(net, d, e)
                    for ((pkg, v) in Repo.query(c, net, d, e)) {
                        val s = stored[pkg]
                        val dd = v[0] - (s?.get(0) ?: 0L); val du = v[1] - (s?.get(1) ?: 0L)
                        if (dd > 0 || du > 0) db.add(e / BS - 1, net, pkg, maxOf(dd, 0), maxOf(du, 0))
                    }
                }
                d = e
            }
        }

        // 2) today: new bytes are spread over the buckets since the last recording (when that was recent)
        val gapOk = lastB in 0 until b && b - lastB <= 192
        val from = if (gapOk) maxOf(lastB + 1, (mid + BS - 1) / BS).coerceAtMost(b) else b
        val n = (b - from + 1).toInt()
        for (net in intArrayOf(MOBILE, WIFI)) {
            val stored = db.sums(net, mid)
            for ((pkg, v) in Repo.query(c, net, mid, now)) {
                val s = stored[pkg]
                val dd = maxOf(v[0] - (s?.get(0) ?: 0L), 0); val du = maxOf(v[1] - (s?.get(1) ?: 0L), 0)
                if (dd == 0L && du == 0L) continue
                for (i in 0 until n) {
                    val last = i == n - 1
                    val x = if (last) dd - dd / n * (n - 1) else dd / n
                    val y = if (last) du - du / n * (n - 1) else du / n
                    if (x > 0 || y > 0) db.add(from + i, net, pkg, x, y)
                }
            }
        }
        for (x in from..b) db.markAlive(x)
    }
}

/** Silent mode (default): a background job every ~15 min, no notification. Precise mode: foreground service every 30 s. */
object Mode {
    fun apply(c: Context) {
        val js = c.getSystemService(JobScheduler::class.java)
        if (Prefs.silent(c)) {
            c.stopService(Intent(c, SamplerService::class.java))
            if (js.getPendingJob(1) == null)
                js.schedule(JobInfo.Builder(1, ComponentName(c, SampleJob::class.java)).setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        } else {
            js.cancel(1)
            try { c.startForegroundService(Intent(c, SamplerService::class.java)) } catch (e: Exception) { }
        }
    }
}

class SampleJob : android.app.job.JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread { try { Sampler.sample(this) } catch (e: Exception) { }; jobFinished(p, false) }.start()
        return true
    }
    override fun onStopJob(p: JobParameters) = false
}

class SamplerService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private val tick = object : Runnable {
        override fun run() { try { Sampler.sample(this@SamplerService) } catch (e: Exception) { }; h.postDelayed(this, 30_000) }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("usage", "Usage monitoring", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "usage").setContentTitle("Data usage monitor is running")
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        thread = HandlerThread("sampler").also { it.start() }
        h = Handler(thread.looper); h.post(tick)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { h.removeCallbacksAndMessages(null); thread.quitSafely(); super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { try { Mode.apply(c) } catch (e: Exception) { } }
}
