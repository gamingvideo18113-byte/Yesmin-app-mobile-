package com.yesmin.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** Auto-update: GitHub Releases ka latest APK check, download, install (install par user ek tap karega). */
object Updater {
    data class Info(val version: String, val url: String, val notes: String)

    suspend fun check(ctx: Context): Result<Info?> = withContext(Dispatchers.IO) {
        runCatching {
            val u = Prefs.updateUrl
            if (u.isBlank()) return@runCatching null
            val body = Gemini.http.newCall(Request.Builder().url(u).header("Accept", "application/vnd.github+json").build())
                .execute().use { it.body?.string() ?: "" }
            val j = JSONObject(body)
            val tag = j.getString("tag_name").trimStart('v')
            val assets = j.optJSONArray("assets")
            var apk: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
            }
            val cur = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
            if (apk != null && newer(tag, cur)) Info(tag, apk, j.optString("body")) else null
        }
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }; val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    fun install(ctx: Context, url: String) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(DownloadManager.Request(Uri.parse(url))
            .setTitle("Yesmin update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(ctx, null, "yesmin-update.apk"))
        val rc = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                val uri = dm.getUriForDownloadedFile(id) ?: return
                c.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                try { c.unregisterReceiver(this) } catch (_: Exception) {}
            }
        }
        ContextCompat.registerReceiver(ctx, rc, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}
