package com.bf1.admin.tool.data.repository

import android.content.Context
import com.bf1.admin.tool.cardtool.RspInfo
import com.bf1.admin.tool.data.local.entity.ServerEntity
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.data.remote.RspServerIdentity
import com.bf1.admin.tool.data.remote.SearchServerEntry
import com.bf1.admin.tool.data.remote.findMatchingServer
import com.bf1.admin.tool.data.session.CredentialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 刷新冷却时间存储接口（便于单测替换，生产使用 SharedPreferences 跨进程生效）。 */
interface RefreshCooldownStore {
    fun getLastRefreshedAt(ownerPersonaId: String): Long
    fun setLastRefreshedAt(ownerPersonaId: String, timestampMs: Long)
}

class PrefsRefreshCooldownStore(context: Context) : RefreshCooldownStore {
    private val prefs = context.applicationContext.getSharedPreferences(
        "server_refresh_cooldown",
        Context.MODE_PRIVATE
    )

    override fun getLastRefreshedAt(ownerPersonaId: String): Long =
        prefs.getLong(ownerPersonaId, 0L)

    override fun setLastRefreshedAt(ownerPersonaId: String, timestampMs: Long) {
        prefs.edit().putLong(ownerPersonaId, timestampMs).apply()
    }
}

/** 单台服务器刷新结果。 */
sealed class ServerRefreshResult {
    data class Success(
        val server: ServerEntity,
        val serverIdChanged: Boolean,
        val nameChanged: Boolean,
        val gameIdChanged: Boolean,
        val guidChanged: Boolean,
        val gameIdMatched: Boolean
    ) : ServerRefreshResult() {
        val changed: Boolean get() = serverIdChanged || nameChanged || gameIdChanged || guidChanged
    }

    data class Failed(
        val server: ServerEntity,
        val reason: String
    ) : ServerRefreshResult()
}

/** 账号下批量刷新结果汇总。 */
data class BatchRefreshSummary(
    val skippedByCooldown: Boolean,
    val total: Int,
    val updated: Int,
    val unchanged: Int,
    val failed: Int,
    val firstError: String? = null
)

/**
 * 以永久不变的 guid (persistedGameId) 为权威锚点的服务器元数据刷新服务：
 *
 * 1. 快路径（仅 1 次 RPC）：若本地有 gameId，先调 GameServer.getFullServerDetails(gameId)，
 *    校验返回的 guid 与本地 guid 一致（旧数据无 guid 时顺带回填），直接刷新服名 / serverId / guid。
 * 2. guid 找回路径（gameId 失效 / 缺失 / guid 不匹配时）：
 *    - 先尝试 RSP.getServerDetails(serverId) 获取最新服名与 persistedGameId；
 *    - 若 serverId 也失效且本地有 guid，则回退使用本地已存服名作为搜索词；
 *    - 调 GameServer.searchServers(服名) 按 guid 精确匹配最新 14 位 gameId；
 *    - 若 serverId 之前失效，拿到新 gameId 后再补一次 getFullServerDetails 找回最新 serverId。
 */
class ServerRefreshService(
    private val loadServers: suspend (ownerPersonaId: String) -> List<ServerEntity>,
    private val persistMetadata: suspend (
        id: Long,
        serverId: String,
        serverName: String,
        gameId: String?,
        guid: String?
    ) -> Unit,
    private val withActiveSession: suspend (block: suspend (String) -> Any?) -> Any?,
    private val fetchFullDetails: (sessionId: String, gameId: String) -> RspInfo,
    private val fetchRspIdentity: (sessionId: String, serverId: String) -> RspServerIdentity,
    private val searchServersByName: (sessionId: String, serverName: String) -> List<SearchServerEntry>,
    private val cooldownStore: RefreshCooldownStore,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    companion object {
        /** 启动/切换账号自动刷新的默认冷却时长：30 分钟。 */
        const val DEFAULT_COOLDOWN_MS: Long = 30L * 60L * 1000L

        fun create(
            context: Context,
            serverRepository: ServerRepository,
            credentialManager: CredentialManager,
            eaApiService: EAApiService
        ): ServerRefreshService = ServerRefreshService(
            loadServers = { personaId -> serverRepository.getServersByOwnerOnce(personaId) },
            persistMetadata = { id, serverId, name, gameId, guid ->
                serverRepository.updateMetadata(id, serverId, name, gameId, guid)
            },
            withActiveSession = { block -> credentialManager.withActiveSession(block) },
            fetchFullDetails = { sessionId, gameId ->
                eaApiService.getFullServerDetails(sessionId, gameId)
            },
            fetchRspIdentity = { sessionId, serverId ->
                eaApiService.getRspServerIdentity(sessionId, serverId)
            },
            searchServersByName = { sessionId, name ->
                eaApiService.searchServersByName(sessionId, name)
            },
            cooldownStore = PrefsRefreshCooldownStore(context)
        )
    }

    private val refreshMutex = Mutex()

    /**
     * 刷新指定账号下的全部服务器。
     *
     * @param force false 时受 30 分钟冷却限制（启动/切换账号）；true 时无视冷却（手动刷新）。
     */
    suspend fun refreshAccountServers(
        ownerPersonaId: String,
        force: Boolean
    ): BatchRefreshSummary = refreshMutex.withLock {
        val now = nowMs()
        if (!force) {
            val last = cooldownStore.getLastRefreshedAt(ownerPersonaId)
            if (last > 0L && now - last < cooldownMs) {
                return@withLock BatchRefreshSummary(
                    skippedByCooldown = true,
                    total = 0,
                    updated = 0,
                    unchanged = 0,
                    failed = 0
                )
            }
        }

        val servers = loadServers(ownerPersonaId)
        if (servers.isEmpty()) {
            return@withLock BatchRefreshSummary(
                skippedByCooldown = false,
                total = 0,
                updated = 0,
                unchanged = 0,
                failed = 0
            )
        }

        var updated = 0
        var unchanged = 0
        var failed = 0
        var firstError: String? = null

        for (server in servers) {
            when (val res = refreshSingleInternal(server, requireGameId = false)) {
                is ServerRefreshResult.Success -> {
                    if (res.changed) updated++ else unchanged++
                }
                is ServerRefreshResult.Failed -> {
                    failed++
                    if (firstError == null) firstError = res.reason
                }
            }
        }

        // 只要至少成功一台，就记录冷却时间；若全部失败（如断网），不消耗冷却时间以便恢复联网后重试
        if (updated + unchanged > 0) {
            cooldownStore.setLastRefreshedAt(ownerPersonaId, nowMs())
        }

        BatchRefreshSummary(
            skippedByCooldown = false,
            total = servers.size,
            updated = updated,
            unchanged = unchanged,
            failed = failed,
            firstError = firstError
        )
    }

    /**
     * 卡服前严格校准单台服务器（无冷却）：
     * 必须校验或通过 guid 找回有效的最新 14 位 gameId，任一步失败均返回 Failed，不回退旧 gameId。
     */
    suspend fun calibrateForCardTool(server: ServerEntity): ServerRefreshResult =
        refreshSingleInternal(server, requireGameId = true)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> runWithSession(block: suspend (String) -> T): T =
        withActiveSession(block) as T

    private suspend fun refreshSingleInternal(
        server: ServerEntity,
        requireGameId: Boolean
    ): ServerRefreshResult {
        val savedGuid = server.guid?.takeIf { it.isNotBlank() }

        // ── 1. 快路径：先用已有 gameId 调 getFullServerDetails，一次拿齐服名 / serverId / guid ──
        if (!server.gameId.isNullOrBlank()) {
            val fastRsp = try {
                runWithSession { sessionId ->
                    withContext(Dispatchers.IO) { fetchFullDetails(sessionId, server.gameId) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EAApiService.CredentialsExpiredException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (fastRsp != null) {
                val rspGuid = fastRsp.persistedGameId.takeIf { it.isNotBlank() }
                val guidMatches = when {
                    savedGuid != null && rspGuid != null -> savedGuid.equals(rspGuid, ignoreCase = true)
                    savedGuid == null -> true
                    else -> false
                }
                if (guidMatches) {
                    val finalServerId = fastRsp.serverId.ifBlank { server.serverId }
                    val finalName = fastRsp.serverSettings.name.ifBlank { server.serverName }
                    val finalGameId = fastRsp.gameId.ifBlank { server.gameId }
                    val finalGuid = rspGuid ?: savedGuid
                    return persistAndReturn(
                        original = server,
                        finalServerId = finalServerId,
                        finalName = finalName,
                        finalGameId = finalGameId,
                        finalGuid = finalGuid,
                        gameIdMatched = true
                    )
                }
                // 若 guid 不一致（极端情况旧 gameId 指向了别的服），继续走下方 guid 权威找回路径
            }
        }

        // ── 2. guid 权威找回路径（gameId 失效 / 为空 / guid 不匹配）──
        var rspSucceeded = false
        var rspError: String? = null
        var targetGuid = savedGuid.orEmpty()
        var searchName = server.serverName
        var resolvedServerId = server.serverId

        if (server.serverId.isNotBlank()) {
            try {
                val identity = runWithSession { sessionId ->
                    withContext(Dispatchers.IO) { fetchRspIdentity(sessionId, server.serverId) }
                }
                val identityGuid = identity.persistedGameId.takeIf { it.isNotBlank() }
                val rspGuidMatches = savedGuid == null ||
                    (identityGuid != null && savedGuid.equals(identityGuid, ignoreCase = true))
                if (rspGuidMatches) {
                    rspSucceeded = true
                    if (identityGuid != null) targetGuid = identityGuid
                    if (identity.serverName.isNotBlank()) searchName = identity.serverName
                    if (identity.serverId.isNotBlank()) resolvedServerId = identity.serverId
                } else {
                    rspError = "ServerID 对应的 GUID 已变化"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EAApiService.CredentialsExpiredException) {
                throw e
            } catch (e: Exception) {
                rspError = e.message ?: e.javaClass.simpleName
            }
        }

        if (targetGuid.isBlank()) {
            val reason = rspError ?: "服务器缺少有效 ServerID 与 GUID，无法刷新"
            return ServerRefreshResult.Failed(server, reason)
        }

        var matchedEntry: SearchServerEntry? = null
        var searchError: String? = null
        if (searchName.isNotBlank()) {
            try {
                val entries = runWithSession { sessionId ->
                    withContext(Dispatchers.IO) { searchServersByName(sessionId, searchName) }
                }
                matchedEntry = findMatchingServer(entries, targetGuid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EAApiService.CredentialsExpiredException) {
                throw e
            } catch (e: Exception) {
                searchError = e.message ?: e.javaClass.simpleName
            }
        }

        val matchedGameId = matchedEntry?.gameId?.takeIf { it.isNotBlank() }
        var finalName = matchedEntry?.name?.takeIf { it.isNotBlank() } ?: searchName

        if (matchedGameId != null) {
            // 若此前 serverId 失效，用新匹配到的 gameId 再补一次 getFullServerDetails 同步回最新 serverId
            if (!rspSucceeded) {
                try {
                    val full = runWithSession { sessionId ->
                        withContext(Dispatchers.IO) { fetchFullDetails(sessionId, matchedGameId) }
                    }
                    if (full.serverId.isNotBlank()) resolvedServerId = full.serverId
                    if (full.serverSettings.name.isNotBlank()) finalName = full.serverSettings.name
                } catch (e: CancellationException) {
                    throw e
                } catch (e: EAApiService.CredentialsExpiredException) {
                    throw e
                } catch (_: Exception) {
                    // serverId 回补失败不阻断已成功找回的 gameId
                }
            }

            return persistAndReturn(
                original = server,
                finalServerId = resolvedServerId,
                finalName = finalName,
                finalGameId = matchedGameId,
                finalGuid = targetGuid,
                gameIdMatched = true
            )
        }

        // 未能在在线搜索中匹配到 gameId
        if (requireGameId) {
            // 严格模式失败前，若已拿到更新的服名或回填了 guid，仍先保存它们且保留原 gameId
            if (rspSucceeded && (finalName != server.serverName || targetGuid != server.guid)) {
                persistMetadata(server.id, resolvedServerId, finalName, server.gameId, targetGuid)
            }
            val reason = when {
                !rspSucceeded && rspError != null ->
                    "ServerID 与 GameID 均失效，且未通过 GUID 找回服务器（$rspError）"
                searchError != null ->
                    "搜索最新 GameID 失败: $searchError"
                else ->
                    "未在在线服务器列表中匹配到该 GUID 对应的最新 GameID（服务器可能未开启或正在重启）"
            }
            return ServerRefreshResult.Failed(server.copy(serverName = finalName, guid = targetGuid), reason)
        }

        // 非严格批量刷新：RSP 成功说明 serverId 仍有效，更新服名和 guid、保留旧 gameId；RSP 也失败则上报失败
        return if (rspSucceeded) {
            persistAndReturn(
                original = server,
                finalServerId = resolvedServerId,
                finalName = finalName,
                finalGameId = server.gameId,
                finalGuid = targetGuid,
                gameIdMatched = false
            )
        } else {
            val detail = rspError ?: searchError ?: "服务器可能已关服或失效"
            ServerRefreshResult.Failed(server, "无法通过 GameID、ServerID 或 GUID 刷新服务器（$detail）")
        }
    }

    private suspend fun persistAndReturn(
        original: ServerEntity,
        finalServerId: String,
        finalName: String,
        finalGameId: String?,
        finalGuid: String?,
        gameIdMatched: Boolean
    ): ServerRefreshResult.Success {
        val serverIdChanged = finalServerId != original.serverId
        val nameChanged = finalName != original.serverName
        val gameIdChanged = finalGameId != original.gameId
        val guidChanged = finalGuid != original.guid

        if (serverIdChanged || nameChanged || gameIdChanged || guidChanged) {
            persistMetadata(original.id, finalServerId, finalName, finalGameId, finalGuid)
        }

        return ServerRefreshResult.Success(
            server = original.copy(
                serverId = finalServerId,
                serverName = finalName,
                gameId = finalGameId,
                guid = finalGuid
            ),
            serverIdChanged = serverIdChanged,
            nameChanged = nameChanged,
            gameIdChanged = gameIdChanged,
            guidChanged = guidChanged,
            gameIdMatched = gameIdMatched
        )
    }
}
