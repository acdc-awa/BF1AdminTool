package com.bf1.admin.tool.ui.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BF1AdminApp
    private val db = app.database
    private val accountRepo = app.accountRepository
    private val serverRepo = ServerRepository(db.serverDao())
    private val adminRepo = app.adminRepository
    private val sessionManager = app.sessionManager

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

    private val _adminList = MutableStateFlow<List<EAApiService.AdminInfo>>(emptyList())
    val adminList: StateFlow<List<EAApiService.AdminInfo>> = _adminList.asStateFlow()

    private val _isRefreshingAdminList = MutableStateFlow(false)
    val isRefreshingAdminList: StateFlow<Boolean> = _isRefreshingAdminList.asStateFlow()

    // ── 凭证编辑 ──
    data class DecryptedCredentials(val remid: String, val sid: String)

    private val _decryptedCredentials = MutableStateFlow<DecryptedCredentials?>(null)
    val decryptedCredentials: StateFlow<DecryptedCredentials?> = _decryptedCredentials.asStateFlow()

    private var isSaving = false

    // ── 服务器查询（用于设置页添加服务器弹窗）──
    private val _lookupServerName = MutableStateFlow<String?>(null)
    val lookupServerName: StateFlow<String?> = _lookupServerName.asStateFlow()

    private val _isLookingUpServer = MutableStateFlow(false)
    val isLookingUpServer: StateFlow<Boolean> = _isLookingUpServer.asStateFlow()

    private val _lookupError = MutableStateFlow<String?>(null)
    val lookupError: StateFlow<String?> = _lookupError.asStateFlow()

    private var lookupGeneration = 0L

    init {
        viewModelScope.launch {
            val account = accountRepo.getActive()
            _activeAccount.value = account
            if (account != null) {
                _activeServer.value = serverRepo.getActiveByOwner(account.personaId)
                initSession(account)
                loadAdminList()
                loadDecryptedCredentials()
            }
        }
    }

    fun switchAccount(account: AccountEntity) {
        viewModelScope.launch {
            accountRepo.switchActive(account.id)
            _activeAccount.value = account
            _activeServer.value = serverRepo.getActiveByOwner(account.personaId)
            initSession(account)
            loadAdminList()
            loadDecryptedCredentials()
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

    // ── 凭证编辑 ──
    private fun loadDecryptedCredentials() {
        viewModelScope.launch {
            val account = _activeAccount.value ?: return@launch
            try {
                val enc = accountRepo.getDecryptedById(account.id)
                _decryptedCredentials.value = enc?.let {
                    DecryptedCredentials(remid = it.remid, sid = it.sid)
                }
            } catch (e: Exception) {
                _message.emit("加载凭证失败: ${e.message}")
            }
        }
    }

    fun saveCredentials(remid: String, sid: String) {
        if (isSaving) return
        viewModelScope.launch {
            isSaving = true
            try {
                val account = _activeAccount.value ?: throw Exception("无活跃账号")
                accountRepo.updateCredentials(account.id, remid, sid)
                val session = withContext(Dispatchers.IO) {
                    adminRepo.authenticate(remid, sid).getOrThrow()
                }
                sessionManager.recordSession(account.id, remid, session.sessionId)
                _message.emit("保存成功，验证通过")
            } catch (e: Exception) {
                _message.emit("验证失败，已保存但凭证可能已失效: ${e.message}")
            } finally {
                isSaving = false
            }
        }
    }

    // ── 服务器查询（用于设置页添加服务器弹窗）──
    fun lookupServer(serverId: String) {
        val generation = ++lookupGeneration
        if (serverId.length != 8) {
            _lookupServerName.value = null
            _lookupError.value = null
            _isLookingUpServer.value = false
            return
        }
        viewModelScope.launch {
            _isLookingUpServer.value = true
            _lookupServerName.value = null
            _lookupError.value = null
            try {
                val (_, name) = sessionManager.withActiveSession { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getServerDetails(sessionId, serverId)
                    }
                }
                if (generation == lookupGeneration) {
                    _lookupServerName.value = name
                }
            } catch (e: Exception) {
                if (generation == lookupGeneration) {
                    _lookupServerName.value = null
                    _lookupError.value = e.message ?: "服务器查询失败，请稍后重试。"
                }
            } finally {
                if (generation == lookupGeneration) {
                    _isLookingUpServer.value = false
                }
            }
        }
    }

    fun addServerFromSettings(serverId: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val (id, name) = sessionManager.withActiveSession { sessionId ->
                    withContext(Dispatchers.IO) {
                        adminRepo.getServerDetails(sessionId, serverId)
                    }
                }
                val account = _activeAccount.value ?: return@launch
                val newId = serverRepo.addServer(id, name, account.personaId)
                serverRepo.switchActive(account.personaId, newId)
                _activeServer.value = ServerEntity(newId, id, name, account.personaId, true)
                _message.emit("已添加服务器: $name")
                onSuccess()
            } catch (e: Exception) {
                _message.emit("添加服务器失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Session 管理由 BattlelogSessionManager 统一处理。
    // ═══════════════════════════════════════════════════

    /**
     * 初始化 / 切换账号时验证凭证并获取 session。
     * 统一缓存由 BattlelogSessionManager 管理，这里仅触发一次确保有效性。
     */
    private suspend fun initSession(account: AccountEntity) {
        try {
            // 仅验证 session 有效性
            withContext(Dispatchers.IO) { sessionManager.getActiveSessionId() }
        } catch (e: EAApiService.CredentialsExpiredException) {
            _expiredAccount.value = account
        } catch (e: Exception) {
            // 网络等瞬态错误静默处理，下次操作时 withSessionRetry 会自动重试
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
            return sessionManager.withActiveSession(block)
        } catch (ce: EAApiService.CredentialsExpiredException) {
            _expiredAccount.value = _activeAccount.value
            throw ce
        }
    }
}
