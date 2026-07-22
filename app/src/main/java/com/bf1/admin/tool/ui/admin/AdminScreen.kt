package com.bf1.admin.tool.ui.admin

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.BuildConfig
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    onNavigateToLogin: () -> Unit,
    onNavigateToAddServer: () -> Unit,
    onNavigateToAccountDetail: (Long) -> Unit = {},
    serverAddedMessage: String? = null,
    onServerAddedMessageShown: () -> Unit = {},
    viewModel: AdminViewModel = viewModel()
) {
    val accounts by viewModel.accounts.collectAsState()
    val activeAccount by viewModel.activeAccount.collectAsState()
    val servers by viewModel.servers.collectAsState()
    val activeServer by viewModel.activeServer.collectAsState()
    val adminList by viewModel.adminList.collectAsState()
    val isRefreshingAdminList by viewModel.isRefreshingAdminList.collectAsState()
    val welcomeMessage by viewModel.welcomeMessage.collectAsState()
    val expiredAccount by viewModel.expiredAccount.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    var playerInput by remember { mutableStateOf("") }
    var showAccountMenu by remember { mutableStateOf(false) }
    var showAccountSwitcherDialog by remember { mutableStateOf(false) }
    var showServerMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var showServerDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteServer by remember { mutableStateOf<ServerEntity?>(null) }
    var showAdminDeleteDialog by remember { mutableStateOf(false) }
    var pendingDeleteAdmin by remember { mutableStateOf<com.bf1.admin.tool.data.remote.EAApiService.AdminInfo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 首次加载时检查更新
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            UpdateChecker.checkForUpdate()
        }?.let { info ->
            updateInfo = info
            showUpdateDialog = true
        }
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(serverAddedMessage) {
        serverAddedMessage?.let {
            snackbarHostState.showSnackbar(it)
            onServerAddedMessageShown()
        }
    }

    // Delete confirmation dialogs
    if (showDeleteDialog && pendingDeleteAccount != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false; pendingDeleteAccount = null },
            title = { Text("删除账号") },
            text = { Text("你确定要删除吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAccount?.let { viewModel.deleteAccount(it) }
                        showDeleteDialog = false
                        pendingDeleteAccount = null
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    )
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false; pendingDeleteAccount = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                ) {
                    Text("取消")
                }
            }
        )
    }

    // 更新提醒弹窗
    if (showUpdateDialog && updateInfo != null) {
        val info = updateInfo!!
        var isDownloading by remember { mutableStateOf(false) }
        var downloadError by remember { mutableStateOf<String?>(null) }
        val canInAppUpdate = info.apkAssetUrl != null

        AlertDialog(
            onDismissRequest = { if (!isDownloading) showUpdateDialog = false },
            icon = {
                if (isDownloading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.primary)
            },
            title = {
                Column {
                    Text(
                        if (isDownloading) "正在下载..."
                        else "发现新版本",
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${info.latestVersion} → 当前 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column {
                    if (isDownloading) {
                        Text("正在下载更新包，请稍候...", style = MaterialTheme.typography.bodySmall)
                    } else if (downloadError != null) {
                        Text(downloadError!!, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    } else if (info.releaseNotes.isNotBlank()) {
                        Text(info.releaseNotes.take(300), style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (canInAppUpdate) {
                    Button(
                        onClick = {
                            isDownloading = true
                            downloadError = null
                            coroutineScope.launch {
                                val result = UpdateChecker.downloadAndInstall(context, info.apkAssetUrl!!)
                                if (!result.success) {
                                    downloadError = result.error
                                    isDownloading = false
                                }
                                // 安装界面已弹出，不再关弹窗
                            }
                        },
                        shape = RoundedCornerShape(50),
                        enabled = !isDownloading
                    ) {
                        if (isDownloading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("APP 内更新")
                    }
                } else {
                    Button(
                        onClick = {
                            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))) }
                            catch (_: Exception) {}
                            showUpdateDialog = false
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("前往下载")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUpdateDialog = false },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray),
                    enabled = !isDownloading
                ) {
                    Text("稍后")
                }
            }
        )
    }

    if (expiredAccount != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearExpiredAccount() },
            title = { Text("账号失效") },
            text = { Text("当前帐号 ${expiredAccount?.name} cookies已失效，是否更新？") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearExpiredAccount()
                        onNavigateToLogin()
                    }
                ) {
                    Text("是")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.clearExpiredAccount() },
                ) {
                    Text("否")
                }
            }
        )
    }

    if (showAccountSwitcherDialog) {
        AlertDialog(
            onDismissRequest = { showAccountSwitcherDialog = false },
            title = { Text("选择账号") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    accounts.forEach { account ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val rowModifier = if (activeAccount?.id != account.id) {
                                Modifier.fillMaxWidth().clickable {
                                    viewModel.switchAccount(account)
                                    showAccountSwitcherDialog = false
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
                                        Text(letters, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.bodyLarge)
                                    if (activeAccount?.id == account.id) {
                                        Text("当前登录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    TextButton(
                                        onClick = {
                                            pendingDeleteAccount = account
                                            showDeleteDialog = true
                                        },
                                        modifier = Modifier.height(32.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Text("移除")
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    TextButton(
                        onClick = {
                            showAccountSwitcherDialog = false
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
                TextButton(onClick = { showAccountSwitcherDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }

    if (showServerDeleteDialog && pendingDeleteServer != null) {
        AlertDialog(
            onDismissRequest = { showServerDeleteDialog = false; pendingDeleteServer = null },
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
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showServerDeleteDialog = false; pendingDeleteServer = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                ) {
                    Text("取消")
                }
            }
        )
    }

    if (showAdminDeleteDialog && pendingDeleteAdmin != null) {
        AlertDialog(
            onDismissRequest = { showAdminDeleteDialog = false; pendingDeleteAdmin = null },
            title = { Text("移除管理员") },
            text = { Text("你确定要移除管理员 ${pendingDeleteAdmin?.displayName} 吗？") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeleteAdmin?.let {
                            viewModel.removeAdminFromList(it)
                        }
                        showAdminDeleteDialog = false
                        pendingDeleteAdmin = null
                    },
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White
                    )
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAdminDeleteDialog = false; pendingDeleteAdmin = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Gray)
                ) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("上下管理工具") },
                actions = {
                    Box(modifier = Modifier.padding(end = 16.dp)) {
                        val avatarLetters = activeAccount?.name?.take(2)?.uppercase() ?: "请登录"
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFFF38A41),
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(50))
                                .clickable { 
                                    if (activeAccount == null) {
                                        onNavigateToLogin()
                                    } else {
                                        showAccountMenu = true 
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = avatarLetters,
                                    color = Color.White,
                                    style = if (activeAccount == null) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall
                                )
                            }
                        }
                        DropdownMenu(expanded = showAccountMenu, onDismissRequest = { showAccountMenu = false }) {
                            DropdownMenuItem(
                                text = { 
                                    val accountText = activeAccount?.name?.let { "当前账户：$it" } ?: "未登录"
                                    Text(accountText, style = MaterialTheme.typography.titleMedium) 
                                },
                                onClick = { }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("设置") },
                                onClick = {
                                    showAccountMenu = false
                                    activeAccount?.let { onNavigateToAccountDetail(it.id) }
                                },
                                leadingIcon = { Icon(Icons.Default.Settings, null) }
                            )
                            DropdownMenuItem(
                                text = { Text("更换账号") },
                                onClick = {
                                    showAccountMenu = false
                                    showAccountSwitcherDialog = true
                                },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Welcome Message
            item {
                if (welcomeMessage != null) {
                    Text(
                        text = welcomeMessage!!,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            // Server selector
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("当前服务器", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Box {
                            OutlinedButton(
                                onClick = { showServerMenu = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Dns, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(activeServer?.serverName ?: "未选择服务器", maxLines = 1)
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                            DropdownMenu(expanded = showServerMenu, onDismissRequest = { showServerMenu = false }) {
                                if (activeAccount == null) {
                                    DropdownMenuItem(
                                        text = { Text("请先登录账号") },
                                        onClick = { showServerMenu = false }
                                    )
                                } else {
                                    servers.forEach { server ->
                                        DropdownMenuItem(
                                            text = { Text(server.serverName, maxLines = 2) },
                                            onClick = {
                                                viewModel.switchServer(server)
                                                showServerMenu = false
                                            },
                                            trailingIcon = {
                                                IconButton(onClick = {
                                                    showServerMenu = false
                                                    pendingDeleteServer = server
                                                    showServerDeleteDialog = true
                                                }) {
                                                    Icon(Icons.Default.Delete, "删除", tint = MaterialTheme.colorScheme.error)
                                                }
                                            }
                                        )
                                    }
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("添加服务器") },
                                        onClick = { showServerMenu = false; onNavigateToAddServer() },
                                        leadingIcon = { Icon(Icons.Default.Add, null) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Player input
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("增减管理员", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = playerInput,
                            onValueChange = { playerInput = it },
                            label = { Text("玩家名（逗号/空格分隔，#personaId）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 5
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = { viewModel.executeAdminAction(playerInput, true) },
                                modifier = Modifier.weight(1f),
                                enabled = !isLoading && playerInput.isNotBlank()
                            ) {
                                if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else { Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("添加管理员") }
                            }
                            OutlinedButton(
                                onClick = { viewModel.executeAdminAction(playerInput, false) },
                                modifier = Modifier.weight(1f),
                                enabled = !isLoading && playerInput.isNotBlank()
                            ) {
                                if (isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else { Icon(Icons.Default.PersonRemove, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("移除管理员") }
                            }
                        }
                    }
                }
            }

            // Admin List
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("管理员列表 (${adminList.size})", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { viewModel.loadAdminList() }) {
                        if (isRefreshingAdminList) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, "刷新")
                        }
                    }
                }
            }

            if (adminList.isEmpty()) {
                item {
                    Text(
                        text = if (isRefreshingAdminList) "正在加载..." else "暂无管理员",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                items(adminList, key = { it.personaId }) { admin ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(admin.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text("PID: ${admin.personaId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                pendingDeleteAdmin = admin
                                showAdminDeleteDialog = true
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "移除管理员",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            // Help text
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("使用说明", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text("1. 确保已登录 EA 账号并选择服务器", style = MaterialTheme.typography.bodySmall)
                        Text("2. 输入玩家名，多个用逗号或空格分隔", style = MaterialTheme.typography.bodySmall)
                        Text("3. 以 # 开头可直接输入 personaId/pid", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
