package com.bf1.admin.tool.ui.server

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.data.repository.AdminRepository
import com.bf1.admin.tool.data.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddServerViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as BF1AdminApp).database
    private val accountRepo = AccountRepository(db.accountDao(), application)
    private val serverRepo = ServerRepository(db.serverDao())
    private val adminRepo = AdminRepository(accountRepo)

    private var cachedSessionId: String? = null

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _serverName = MutableStateFlow<String?>(null)
    val serverName: StateFlow<String?> = _serverName.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private val _success = MutableSharedFlow<Unit>()
    val success: SharedFlow<Unit> = _success.asSharedFlow()

    fun lookupServer(serverId: String) {
        if (serverId.length != 8) {
            _serverName.value = null
            return
        }
        viewModelScope.launch {
            try {
                val sessionId = ensureSession()
                val (_, name) = withContext(Dispatchers.IO) {
                    adminRepo.getServerDetails(sessionId, serverId)
                }
                _serverName.value = name
            } catch (_: Exception) {
                _serverName.value = null
            }
        }
    }

    fun addServer(serverId: String) {
        val name = _serverName.value ?: return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val account = accountRepo.getActive() ?: throw Exception("请先登录 EA 账号")
                serverRepo.addServer(serverId, name, account.personaId)
                _message.emit("已添加服务器: $name")
                _success.emit(Unit)
            } catch (e: Exception) {
                _message.emit("添加失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun ensureSession(): String {
        cachedSessionId?.let { return it }
        val encrypted = accountRepo.getActiveEncrypted()
            ?: throw Exception("请先登录 EA 账号")
        val session = withContext(Dispatchers.IO) {
            adminRepo.authenticate(encrypted.remid, encrypted.sid).getOrThrow()
        }
        cachedSessionId = session.sessionId
        return session.sessionId
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onBack: () -> Unit,
    viewModel: AddServerViewModel = viewModel()
) {
    var serverId by remember { mutableStateOf("") }
    val isLoading by viewModel.isLoading.collectAsState()
    val serverName by viewModel.serverName.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.success.collect { onBack() }
    }
    // 输入满 8 位自动查询服务器名称（参考 add_admin.py get_server_name）
    LaunchedEffect(serverId) {
        viewModel.lookupServer(serverId)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("添加服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("输入服务器 ID", style = MaterialTheme.typography.titleMedium)
            Text("请输入 8 位服务器 ID", style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = serverId,
                onValueChange = {
                    if (it.length <= 8) serverId = it.filter { c -> c.isDigit() }
                },
                label = { Text("服务器 ID (8位数字)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = serverId.length == 8 && serverName == null && serverId.isNotEmpty()
            )

            // 显示查询到的服务器名称（前10字符）
            if (serverId.length == 8) {
                if (serverName != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = "服务器: ${serverName!!.take(10)}${if (serverName!!.length > 10) "..." else ""}",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                } else {
                    Text(
                        text = "正在查询服务器信息...",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Button(
                onClick = { viewModel.addServer(serverId) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && serverId.length == 8 && serverName != null
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("添加服务器")
                }
            }
        }
    }
}
