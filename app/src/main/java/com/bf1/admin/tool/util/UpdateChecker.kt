package com.bf1.admin.tool.util

import android.util.Log
import com.bf1.admin.tool.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "BF1Update"

object UpdateChecker {

    // 你的 GitHub 仓库
    private const val GITHUB_API = "https://api.github.com/repos/acdc-awa/BF1AdminTool/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,   // 例如 "v1.4.0"
        val releaseName: String,     // 例如 "v1.4.0 - 新增自动更新"
        val releaseNotes: String,    // 更新日志（Markdown）
        val downloadUrl: String      // 浏览器打开的链接
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 GitHub 是否有新版本。
     * 返回 [UpdateInfo] 表示有新版本，返回 null 表示已是最新或检查失败。
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "GitHub API returned ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tagName = json.optString("tag_name", "")
            if (tagName.isEmpty()) return@withContext null

            val currentVersion = BuildConfig.VERSION_NAME

            if (isNewer(tagName, currentVersion)) {
                UpdateInfo(
                    latestVersion = tagName,
                    releaseName = json.optString("name", tagName),
                    releaseNotes = json.optString("body", ""),
                    downloadUrl = json.optString("html_url", "")
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Check for update failed", e)
            null
        }
    }

    /**
     * 比较 GitHub tag 和当前版本。
     * 例如 tag = "v1.4.0", current = "1.3.0" → true
     */
    internal fun isNewer(tag: String, current: String): Boolean {
        val remote = tag.removePrefix("v").removePrefix("V")
        val remoteParts = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false // 版本相同
    }
}
