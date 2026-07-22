package com.bf1.admin.tool.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bf1.admin.tool.BuildConfig
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.ui.admin.AdminViewModel
import com.bf1.admin.tool.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Tab 1: 设置。
 * 聚合：关于、更新、账号信息、Cookie 编辑、服务器管理、使用说明。
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    activeAccount: AccountEntity?,
    decryptedCredentials: AdminViewModel.DecryptedCredentials?,
    servers: List<ServerEntity>,
    isLoading: Boolean,
    lookupServerName: String?,
    isLookingUpServer: Boolean,
    lookupError: String?,
    onShowAccountSwitcher: (Boolean) -> Unit,
    onShowServerDeleteDialog: (Boolean) -> Unit,
    onPendingDeleteServer: (ServerEntity?) -> Unit,
    onNavigateToLogin: () -> Unit,
    onSaveCredentials: (String, String) -> Unit,
    onLookupServer: (String) -> Unit,
    onAddServer: (String, () -> Unit) -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 凭证编辑状态
    var remid by remember(decryptedCredentials) {
        mutableStateOf(decryptedCredentials?.remid ?: "")
    }
    var sid by remember(decryptedCredentials) {
        mutableStateOf(decryptedCredentials?.sid ?: "")
    }

    // 更新检查状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    // 弹窗
    var showAddServerDialog by remember { mutableStateOf(false) }
    var showAccountDetailDialog by remember { mutableStateOf(false) }

    // ── 更新弹窗 ──
    if (showUpdateDialog && updateCheckResult != null) {
        val info = updateCheckResult!!
        val canInApp = info.apkAssetUrl != null

        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
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
                        "当前 v${BuildConfig.VERSION_NAME} → ${info.latestVersion}",
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
                    } else if (isDownloading) {
                        Text("正在下载更新包，请稍候...", style = MaterialTheme.typography.bodySmall)
                    } else if (downloadError != null) {
                        Text(
                            downloadError!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (info.releaseNotes.isNotBlank()) {
                        Text(info.releaseNotes.take(300), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (canInApp) {
                    Button(
                        onClick = {
                            isDownloading = true
                            downloadError = null
                            coroutineScope.launch {
                                try {
                                    val bestUrl = UpdateChecker.selectFastestProxyUrl(info.apkAssetUrl!!)
                                    val result = UpdateChecker.downloadAndInstall(
                                        context, bestUrl
                                    ) { progress -> downloadProgress = progress }
                                    if (result.success) {
                                        showUpdateDialog = false
                                    } else {
                                        downloadError = result.error
                                        isDownloading = false
                                    }
                                } catch (e: Exception) {
                                    downloadError = e.message
                                    isDownloading = false
                                }
                            }
                        },
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
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl)))
                            } catch (_: Exception) {
                            }
                            showUpdateDialog = false
                        },
                        shape = RoundedCornerShape(50)
                    ) { Text("前往下载") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpdateDialog = false },
                    enabled = !isDownloading
                ) { Text("稍后") }
            }
        )
    }

    // ── 账号详情弹窗 ──
    if (showAccountDetailDialog && activeAccount != null) {
        AccountDetailDialog(
            remid = remid,
            sid = sid,
            isLoading = isLoading,
            onRemidChange = { remid = it },
            onSidChange = { sid = it },
            onSave = { onSaveCredentials(remid, sid) },
            onDismiss = { showAccountDetailDialog = false }
        )
    }

    // ── 添加服务器弹窗 ──
    if (showAddServerDialog) {
        AddServerDialog(
            lookupServerName = lookupServerName,
            isLookingUp = isLookingUpServer,
            lookupError = lookupError,
            isLoading = isLoading,
            onLookup = onLookupServer,
            onAdd = { serverId ->
                onAddServer(serverId) { showAddServerDialog = false }
            },
            onDismiss = { showAddServerDialog = false }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ═══════ 当前账号 ═══════
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("当前账号", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (activeAccount != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val letters = activeAccount.name.take(2).uppercase()
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFFF38A41),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    letters, color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(activeAccount.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Persona ID: ${activeAccount.personaId}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAccountDetailDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("账号详情")
                        }
                        OutlinedButton(
                            onClick = { onShowAccountSwitcher(true) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("更换账号")
                        }
                    }
                } else {
                    Text(
                        "未登录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onNavigateToLogin, modifier = Modifier.fillMaxWidth()) {
                        Text("添加 EA 账号")
                    }
                }
            }
        }

        // ═══════ 服务器管理 ═══════
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("服务器管理", style = MaterialTheme.typography.titleMedium)
                    if (activeAccount != null) {
                        IconButton(
                            onClick = { showAddServerDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Add, "添加服务器", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (activeAccount == null) {
                    Text(
                        "请先登录账号",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (servers.isEmpty()) {
                    Text(
                        "暂无服务器，点击 ＋ 添加",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    servers.forEach { server ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Storage, null,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                server.serverName,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 2
                            )
                            IconButton(
                                onClick = {
                                    onPendingDeleteServer(server)
                                    onShowServerDeleteDialog(true)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete, "删除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ═══════ 关于 & 更新 ═══════
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("关于", style = MaterialTheme.typography.titleMedium)
                    Icon(
                        Icons.Default.Info, null, Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("BF1 管理员工具", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "作者:acdc",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本: ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // 更新检查按钮
                if (updateCheckResult != null) {
                    val info = updateCheckResult!!
                    Text(
                        "发现新版本: ${info.latestVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { showUpdateDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("更新")
                        }
                        OutlinedButton(
                            onClick = {
                                isCheckingUpdate = true
                                coroutineScope.launch {
                                    try {
                                        val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
                                        updateCheckResult = result
                                        if (result == null) {
                                            snackbarHostState.showSnackbar("已是最新版本")
                                        } else {
                                            showUpdateDialog = true
                                        }
                                    } catch (e: Exception) {
                                        // 失败时保留旧结果，不覆盖
                                        snackbarHostState.showSnackbar("检查失败: ${e.message}")
                                    } finally {
                                        isCheckingUpdate = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isCheckingUpdate,
                            shape = RoundedCornerShape(50)
                        ) {
                            if (isCheckingUpdate) CircularProgressIndicator(
                                Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            else Text("重新检查")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            isCheckingUpdate = true
                            coroutineScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
                                    updateCheckResult = result
                                    if (result == null) {
                                        snackbarHostState.showSnackbar("已是最新版本")
                                    } else {
                                        showUpdateDialog = true
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("检查失败: ${e.message}")
                                } finally {
                                    isCheckingUpdate = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCheckingUpdate,
                        shape = RoundedCornerShape(50)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查...")
                        } else {
                            Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("检查更新")
                        }
                    }
                }
            }
        }

        // ═══════ 使用说明 ═══════
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用说明", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. 确保已登录 EA 账号并选择服务器",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "2. 输入玩家名，多个用逗号或空格分隔",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "3. 以 # 开头可直接输入 personaId/pid",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ──────────────────────────────────────
// 账号详情弹窗（Cookie 凭证编辑）
// ──────────────────────────────────────

@Composable
private fun AccountDetailDialog(
    remid: String,
    sid: String,
    isLoading: Boolean,
    onRemidChange: (String) -> Unit,
    onSidChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("账号详情") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // remid
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("remid", style = MaterialTheme.typography.titleSmall)
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(remid)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(18.dp))
                        }
                    }
                    OutlinedTextField(
                        value = remid,
                        onValueChange = onRemidChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                }

                // sid
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("sid", style = MaterialTheme.typography.titleSmall)
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(sid)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(18.dp))
                        }
                    }
                    OutlinedTextField(
                        value = sid,
                        onValueChange = onSidChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
                enabled = !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("保存并验证")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) { Text("关闭") }
        }
    )
}

// ──────────────────────────────────────
// 添加服务器弹窗
// ──────────────────────────────────────

@Composable
private fun AddServerDialog(
    lookupServerName: String?,
    isLookingUp: Boolean,
    lookupError: String?,
    isLoading: Boolean,
    onLookup: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var serverId by remember { mutableStateOf("") }

    LaunchedEffect(serverId) { onLookup(serverId) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("添加服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "请输入 8 位服务器 ID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = serverId,
                    onValueChange = {
                        if (it.length <= 8) serverId = it.filter { c -> c.isDigit() }
                    },
                    label = { Text("服务器 ID (8位数字)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = lookupError != null
                )

                if (serverId.length == 8) {
                    when {
                        isLookingUp -> Text(
                            "正在查询服务器信息...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        lookupServerName != null -> Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                "服务器：$lookupServerName",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2
                            )
                        }
                        lookupError != null -> Text(
                            lookupError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(serverId) },
                enabled = !isLoading && serverId.length == 8 && lookupServerName != null
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("添加")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isLoading
            ) { Text("取消") }
        }
    )
}
