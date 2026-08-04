package com.bf1.admin.tool.ui.cardtool

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.cardtool.CardToolConfig
import com.bf1.admin.tool.cardtool.CardToolService
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.repository.ServerRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 卡服页 ViewModel。
 *
 * activeAccount / activeServer 由 Room 数据流派生，设置页切换账号/服务器、
 * 添加/删除服务器、回填 gameId 后这里自动同步，无需手动维护状态。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CardToolViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as BF1AdminApp
    private val accountRepo = app.accountRepository
    private val serverRepo = ServerRepository(app.database.serverDao())
    private val service = CardToolService(accountRepo)

    val accounts: StateFlow<List<AccountEntity>> = accountRepo.allAccounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeAccount: StateFlow<AccountEntity?> = accounts
        .map { list -> list.firstOrNull { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val servers: StateFlow<List<ServerEntity>> = activeAccount.filterNotNull()
        .flatMapLatest { serverRepo.getServersByOwner(it.personaId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeServer: StateFlow<ServerEntity?> = servers
        .map { list -> list.firstOrNull { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── 运行状态 ──

    data class LogLine(val text: String, val isError: Boolean = false)

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs.asStateFlow()

    private val _phase = MutableStateFlow<String?>(null)
    val phase: StateFlow<String?> = _phase.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var runJob: Job? = null

    fun switchServer(server: ServerEntity) {
        val account = activeAccount.value ?: return
        viewModelScope.launch {
            serverRepo.switchActive(account.personaId, server.id)
        }
    }

    /** 回填/更新选中服务器的 gameId（14 位 GUID）。 */
    fun saveGameId(gameId: String) {
        val server = activeServer.value ?: return
        if (gameId.length != 14) return
        viewModelScope.launch {
            serverRepo.updateGameId(server.id, gameId)
        }
    }

    /** 只读诊断：登录 + 查询，不改服务器。 */
    fun startDiagnostic(config: CardToolConfig) = start(config, diagnostic = true)

    /** 完整卡服流程（写操作）。 */
    fun startCard(config: CardToolConfig) = start(config, diagnostic = false)

    private fun start(config: CardToolConfig, diagnostic: Boolean) {
        if (_isRunning.value) return
        _logs.value = emptyList()
        _phase.value = if (diagnostic) "诊断中" else "准备中"
        _isRunning.value = true
        runJob = viewModelScope.launch(Dispatchers.IO) {
            val onEvent: (CardToolService.Event) -> Unit = { event ->
                when (event) {
                    is CardToolService.Event.Log -> {
                        _logs.value = _logs.value + LogLine(event.message, event.isError)
                    }
                    is CardToolService.Event.Phase -> _phase.value = event.phase
                    is CardToolService.Event.Finished -> {
                        _isRunning.value = false
                        _phase.value = null
                        _logs.value = _logs.value + LogLine(
                            if (event.success) "完成: ${event.message}" else "失败: ${event.message}",
                            isError = !event.success
                        )
                        _message.tryEmit(event.message)
                    }
                }
            }
            if (diagnostic) {
                service.runDiagnostic(config, onEvent)
            } else {
                service.run(config, onEvent)
            }
        }
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        if (_isRunning.value) {
            _isRunning.value = false
            _phase.value = null
            _logs.value = _logs.value + LogLine("已停止", isError = true)
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        super.onCleared()
    }
}
