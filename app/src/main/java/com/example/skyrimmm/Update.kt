package com.example.skyrimmm

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class UpdateInfo(val tag: String, val url: String, val apkUrl: String?, val notes: String)

/** Looks up the newest published GitHub release of this app. Only public repositories can be read this way. */
object Updater {
    fun latest(repo: String): UpdateInfo? {
        if (!repo.contains('/')) return null
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "SkyrimModManager")
        try {
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", true)) { apk = a.optString("browser_download_url"); break }
            }
            return UpdateInfo(o.getString("tag_name"), o.optString("html_url"), apk, o.optString("body"))
        } finally { c.disconnect() }
    }

    /** True if the release tag (for example v1.3.7 or v1.3.7-beta) is newer than the installed version name. */
    fun isNewer(current: String, tag: String): Boolean {
        fun clean(v: String) = v.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        return !verAtLeast(clean(current), clean(tag))
    }
}
