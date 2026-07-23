package com.bf1.admin.tool.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bf1.admin.tool.BuildConfig
import com.bf1.admin.tool.util.UpdateChecker
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.math.roundToInt

/**
 * 统一的更新提醒弹窗。
 *
 * 根据 [updateInfo] 是否有 APK 资产自动决定走应用内下载还是浏览器下载。
 * 组件内部自行管理下载状态、进度、错误提示，调用方只需负责开关弹窗。
 *
 * @param updateInfo 由 [UpdateChecker.checkForUpdate] 返回的版本信息
 * @param onDismiss  关闭弹窗回调（用户点击"稍后"或下载成功后触发）
 */
@Composable
fun UpdateDialog(
    updateInfo: UpdateChecker.UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val canInApp = updateInfo.apkAssetUrl != null

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf<String?>(null) }

    // updateInfo 变更时重置所有状态，避免关闭重开后残留旧错误/进度
    LaunchedEffect(updateInfo) {
        isDownloading = false
        downloadProgress = 0f
        downloadError = null
        statusText = null
    }

    val startDownload: () -> Unit = {
        isDownloading = true
        downloadError = null
        downloadProgress = 0f
        statusText = "正在选择最快下载通道..."

        coroutineScope.launch {
            try {
                val bestUrl = UpdateChecker.selectFastestProxyUrl(updateInfo.apkAssetUrl!!)
                statusText = "正在下载..."

                val result = UpdateChecker.downloadAndInstall(
                    context, bestUrl
                ) { progress -> downloadProgress = progress }

                if (result.success) {
                    onDismiss()
                } else {
                    downloadError = result.error
                    statusText = null
                    isDownloading = false
                }
            } catch (e: SocketTimeoutException) {
                downloadError = "下载超时，请检查网络后重试"
                statusText = null
                isDownloading = false
            } catch (e: UnknownHostException) {
                downloadError = "网络连接失败，请检查网络后重试"
                statusText = null
                isDownloading = false
            } catch (e: IOException) {
                downloadError = "网络连接失败，请检查网络后重试"
                statusText = null
                isDownloading = false
            } catch (e: ActivityNotFoundException) {
                downloadError = "无法打开安装程序"
                statusText = null
                isDownloading = false
            } catch (e: Exception) {
                downloadError = e.message ?: "未知错误"
                statusText = null
                isDownloading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        icon = {
            if (isDownloading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
        },
        title = {
            Column {
                Text(
                    if (isDownloading) "正在下载..." else "发现新版本",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前 v${BuildConfig.VERSION_NAME} → ${updateInfo.latestVersion}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                if (isDownloading && downloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(downloadProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (isDownloading && statusText != null) {
                    Text(statusText!!, style = MaterialTheme.typography.bodySmall)
                } else if (downloadError != null) {
                    Text(
                        downloadError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (updateInfo.releaseNotes.isNotBlank()) {
                    if (updateInfo.releaseNotes.length > 300) {
                        Text(updateInfo.releaseNotes.take(297) + "...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(updateInfo.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (canInApp) {
                Button(
                    onClick = startDownload,
                    shape = RoundedCornerShape(50),
                    enabled = !isDownloading
                ) {
                    if (isDownloading) CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    else Text("更新")
                }
            } else {
                Button(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                            )
                        } catch (_: Exception) {
                        }
                        onDismiss()
                    },
                    shape = RoundedCornerShape(50)
                ) { Text("前往下载") }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                enabled = !isDownloading
            ) { Text("稍后") }
        }
    )
}
