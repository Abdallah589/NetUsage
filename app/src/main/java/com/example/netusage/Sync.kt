package com.example.netusage

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Connection to ChromeHub on the PC: plain HTTP on the local network, no password, no notifications.
 * The phone is the source of truth for its own records. Every round it
 *   1) says hello (name, groups, cycle day, deleted ranges) and receives the PC's pending commands,
 *   2) sends a digest of its 4-hour chunks and re-sends only the chunks that differ on the PC,
 *   3) sends the names and icons the PC is still missing.
 * If the PC is off, nothing is lost: the next successful round brings the PC up to date.
 */
object Sync {
    private fun sp(c: Context) = c.getSharedPreferences("sync", 0)
    private val running = AtomicBoolean(false)
    private val tombLock = Any()

    fun deviceId(c: Context): String =
        sp(c).getString("id", null) ?: UUID.randomUUID().toString().also { sp(c).edit().putString("id", it).apply() }
    private fun name(c: Context): String = sp(c).getString("name", null) ?: (Build.MODEL ?: "Phone")

    fun status(c: Context): JSONObject { val s = sp(c)
        return JSONObject().put("name", name(c)).put("port", s.getInt("port", 9998)).put("prefix", s.getString("prefix", "192.168.1."))
            .put("from", s.getInt("from", 1)).put("to", s.getInt("to", 254)).put("enabled", s.getBoolean("enabled", false))
            .put("online", s.getBoolean("online", false)).put("ip", s.getString("last_ip", "")).put("lastSync", s.getLong("last_sync", 0))
            .put("forget", s.getBoolean("forget", false)).put("error", s.getString("error", ""))
    }

    fun start(c: Context, name: String, port: Int, prefix: String, from: Int, to: Int) {
        var p = prefix.trim(); if (p.isNotEmpty() && !p.endsWith(".")) p += "."
        val f = from.coerceIn(1, 254)
        sp(c).edit().putString("name", name.ifBlank { Build.MODEL ?: "Phone" }).putInt("port", port.coerceIn(1, 65535))
            .putString("prefix", if (p.isEmpty()) "192.168.1." else p).putInt("from", f).putInt("to", to.coerceIn(f, 254))
            .putBoolean("enabled", true).putBoolean("forget", false).putLong("verified", 0).putLong("last_scan", 0).apply()
    }

    /** "Delete connection": the PC forgets this phone (now, or as soon as it is reachable). */
    fun delete(c: Context) { sp(c).edit().putBoolean("enabled", false).putBoolean("forget", true).putLong("verified", 0).apply() }

    fun resetAudit(c: Context) { sp(c).edit().putLong("verified", 0).apply() }

    /** A previous cycle was deleted on the phone: remove it here and tell the PC (also later, if the PC is off). */
    fun deleteRange(c: Context, from: Long, to: Long) {
        val fb = from / BS; val tb = (to + BS - 1) / BS
        Db.get(c).deleteBuckets(fb, tb)
        synchronized(tombLock) {
            val t = JSONArray(sp(c).getString("tomb", "[]")); t.put(JSONArray().put(fb).put(tb))
            sp(c).edit().putString("tomb", t.toString()).apply()
        }
    }

    private fun http(url: String, body: String?, connectMs: Int, readMs: Int): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = connectMs; con.readTimeout = readMs
        try {
            if (body != null) {
                con.requestMethod = "POST"; con.doOutput = true
                con.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = con.responseCode
            val txt = (if (code in 200..299) con.inputStream else con.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code $txt")
            return txt
        } finally { con.disconnect() }
    }
    private fun post(url: String, j: JSONObject): JSONObject = JSONObject(http(url, j.toString(), 2000, 120000))
    private fun ping(ip: String, port: Int): Boolean =
        try { http("http://$ip:$port/sync/ping", null, 400, 800).contains("chromehub") } catch (e: Exception) { false }

    /** Last known address first; otherwise try prefix.from .. prefix.to in parallel (at most every 5 minutes, unless forced). */
    private fun find(c: Context, force: Boolean): String? {
        val s = sp(c); val port = s.getInt("port", 9998)
        val last = s.getString("last_ip", null)
        if (!last.isNullOrEmpty() && ping(last, port)) return last
        val now = System.currentTimeMillis() / 1000
        if (!force && now - s.getLong("last_scan", 0) < 300) return null
        s.edit().putLong("last_scan", now).apply()
        val prefix = s.getString("prefix", "192.168.1.") ?: "192.168.1."
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(24)
        try {
            val jobs = (s.getInt("from", 1)..s.getInt("to", 254)).map { i ->
                pool.submit(Runnable { if (found.get() == null) { val ip = prefix + i; if (ping(ip, port)) found.compareAndSet(null, ip) } })
            }
            for (j in jobs) try { j.get() } catch (e: Exception) { }
        } finally { pool.shutdownNow() }
        return found.get()
    }

    fun run(c: Context, force: Boolean = false) {
        val s = sp(c)
        if (!s.getBoolean("enabled", false) && !s.getBoolean("forget", false)) return
        val now = System.currentTimeMillis() / 1000
        if (!force && now - s.getLong("last_try", 0) < 25) return
        if (!running.compareAndSet(false, true)) return
        try {
            s.edit().putLong("last_try", now).apply()
            val ip = find(c, force)
            if (ip == null) { s.edit().putBoolean("online", false).apply(); return }
            val base = "http://$ip:${s.getInt("port", 9998)}"
            try {
                if (s.getBoolean("forget", false)) {
                    post("$base/sync/forget", JSONObject().put("id", deviceId(c)))
                    s.edit().putBoolean("forget", false).putBoolean("online", false).apply(); return
                }
                syncAll(c, base, now)
                s.edit().putBoolean("online", true).putString("last_ip", ip).putLong("last_sync", now).putString("error", "").apply()
            } catch (e: Exception) { s.edit().putBoolean("online", false).putString("error", e.message ?: e.toString()).apply() }
        } finally { running.set(false) }
    }

    private fun syncAll(c: Context, base: String, now: Long) {
        val s = sp(c); val db = Db.get(c); val id = deviceId(c)
        val tombSent = synchronized(tombLock) { JSONArray(s.getString("tomb", "[]")) }
        val hello = JSONObject().put("id", id).put("name", name(c)).put("cycle_day", Prefs.cycleDay(c))
            .put("user", JSONArray(Prefs.users(c).toList())).put("local", JSONArray(Prefs.local(c).toList()))
            .put("ack", JSONArray(s.getString("acks", "[]"))).put("deleted", tombSent)
        val r = post("$base/sync/hello", hello)

        synchronized(tombLock) {                 // the PC has applied the deletions we sent
            val cur = JSONArray(s.getString("tomb", "[]")); val keep = JSONArray()
            for (i in 0 until cur.length()) {
                val e = cur.getJSONArray(i); var sent = false
                for (j in 0 until tombSent.length()) { val t = tombSent.getJSONArray(j); if (t.getLong(0) == e.getLong(0) && t.getLong(1) == e.getLong(1)) sent = true }
                if (!sent) keep.put(e)
            }
            s.edit().putString("tomb", keep.toString()).apply()
        }
        val cmds = r.optJSONArray("cmds") ?: JSONArray(); val acks = JSONArray()
        for (i in 0 until cmds.length()) {
            val m = cmds.getJSONObject(i)
            try { applyCmd(c, m) } catch (e: Exception) { }
            acks.put(m.getLong("id"))
        }
        s.edit().putString("acks", acks.toString()).apply()

        val nowChunk = now / BS / 16
        val verified = s.getLong("verified", 0)
        val full = verified <= 0 || now - s.getLong("last_full", 0) > 86400
        val from = if (full) 0L else verified
        val dg = JSONArray(); for (d in db.digests(from)) dg.put(JSONArray().put(d[0]).put(d[1]).put(d[2]).put(d[3]))
        val dr = post("$base/sync/digest", JSONObject().put("id", id).put("from", from).put("to", nowChunk).put("chunks", dg))
        val need = dr.optJSONArray("need") ?: JSONArray()
        var needMeta = r.optJSONArray("need_meta")
        var i = 0
        while (i < need.length()) {
            val batch = JSONArray(); var n = 0
            while (i < need.length() && batch.length() < 8 && n < 60000) {
                val ch = need.getLong(i); val rows = JSONArray()
                for (row in db.chunkRows(ch)) rows.put(JSONArray().put(row[0]).put(row[1]).put(row[2]).put(row[3]).put(row[4]))
                n += rows.length(); batch.put(JSONObject().put("c", ch).put("rows", rows)); i++
            }
            val rr = post("$base/sync/rows", JSONObject().put("id", id).put("chunks", batch))
            needMeta = rr.optJSONArray("need_meta") ?: needMeta
        }
        s.edit().putLong("verified", maxOf(nowChunk - 2, 1L)).apply()
        if (full) s.edit().putLong("last_full", now).apply()

        if (needMeta != null && needMeta.length() > 0) {
            val apps = JSONObject()
            for (k in 0 until needMeta.length()) { val p = needMeta.getString(k); apps.put(p, JSONObject().put("l", Repo.label(c, p)).put("i", Repo.icon(c, p))) }
            post("$base/sync/meta", JSONObject().put("id", id).put("apps", apps))
        }
    }

    private fun applyCmd(c: Context, m: JSONObject) {
        when (m.getString("op")) {
            "group" -> Prefs.setGroup(c, m.getString("pkg"), m.getInt("g"))
            "cycle_day" -> Prefs.setCycleDay(c, m.getInt("day"))
            "delete" -> Db.get(c).deleteBuckets(m.getLong("from") / BS, (m.getLong("to") + BS - 1) / BS)
        }
    }
}
