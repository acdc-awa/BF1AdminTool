package com.bf1.admin.tool.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.bf1.admin.tool.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private const val TAG = "BF1Update"

object UpdateChecker {

    private const val GITHUB_API =
        "https://api.github.com/repos/acdc-awa/BF1AdminTool/releases/latest"

    /** GitHub 下载代理列表（按默认优先级排序） */
    private val PROXY_LIST = listOf(
        "https://cdn.gh-proxy.org/",
        "https://gh-proxy.org/",
        "https://v6.gh-proxy.org/",
        "https://v4.gh-proxy.org/",
    )

    data class UpdateInfo(
        val latestVersion: String,
        val releaseName: String,
        val releaseNotes: String,
        val downloadUrl: String,     // GitHub release 页面（浏览器打开）
        val apkAssetUrl: String?     // APK 直链（原始 GitHub URL，下载时选代理；无 APK 资产时为 null）
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

    // 测速用 client，超时很短
    private val speedTestClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * 检查 GitHub 是否有新版本。
     * @return [UpdateInfo] 有新版本，null 已是最新，[[Exception]] 网络/API 错误。
     */
    @Throws(Exception::class)
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GITHUB_API)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("GitHub API 返回 HTTP ${response.code}")
        }

        val body = response.body?.string()
            ?: throw Exception("GitHub API 响应体为空")

        val json = JSONObject(body)

        val tagName = json.optString("tag_name", "")
        if (tagName.isEmpty()) {
            throw Exception("GitHub API 未返回 tag_name")
        }

        if (!isNewer(tagName, BuildConfig.VERSION_NAME)) return@withContext null

        // 查找 .apk 资产（保留原始 URL，代理在下载时动态选择）
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
    }

    /**
     * 从代理列表中测速选出最快的 URL。
     * 返回最快的完整下载链接，如果所有代理都不可用则返回原始链接。
     */
    suspend fun selectFastestProxyUrl(rawApkUrl: String): String = withContext(Dispatchers.IO) {
        if (rawApkUrl.isBlank()) return@withContext rawApkUrl

        data class SpeedResult(val url: String, val elapsedMs: Long)

        val results = PROXY_LIST.map { proxy ->
            async {
                val proxyUrl = proxy + rawApkUrl
                try {
                    val start = System.nanoTime()
                    val request = Request.Builder()
                        .url(proxyUrl)
                        .head()
                        .build()
                    val response = speedTestClient.newCall(request).execute()
                    response.close()
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (response.isSuccessful || response.code in 300..399) {
                        SpeedResult(proxyUrl, elapsed)
                    } else {
                        Log.d(TAG, "Proxy $proxy returned ${response.code} in ${elapsed}ms")
                        null
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "Proxy $proxy unreachable: ${e.message}")
                    null
                }
            }
        }.awaitAll().filterNotNull()

        if (results.isEmpty()) {
            Log.w(TAG, "All proxies unreachable, using direct URL")
            rawApkUrl
        } else {
            val best = results.minBy { it.elapsedMs }
            Log.i(TAG, "Selected proxy: ${best.url} (${best.elapsedMs}ms)")
            best.url
        }
    }

    /**
     * 下载 APK 到缓存目录并触发安装。
     * @param onProgress 下载进度回调 (0f..1f)，在 IO 线程调用。
     * @return [DownloadResult]。
     */
    suspend fun downloadAndInstall(
        context: Context,
        apkUrl: String,
        onProgress: ((Float) -> Unit)? = null
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
            val buffer = okio.Buffer()
            var lastReport = 0L

            while (!source.exhausted()) {
                val read = source.read(buffer, 8192)
                if (read == -1L) break
                sink.write(buffer, read)
                downloaded += read

                // 每 64KB 或总计每 2% 回报一次进度
                if (totalBytes > 0 && (downloaded - lastReport >= 65536 || downloaded >= totalBytes)) {
                    lastReport = downloaded
                    onProgress?.invoke(downloaded.toFloat() / totalBytes)
                }
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
