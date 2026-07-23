package com.bf1.admin.tool.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val isRefreshingAdminList by viewModel.isRefreshingAdminList.collectAsState()
    val expiredAccount by viewModel.expiredAccount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val decryptedCredentials by viewModel.decryptedCredentials.collectAsState()
    val lookupServerName by viewModel.lookupServerName.collectAsState()
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

    // 账号失效
    if (expiredAccount != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearExpiredAccount() },
            title = { Text("账号失效") },
            text = { Text("当前帐号 ${expiredAccount?.name} cookies已失效，是否更新？") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearExpiredAccount()
                    onNavigateToLogin()
                }) { Text("是") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.clearExpiredAccount() }) { Text("否") }
            }
        )
    }

    // 账号切换器
    if (showAccountSwitcher) {
        AlertDialog(
            onDismissRequest = { showAccountSwitcher = false },
            title = { Text("选择账号") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    accounts.forEach { account ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            val rowModifier = if (activeAccount?.id != account.id) {
                                Modifier.fillMaxWidth().clickable {
                                    viewModel.switchAccount(account)
                                    showAccountSwitcher = false
                                }.padding(12.dp)
                            } else {
                                Modifier.fillMaxWidth().padding(12.dp)
                            }
                            Row(
                                modifier = rowModifier,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val letters = account.name.take(2).uppercase()
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
                                    Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                    if (activeAccount?.id == account.id) {
                                        Text(
                                            "当前登录", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(
                                    onClick = {
                                        pendingDeleteAccount = account
                                        showAccountDeleteDialog = true
                                    },
                                    modifier = Modifier.height(32.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) { Text("移除") }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = {
                            showAccountSwitcher = false
                            onNavigateToLogin()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text("添加其他账号")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAccountSwitcher = false }) { Text("关闭") }
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
            title = { Text("删除账号") },
            text = { Text("你确定要删除吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAccount?.let { viewModel.deleteAccount(it) }
                        showAccountDeleteDialog = false
                        pendingDeleteAccount = null
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    )
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAccountDeleteDialog = false
                        pendingDeleteAccount = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
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
            title = { Text("删除服务器") },
            text = { Text("你确定要删除吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteServer?.let { viewModel.deleteServer(it) }
                        showServerDeleteDialog = false
                        pendingDeleteServer = null
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    )
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showServerDeleteDialog = false
                        pendingDeleteServer = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
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
            title = { Text("移除管理员") },
            text = { Text("你确定要移除管理员 ${pendingDeleteAdmin?.displayName} 吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAdmin?.let { viewModel.removeAdminFromList(it) }
                        showAdminDeleteDialog = false
                        pendingDeleteAdmin = null
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    )
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAdminDeleteDialog = false
                        pendingDeleteAdmin = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
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
    // 主布局
    // ═══════════════════════════════════════════

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (selectedTab == 0) "管理员管理" else "设置")
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("上下管理") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
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
            1 -> SettingsTab(
                modifier = Modifier.padding(padding),
                activeAccount = activeAccount,
                decryptedCredentials = decryptedCredentials,
                servers = servers,
                isLoading = isLoading,
                lookupServerName = lookupServerName,
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
