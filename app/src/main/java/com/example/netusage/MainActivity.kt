package com.example.netusage

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var web: WebView
    private val iconCache = HashMap<String, String>()
    private val reqExport = 11
    private val reqImport = 12

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = FrameLayout(this)
        web = WebView(this).also { root.addView(it) }
        setContentView(root)
        @Suppress("DEPRECATION")
        root.setOnApplyWindowInsetsListener { v, ins -> v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins }
        web.settings.javaScriptEnabled = true
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("file:///android_asset/index.html")

        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29) need.add(Manifest.permission.READ_PHONE_STATE)
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onResume() {
        super.onResume()
        if (Bridge().hasAccess()) try { Mode.apply(this) } catch (e: Exception) { }
        web.evaluateJavascript("window.onResumeApp && onResumeApp()", null)
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_LONG).show() }

    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        Thread {
            try {
                if (req == reqExport) {
                    contentResolver.openOutputStream(uri, "wt")?.use { Backup.write(this, it) }
                    toast("Backup saved")
                } else if (req == reqImport) {
                    val n = contentResolver.openInputStream(uri)!!.use { Backup.read(this, it) }
                    toast("Imported $n records")
                    runOnUiThread { web.evaluateJavascript("window.onResumeApp && onResumeApp()", null) }
                }
            } catch (e: Exception) { toast("Failed: ${e.message}") }
        }.start()
    }

    inner class Bridge {
        @JavascriptInterface fun hasAccess(): Boolean {
            val ops = getSystemService(AppOpsManager::class.java)
            @Suppress("DEPRECATION")
            return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }

        @JavascriptInterface fun openSettings() { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        @JavascriptInterface fun setGroup(pkg: String, g: Int) { Prefs.setGroup(this@MainActivity, pkg, g) }
        @JavascriptInterface fun isSilent(): Boolean = Prefs.silent(this@MainActivity)
        @JavascriptInterface fun setSilent(on: Boolean) { Prefs.setSilent(this@MainActivity, on); Mode.apply(this@MainActivity) }
        @JavascriptInterface fun setCycleDay(d: Int) { Prefs.setCycleDay(this@MainActivity, d) }

        @JavascriptInterface fun exportData() = runOnUiThread {
            val name = "netusage-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), reqExport)
        }

        @JavascriptInterface fun importData() = runOnUiThread {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), reqImport)
        }

        /** 48x48 PNG data URI of the app's icon, or "" when it has none (system entries, removed apps). */
        @JavascriptInterface fun getIcon(pkg: String): String = iconCache.getOrPut(pkg) {
            try {
                val d = packageManager.getApplicationIcon(pkg)
                val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 48, 48); d.draw(Canvas(bmp))
                val bos = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) { "" }
        }

        /** Wi-Fi usage per app between two moments (epoch seconds), from our own history. Used for the selected chart range. */
        @JavascriptInterface fun getApps(from: Long, to: Long): String {
            if (!hasAccess()) return "[]"
            val c = this@MainActivity; val users = Prefs.users(c); val local = Prefs.local(c)
            val a = JSONArray()
            Db.get(c).sumsB(WIFI, (from + BS - 1) / BS, to / BS).forEach { (p, v) ->
                if (v[0] + v[1] > 0) a.put(JSONObject().put("pkg", p).put("label", Repo.label(c, p)).put("down", v[0]).put("up", v[1])
                    .put("g", if (p in local) 2 else if (p in users) 0 else 1))
            }
            return a.toString()
        }

        /**
         * Mobile = today only. Wi-Fi = billing cycle number [cycle] (0 = current, 1 = previous...) or, when [today] is true,
         * today only. Chart series come from our own full history, which is never deleted, only cut into cycles here.
         */
        @JavascriptInterface fun getData(cycle: Int, today: Boolean): String {
            if (!hasAccess()) return """{"noaccess":true}"""
            val c = this@MainActivity
            try { Sampler.sample(c) } catch (e: Exception) { }          // bring our history up to date whenever the UI asks
            val now = System.currentTimeMillis() / 1000
            val users = Prefs.users(c); val local = Prefs.local(c); val day = Prefs.cycleDay(c); val db = Db.get(c)
            fun grp(p: String) = if (p in local) 2 else if (p in users) 0 else 1

            val oldest = db.oldest(WIFI)
            var n = 1
            while (n < 36 && oldest > 0 && cycleStart(day, n - 1) > oldest) n++
            val k = cycle.coerceIn(0, n - 1)
            val s = if (today) midnight() else cycleStart(day, k)
            val e = if (today || k == 0) now else cycleStart(day, k - 1)       // end (exclusive)
            val shownEnd = minOf(e, now)

            fun apps(m: Map<String, LongArray>) = JSONArray().also { a ->
                m.forEach { (p, v) -> if (v[0] + v[1] > 0) a.put(JSONObject().put("pkg", p).put("label", Repo.label(c, p))
                    .put("down", v[0]).put("up", v[1]).put("g", grp(p))) }
            }
            // Wi-Fi apps: the larger of (system counters still retained) and (our own stored history)
            val wifi = HashMap(Repo.query(c, WIFI, s, shownEnd))
            for ((p, v) in db.sums(WIFI, s, e)) { val a = wifi.getOrPut(p) { LongArray(2) }; a[0] = maxOf(a[0], v[0]); a[1] = maxOf(a[1], v[1]) }

            val agg = Array(3) { HashMap<Long, LongArray>() }
            val fromB = s / BS; val toB = (e + BS - 1) / BS
            db.eachRow(WIFI, fromB, toB) { b, p, d, u -> val a = agg[grp(p)].getOrPut(b) { LongArray(2) }; a[0] += d; a[1] += u }
            fun rows(m: Map<Long, LongArray>) = JSONArray().also { r -> m.forEach { (b, v) -> r.put(JSONArray().put(b).put(v[0]).put(v[1])) } }
            val cycles = JSONArray().also { a -> for (i in 0 until n) a.put(JSONArray().put(cycleStart(day, i)).put(if (i == 0) now else cycleStart(day, i - 1))) }

            return JSONObject().put("now", now).put("start", s).put("end", shownEnd).put("bucket", BS)
                .put("cycleDay", day).put("cycle", k).put("cycles", cycles).put("today", today)
                .put("mobile", apps(Repo.query(c, MOBILE, midnight(), now)))
                .put("wifi", apps(wifi))
                .put("rows", JSONObject().put("user", rows(agg[0])).put("other", rows(agg[1])).put("local", rows(agg[2])))
                .put("alive", JSONArray(db.alive(fromB, toB))).toString()
        }
    }
}
