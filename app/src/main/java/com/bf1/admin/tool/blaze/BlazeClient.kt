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

/** getFullGameData 结果。 */
data class FullGameData(
    val gameInfo: Map<String, Any?>?,
    val players: List<Map<String, Any?>>,
    val protocolVersion: String,
    val roles: List<String>
)

/** joinGame 结果。 */
data class JoinResult(
    val ok: Boolean,
    val reason: String?,
    val protocolVersion: String?,
    val prosSeen: Boolean,
    val socketDead: Boolean
)

/**
 * Blaze 高层客户端：封装登录/查身份/进服/查询等操作，对应 CardTool.js 的
 * blazeLogin / lookupUsers / joinServer / fetchFullGameData / waitUntilSeen。
 *
 * 所有发送均带超时；socket 断开时抛 [BlazeConnectionClosedException]，由上层重建。
 */
class BlazeClient(
    private val socket: BlazeSocket,
    private val requestTimeoutMs: Long = 15_000,
    private val onDebug: ((String) -> Unit)? = null
) {
    private fun debug(msg: String) {
        onDebug?.invoke(msg)
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
        val ulst = resp.data?.get("ULST 43") as? List<*>
        val first = ulst?.firstOrNull() as? Map<*, *> ?: return null
        val edat = first["EDAT 3"] as? Map<*, *> ?: return null
        val inner = edat["ULST 49"] as? List<*> ?: return null
        return inner.mapNotNull { (it as? Number)?.toLong() }
    }

    /** 进服前上报客户端状态（MODE=1）与网络信息。 */
    suspend fun reportClientState() {
        send("Util.setClientState", BlazePackets.setClientState(1), timeoutMs = 8_000)
        send("UserSessions.updateNetworkInfo", BlazePackets.updateNetworkInfo(), timeoutMs = 8_000)
    }

    /** GameManager.getFullGameData。 */
    suspend fun getFullGameData(gameId: Long, timeoutMs: Long = 20_000): FullGameData {
        val resp = send("GameManager.getFullGameData", BlazePackets.getFullGameData(gameId), timeoutMs = timeoutMs)
        val data = resp.data ?: throw BlazeProtocolException("getFullGameData 无数据")
        val lgam = data["LGAM 43"] as? List<*> ?: emptyList<Any?>()
        val block = lgam.firstOrNull() as? Map<*, *>
        val gameInfo = block?.get("GAME 3") as? Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val players = (block?.get("PROS 43") as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()
        return FullGameData(
            gameInfo = gameInfo?.mapKeys { it.key.toString() },
            players = players,
            protocolVersion = gameInfo?.get("VSTR 1")?.toString().orEmpty(),
            roles = BlazeParsing.rolesFromGameInfo(gameInfo?.mapKeys { it.key.toString() })
        )
    }

    /**
     * GameManager.joinGame（direct 直连）。
     * [connectionGroup] 优先 lookupUsers 前三项，否则回退 [30722, 2, connectionGroupId]。
     */
    suspend fun joinGame(
        gameId: Long,
        personaId: Long,
        platformId: Long,
        connectionGroupId: Long,
        userExtendedData: List<Long>?,
        protocolVersionCache: String,
        joinConfirmTimeoutMs: Long = 12_000,
        joinPollIntervalMs: Long = 500
    ): JoinResult {
        // 进服前像真实客户端一样上报客户端状态与网络信息（对应 CardTool joinServer 前两步）
        runCatching { reportClientState() }
        val gameData = runCatching { getFullGameData(gameId) }.getOrNull()
        val protocolVersion = gameData?.protocolVersion?.ifEmpty { protocolVersionCache } ?: protocolVersionCache
        val role = gameData?.roles?.firstOrNull { it == "soldier" } ?: gameData?.roles?.firstOrNull() ?: "soldier"
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
            return JoinResult(false, "连接已断开", protocolVersion, false, true)
        }
        if (resp.error != null) {
            val dead = resp.error.name.contains("AUTHENTICATION_REQUIRED")
            return JoinResult(
                ok = false,
                reason = "joinGame 返回错误: ${resp.error.message}",
                protocolVersion = protocolVersion,
                prosSeen = false,
                socketDead = dead
            )
        }

        // 确认进服：轮询 PROS 直到 personaId 出现
        val seen = waitUntilSeen(gameId, personaId, joinConfirmTimeoutMs, joinPollIntervalMs)
        if (seen) {
            return JoinResult(true, null, protocolVersion, true, false)
        }
        return JoinResult(
            ok = false,
            reason = "进服确认超时，未在玩家列表中找到 personaId=$personaId",
            protocolVersion = protocolVersion,
            prosSeen = false,
            socketDead = false
        )
    }

    /** 轮询 getFullGameData 直到 personaId 出现在玩家列表或超时。 */
    suspend fun waitUntilSeen(gameId: Long, personaId: Long, timeoutMs: Long, pollMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { getFullGameData(gameId) }.getOrNull()
            if (state != null && BlazeParsing.playerInPros(state.players, personaId)) return true
            kotlinx.coroutines.delay(pollMs)
        }
        return false
    }

    // ── 底层发送 ──

    private suspend fun send(
        method: String,
        data: Map<String, Any?>,
        timeoutMs: Long = requestTimeoutMs
    ): BlazePacket = withTimeout(timeoutMs) {
        socket.send(BlazeRequest(method = method, data = data)).awaitResult()
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
