package com.bf1.admin.tool.cardtool

import com.bf1.admin.tool.blaze.BlazeClient
import com.bf1.admin.tool.blaze.BlazeConnectionClosedException
import com.bf1.admin.tool.blaze.BlazeLoginResult
import com.bf1.admin.tool.blaze.BlazeSocket
import com.bf1.admin.tool.data.local.entity.EncryptedAccount
import com.bf1.admin.tool.data.remote.CardToolApiService
import com.bf1.admin.tool.data.remote.GatewayError
import com.bf1.admin.tool.data.remote.RotatedCookies
import com.bf1.admin.tool.data.repository.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 卡服流程编排，对应 CardTool.js 主流程：
 * 登录（Gateway session + Blaze authCode）→ 连接 Blaze 登录 → 管理员校验 →
 * 预热（可选）→ 占位进服循环 → 锚定地图轮换；断线/会话失效自动重建。
 *
 * [run] 会修改服务器轮换（写操作）；[runDiagnostic] 只登录并查询，不改服务器，用于实机验证链路。
 * 通过 [onEvent] 把日志/阶段/结果推给 UI；协程取消即停止（UI 停止按钮）。
 */
class CardToolService(
    private val accountRepository: AccountRepository,
    private val api: CardToolApiService = CardToolApiService()
) {

    sealed class Event {
        data class Log(val message: String, val isError: Boolean = false) : Event()
        data class Phase(val phase: String) : Event()
        data class Finished(val success: Boolean, val message: String) : Event()
    }

    private class Reconnected(val socket: BlazeSocket, val client: BlazeClient, val login: BlazeLoginResult)

    /** 只读诊断：登录 + 管理员校验 + getFullGameData，不改任何服务器状态。 */
    suspend fun runDiagnostic(config: CardToolConfig, onEvent: (Event) -> Unit) {
        runScoped(config, onEvent, diagnosticOnly = true)
    }

    /** 完整卡服流程（写操作，会改动服务器轮换）。 */
    suspend fun run(config: CardToolConfig, onEvent: (Event) -> Unit) {
        runScoped(config, onEvent, diagnosticOnly = false)
    }

    private suspend fun runScoped(config: CardToolConfig, onEvent: (Event) -> Unit, diagnosticOnly: Boolean) {
        try {
            onEvent(Event.Log("正在初始化"))
            val account = requireCredentials()
            var remid = account.remid
            var sid = account.sid

            onEvent(Event.Log("获取 Gateway Session..."))
            val gateway = api.getGatewaySession(remid, sid).getOrThrow()
            gateway.rotated.remid?.let { remid = it }
            gateway.rotated.sid?.let { sid = it }
            persistRotation(account, gateway.rotated)
            val sessionId = gateway.sessionId

            onEvent(Event.Log("获取 Blaze AuthCode..."))
            val blazeAuth = api.getBlazeAuthCode(remid, sid).getOrThrow()
            persistRotation(account, blazeAuth.rotated)

            val (host, port) = api.getBlazeServerAddress()
            onEvent(Event.Log("Blaze 服务器: $host:$port"))

            onEvent(Event.Log("连接 Blaze 并登录..."))
            val socket = BlazeSocket(host, port)
            socket.connect()
            try {
                val client = BlazeClient(socket)
                val login = client.login(blazeAuth.authCode)
                onEvent(Event.Log("已登录 User: ${login.displayName} (personaId=${login.personaId})"))

                if (diagnosticOnly) {
                    runDiagnosticBody(config, login, client, sessionId, onEvent)
                } else {
                    runCardBody(config, login, client, socket, sessionId, onEvent)
                }
            } finally {
                socket.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onEvent(Event.Finished(false, e.message ?: "未知错误"))
        }
    }

    // ═══════════════════════════════════════════════════
    // 只读诊断
    // ═══════════════════════════════════════════════════

    private suspend fun runDiagnosticBody(
        config: CardToolConfig,
        login: BlazeLoginResult,
        client: BlazeClient,
        sessionId: String,
        onEvent: (Event) -> Unit
    ) {
        val gameId = requireGameId(config)
        val rsp = api.getFullServerDetails(sessionId, config.gameId)
        onEvent(Event.Log("服务器: ${rsp.serverSettings.name} (serverId=${rsp.serverId})"))

        val adminIds = rsp.adminList.map { it.personaId }.toSet() + rsp.ownerPersonaId
        val isAdmin = login.personaId.toString() in adminIds
        onEvent(Event.Log(if (isAdmin) "管理员身份已确认" else "警告：当前账号不是该服务器管理员"))

        onEvent(Event.Log("上报客户端状态并查询服务器..."))
        client.reportClientState()
        val data = client.getFullGameData(gameId)
        onEvent(
            Event.Log(
                "getFullGameData: protocolVersion=${data.protocolVersion} " +
                    "players=${data.players.size} roles=${data.roles.joinToString(",")}"
            )
        )
        val name = data.gameInfo?.get("GNAM 1")?.toString().orEmpty()
        if (name.isNotEmpty()) onEvent(Event.Log("服务器名(GAME): $name"))
        onEvent(Event.Finished(true, "诊断完成：登录/Blaze/服务器查询链路正常"))
    }

    // ═══════════════════════════════════════════════════
    // 完整卡服流程
    // ═══════════════════════════════════════════════════

    private suspend fun runCardBody(
        config: CardToolConfig,
        initialLogin: BlazeLoginResult,
        initialClient: BlazeClient,
        initialSocket: BlazeSocket,
        initialSessionId: String,
        onEvent: (Event) -> Unit
    ) {
        val gameId = requireGameId(config)
        val modeName = config.modeName ?: throw IllegalArgumentException("模式未选定")
        var login = initialLogin
        var client = initialClient
        var socket = initialSocket
        var sessionId = initialSessionId
        var protocolVersionCache = ""
        var socketDead = false

        // 1. 服务器详情 + 管理员校验
        val rsp = api.getFullServerDetails(sessionId, config.gameId)
        val adminIds = rsp.adminList.map { it.personaId }.toSet() + rsp.ownerPersonaId
        if (login.personaId.toString() !in adminIds) {
            throw IllegalStateException("该用户不是服务器管理员")
        }
        onEvent(Event.Log("管理员身份已确认: ${rsp.serverSettings.name}"))
        onEvent(Event.Log("选定模式: ${config.modePrettyName} [${config.player}人]"))

        val payloadBase = buildServerUpdatePayload(
            serverId = rsp.serverId,
            name = rsp.serverSettings.name,
            description = rsp.serverSettings.description,
            message = "${System.currentTimeMillis()} CardTool",
            password = rsp.serverSettings.password,
            customGameSettings = rsp.serverSettings.customGameSettings,
            playerLimit = config.player
        )

        // 2. 预热阶段（可选）
        if (config.primeGids.isNotEmpty()) {
            runPrimePhase(config, gameId, login, client, sessionId, onEvent)
        }

        // 3. 主循环：占位进服 → 刷新模式 → 检查条件 → 锚定或重试
        onEvent(Event.Phase("开始卡服"))
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                if (socketDead) {
                    onEvent(Event.Log("Blaze 连接已断开，重建连接"))
                    val re = reconnect()
                    socket.close()
                    socket = re.socket
                    client = re.client
                    login = re.login
                    socketDead = false
                }

                if (config.joinStyle == JoinStyle.CARDTOOL) {
                    runCatching { api.reserveSlot(sessionId, config.gameId) }
                        .onSuccess { if (it != "Joined") onEvent(Event.Log("reserveSlot 未返回 Joined: $it")) }
                }

                val joinResult = client.joinGame(
                    gameId = gameId,
                    personaId = login.personaId,
                    platformId = login.nucleusId,
                    connectionGroupId = login.connectionGroupId,
                    userExtendedData = login.userExtendedData,
                    protocolVersionCache = protocolVersionCache,
                    joinConfirmTimeoutMs = config.joinTimeoutMs,
                    joinPollIntervalMs = config.joinPollIntervalMs
                )
                joinResult.protocolVersion?.let { protocolVersionCache = it }
                if (!joinResult.ok) {
                    onEvent(Event.Log("进服失败: ${joinResult.reason}", isError = true))
                    if (joinResult.socketDead) {
                        socketDead = true
                        continue
                    }
                    runCatching { api.leaveGame(sessionId, config.gameId) }
                    delay(1000)
                    continue
                }
                onEvent(Event.Log("进服成功"))

                runCatching { api.updateServer(sessionId, payloadBase) }
                delay(2000)

                val current = api.getServerDetails(sessionId, config.gameId)
                val firstMap = current.rotation.firstOrNull()?.mapPrettyName.orEmpty()
                val modesText = current.rotation.map { it.modePrettyName }.distinct().joinToString(" ")
                onEvent(
                    Event.Log(
                        "状态: ${current.slots.occupied}/${current.slots.soldierMax} $firstMap " +
                            "(${current.rotation.size}图) $modesText"
                    )
                )

                if (current.mapMode == modeName && current.rotation.size >= config.minMap) {
                    onEvent(Event.Log("地图符合条件，正在锚定"))
                    val pinned = buildPinnedRotation(current.rotation, config.mode)
                    val anchorPayload = buildServerUpdatePayload(
                        serverId = rsp.serverId,
                        name = rsp.serverSettings.name,
                        description = rsp.serverSettings.description,
                        message = "${System.currentTimeMillis()} CardTool",
                        password = rsp.serverSettings.password,
                        customGameSettings = rsp.serverSettings.customGameSettings,
                        playerLimit = config.player,
                        mapsOverride = pinned
                    )
                    try {
                        api.chooseLevel(sessionId, current.guid, 0)
                        api.updateServer(sessionId, anchorPayload)
                        delay(1000)
                        api.chooseLevel(sessionId, current.guid, 0)
                    } catch (e: Exception) {
                        onEvent(Event.Log("锚定失败，请手动锚定: ${e.message}", isError = true))
                    }
                    onEvent(Event.Log("已完成，请尽快进入服务器"))
                    onEvent(Event.Finished(true, "卡服完成，请尽快进入服务器"))
                    return
                }

                // 条件不满足 → 离开重试
                runCatching { api.leaveGame(sessionId, config.gameId) }
                delay(1000)
                runCatching { api.updateServer(sessionId, payloadBase) }
                runCatching { api.updateServer(sessionId, payloadBase) }
                delay(3000)
                if (current.slots.occupied > 1) {
                    onEvent(Event.Log("服务器有其他玩家进入，等待中"))
                    delay(10_000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onEvent(Event.Log("循环出错: ${e.message}", isError = true))
                if (e is BlazeConnectionClosedException) {
                    socketDead = true
                } else if (e is GatewayError && (e.code == -32501 || e.code == -32504)) {
                    onEvent(Event.Log("Gateway Session 失效，刷新..."))
                    sessionId = refreshGatewaySession()
                }
                delay(3000)
            }
        }
    }

    /** 预热阶段：先进出暖服若干次（对应 CardTool runPrimePhase）。 */
    private suspend fun runPrimePhase(
        config: CardToolConfig,
        gameId: Long,
        login: BlazeLoginResult,
        client: BlazeClient,
        sessionId: String,
        onEvent: (Event) -> Unit
    ) {
        val gids = config.primeGids.filter { it != config.gameId }
        if (gids.isEmpty()) return
        onEvent(
            Event.Log(
                "预热阶段开始：暖服=[${gids.joinToString(",")}] 每服 ${config.primeRounds} 次，" +
                    "每次停留 ${config.primeStaySeconds} 秒"
            )
        )
        for (round in 1..config.primeRounds) {
            for (gid in gids) {
                currentCoroutineContext().ensureActive()
                val g = gid.toLongOrNull() ?: continue
                onEvent(Event.Log("预热 第$round/${config.primeRounds}轮 进入暖服 $gid"))
                val r = client.joinGame(
                    gameId = g,
                    personaId = login.personaId,
                    platformId = login.nucleusId,
                    connectionGroupId = login.connectionGroupId,
                    userExtendedData = login.userExtendedData,
                    protocolVersionCache = "",
                    joinConfirmTimeoutMs = 12_000,
                    joinPollIntervalMs = 500
                )
                if (!r.ok) {
                    onEvent(Event.Log("预热 进服失败($gid): ${r.reason}", isError = true))
                    continue
                }
                onEvent(Event.Log("预热 已在暖服 $gid，停留 ${config.primeStaySeconds} 秒"))
                delay(config.primeStaySeconds * 1000L)
                runCatching { api.leaveGame(sessionId, gid) }
                onEvent(Event.Log("预热 已离开暖服 $gid"))
                delay(1500)
            }
        }
        onEvent(Event.Log("预热阶段完成"))
    }

    // ═══════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════

    private suspend fun reconnect(): Reconnected {
        val account = requireCredentials()
        val auth = api.getBlazeAuthCode(account.remid, account.sid).getOrThrow()
        persistRotation(account, auth.rotated)
        val (host, port) = api.getBlazeServerAddress()
        val socket = BlazeSocket(host, port)
        socket.connect()
        val client = BlazeClient(socket)
        val login = client.login(auth.authCode)
        return Reconnected(socket, client, login)
    }

    private suspend fun refreshGatewaySession(): String {
        val account = requireCredentials()
        val g = api.getGatewaySession(account.remid, account.sid).getOrThrow()
        persistRotation(account, g.rotated)
        return g.sessionId
    }

    private suspend fun requireCredentials(): EncryptedAccount =
        accountRepository.getActiveEncrypted() ?: throw IllegalStateException("请先登录 EA 账号")

    private fun requireGameId(config: CardToolConfig): Long =
        config.gameId.toLongOrNull() ?: throw IllegalArgumentException("gameId 无效：${config.gameId}")

    private suspend fun persistRotation(account: EncryptedAccount, rotated: RotatedCookies) {
        if (!rotated.hasAny) return
        accountRepository.updateCredentials(
            account.id,
            rotated.remid ?: account.remid,
            rotated.sid ?: account.sid
        )
    }

}
