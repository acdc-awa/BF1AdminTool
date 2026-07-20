package com.bf1.admin.tool.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.bf1.admin.tool.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "BF1Update"

object UpdateChecker {

    private const val GITHUB_API =
        "https://api.github.com/repos/acdc-awa/BF1AdminTool/releases/latest"

    data class UpdateInfo(
        val latestVersion: String,
        val releaseName: String,
        val releaseNotes: String,
        val downloadUrl: String,     // GitHub release 页面（浏览器打开）
        val apkAssetUrl: String?     // APK 直链（用于 APP 内下载，无 APK 资产时为 null）
    )

    data class DownloadResult(
        val success: Boolean,
        val error: String? = null
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    // 下载用 client，超时更长
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    /**
     * 检查 GitHub 是否有新版本。
     * 返回 [UpdateInfo] 表示有新版本，null 表示已是最新或出错。
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

            if (!isNewer(tagName, BuildConfig.VERSION_NAME)) return@withContext null

            // 查找 .apk 资产
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            UpdateInfo(
                latestVersion = tagName,
                releaseName = json.optString("name", tagName),
                releaseNotes = json.optString("body", ""),
                downloadUrl = json.optString("html_url", ""),
                apkAssetUrl = apkUrl
            )
        } catch (e: Exception) {
            Log.w(TAG, "Check for update failed", e)
            null
        }
    }

    /**
     * 下载 APK 到缓存目录并触发安装。
     * 返回 [DownloadResult]。
     */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates")
            if (!updateDir.exists()) updateDir.mkdirs()

            val apkFile = File(updateDir, "update.apk")
            if (apkFile.exists()) apkFile.delete()

            val request = Request.Builder().url(apkUrl).build()
            val response = downloadClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext DownloadResult(false, "下载失败: HTTP ${response.code}")
            }

            val body = response.body ?: return@withContext DownloadResult(false, "响应体为空")
            val totalBytes = body.contentLength()

            val source = body.source()
            val sink = apkFile.sink().buffer()
            var downloaded = 0L

            // 带缓冲的流式下载
            val buffer = okio.Buffer()
            while (!source.exhausted()) {
                val read = source.read(buffer, 8192)
                if (read == -1L) break
                sink.write(buffer, read)
                downloaded += read
            }
            source.close()
            sink.close()

            if (totalBytes > 0 && apkFile.length() < totalBytes) {
                return@withContext DownloadResult(false, "下载不完整")
            }

            // FileProvider URI
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            // 触发安装
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(installIntent)
            DownloadResult(true)
        } catch (e: Exception) {
            Log.e(TAG, "Download & install failed", e)
            DownloadResult(false, e.message ?: "未知错误")
        }
    }

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
        return false
    }
}
