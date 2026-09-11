package com.bf1.admin.tool.blaze

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 响应包解析辅助（纯函数，可单测），对应 CardTool.js 的
 * connectionGroupIdFromLogin / rolesFromGameInfo / playerInPros / fetchFullGameData 提取逻辑。
 */
object BlazeParsing {

    /** 从 Authentication.login 响应提取连接组 ID。 */
    fun connectionGroupIdFromLogin(data: Map<String, Any?>?): Long {
        val sess = data?.get("SESS 3") as? Map<*, *> ?: data?.get("SESS") as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val cgid: Any? = data?.get("CGID 9") ?: sess["CGID 9"]
        return when (cgid) {
            is List<*> -> (cgid.lastOrNull() as? Number)?.toLong() ?: 0L
            is Map<*, *> -> {
                val v = cgid["id"] ?: cgid["ID"] ?: cgid["entityId"]
                (v as? Number)?.toLong() ?: 0L
            }
            is Number -> cgid.toLong()
            else -> 0L
        }
    }

    /**
     * 从 UserSessions.lookupUsers 响应提取 userExtendedData（连接组三要素）。
     * 结构：ULST 43[0].EDAT 3.ULST 49[0] = [30722, 2, CGID] —— 必须取 ULST 49 的第一个元素，
     * 而非遍历整个 ULST 49（CardTool 的 userExtendedData = ULST 49[0]）。
     */
    fun parseUserExtendedData(data: Map<String, Any?>?): List<Long>? {
        val ulst = data?.get("ULST 43") as? List<*>
        val first = ulst?.firstOrNull() as? Map<*, *>
        val edat = first?.get("EDAT 3") as? Map<*, *>
        val inner = edat?.get("ULST 49") as? List<*>
        val ued = inner?.firstOrNull() as? List<*>
        return ued?.mapNotNull { (it as? Number)?.toLong() }
    }

    /** 从 getFullGameData 的 gameInfo 提取可用角色列表（RNFO.CRIT 的 key）。 */
    fun rolesFromGameInfo(gameInfo: Map<String, Any?>?): List<String> {
        val rnfo = gameInfo?.get("RNFO 3") as? Map<*, *> ?: gameInfo?.get("RNFO") as? Map<*, *> ?: return emptyList()
        val crit = rnfo["CRIT 513"] as? Map<*, *> ?: rnfo["CRIT"] as? Map<*, *> ?: return emptyList()
        return crit.keys.mapNotNull { it?.toString() }
    }

    /** 玩家是否在 PROS 列表中（按 personaId 匹配 PID）。 */
    fun playerInPros(players: List<Any?>?, personaId: Long): Boolean {
        return players.orEmpty().any { p ->
            val pid = (p as? Map<*, *>)?.get("PID  0") as? Number
            pid?.toLong() == personaId
        }
    }

    /**
     * 玩家记录是否"像真客户端在服里"（对应 Python joined_like_client）：
     * 有 PNET 字段、CONG 非 0、或 STAT ∈ (2, 4)。
     */
    fun joinedLikeClient(player: Map<String, Any?>?): Boolean {
        if (player == null) return false
        val hasPnet = player.keys.any { it.startsWith("PNET") }
        val cong = player.entries.firstOrNull { it.key.startsWith("CONG") }?.value as? Number
        val stat = player.entries.firstOrNull { it.key.startsWith("STAT") }?.value as? Number
        return hasPnet || (cong?.toLong() ?: 0L) != 0L || stat?.toLong() == 2L || stat?.toLong() == 4L
    }

    /** 从 gameInfo 提取协议版本 VSTR。 */
    fun protocolVersionFrom(gameInfo: Map<String, Any?>?): String =
        gameInfo?.get("VSTR 1")?.toString().orEmpty()
}

/** Blaze 登录结果（对应 CardTool createBlazeSession 中提取的信息）。 */
data class BlazeLoginResult(
    val displayName: String,
    val personaId: Long,
    val nucleusId: Long,
    val connectionGroupId: Long,
    val userExtendedData: List<Long>?
)

/** getFullGameData 结果；出错时 [errorName]/[errc] 非空、数据为空。 */
data class FullGameData(
    val gameInfo: Map<String, Any?>?,
    val players: List<Map<String, Any?>>,
    val protocolVersion: String,
    val roles: List<String>,
    val errorName: String? = null,
    val errc: Long? = null
)

/** joinGame 结果。 */
data class JoinResult(
    val ok: Boolean,
    val reason: String?,
    val protocolVersion: String?,
    val prosSeen: Boolean,
    val socketDead: Boolean,
    val roles: List<String> = emptyList()
)

/**
 * Blaze 高层客户端：封装登录/查身份/进服/查询等操作，对应 CardTool.js 的
 * blazeLogin / lookupUsers / joinServer / fetchFullGameData。
 *
 * 所有发送均带超时；socket 断开时抛 [BlazeConnectionClosedException]，由上层重建。
 */
class BlazeClient(
    private val socket: BlazeSocket,
    private val requestTimeoutMs: Long = 15_000,
    private val onDebug: ((String) -> Unit)? = null,
    private val onDebugError: ((String) -> Unit)? = null
) {
    private fun debug(msg: String) {
        onDebug?.invoke(msg)
    }

    /** 错误诊断通道（B3/B4）：仅错误/解析失败时输出，对排障有用，应转发给 UI。 */
    private fun debugError(msg: String) {
        onDebugError?.invoke(msg)
    }
    /** Authentication.login，返回 [BlazeLoginResult]。 */
    suspend fun login(authCode: String): BlazeLoginResult {
        val resp = send("Authentication.login", BlazePackets.login(authCode))
        if (resp.error != null) {
            val e = resp.error
            val raw = resp.rawBytes?.joinToString("") { "%02x".format(it) }.orEmpty()
            throw BlazeProtocolException(
                "Blaze 登录失败: ${e.message} " +
                    "(component=${e.component} errc=0x${resp.errc?.toString(16)} " +
                    "raw=${raw.take(160)})"
            )
        }
        val data = resp.data
            ?: throw BlazeProtocolException("Blaze 登录响应无数据")
        val sess = data["SESS 3"] as? Map<*, *>
            ?: throw BlazeProtocolException("Blaze 登录响应缺少 SESS")
        val displayName = (sess["PDTL 3"] as? Map<*, *>)?.get("DSNM 1")?.toString().orEmpty()
        val personaId = (sess["BUID 0"] as? Number)?.toLong() ?: 0L
        val nucleusId = (sess["UID  0"] as? Number)?.toLong() ?: 0L
        val connectionGroupId = BlazeParsing.connectionGroupIdFromLogin(data)
        val userExtendedData = lookupUserExtendedData(displayName)
        debug("登录: displayName=$displayName personaId=$personaId nucleusId=$nucleusId cgid=$connectionGroupId")
        debug("lookupUsers: userExtendedData=$userExtendedData")
        return BlazeLoginResult(
            displayName = displayName,
            personaId = personaId,
            nucleusId = nucleusId,
            connectionGroupId = connectionGroupId,
            userExtendedData = userExtendedData
        )
    }

    /** UserSessions.lookupUsers → userExtendedData（连接组三要素），失败返回 null。 */
    private suspend fun lookupUserExtendedData(displayName: String): List<Long>? {
        val resp = send("30722.50", BlazePackets.lookupUsers(displayName))
        if (resp.error != null) {
            debugError("lookupUsers 错误: ${resp.error.message} (component=${resp.error.component} errc=0x${resp.errc?.toString(16)})")
            return null
        }
        val ued = BlazeParsing.parseUserExtendedData(resp.data)
        if (ued == null) {
            debugError("lookupUsers 解析失败: keys=${resp.data?.keys}")
        }
        return ued
    }

    /** 进服前上报客户端状态（MODE=1）与网络信息。 */
    suspend fun reportClientState() {
        send("Util.setClientState", BlazePackets.setClientState(1), timeoutMs = 8_000)
        send("UserSessions.updateNetworkInfo", BlazePackets.updateNetworkInfo(), timeoutMs = 8_000)
    }

    /** GameManager.getFullGameData；出错时不抛异常，把 errorName/errc 带回给调用方。 */
    suspend fun getFullGameData(gameId: Long, timeoutMs: Long = 20_000): FullGameData {
        val resp = send("GameManager.getFullGameData", BlazePackets.getFullGameData(gameId), timeoutMs = timeoutMs)
        if (resp.error != null) {
            return FullGameData(null, emptyList(), "", emptyList(), resp.error.name, resp.errc)
        }
        val data = resp.data
            ?: return FullGameData(null, emptyList(), "", emptyList(), "NO_DATA", null)
        val lgam = data["LGAM 43"] as? List<*> ?: emptyList<Any?>()
        val block = lgam.firstOrNull() as? Map<*, *>
        val gameInfo = (block?.get("GAME 3") as? Map<*, *>)?.mapKeys { it.key.toString() }
        @Suppress("UNCHECKED_CAST")
        val players = (block?.get("PROS 43") as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
        return FullGameData(
            gameInfo = gameInfo,
            players = players,
            protocolVersion = gameInfo?.get("VSTR 1")?.toString().orEmpty(),
            roles = BlazeParsing.rolesFromGameInfo(gameInfo)
        )
    }

    /**
     * GameManager.joinGame（纯 Blaze 直连）。
     *
     * 进服前用 getFullGameData 拿真实协议版本与角色；满员（[PARTICIPANT_SLOTS_FULL_ERRC]）
     * 或查询失败时复用 [protocolVersionCache]/[rolesCache]（对应 Python direct_join 的复用逻辑）。
     * joinGame 报错后由 [shouldRebuildSession] 判定是否要重建整个会话。
     */
    suspend fun joinGame(
        gameId: Long,
        personaId: Long,
        platformId: Long,
        connectionGroupId: Long,
        userExtendedData: List<Long>?,
        protocolVersionCache: String,
        rolesCache: List<String> = emptyList(),
        postJoinState: Boolean = true,
        joinConfirmTimeoutMs: Long = 12_000,
        joinPollIntervalMs: Long = 500
    ): JoinResult {
        val gameData = runCatching { getFullGameData(gameId) }.getOrNull()
        val slotsFull = gameData?.errc == PARTICIPANT_SLOTS_FULL_ERRC
        val protocolVersion = gameData?.protocolVersion?.takeIf { it.isNotEmpty() }
            ?: protocolVersionCache.ifEmpty { DEFAULT_PROTOCOL_VERSION }
        val roles = gameData?.roles?.takeIf { it.isNotEmpty() } ?: rolesCache
        if (slotsFull) {
            debug("getFullGameData 满员(errc=$PARTICIPANT_SLOTS_FULL_ERRC)，复用 protocolVersion=$protocolVersion roles=$roles")
        } else if (gameData?.errorName != null) {
            debugError("getFullGameData 失败: ${gameData.errorName}，复用 protocolVersion=$protocolVersion roles=$roles")
        }
        val role = roles.firstOrNull { it == "soldier" } ?: roles.firstOrNull() ?: "soldier"
        val connectionGroup = if (userExtendedData != null && userExtendedData.size >= 3) {
            userExtendedData.take(3)
        } else {
            listOf(30722L, 2L, connectionGroupId)
        }

        debug(
            "joinGame: gameId=$gameId personaId=$personaId platformId=$platformId " +
                "connectionGroup=$connectionGroup protocolVersion=$protocolVersion role=$role"
        )
        val resp = try {
            send(
                "GameManager.joinGame",
                BlazePackets.joinGame(
                    gameId = gameId,
                    personaId = personaId,
                    platformId = platformId,
                    connectionGroup = connectionGroup,
                    protocolVersion = protocolVersion,
                    role = role,
                    gent = 0
                ),
                timeoutMs = 12_000
            )
        } catch (e: BlazeConnectionClosedException) {
            return JoinResult(false, "连接已断开", protocolVersion, false, true, roles)
        }
        if (resp.error != null) {
            return JoinResult(
                ok = false,
                reason = "joinGame 返回错误: ${resp.error.message}",
                protocolVersion = protocolVersion,
                prosSeen = false,
                socketDead = shouldRebuildSession(resp.error.name),
                roles = roles
            )
        }

        // 进服后伪装真客户端（对应 Python send_state_bundle）；失败只记日志，不影响进服判定
        if (postJoinState) {
            runCatching { sendPostJoinState(gameId, personaId, connectionGroup.last(), gameData?.gameInfo) }
                .onFailure { debugError("post-join 状态包失败: ${it.message}") }
            // 等 UserSessions 的 game 绑定通知（best-effort，对应 Python wait_session_game_binding）
            runCatching { waitSessionGameBinding(gameId, personaId, 1_500) }
                .onSuccess { if (it) debug("已收到 UserSessionExtendedDataUpdate 绑定通知") }
        }

        // 确认进服：只认 PROS 里出现自己的 PID（对齐 Python wait_until_seen）。
        // 不再把 errc == PARTICIPANT_SLOTS_FULL_ERRC 当「已进入」：该 errc 按协议表是
        // Core.ERR_AUTHENTICATION_REQUIRED，认证失败时会被误判成进服成功。
        val deadline = System.currentTimeMillis() + joinConfirmTimeoutMs
        var lastErrorName: String? = null
        var lastErrc: Long? = null
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { getFullGameData(gameId) }.getOrNull()
            lastErrorName = state?.errorName
            lastErrc = state?.errc
            val player = state?.players?.let { playerRecord(it, personaId) }
            if (player != null) {
                val likeClient = BlazeParsing.joinedLikeClient(player)
                debug("确认进服: PID 已在 PROS (joinedLikeClient=$likeClient)")
                return JoinResult(true, null, protocolVersion, true, false, roles)
            }
            kotlinx.coroutines.delay(joinPollIntervalMs)
        }
        val tail = lastErrorName?.let { " (最近一次 getFullGameData: $it errc=$lastErrc)" }.orEmpty()
        return JoinResult(
            ok = false,
            reason = "进服确认超时，未在玩家列表中找到 personaId=$personaId$tail",
            protocolVersion = protocolVersion,
            prosSeen = false,
            socketDead = false,
            roles = roles
        )
    }

    /** 等 UserSessions.UserSessionExtendedDataUpdate 通知里出现该 persona+gameId 的绑定。 */
    suspend fun waitSessionGameBinding(gameId: Long, personaId: Long, timeoutMs: Long): Boolean {
        val packet = socket.waitForNotification(timeoutMs) { p ->
            p.method == "UserSessions.UserSessionExtendedDataUpdate" &&
                sessionHasGame(p.data, personaId, gameId)
        }
        return packet != null
    }

    /** 进服后「伪装真客户端」状态包：mesh / 玩家属性 / MODE=3 / telemetry。 */
    private suspend fun sendPostJoinState(
        gameId: Long,
        personaId: Long,
        localConnectionGroupId: Long,
        gameInfo: Map<String, Any?>?
    ) {
        val groups = connectionGroups(gameInfo, localConnectionGroupId)
        for (group in groups) {
            runCatching { send("GameManager.meshEndpointsConnected", BlazePackets.meshEndpointsConnected(gameId, group), 8_000) }
        }
        runCatching { send("GameManager.updateMeshConnection", BlazePackets.updateMeshConnection(gameId, personaId), 8_000) }
        for ((key, value) in listOf(
            "latency" to "40",
            "InGame" to "true",
            "OriginalPartyLeader" to "false",
            "UserState" to "Loading",
            "UserState" to "Playing"
        )) {
            runCatching { send("GameManager.setPlayerAttributes", BlazePackets.setPlayerAttributes(gameId, personaId, key, value), 8_000) }
        }
        runCatching { send("Util.setClientState", BlazePackets.setClientState(3), 8_000) }
        val telemetryTargets = groups.filter { it != localConnectionGroupId }.ifEmpty { groups }
        for (target in telemetryTargets) {
            runCatching { send("GameManager.reportTelemetry", BlazePackets.reportTelemetry(gameId, localConnectionGroupId, target), 8_000) }
        }
    }

    private fun playerRecord(players: List<Map<String, Any?>>, personaId: Long): Map<String, Any?>? =
        players.firstOrNull { (it["PID  0"] as? Number)?.toLong() == personaId }

    private fun sessionHasGame(data: Map<String, Any?>?, personaId: Long, gameId: Long): Boolean {
        val d = data ?: return false
        if ((d["USID"] as? Number)?.toLong() != personaId && (d["USID 0"] as? Number)?.toLong() != personaId) return false
        val sessionData = d["DATA"] as? Map<*, *> ?: d["DATA 3"] as? Map<*, *> ?: return false
        val ulst = sessionData["ULST"] as? List<*> ?: sessionData["ULST 43"] as? List<*> ?: return false
        return ulst.any { item ->
            item is List<*> && item.size >= 3 &&
                item[0]?.toString() == "GameManager" &&
                (item[2] as? Number)?.toLong() == gameId
        }
    }

    private fun connectionGroups(gameInfo: Map<String, Any?>?, localConnectionGroupId: Long): List<Long> {
        val groups = LinkedHashSet<Long>()
        if (localConnectionGroupId != 0L) groups.add(localConnectionGroupId)
        for (key in listOf("PHST", "THST", "DHST")) {
            val entry = gameInfo?.entries?.firstOrNull { it.key.startsWith(key) }?.value as? Map<*, *>
            val cong = (entry?.get("CONG") as? Number)?.toLong()
                ?: (entry?.get("CONG 0") as? Number)?.toLong()
            if (cong != null && cong != 0L) groups.add(cong)
        }
        return groups.toList()
    }

    // ── 底层发送 ──

    private suspend fun send(
        method: String,
        data: Map<String, Any?>,
        timeoutMs: Long = requestTimeoutMs
    ): BlazePacket = withTimeout(timeoutMs) {
        socket.send(BlazeRequest(method = method, data = data)).awaitResult()
    }

    companion object {
        /** GameManager 4.4 PARTICIPANT_SLOTS_FULL：服务器满员时 getFullGameData 返回的 errc（0x40040000）。 */
        const val PARTICIPANT_SLOTS_FULL_ERRC = 1074003968L

        /** 默认协议版本（与 CardTool / Python DEFAULT_PROTOCOL_VERSION 一致）。 */
        const val DEFAULT_PROTOCOL_VERSION = "3779779"

        /**
         * joinGame 报错后是否需要重建整个 Blaze 会话（对应 Python stay_forever 的
         * close_session + connect_session），而不是在同一 socket 上继续重试。
         */
        fun shouldRebuildSession(errorName: String): Boolean =
            errorName.contains("AUTHENTICATION_REQUIRED") ||
                errorName.contains("UNRESPONSIVE_GAME_STATE") ||
                errorName.contains("INVALID_SESSION") ||
                errorName.contains("SESSION_EXPIRED") ||
                errorName.contains("TIMEOUT")
    }
}

/** Blaze 协议错误（响应结构不符合预期）。 */
class BlazeProtocolException(message: String) : Exception(message)

private suspend fun <T> CompletableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        if (error != null) cont.resumeWithException(error) else cont.resume(value)
    }
    cont.invokeOnCancellation { cancel(true) }
}
