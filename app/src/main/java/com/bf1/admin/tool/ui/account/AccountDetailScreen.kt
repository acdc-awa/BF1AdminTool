package com.bf1.admin.tool.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.BuildConfig
import com.bf1.admin.tool.util.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    accountId: Long,
    onNavigateToLogin: () -> Unit,
    onBack: () -> Unit,
    viewModel: AccountDetailViewModel = viewModel()
) {
    val account by viewModel.account.collectAsState()
    val remid by viewModel.remid.collectAsState()
    val sid by viewModel.sid.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 更新检查状态
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateCheckResult by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(accountId) {
        viewModel.loadAccount(accountId)
    }
    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    // 更新弹窗
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
                        "${info.latestVersion} → 当前 v${BuildConfig.VERSION_NAME}",
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
                if (canInApp) {
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
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(info.downloadUrl)))
                            } catch (_: Exception) {}
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
                    enabled = !isDownloading
                ) {
                    Text("稍后")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (account == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("账号不存在")
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ═══════ 关于 ═══════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("关于", style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.Info, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("BF1 管理员工具", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("当前版本: ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ═══════ 更新 ═══════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("软件更新", style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Default.SystemUpdate, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))

                    if (updateCheckResult != null) {
                        // 已经检查到了新版本
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
                                        val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
                                        updateCheckResult = result
                                        isCheckingUpdate = false
                                        if (result == null) {
                                            snackbarHostState.showSnackbar("已是最新版本")
                                        } else {
                                            showUpdateDialog = true
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isCheckingUpdate,
                                shape = RoundedCornerShape(50)
                            ) {
                                if (isCheckingUpdate) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                else Text("重新检查")
                            }
                        }
                    } else {
                        // 还没检查或已是最新版
                        Button(
                            onClick = {
                                isCheckingUpdate = true
                                coroutineScope.launch {
                                    val result = withContext(Dispatchers.IO) { UpdateChecker.checkForUpdate() }
                                    updateCheckResult = result
                                    isCheckingUpdate = false
                                    if (result == null) {
                                        snackbarHostState.showSnackbar("已是最新版本")
                                    } else {
                                        showUpdateDialog = true
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isCheckingUpdate,
                            shape = RoundedCornerShape(50)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
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

            // ═══════ 账号信息 ═══════
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("账号信息", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("名称: ${account!!.name}", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Text("Persona ID: ${account!!.personaId}", style = MaterialTheme.typography.bodySmall)
                }
            }

            // remid field
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = remid,
                        onValueChange = { viewModel.updateRemid(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }

            // sid field
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sid,
                        onValueChange = { viewModel.updateSid(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                ) {
                    Text("退出")
                }
                Button(
                    onClick = onNavigateToLogin,
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("更新账号")
                }
                Button(
                    onClick = { viewModel.saveAndVerify() },
                    modifier = Modifier.weight(1f),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("保存")
                    }
                }
            }
        }
    }
}
