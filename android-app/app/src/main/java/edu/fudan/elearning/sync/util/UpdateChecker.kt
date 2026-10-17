package edu.fudan.elearning.sync.util

import edu.fudan.elearning.sync.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppUpdate(val version: String, val url: String)

object UpdateChecker {
    private const val RELEASES_URL = "https://api.github.com/repos/lsx626/fuxiaoxue/releases/latest"

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

    suspend fun check(): AppUpdate? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "FuXiaoXue/${BuildConfig.VERSION_NAME}")
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val version = (json.optString("tag_name") ?: "").removePrefix("v").trim()
                val url =
                    json.optString("html_url").ifBlank {
                        "https://github.com/lsx626/fuxiaoxue/releases"
                    }
                if (version.isBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) null
                else AppUpdate(version, url)
            }
        }

    fun isNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

    private fun compareVersions(left: String, right: String): Int {
        val a = left.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val b = right.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(a.size, b.size)) {
            val delta = (a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 })
            if (delta != 0) return delta.coerceIn(-1, 1)
        }
        return 0
    }
}
