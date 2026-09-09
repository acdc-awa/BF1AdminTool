package com.bf1.admin.tool.cardtool

import com.bf1.admin.tool.blaze.BlazeClient
import com.bf1.admin.tool.blaze.BlazeConnectionClosedException
import com.bf1.admin.tool.blaze.BlazeLoginResult
import com.bf1.admin.tool.blaze.BlazeSocket
import com.bf1.admin.tool.data.remote.CardToolApiService
import com.bf1.admin.tool.data.remote.GatewayError
import com.bf1.admin.tool.data.session.CredentialManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/**
 * 卡服流程编排：
 * 登录（网关 sessionId + Blaze authCode，均由 CredentialManager 生产）→ 连接 Blaze 登录 →
 * 管理员校验 → 预热（可选，先直连进暖服几次）→ 纯 Blaze 直连进服循环 → 锚定地图轮换；
 * 断线/会话失效自动重建。
 *
 * 进服对齐 bf1_direct_join_stay_forever.py：只用 Blaze 直连
 * （setClientState → updateNetworkInfo → getFullGameData → joinGame(gent=0) → 轮询 PROS 确认），
 * 不发 HTTP 进服请求（无 Game.reserveSlot），避免观战占位特征被风控。
 *
 * [run] 会修改服务器轮换（写操作）；[anchorNow] 只对当前轮换锚定一次；
 * [runDiagnostic] 只登录并查询，不改服务器。通过 [onEvent] 把日志/阶段/结果推给 UI；
 * 协程取消即停止（UI 停止按钮）。
 */
class CardToolService(
    private val credentialManager: CredentialManager,
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

    /**
     * 手动锚定（写操作）：不跑卡服循环，直接对服务器**当前轮换**执行一次锚定，
     * 用于自动锚定失败后补救。只需要 Gateway Session，不需要 Blaze 连接。
     */
    suspend fun anchorNow(config: CardToolConfig, onEvent: (Event) -> Unit) {
        try {
            onEvent(Event.Phase("手动锚定"))
            onEvent(Event.Log("手动锚定：获取 Gateway Session"))
            val sessionId = credentialManager.getActiveSessionId()

            onEvent(Event.Log("手动锚定：查询服务器（getFullServerDetails + getServerDetails）"))
            val rsp = api.getFullServerDetails(sessionId, config.gameId)
            val current = api.getServerDetails(sessionId, config.gameId)
            onEvent(Event.Log("服务器: ${rsp.serverSettings.name} (serverId=${rsp.serverId})"))
            onEvent(
                Event.Log(
                    "当前状态: ${current.slots.occupied}/${current.slots.soldierMax} " +
                        "(${current.rotation.size}图) mapMode=${current.mapMode} guid=${current.guid}"
                )
            )

            if (current.guid.isEmpty()) {
                onEvent(Event.Finished(false, "手动锚定失败：服务器 guid 为空"))
                return
            }
            if (current.rotation.isEmpty()) {
                onEvent(Event.Finished(false, "手动锚定失败：当前轮换为空"))
                return
            }

            val pinned = buildPinnedRotation(current.rotation, config.mode)
            onEvent(Event.Log("锚定轮换: ${pinned.size} 图，gameMode=${pinned.firstOrNull()?.get("gameMode")}"))
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
            val error = performAnchor(sessionId, current.guid, anchorPayload, "手动锚定", onEvent)
            if (error == null) {
                onEvent(Event.Finished(true, "手动锚定完成，请尽快进入服务器"))
            } else {
                onEvent(Event.Finished(false, "手动锚定失败: $error"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onEvent(Event.Finished(false, e.message ?: "手动锚定失败"))
        }
    }

    private suspend fun runScoped(config: CardToolConfig, onEvent: (Event) -> Unit, diagnosticOnly: Boolean) {
        try {
            onEvent(Event.Log("正在初始化"))
            // 网关 sessionId 与管理页共用同一份缓存（同一网关、同一换法），
            // Blaze authCode 一次性、每次登录现取。
            val sessionId = credentialManager.getActiveSessionId()
            onEvent(Event.Log("获取 Blaze AuthCode..."))
            val blazeAuthCode = credentialManager.acquireBlazeAuthCode()

            val (host, port) = api.getBlazeServerAddress()
            onEvent(Event.Log("Blaze 服务器: $host:$port"))

            onEvent(Event.Log("Blaze AuthCode 长度: ${blazeAuthCode.length}"))
            onEvent(Event.Log("连接 Blaze 并登录..."))
            val socket = BlazeSocket(host, port)
            try {
                socket.connect()
                onEvent(Event.Log("Blaze TCP/TLS 已连接"))
                val client = BlazeClient(socket, onDebugError = { msg -> onEvent(Event.Log("[blaze] $msg")) })
                val login = client.login(blazeAuthCode)
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
        // updateServer 在服务端常因 banner 鉴权失败（ERR_AUTHORIZATION_REQUIRED），原版一律忽略；
        // 相同错误只提示一次，避免刷屏。
        var lastUpdateServerWarn: String? = null

        /** 发 updateServer，失败只记日志（对齐原版 `.catch(()=>{})`）。 */
        suspend fun updateQuiet(payload: Map<String, Any?>, tag: String) {
            runCatching { api.updateServer(sessionId, payload) }.onFailure { e ->
                val detail = describe(e)
                if (detail != lastUpdateServerWarn) {
                    lastUpdateServerWarn = detail
                    onEvent(Event.Log("[$tag] RSP.updateServer 失败(原版同样忽略): $detail", isError = true))
                }
            }
        }

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

        // 3. 原版：进循环前先打一次基础轮换（失败忽略）
        onEvent(Event.Log("发送初始 RSP.updateServer（原版在循环前调用一次）"))
        updateQuiet(payloadBase, "初始")

        // 4. 主循环（Blaze 直连进服 → updateServer → sleep 2s → getServerDetails → 锚定/重试）
        onEvent(Event.Phase("开始卡服"))
        var loopCount = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            loopCount++
            try {
                if (socketDead) {
                    onEvent(Event.Log("[#$loopCount] Blaze 连接已断开，重建连接"))
                    val re = reconnect(onEvent)
                    socket.close()
                    socket = re.socket
                    client = re.client
                    login = re.login
                    socketDead = false
                    onEvent(Event.Log("[#$loopCount] 重连成功: ${re.login.displayName} (personaId=${re.login.personaId})"))
                }

                // 纯 Blaze 直连进服（对齐 bf1_direct_join_stay_forever.py）：
                // 不发任何 HTTP 进服请求、不 reserveSlot；joinGame 内部完成
                // setClientState/updateNetworkInfo → getFullGameData → joinGame(gent=0) → 轮询 PROS 确认
                onEvent(Event.Log("[#$loopCount] Blaze 直连进服（无 HTTP reserveSlot）"))
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
                    onEvent(Event.Log("[#$loopCount] 进服失败: ${joinResult.reason}", isError = true))
                    if (joinResult.socketDead) {
                        socketDead = true
                        continue
                    }
                    runCatching { api.leaveGame(sessionId, config.gameId) }
                    delay(1000)
                    continue
                }
                onEvent(Event.Log("[#$loopCount] 进服成功"))

                updateQuiet(payloadBase, "循环#$loopCount")
                delay(2000)

                val current = api.getServerDetails(sessionId, config.gameId)
                val firstMap = current.rotation.firstOrNull()?.mapPrettyName.orEmpty()
                val modesText = current.rotation.map { it.modePrettyName }.distinct().joinToString(" ")
                onEvent(
                    Event.Log(
                        "[#$loopCount] 状态: ${current.slots.occupied}/${current.slots.soldierMax} $firstMap " +
                            "(${current.rotation.size}图) $modesText | mapMode=${current.mapMode} guid=${current.guid}"
                    )
                )

                if (current.mapMode == modeName && current.rotation.size >= config.minMap) {
                    onEvent(Event.Log("[#$loopCount] 地图符合条件，正在锚定"))
                    val pinned = buildPinnedRotation(current.rotation, config.mode)
                    onEvent(Event.Log("[#$loopCount] 锚定轮换: ${pinned.size} 图，gameMode=${pinned.firstOrNull()?.get("gameMode")}"))
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
                    val error = performAnchor(sessionId, current.guid, anchorPayload, "#$loopCount 自动锚定", onEvent)
                    if (error != null) {
                        onEvent(Event.Log("[#$loopCount] 锚定失败，可点「手动锚定」重试: $error", isError = true))
                    }
                    onEvent(Event.Log("已完成，请尽快进入服务器"))
                    onEvent(Event.Finished(true, "卡服完成，请尽快进入服务器"))
                    return
                }

                // 条件不满足 → 离开重试（对齐原版顺序）
                onEvent(
                    Event.Log(
                        "[#$loopCount] 条件不满足(mapMode=${current.mapMode} 需要 $modeName，" +
                            "${current.rotation.size}/$config.minMap 图)，离开重试"
                    )
                )
                runCatching { api.leaveGame(sessionId, config.gameId) }
                delay(1000)
                updateQuiet(payloadBase, "重试#$loopCount")
                updateQuiet(payloadBase, "重试#$loopCount")
                delay(3000)
                if (current.slots.occupied > 1) {
                    onEvent(Event.Log("[#$loopCount] 服务器有其他玩家进入，等待中"))
                    delay(10_000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onEvent(Event.Log("[#$loopCount] 循环出错: ${describe(e)}", isError = true))
                if (e is BlazeConnectionClosedException) {
                    socketDead = true
                } else if (e is GatewayError && (e.code == -32501 || e.code == -32504)) {
                    // 缓存的 session 已被网关废弃：强制失效后重换（走 CredentialManager 统一路径）
                    onEvent(Event.Log("[#$loopCount] Gateway Session 失效，强制重换..."))
                    credentialManager.invalidateActiveSession()
                    sessionId = credentialManager.getActiveSessionId()
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
                onEvent(Event.Log("预热 第$round/${config.primeRounds}轮 Blaze 直连进暖服 $gid"))
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

    private suspend fun reconnect(onEvent: (Event) -> Unit): Reconnected {
        // authCode 一次性：每次断线重连都必须现取新码（轮换落库由 CredentialManager 负责）
        val authCode = credentialManager.acquireBlazeAuthCode()
        val (host, port) = api.getBlazeServerAddress()
        val socket = BlazeSocket(host, port)
        socket.connect()
        val client = BlazeClient(socket, onDebugError = { msg -> onEvent(Event.Log("[blaze] $msg")) })
        val login = client.login(authCode)
        return Reconnected(socket, client, login)
    }

    private fun requireGameId(config: CardToolConfig): Long =
        config.gameId.toLongOrNull() ?: throw IllegalArgumentException("gameId 无效：${config.gameId}")

    /**
     * 执行一次锚定：chooseLevel → updateServer（吞错）→ 等 1 秒 → chooseLevel。
     * 成败只由两次 chooseLevel 决定；updateServer 的服务端错误（如 banner 鉴权
     * ERR_AUTHORIZATION_REQUIRED）一律忽略，与原版 CardTool 一致。
     * @return 成功返回 null，失败返回错误描述
     */
    private suspend fun performAnchor(
        sessionId: String,
        persistedGameId: String,
        anchorPayload: Map<String, Any?>,
        tag: String,
        onEvent: (Event) -> Unit
    ): String? = try {
        onEvent(Event.Log("[$tag] RSP.chooseLevel #1 persistedGameId=$persistedGameId levelIndex=0"))
        api.chooseLevel(sessionId, persistedGameId, 0)
        runCatching { api.updateServer(sessionId, anchorPayload) }
            .onFailure {
                onEvent(Event.Log("[$tag] RSP.updateServer 失败(已忽略): ${describe(it)}", isError = true))
            }
        delay(1000)
        onEvent(Event.Log("[$tag] RSP.chooseLevel #2 persistedGameId=$persistedGameId levelIndex=0"))
        api.chooseLevel(sessionId, persistedGameId, 0)
        null
    } catch (e: Exception) {
        describe(e)
    }

    /** 错误详情：GatewayError 展开原始 code/message/method，便于定位服务端拒绝原因。 */
    private fun describe(e: Throwable): String = when (e) {
        is GatewayError -> "code=${e.code} method=${e.method} raw=${e.rawMessage} (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }

}
