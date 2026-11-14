package com.example.netusage

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.JsonReader
import android.util.JsonWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Calendar

const val BS = 900L      // chart bucket = 15 minutes
const val MOBILE = 0
const val WIFI = 1

private fun cal(sec: Long = System.currentTimeMillis() / 1000) = Calendar.getInstance().apply {
    timeInMillis = sec * 1000
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}

/** Local midnight (epoch seconds) of today, or of [daysBack] days ago. */
fun midnight(daysBack: Int = 0): Long = cal().apply { add(Calendar.DAY_OF_YEAR, -daysBack) }.timeInMillis / 1000
fun dayStart(sec: Long): Long = cal(sec).timeInMillis / 1000
fun nextMidnight(sec: Long): Long = cal(sec).apply { add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis / 1000

/**
 * Start (epoch seconds, local 00:00) of the Wi-Fi billing cycle: [back] = 0 is the current cycle, 1 the previous one...
 * A cycle begins on day-of-month [day] (clamped to the month's length) and ends where the next one begins.
 */
fun cycleStart(day: Int, back: Int): Long {
    val n = Calendar.getInstance()
    val y = n.get(Calendar.YEAR); var m = n.get(Calendar.MONTH)
    fun at(mm: Int) = Calendar.getInstance().apply {
        clear(); set(y, mm, 1); set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
    }
    if (at(m).after(n)) m -= 1
    return at(m - back).timeInMillis / 1000
}

/** Our own time series (the system only keeps ~2-hour buckets). One row per 15 min, network, package. */
class Db private constructor(c: Context) : SQLiteOpenHelper(c, "usage.db", null, 1) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE usage(b INTEGER, net INTEGER, pkg TEXT, down INTEGER, up INTEGER, PRIMARY KEY(b,net,pkg))")
        d.execSQL("CREATE TABLE alive(b INTEGER PRIMARY KEY)")
    }
    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {}

    @Synchronized fun add(b: Long, net: Int, pkg: String, down: Long, up: Long) {
        val w = writableDatabase
        val st = w.compileStatement("UPDATE usage SET down=down+?,up=up+? WHERE b=? AND net=? AND pkg=?")
        st.bindLong(1, down); st.bindLong(2, up); st.bindLong(3, b); st.bindLong(4, net.toLong()); st.bindString(5, pkg)
        if (st.executeUpdateDelete() == 0) w.execSQL("INSERT INTO usage VALUES(?,?,?,?,?)", arrayOf<Any>(b, net.toLong(), pkg, down, up))
    }

    /** Used by import: keeps the larger of the existing and the imported value. */
    @Synchronized fun merge(b: Long, net: Int, pkg: String, down: Long, up: Long) {
        val w = writableDatabase
        val st = w.compileStatement("UPDATE usage SET down=MAX(down,?),up=MAX(up,?) WHERE b=? AND net=? AND pkg=?")
        st.bindLong(1, down); st.bindLong(2, up); st.bindLong(3, b); st.bindLong(4, net.toLong()); st.bindString(5, pkg)
        if (st.executeUpdateDelete() == 0) w.execSQL("INSERT INTO usage VALUES(?,?,?,?,?)", arrayOf<Any>(b, net.toLong(), pkg, down, up))
    }

    @Synchronized fun batch(f: () -> Unit) {
        val w = writableDatabase; w.beginTransaction()
        try { f(); w.setTransactionSuccessful() } finally { w.endTransaction() }
    }

    @Synchronized fun deleteRange(fromB: Long, toB: Long) {
        writableDatabase.execSQL("DELETE FROM usage WHERE b>=? AND b<?", arrayOf<Any>(fromB, toB))
    }

    /** Stored totals per package in [fromSec, toSec). */
    @Synchronized fun sums(net: Int, fromSec: Long, toSec: Long = 4_000_000_000L): Map<String, LongArray> {
        val m = HashMap<String, LongArray>()
        readableDatabase.rawQuery("SELECT pkg,SUM(down),SUM(up) FROM usage WHERE net=? AND b>=? AND b<? GROUP BY pkg",
            arrayOf("$net", "${(fromSec + BS - 1) / BS}", "${(toSec + BS - 1) / BS}"))
            .use { while (it.moveToNext()) m[it.getString(0)] = longArrayOf(it.getLong(1), it.getLong(2)) }
        return m
    }

    /** Stored totals per package for buckets fromB..toB (both inclusive). */
    @Synchronized fun sumsB(net: Int, fromB: Long, toB: Long): Map<String, LongArray> {
        val m = HashMap<String, LongArray>()
        readableDatabase.rawQuery("SELECT pkg,SUM(down),SUM(up) FROM usage WHERE net=? AND b>=? AND b<=? GROUP BY pkg",
            arrayOf("$net", "$fromB", "$toB"))
            .use { while (it.moveToNext()) m[it.getString(0)] = longArrayOf(it.getLong(1), it.getLong(2)) }
        return m
    }

    @Synchronized fun eachRow(net: Int, fromB: Long, toB: Long, f: (Long, String, Long, Long) -> Unit) {
        readableDatabase.rawQuery("SELECT b,pkg,down,up FROM usage WHERE net=? AND b>=? AND b<?", arrayOf("$net", "$fromB", "$toB"))
            .use { while (it.moveToNext()) f(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3)) }
    }

    @Synchronized fun eachAll(f: (Long, Int, String, Long, Long) -> Unit) {
        readableDatabase.rawQuery("SELECT b,net,pkg,down,up FROM usage", null)
            .use { while (it.moveToNext()) f(it.getLong(0), it.getInt(1), it.getString(2), it.getLong(3), it.getLong(4)) }
    }

    /** Start (epoch seconds) of the oldest stored record of [net], or 0 when there is none. */
    @Synchronized fun oldest(net: Int): Long =
        readableDatabase.rawQuery("SELECT MIN(b) FROM usage WHERE net=?", arrayOf("$net")).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) * BS else 0L }

    /** When monitoring first ran (epoch seconds), or -1. */
    @Synchronized fun firstAlive(): Long =
        readableDatabase.rawQuery("SELECT MIN(b) FROM alive", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) * BS else -1L }

    /** Newest monitoring mark (bucket number), or -1. */
    @Synchronized fun lastAlive(): Long =
        readableDatabase.rawQuery("SELECT MAX(b) FROM alive", null).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L }

    @Synchronized fun markAlive(b: Long) { writableDatabase.execSQL("INSERT OR IGNORE INTO alive VALUES(?)", arrayOf<Any>(b)) }

    @Synchronized fun alive(fromB: Long, toB: Long): List<Long> {
        val l = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT b FROM alive WHERE b>=? AND b<?", arrayOf("$fromB", "$toB")).use { while (it.moveToNext()) l.add(it.getLong(0)) }
        return l
    }

    companion object {
        @Volatile private var inst: Db? = null
        fun get(c: Context): Db = inst ?: synchronized(this) { inst ?: Db(c.applicationContext).also { inst = it } }
    }
}

/** App groups: 0 = user apps (browsers...), 1 = other apps / system (default), 2 = local network (apps you assign). */
object Prefs {
    private val DEFAULT = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "org.mozilla.firefox", "org.mozilla.fenix", "com.brave.browser", "com.microsoft.emmx",
        "com.opera.browser", "com.opera.mini.native", "com.sec.android.app.sbrowser", "com.kiwibrowser.browser",
        "com.duckduckgo.mobile.android", "com.vivaldi.browser"
    )
    private fun sp(c: Context) = c.getSharedPreferences("p", 0)
    fun users(c: Context): Set<String> = sp(c).getStringSet("user", null) ?: DEFAULT
    fun local(c: Context): Set<String> = sp(c).getStringSet("local", null) ?: emptySet()
    fun setUsers(c: Context, s: Set<String>) { sp(c).edit().putStringSet("user", HashSet(s)).apply() }
    fun setLocal(c: Context, s: Set<String>) { sp(c).edit().putStringSet("local", HashSet(s)).apply() }
    fun setGroup(c: Context, pkg: String, g: Int) {
        val u = HashSet(users(c)); val l = HashSet(local(c)); u.remove(pkg); l.remove(pkg)
        if (g == 0) u.add(pkg) else if (g == 2) l.add(pkg)
        setUsers(c, u); setLocal(c, l)
    }
    fun silent(c: Context): Boolean = sp(c).getBoolean("silent", true)
    fun setSilent(c: Context, on: Boolean) { sp(c).edit().putBoolean("silent", on).apply() }
    fun cycleDay(c: Context): Int = sp(c).getInt("cycle_day", 12)
    fun setCycleDay(c: Context, d: Int) { sp(c).edit().putInt("cycle_day", d.coerceIn(1, 31)).apply() }
}

object Repo {
    private val uidCache = HashMap<Int, String>()

    fun pkgOf(c: Context, uid: Int): String = synchronized(uidCache) {
        uidCache.getOrPut(uid) {
            when (uid) {
                NetworkStats.Bucket.UID_REMOVED -> "removed"
                NetworkStats.Bucket.UID_TETHERING -> "tethering"
                else -> c.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
            }
        }
    }

    @Suppress("DEPRECATION")
    fun subscriber(c: Context, net: Int): String? =
        if (net == MOBILE && Build.VERSION.SDK_INT < 29) try { c.getSystemService(TelephonyManager::class.java).subscriberId } catch (e: Exception) { null } else null

    @Suppress("DEPRECATION")
    fun type(net: Int) = if (net == WIFI) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE

    /** Bytes per package between [from] and [to] (epoch seconds), straight from the system counters. [down, up] */
    fun query(c: Context, net: Int, from: Long, to: Long): Map<String, LongArray> {
        val out = HashMap<String, LongArray>()
        val nsm = c.getSystemService(NetworkStatsManager::class.java) ?: return out
        val stats = try { nsm.querySummary(type(net), subscriber(c, net), from * 1000, to * 1000) } catch (e: Exception) { return out }
        val bk = NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bk)
            val a = out.getOrPut(pkgOf(c, bk.uid)) { LongArray(2) }
            a[0] += bk.rxBytes; a[1] += bk.txBytes
        }
        stats.close()
        return out
    }

    fun label(c: Context, pkg: String): String = when {
        pkg == "removed" -> "Removed apps"
        pkg == "tethering" -> "Hotspot / tethering"
        else -> try { val pm = c.packageManager; pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: Exception) { pkg }
    }
}

/**
 * One-time history rebuild. querySummary only gives totals, but queryDetails gives ~2-hour buckets, so for the time
 * before this app started recording (and for the first recorded day, whose first sample used to be one big lump)
 * we copy the system's own history into our database, spreading each system bucket over 15-minute slots.
 */
object Backfill {
    fun run(c: Context) {
        val p = c.getSharedPreferences("p", 0)
        if (p.getBoolean("backfilled", false)) return
        val nsm = c.getSystemService(NetworkStatsManager::class.java) ?: return
        val db = Db.get(c); val now = System.currentTimeMillis() / 1000
        val from = cycleStart(Prefs.cycleDay(c), 2)
        val first = db.firstAlive()
        var cutoff = now; var delFrom = -1L; var delTo = -1L
        if (first >= 0) {                      // the first recorded day is rebuilt completely from the system history
            val de = nextMidnight(first)
            delFrom = dayStart(first) / BS; delTo = (de + BS - 1) / BS; cutoff = minOf(de, now)
        }
        val acc = Array(2) { HashMap<Pair<Long, String>, LongArray>() }
        for (net in intArrayOf(MOBILE, WIFI)) {
            val st = nsm.queryDetails(Repo.type(net), Repo.subscriber(c, net), from * 1000, cutoff * 1000)   // throws if no access
            val bk = NetworkStats.Bucket()
            while (st.hasNextBucket()) {
                st.getNextBucket(bk)
                val s = bk.startTimeStamp / 1000; val e = minOf(bk.endTimeStamp / 1000, now)
                if (e <= s || bk.rxBytes + bk.txBytes <= 0) continue
                val lo = maxOf(s, from); val hi = minOf(e, cutoff)
                if (hi <= lo) continue
                val pkg = Repo.pkgOf(c, bk.uid); val len = (e - s).toDouble()
                var b = lo / BS
                while (b * BS < hi) {
                    val ov = minOf((b + 1) * BS, hi) - maxOf(b * BS, lo)
                    if (ov > 0) { val a = acc[net].getOrPut(Pair(b, pkg)) { LongArray(2) }; a[0] += (bk.rxBytes * ov / len).toLong(); a[1] += (bk.txBytes * ov / len).toLong() }
                    b++
                }
            }
            st.close()
        }
        db.batch {
            if (delFrom >= 0) db.deleteRange(delFrom, delTo)
            for (net in 0..1) for ((k, v) in acc[net]) { db.add(k.first, net, k.second, v[0], v[1]); db.markAlive(k.first) }
        }
        p.edit().putBoolean("backfilled", true).apply()
    }
}

/** Full-history backup as one JSON file (usage rows, monitoring marks, groups, cycle day). */
object Backup {
    fun write(c: Context, out: OutputStream) {
        val w = JsonWriter(OutputStreamWriter(out, "UTF-8")); val db = Db.get(c)
        w.beginObject()
        w.name("app").value("NetUsage"); w.name("version").value(1L)
        w.name("cycleDay").value(Prefs.cycleDay(c).toLong())
        w.name("user").beginArray(); Prefs.users(c).forEach { w.value(it) }; w.endArray()
        w.name("local").beginArray(); Prefs.local(c).forEach { w.value(it) }; w.endArray()
        w.name("usage").beginArray()
        db.eachAll { b, net, pkg, d, u -> w.beginArray().value(b).value(net.toLong()).value(pkg).value(d).value(u).endArray() }
        w.endArray()
        w.name("alive").beginArray(); db.alive(0, Long.MAX_VALUE).forEach { w.value(it) }; w.endArray()
        w.endObject(); w.flush()
    }

    /** Merges a backup into the database (larger value wins per record). Returns the number of usage records read. */
    fun read(c: Context, inp: InputStream): Int {
        val r = JsonReader(InputStreamReader(inp, "UTF-8")); val db = Db.get(c); var n = 0
        fun strings(): Set<String> { val s = HashSet<String>(); r.beginArray(); while (r.hasNext()) s.add(r.nextString()); r.endArray(); return s }
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "cycleDay" -> Prefs.setCycleDay(c, r.nextInt())
            "user" -> Prefs.setUsers(c, strings())
            "local" -> Prefs.setLocal(c, strings())
            "usage" -> { r.beginArray(); db.batch { while (r.hasNext()) {
                r.beginArray(); val b = r.nextLong(); val net = r.nextInt(); val pkg = r.nextString(); val d = r.nextLong(); val u = r.nextLong(); r.endArray()
                db.merge(b, net, pkg, d, u); n++ } }; r.endArray() }
            "alive" -> { r.beginArray(); db.batch { while (r.hasNext()) db.markAlive(r.nextLong()) }; r.endArray() }
            else -> r.skipValue()
        }
        r.endObject()
        c.getSharedPreferences("p", 0).edit().putBoolean("backfilled", true).apply()   // imported history must not be rebuilt
        return n
    }
}
