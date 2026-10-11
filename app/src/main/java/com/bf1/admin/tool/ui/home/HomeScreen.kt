package com.bf1.admin.tool.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.ui.admin.AdminViewModel
import com.bf1.admin.tool.ui.cardtool.CardToolScreen
import com.bf1.admin.tool.ui.common.UpdateDialog
import com.bf1.admin.tool.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主页容器：TopBar + 中间内容区（两个 tab） + BottomBar。
 * 所有跨 tab 的弹窗提升到此处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToLogin: () -> Unit,
    viewModel: AdminViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val activeAccount by viewModel.activeAccount.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val adminList by viewModel.adminList.collectAsState()
    val adminListUiState by viewModel.adminListUiState.collectAsState()
    val isRefreshingAdminList by viewModel.isRefreshingAdminList.collectAsState()
    val expiredAccount by viewModel.expiredAccount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val decryptedCredentials by viewModel.decryptedCredentials.collectAsState()
    val lookupServerName by viewModel.lookupServerName.collectAsState()
    val lookupServerId by viewModel.lookupServerId.collectAsState()
    val isLookingUpServer by viewModel.isLookingUpServer.collectAsState()
    val lookupError by viewModel.lookupError.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    // 玩家输入（AdminTab 共享）
    var playerInput by remember { mutableStateOf("") }

    // 弹窗状态
    var showAccountSwitcher by remember { mutableStateOf(false) }
    var showAccountDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var showServerDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteServer by remember { mutableStateOf<ServerEntity?>(null) }
    var showAdminDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteAdmin by remember { mutableStateOf<EAApiService.AdminInfo?>(null) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // 首次加载检查更新
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }?.let { info ->
                updateInfo = info
                showUpdateDialog = true
            }
        } catch (_: Exception) {
            // 静默处理，用户可手动到设置页检查
        }
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    // ═══════════════════════════════════════════
    // 弹窗
    // ═══════════════════════════════════════════

    // ═══════════════════════════════════════════
    // 弹窗体系 (Material 3 规范)
    // ═══════════════════════════════════════════

    // 账号失效
    if (expiredAccount != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearExpiredAccount() },
            icon = {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    "账号凭证失效",
                    style = MaterialTheme.typography.headlineSmall
                )
            },
            text = {
                Text(
                    "当前账号 ${expiredAccount?.name} 的 Cookie 已过期，是否立即重新登录？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearExpiredAccount()
                        onNavigateToLogin()
                    }
                ) { Text("重新登录") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearExpiredAccount() }) { Text("稍后") }
            }
        )
    }

    // 账号切换器
    if (showAccountSwitcher) {
        AlertDialog(
            onDismissRequest = { showAccountSwitcher = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text("切换账号", style = MaterialTheme.typography.headlineSmall)
            },
            shape = RoundedCornerShape(28.dp),
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    accounts.forEach { account ->
                        val isSelected = activeAccount?.id == account.id
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    if (!isSelected) {
                                        viewModel.switchAccount(account)
                                    }
                                    showAccountSwitcher = false
                                },
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            border = if (isSelected) {
                                androidx.compose.foundation.BorderStroke(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary
                                )
                            } else null
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val letters = account.name.take(2).uppercase()
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = letters,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = account.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = if (isSelected) androidx.compose.ui.text.font.FontWeight.SemiBold else androidx.compose.ui.text.font.FontWeight.Normal
                                    )
                                    if (isSelected) {
                                        Text(
                                            text = "当前使用中",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        pendingDeleteAccount = account
                                        showAccountDeleteDialog = true
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "移除账号",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedButton(
                        onClick = {
                            showAccountSwitcher = false
                            onNavigateToLogin()
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("登录添加其他账号")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountSwitcher = false }) { Text("完成") }
            }
        )
    }

    // 删除账号确认
    if (showAccountDeleteDialog && pendingDeleteAccount != null) {
        AlertDialog(
            onDismissRequest = {
                showAccountDeleteDialog = false
                pendingDeleteAccount = null
            },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("删除账号", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "确定要删除账号「${pendingDeleteAccount?.name}」吗？该账号保存的凭据将被清理。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAccount?.let { viewModel.deleteAccount(it) }
                        showAccountDeleteDialog = false
                        pendingDeleteAccount = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAccountDeleteDialog = false
                        pendingDeleteAccount = null
                    }
                ) { Text("取消") }
            }
        )
    }

    // 删除服务器确认
    if (showServerDeleteDialog && pendingDeleteServer != null) {
        AlertDialog(
            onDismissRequest = {
                showServerDeleteDialog = false
                pendingDeleteServer = null
            },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("删除服务器", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "确定要从列表中移除服务器「${pendingDeleteServer?.serverName}」吗？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteServer?.let { viewModel.deleteServer(it) }
                        showServerDeleteDialog = false
                        pendingDeleteServer = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showServerDeleteDialog = false
                        pendingDeleteServer = null
                    }
                ) { Text("取消") }
            }
        )
    }

    // 移除管理员确认
    if (showAdminDeleteDialog && pendingDeleteAdmin != null) {
        AlertDialog(
            onDismissRequest = {
                showAdminDeleteDialog = false
                pendingDeleteAdmin = null
            },
            icon = {
                Icon(
                    Icons.Default.PersonRemove,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = { Text("移除管理员", style = MaterialTheme.typography.headlineSmall) },
            text = {
                Text(
                    "确定要从服务器管理列表中移除「${pendingDeleteAdmin?.displayName}」吗？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            shape = RoundedCornerShape(28.dp),
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAdmin?.let { viewModel.removeAdminFromList(it) }
                        showAdminDeleteDialog = false
                        pendingDeleteAdmin = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("确认移除") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdminDeleteDialog = false
                        pendingDeleteAdmin = null
                    }
                ) { Text("取消") }
            }
        )
    }

    // 更新提醒弹窗
    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { showUpdateDialog = false }
        )
    }

    // ═══════════════════════════════════════════
    // 主布局 (Material 3 标准脚手架)
    // ═══════════════════════════════════════════

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (selectedTab) {
                            0 -> "上下管理"
                            1 -> "卡行动"
                            else -> "设置"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                },
                actions = {
                    if (activeAccount != null) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { showAccountSwitcher = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = activeAccount?.name?.take(1)?.uppercase() ?: "A",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = activeAccount?.name ?: "",
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 3.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.AdminPanelSettings, contentDescription = null) },
                    label = { Text("上下管理") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                    label = { Text("卡行动") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            0 -> AdminManagementTab(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding),
                servers = servers,
                activeServer = activeServer,
                activeAccount = activeAccount,
                adminList = adminList,
                isRefreshingAdminList = isRefreshingAdminList,
                isLoading = isLoading,
                uiState = adminListUiState,
                onNavigateToLogin = onNavigateToLogin,
                onSwitchServer = { viewModel.switchServer(it) },
                onRefreshAdminList = { viewModel.loadAdminList() },
                onAddAdmin = { viewModel.executeAdminAction(it, true) },
                onRemoveAdmin = { viewModel.executeAdminAction(it, false) },
                onDeleteAdmin = { admin ->
                    pendingDeleteAdmin = admin
                    showAdminDeleteDialog = true
                },
                playerInput = playerInput,
                onPlayerInputChange = { playerInput = it }
            )
            1 -> CardToolScreen(
                modifier = Modifier
                    .padding(padding)
                    .consumeWindowInsets(padding)
            )
            2 -> SettingsTab(
                modifier = Modifier.padding(padding),
                activeAccount = activeAccount,
                decryptedCredentials = decryptedCredentials,
                servers = servers,
                isLoading = isLoading,
                lookupServerName = lookupServerName,
                lookupServerId = lookupServerId,
                isLookingUpServer = isLookingUpServer,
                lookupError = lookupError,
                onShowAccountSwitcher = { showAccountSwitcher = it },
                onShowServerDeleteDialog = { showServerDeleteDialog = it },
                onPendingDeleteServer = { pendingDeleteServer = it },
                onNavigateToLogin = onNavigateToLogin,
                onSaveCredentials = { remid, sid -> viewModel.saveCredentials(remid, sid) },
                onLookupServer = { viewModel.lookupServer(it) },
                onAddServer = { serverId, onSuccess ->
                    viewModel.addServerFromSettings(serverId, onSuccess)
                },
                snackbarHostState = snackbarHostState
            )
        }
    }
}
