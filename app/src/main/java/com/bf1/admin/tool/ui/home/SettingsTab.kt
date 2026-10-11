package com.bf1.admin.tool.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bf1.admin.tool.BuildConfig
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.ui.admin.AdminViewModel
import com.bf1.admin.tool.ui.common.UpdateDialog
import com.bf1.admin.tool.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab 2: 设置 (Material 3 规范顶级质感)。
 * 聚合：关于、更新、账号管理、Cookie 编辑、服务器管理、使用说明。
 */
@Composable
fun SettingsTab(
    modifier: Modifier = Modifier,
    activeAccount: AccountEntity?,
    decryptedCredentials: AdminViewModel.DecryptedCredentials?,
    servers: List<ServerEntity>,
    isLoading: Boolean,
    lookupServerName: String?,
    lookupServerId: String?,
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

    // 弹窗
    var showAddServerDialog by remember { mutableStateOf(false) }
    var showAccountDetailDialog by remember { mutableStateOf(false) }

    // ── 更新弹窗 ──
    if (showUpdateDialog && updateCheckResult != null) {
        UpdateDialog(
            updateInfo = updateCheckResult!!,
            onDismiss = { showUpdateDialog = false }
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
            lookupServerId = lookupServerId,
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
        // ═══════ 当前账号卡片 (Hero Card) ═══════
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "EA 账号中心",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (activeAccount != null) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "已授权",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (activeAccount != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = activeAccount.name.take(2).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeAccount.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(2.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = "PID: ${activeAccount.personaId}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { showAccountDetailDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Key, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("凭证详情", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = { onShowAccountSwitcher(true) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("更换账号", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else {
                    Text(
                        text = "暂未登录 EA 账号，请先添加登录凭证以使用服务器管理功能。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onNavigateToLogin,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Login, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("登录添加 EA 账号", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // ═══════ 服务器管理卡片 ═══════
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "服务器列表 (${servers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (activeAccount != null) {
                        FilledTonalIconButton(
                            onClick = { showAddServerDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "添加服务器",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (activeAccount == null) {
                    Text(
                        text = "请先登录 EA 账号以载入和添加服务器",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂未添加任何服务器，点击右上角 ＋ 添加",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        servers.forEach { server ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Dns,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = server.serverName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1
                                        )
                                        if (!server.gameId.isNullOrEmpty()) {
                                            Text(
                                                text = "GID: ${server.gameId}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            onPendingDeleteServer(server)
                                            onShowServerDeleteDialog(true)
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ═══════ 关于 & 更新卡片 ═══════
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
            ),
            tonalElevation = 2.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "关于工具",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "战地1 服务器管理工具",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "当前版本：v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))

                // 更新检查按钮
                if (updateCheckResult != null) {
                    val info = updateCheckResult!!
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "发现新版本: ${info.latestVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showUpdateDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("立即更新", fontWeight = FontWeight.SemiBold)
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
                                        snackbarHostState.showSnackbar("检查失败: ${e.message}")
                                    } finally {
                                        isCheckingUpdate = false
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isCheckingUpdate,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Text("重新检查", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            isCheckingUpdate = true
                            coroutineScope.launch {
                                try {
                                    val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
                                    updateCheckResult = result
                                    if (result == null) {
                                        snackbarHostState.showSnackbar("当前已是最新版本")
                                    } else {
                                        showUpdateDialog = true
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("检查更新失败: ${e.message}")
                                } finally {
                                    isCheckingUpdate = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCheckingUpdate,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在检查更新...")
                        } else {
                            Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("检查新版本", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ═══════ 使用说明卡片 ═══════
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "快捷操作说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "• 确保已在设置中选择 EA 账号并选定当前目标服务器。\n• 管理员列表输入框支持玩家名过滤或直接长按/点击删除。\n• 以 # 号开头输入可直接按 personaId (PID) 添加/移除管理员。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ──────────────────────────────────────
// 账号详情弹窗（Cookie 凭证编辑 - M3 规范）
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
        icon = {
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "Cookie 凭证详情",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        shape = RoundedCornerShape(28.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // remid
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "remid 凭证",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(remid)) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = remid,
                        onValueChange = onRemidChange,
                        shape = RoundedCornerShape(12.dp),
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
                        Text(
                            text = "sid 凭证",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = { clipboardManager.setText(AnnotatedString(sid)) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, "复制", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = sid,
                        onValueChange = onSidChange,
                        shape = RoundedCornerShape(12.dp),
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
                enabled = !isLoading,
                shape = RoundedCornerShape(100)
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
                enabled = !isLoading,
                shape = RoundedCornerShape(100)
            ) { Text("关闭") }
        }
    )
}

// ──────────────────────────────────────
// 添加服务器弹窗 (M3 规范)
// ──────────────────────────────────────

@Composable
private fun AddServerDialog(
    lookupServerName: String?,
    lookupServerId: String?,
    isLookingUp: Boolean,
    lookupError: String?,
    isLoading: Boolean,
    onLookup: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var gameId by remember { mutableStateOf("") }

    LaunchedEffect(gameId) { onLookup(gameId) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Default.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = { Text("添加服务器", style = MaterialTheme.typography.headlineSmall) },
        shape = RoundedCornerShape(28.dp),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "请输入 14 位 GameID（可在 GameTools 或服务器分享链接中获取）：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = gameId,
                    onValueChange = {
                        if (it.length <= 14) gameId = it.filter { ch -> ch.isDigit() }
                    },
                    label = { Text("GameID (14位纯数字)") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = lookupError != null
                )

                if (gameId.length == 14) {
                    when {
                        isLookingUp -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "正在查询服务器信息...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        lookupServerName != null -> {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                )
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(
                                        text = "服务器：$lookupServerName",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 2
                                    )
                                    if (lookupServerId != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "ServerID：$lookupServerId",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                        )
                                    }
                                }
                            }
                        }
                        lookupError != null -> {
                            Text(
                                text = lookupError,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(gameId) },
                enabled = !isLoading && gameId.length == 14 && lookupServerName != null,
                shape = RoundedCornerShape(100)
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
                enabled = !isLoading,
                shape = RoundedCornerShape(100)
            ) { Text("取消") }
        }
    )
}
