package com.bf1.admin.tool.ui.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.data.repository.AdminRepository
import com.bf1.admin.tool.data.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminViewModel(application: Application) : AndroidViewModel(application) {
    private val db = (application as BF1AdminApp).database
    private val accountRepo = AccountRepository(db.accountDao(), application)
    private val serverRepo = ServerRepository(db.serverDao())
    private val adminRepo = AdminRepository(accountRepo)

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

    private val _welcomeMessage = MutableStateFlow<String?>(null)
    val welcomeMessage: StateFlow<String?> = _welcomeMessage.asStateFlow()

    private val _expiredAccount = MutableStateFlow<AccountEntity?>(null)
    val expiredAccount: StateFlow<AccountEntity?> = _expiredAccount.asStateFlow()

    fun clearExpiredAccount() {
        _expiredAccount.value = null
    }

    private val _adminList = MutableStateFlow<List<EAApiService.AdminInfo>>(emptyList())
    val adminList: StateFlow<List<EAApiService.AdminInfo>> = _adminList.asStateFlow()

    private val _isRefreshingAdminList = MutableStateFlow(false)
    val isRefreshingAdminList: StateFlow<Boolean> = _isRefreshingAdminList.asStateFlow()

    init {
        viewModelScope.launch {
            val account = accountRepo.getActive()
            _activeAccount.value = account
            if (account != null) {
                _activeServer.value = serverRepo.getActiveByOwner(account.personaId)
                initSession(account)
                loadAdminList()
            }
        }
    }

    fun switchAccount(account: AccountEntity) {
        viewModelScope.launch {
            accountRepo.switchActive(account.id)
            _activeAccount.value = account
            _activeServer.value = serverRepo.getActiveByOwner(account.personaId)
            _welcomeMessage.value = null
            initSession(account)
            loadAdminList()
        }
    }

    fun switchServer(server: ServerEntity) {
        viewModelScope.launch {
            val account = _activeAccount.value ?: return@launch
            serverRepo.switchActive(account.personaId, server.id)
            _activeServer.value = server
            loadAdminList()
        }
    }

    fun deleteAccount(account: AccountEntity) {
        viewModelScope.launch {
            accountRepo.delete(account)
            if (_activeAccount.value?.id == account.id) {
                val newActive = accountRepo.getActive()
                _activeAccount.value = newActive
                _activeServer.value = newActive?.let { serverRepo.getActiveByOwner(it.personaId) }
                if (newActive != null) {
                    initSession(newActive)
                    loadAdminList()
                } else {
                    _adminList.value = emptyList()
                }
            }
        }
    }

    fun deleteServer(server: ServerEntity) {
        viewModelScope.launch {
            serverRepo.delete(server)
            if (_activeServer.value?.id == server.id) {
                val account = _activeAccount.value ?: return@launch
                _activeServer.value = serverRepo.getActiveByOwner(account.personaId)
                loadAdminList()
            }
        }
    }

    fun addServer(serverId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val (id, name) = withSessionRetry { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getServerDetails(sessionId, serverId)
                    }
                }
                val account = _activeAccount.value ?: return@launch
                val newId = serverRepo.addServer(id, name, account.personaId)
                serverRepo.switchActive(account.personaId, newId)
                _activeServer.value = ServerEntity(newId, id, name, account.personaId, true)
                _message.emit("已添加服务器: $name")
            } catch (e: Exception) {
                _message.emit("添加服务器失败: ${e.message}")
            } finally {
                _isLoading.value = false
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
                        val personaId = if (player.startsWith("#")) {
                            player.removePrefix("#")
                        } else {
                            withContext(Dispatchers.IO) { adminRepo.resolvePlayerName(player) }
                        }
                        withSessionRetry { sessionId ->
                            withContext(Dispatchers.IO) {
                                if (isAdd) adminRepo.addAdmin(sessionId, server.serverId, personaId)
                                else adminRepo.removeAdmin(sessionId, server.serverId, personaId)
                            }
                        }
                        results.add("$player: 成功")
                    } catch (e: Exception) {
                        results.add("$player: 失败 (${e.message})")
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

    fun loadAdminList() {
        viewModelScope.launch {
            val server = _activeServer.value
            if (server == null) {
                _adminList.value = emptyList()
                return@launch
            }
            _isRefreshingAdminList.value = true
            try {
                val list = withSessionRetry { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getAdminList(sessionId, server.serverId)
                    }
                }
                _adminList.value = list
            } catch (e: Exception) {
                _message.emit("获取管理员列表失败: ${e.message}")
            } finally {
                _isRefreshingAdminList.value = false
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Session 管理（内部缓存由 EAApiService 处理）
    // ═══════════════════════════════════════════════════

    /**
     * 初始化 / 切换账号时验证凭证并获取 session。
     * 内部缓存由 EAApiService 管理（2h TTL），这里仅触发一次确保有效性。
     */
    private suspend fun initSession(account: AccountEntity) {
        try {
            val encrypted = accountRepo.getActiveEncrypted() ?: return

            val sessionId = withContext(Dispatchers.IO) {
                adminRepo.ensureSessionId(account.id, encrypted.remid, encrypted.sid)
            }

            val welcome = withContext(Dispatchers.IO) {
                adminRepo.getWelcomeMessage(sessionId)
            }
            _welcomeMessage.value = welcome
        } catch (e: EAApiService.CredentialsExpiredException) {
            _welcomeMessage.value = null
            _expiredAccount.value = account
        } catch (e: Exception) {
            _welcomeMessage.value = null
            // 网络等瞬态错误静默处理，下次操作时 withSessionRetry 会自动重试
        }
    }

    /**
     * 获取有效的 sessionId。
     * 内部缓存由 EAApiService 管理（2h TTL，Mutex 防并发刷新）。
     */
    private suspend fun getSessionId(): String {
        val encrypted = accountRepo.getActiveEncrypted()
            ?: throw Exception("请先登录 EA 账号")
        val account = _activeAccount.value
            ?: throw Exception("没有活跃账号")
        return withContext(Dispatchers.IO) {
            adminRepo.ensureSessionId(account.id, encrypted.remid, encrypted.sid)
        }
    }

    /**
     * 带 session 过期自动重试的 API 调用包装。
     *
     * 因为 EAApiService 内部已经有 2h TTL 缓存 + Mutex 保护，
     * 这里的重试主要处理 sessionId 被 EA 服务端提前失效的边界情况。
     *
     * - API 返回 session/auth 错误 → 清除缓存，强制刷新 sessionId 后重试
     * - remid/sid 也过期 → 标记账户过期，通知用户重新登录
     */
    private suspend fun <T> withSessionRetry(block: suspend (sessionId: String) -> T): T {
        if (_expiredAccount.value != null) {
            throw EAApiService.CredentialsExpiredException("凭证已过期，请重新登录")
        }
        try {
            val sessionId = getSessionId()
            return block(sessionId)
        } catch (ce: EAApiService.CredentialsExpiredException) {
            _expiredAccount.value = _activeAccount.value
            throw ce
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("session") || msg.contains("auth") ||
                msg.contains("401") || msg.contains("403")
            ) {
                // 清除 ViewModel 级缓存，强制下一次 getSessionId 走完整流程
                // （EAApiService 内部的 sessionId 缓存已过期或即将过期）
                try {
                    val newSessionId = getSessionId()
                    return block(newSessionId)
                } catch (ce: EAApiService.CredentialsExpiredException) {
                    _expiredAccount.value = _activeAccount.value
                    throw ce
                }
            } else {
                throw e
            }
        }
    }
}
