package com.bf1.admin.tool.ui.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.repository.ServerRefreshService
import com.bf1.admin.tool.data.repository.ServerRepository
import com.bf1.admin.tool.data.session.PlayerResolveSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BF1AdminApp
    private val accountRepo = app.accountRepository
    private val serverRepo = app.serverRepository
    private val adminRepo = app.adminRepository
    private val credentialManager = app.credentialManager
    private val serverRefreshService: ServerRefreshService = app.serverRefreshService

    val accounts: StateFlow<List<AccountEntity>> = accountRepo.allAccounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeAccount = MutableStateFlow<AccountEntity?>(null)
    val activeAccount: StateFlow<AccountEntity?> = _activeAccount.asStateFlow()

    val servers: StateFlow<List<ServerEntity>> = _activeAccount.filterNotNull()
        .flatMapLatest { serverRepo.getServersByOwner(it.personaId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeServer = MutableStateFlow<ServerEntity?>(null)
    val activeServer: StateFlow<ServerEntity?> = _activeServer.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _expiredAccount = MutableStateFlow<AccountEntity?>(null)
    val expiredAccount: StateFlow<AccountEntity?> = _expiredAccount.asStateFlow()

    fun clearExpiredAccount() {
        _expiredAccount.value = null
    }

    sealed class AdminListUiState {
        object Idle : AdminListUiState()
        object Loading : AdminListUiState()
        object NoServer : AdminListUiState()
        data class Success(val list: List<EAApiService.AdminInfo>) : AdminListUiState()
        data class NetworkError(val message: String) : AdminListUiState()
        data class AuthExpired(val message: String) : AdminListUiState()
        data class Error(val message: String) : AdminListUiState()
    }

    private val _adminList = MutableStateFlow<List<EAApiService.AdminInfo>>(emptyList())
    val adminList: StateFlow<List<EAApiService.AdminInfo>> = _adminList.asStateFlow()

    private val _adminListUiState = MutableStateFlow<AdminListUiState>(AdminListUiState.Loading)
    val adminListUiState: StateFlow<AdminListUiState> = _adminListUiState.asStateFlow()

    private val _isRefreshingAdminList = MutableStateFlow(false)
    val isRefreshingAdminList: StateFlow<Boolean> = _isRefreshingAdminList.asStateFlow()

    private val _isRefreshingServers = MutableStateFlow(false)
    val isRefreshingServers: StateFlow<Boolean> = _isRefreshingServers.asStateFlow()

    // ── 凭证查看 ──
    data class DecryptedCredentials(val remid: String, val sid: String)

    private val _decryptedCredentials = MutableStateFlow<DecryptedCredentials?>(null)
    val decryptedCredentials: StateFlow<DecryptedCredentials?> = _decryptedCredentials.asStateFlow()

    // ── 服务器查询（用于设置页添加服务器弹窗）──
    private val _lookupServerName = MutableStateFlow<String?>(null)
    val lookupServerName: StateFlow<String?> = _lookupServerName.asStateFlow()

    private val _lookupServerId = MutableStateFlow<String?>(null)
    val lookupServerId: StateFlow<String?> = _lookupServerId.asStateFlow()

    private val _isLookingUpServer = MutableStateFlow(false)
    val isLookingUpServer: StateFlow<Boolean> = _isLookingUpServer.asStateFlow()

    private val _lookupError = MutableStateFlow<String?>(null)
    val lookupError: StateFlow<String?> = _lookupError.asStateFlow()

    private var lookupGeneration = 0L

    init {
        // 跟随 Room 服务器列表流同步当前选中服务器的最新名称与 gameId
        viewModelScope.launch {
            servers.collect { list ->
                val currentActive = list.firstOrNull { it.isActive }
                if (currentActive != null) {
                    _activeServer.value = currentActive
                } else if (_activeServer.value != null && list.none { it.id == _activeServer.value?.id }) {
                    _activeServer.value = null
                }
            }
        }

        // 跟随 Room 账号表实时同步当前账号的解密凭证（EA cookie 轮换落库后立刻同步到「查看Cookies」）
        viewModelScope.launch {
            combine(
                accountRepo.allAccounts,
                _activeAccount.map { it?.id }.distinctUntilChanged()
            ) { list, activeId ->
                if (activeId == null) null else list.firstOrNull { it.id == activeId }
            }
                .distinctUntilChangedBy { it?.let { a -> Triple(a.id, a.remid, a.sid) } }
                .collect { account ->
                    if (account == null) {
                        _decryptedCredentials.value = null
                    } else {
                        try {
                            val enc = withContext(Dispatchers.IO) { accountRepo.getDecryptedById(account.id) }
                            _decryptedCredentials.value = enc?.let {
                                DecryptedCredentials(remid = it.remid, sid = it.sid)
                            }
                        } catch (e: Exception) {
                            _message.emit("加载凭证失败: ${e.message}")
                        }
                    }
                }
        }

        viewModelScope.launch {
            _adminListUiState.value = AdminListUiState.Loading
            var account = accountRepo.getActive()
            if (account == null) {
                val firstAccount = accountRepo.allAccounts.first().firstOrNull()
                if (firstAccount != null) {
                    accountRepo.switchActive(firstAccount.id)
                    account = firstAccount
                }
            }
            _activeAccount.value = account
            if (account != null) {
                val server = serverRepo.getActiveByOwner(account.personaId)
                _activeServer.value = server
                // 启动时静默按需刷新服务器元数据（带 30 分钟冷却）
                launchSilentServerRefresh(account)
                if (server != null) {
                    try {
                        initSession(account)
                        loadAdminList(isInitial = true)
                    } catch (e: Exception) {
                        _adminListUiState.value = classifyError(e)
                    }
                } else {
                    _adminListUiState.value = AdminListUiState.NoServer
                }
            } else {
                _adminListUiState.value = AdminListUiState.Idle
            }
        }
    }

    fun switchAccount(account: AccountEntity) {
        viewModelScope.launch {
            accountRepo.switchActive(account.id)
            _activeAccount.value = account
            val server = serverRepo.getActiveByOwner(account.personaId)
            _activeServer.value = server
            // 切换账号后静默按需刷新新账号的服务器元数据（带 30 分钟冷却）
            launchSilentServerRefresh(account)
            if (server != null) {
                _adminListUiState.value = AdminListUiState.Loading
                try {
                    initSession(account)
                    loadAdminList(isInitial = true)
                } catch (e: Exception) {
                    _adminListUiState.value = classifyError(e)
                }
            } else {
                _adminList.value = emptyList()
                _adminListUiState.value = AdminListUiState.NoServer
            }
        }
    }

    /** 启动/切换账号时静默刷新服务器名称与 gameId（受 30 分钟冷却约束）。 */
    private fun launchSilentServerRefresh(account: AccountEntity) {
        viewModelScope.launch {
            try {
                serverRefreshService.refreshAccountServers(account.personaId, force = false)
            } catch (_: EAApiService.CredentialsExpiredException) {
                if (_activeAccount.value?.id == account.id) {
                    _expiredAccount.value = account
                }
            } catch (_: Exception) {
                // 启动静默刷新不打扰用户
            }
        }
    }

    /** 设置页手动刷新当前账号下的全部服务器（无冷却）。 */
    fun refreshServersManually() {
        val account = _activeAccount.value ?: return
        if (_isRefreshingServers.value) return
        viewModelScope.launch {
            _isRefreshingServers.value = true
            try {
                val summary = serverRefreshService.refreshAccountServers(account.personaId, force = true)
                val msg = when {
                    summary.total == 0 -> "当前账号暂无服务器"
                    summary.failed == 0 && summary.updated > 0 ->
                        "已刷新 ${summary.total} 台服务器，${summary.updated} 台有更新"
                    summary.failed == 0 ->
                        "已检查 ${summary.total} 台服务器，均为最新"
                    summary.updated + summary.unchanged == 0 ->
                        "刷新失败: ${summary.firstError ?: "请检查网络或权限"}"
                    else ->
                        "刷新完成：成功 ${summary.updated + summary.unchanged}/${summary.total} 台，失败 ${summary.failed} 台"
                }
                _message.emit(msg)
            } catch (e: EAApiService.CredentialsExpiredException) {
                _expiredAccount.value = account
                _message.emit("刷新失败：EA 凭证已过期，请重新登录")
            } catch (e: Exception) {
                _message.emit("刷新失败: ${e.message ?: "未知错误"}")
            } finally {
                _isRefreshingServers.value = false
            }
        }
    }

    fun switchServer(server: ServerEntity) {
        viewModelScope.launch {
            val account = _activeAccount.value ?: return@launch
            serverRepo.switchActive(account.personaId, server.id)
            _activeServer.value = server
            loadAdminList(isInitial = true)
        }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch {
            accountRepo.delete(account)
            if (_activeAccount.value?.id == account.id) {
                val remaining = accountRepo.allAccounts.first().filter { it.id != account.id }
                val nextActive = remaining.firstOrNull()
                if (nextActive != null) {
                    switchAccount(nextActive)
                } else {
                    _activeAccount.value = null
                    _activeServer.value = null
                    _decryptedCredentials.value = null
                    _adminList.value = emptyList()
                    _adminListUiState.value = AdminListUiState.Idle
                }
            }
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            serverRepo.delete(server)
            if (_activeServer.value?.id == server.id) {
                val account = _activeAccount.value ?: return@launch
                val nextServer = serverRepo.getActiveByOwner(account.personaId)
                _activeServer.value = nextServer
                if (nextServer != null) {
                    loadAdminList(isInitial = true)
                } else {
                    _adminList.value = emptyList()
                    _adminListUiState.value = AdminListUiState.NoServer
                }
            }
        }
    }

    fun executeAdminAction(playerInput: String, isAdd: Boolean) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val server = _activeServer.value ?: throw Exception("请先选择服务器")

                val players = playerInput.split(",", "，", " ", "\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                if (players.isEmpty()) throw Exception("请输入玩家名")

                val results = mutableListOf<String>()
                for (player in players) {
                    try {
                        val isDirect = player.startsWith("#")
                        val resolve = if (isDirect) null
                        else withContext(Dispatchers.IO) { credentialManager.resolvePlayerName(player) }
                        val personaId = resolve?.personaId ?: player.removePrefix("#")
                        if (personaId.isBlank() || personaId == "null") {
                            throw Exception("无法解析玩家 $player 的 PID")
                        }
                        withSessionRetry { sessionId ->
                            withContext(Dispatchers.IO) {
                                if (isAdd) adminRepo.addAdmin(sessionId, server.serverId, personaId)
                                else adminRepo.removeAdmin(sessionId, server.serverId, personaId)
                            }
                        }
                        val source = when {
                            isDirect -> ""
                            resolve?.source == PlayerResolveSource.EA -> " (EA)"
                            else -> " (gametools)"
                        }
                        results.add("$player: 成功$source")
                    } catch (e: Exception) {
                        val detail = if (e.message != null) e.message!!
                            else "${e.javaClass.simpleName}"
                        results.add("$player: 失败 ($detail)")
                    }
                }
                _message.emit(results.joinToString("\n"))
                loadAdminList()
            } catch (e: Exception) {
                _message.emit("操作失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeAdminFromList(admin: EAApiService.AdminInfo) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val server = _activeServer.value ?: throw Exception("请先选择服务器")
                withSessionRetry { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.removeAdmin(sessionId, server.serverId, admin.personaId)
                    }
                }
                _message.emit("${admin.displayName}: 成功")
                loadAdminList()
            } catch (e: Exception) {
                _message.emit("${admin.displayName}: 失败 (${e.message})")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadAdminList(isInitial: Boolean = false) {
        viewModelScope.launch {
            val server = _activeServer.value
            if (server == null) {
                _adminList.value = emptyList()
                _adminListUiState.value = AdminListUiState.NoServer
                return@launch
            }
            if (isInitial || _adminList.value.isEmpty()) {
                _adminListUiState.value = AdminListUiState.Loading
            }
            _isRefreshingAdminList.value = true
            try {
                val list = withSessionRetry { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getAdminList(sessionId, server.serverId)
                    }
                }
                _adminList.value = list
                _adminListUiState.value = AdminListUiState.Success(list)
            } catch (e: Exception) {
                val classified = classifyError(e)
                _adminListUiState.value = classified
                _message.emit("获取管理员列表失败: ${e.message ?: "未知错误"}")
            } finally {
                _isRefreshingAdminList.value = false
            }
        }
    }

    // ── 服务器查询（用于设置页添加服务器弹窗）──
    fun lookupServer(gameId: String) {
        val generation = ++lookupGeneration
        if (gameId.length != 14) {
            _lookupServerName.value = null
            _lookupServerId.value = null
            _lookupError.value = null
            _isLookingUpServer.value = false
            return
        }
        viewModelScope.launch {
            _isLookingUpServer.value = true
            _lookupServerName.value = null
            _lookupServerId.value = null
            _lookupError.value = null
            try {
                val rsp = credentialManager.withActiveSession { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getFullServerDetails(sessionId, gameId)
                    }
                }
                if (generation == lookupGeneration) {
                    _lookupServerName.value = rsp.serverSettings.name
                    _lookupServerId.value = rsp.serverId
                }
            } catch (e: Exception) {
                if (generation == lookupGeneration) {
                    _lookupServerName.value = null
                    _lookupServerId.value = null
                    _lookupError.value = e.message ?: "服务器查询失败，请稍后重试。"
                }
            } finally {
                if (generation == lookupGeneration) {
                    _isLookingUpServer.value = false
                }
            }
        }
    }

    fun addServerFromSettings(gameId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val rsp = credentialManager.withActiveSession { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getFullServerDetails(sessionId, gameId)
                    }
                }
                val account = _activeAccount.value ?: return@launch
                val guid = rsp.persistedGameId.takeIf { it.isNotBlank() }
                val (rowId, isUpdated) = serverRepo.addOrUpdateServer(
                    serverId = rsp.serverId,
                    serverName = rsp.serverSettings.name,
                    ownerPersonaId = account.personaId,
                    gameId = rsp.gameId,
                    guid = guid
                )
                serverRepo.switchActive(account.personaId, rowId)
                _activeServer.value = ServerEntity(
                    id = rowId,
                    serverId = rsp.serverId,
                    serverName = rsp.serverSettings.name,
                    ownerPersonaId = account.personaId,
                    isActive = true,
                    gameId = rsp.gameId,
                    guid = guid
                )
                _message.emit(
                    if (isUpdated) "已更新服务器信息: ${rsp.serverSettings.name}"
                    else "已添加服务器: ${rsp.serverSettings.name}"
                )
                onSuccess()
            } catch (e: Exception) {
                _message.emit("添加服务器失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Session 管理由 CredentialManager 统一处理。
    // ═══════════════════════════════════════════════════

    /**
     * 初始化 / 切换账号时验证凭证并获取 session。
     * 统一缓存由 CredentialManager 管理，这里仅触发一次确保有效性。
     */
    private suspend fun initSession(account: AccountEntity) {
        try {
            // 仅验证 session 有效性
            withContext(Dispatchers.IO) { credentialManager.getActiveSessionId() }
        } catch (e: EAApiService.CredentialsExpiredException) {
            _expiredAccount.value = account
            throw e
        }
    }

    /**
     * 带 session 过期自动重试的 API 调用包装。
     *
     * 统一管理器会在 session/auth 错误时清除持久化缓存并完成一次重新兑换。
     * remid/sid 过期时标记账户过期，通知用户重新登录。
     */
    private suspend fun <T> withSessionRetry(block: suspend (sessionId: String) -> T): T {
        try {
            return credentialManager.withActiveSession(block)
        } catch (ce: EAApiService.CredentialsExpiredException) {
            _expiredAccount.value = _activeAccount.value
            throw ce
        }
    }

    private fun classifyError(e: Throwable): AdminListUiState {
        val message = e.message.orEmpty()
        val isAuthExpired = e is EAApiService.CredentialsExpiredException ||
            message.contains("Credentials expired", ignoreCase = true) ||
            message.contains("登录已过期", ignoreCase = true) ||
            message.contains("Session expired", ignoreCase = true) ||
            message.contains("401", ignoreCase = true) ||
            message.contains("-32501", ignoreCase = true) ||
            message.contains("AUTHORIZATION_REQUIRED", ignoreCase = true)

        if (isAuthExpired) {
            return AdminListUiState.AuthExpired(if (message.isNotBlank()) message else "EA 凭证已过期，请重新登录")
        }

        val isNetwork = e is java.net.UnknownHostException ||
            e is java.net.SocketTimeoutException ||
            e is java.net.ConnectException ||
            e is java.net.SocketException ||
            (e is java.io.IOException && (
                message.contains("timeout", ignoreCase = true) ||
                message.contains("connect", ignoreCase = true) ||
                message.contains("SSL", ignoreCase = true) ||
                message.contains("route", ignoreCase = true) ||
                message.contains("unreachable", ignoreCase = true)
            ))

        if (isNetwork) {
            return AdminListUiState.NetworkError(if (message.isNotBlank()) message else "网络连接超时或无法访问，请检查网络设置")
        }

        return AdminListUiState.Error(if (message.isNotBlank()) message else "获取管理员列表失败，请稍后重试")
    }
}
