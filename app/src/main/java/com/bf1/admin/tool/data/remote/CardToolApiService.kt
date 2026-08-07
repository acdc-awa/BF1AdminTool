package com.bf1.admin.tool.data.remote

import com.bf1.admin.tool.cardtool.MapRotationEntry
import com.bf1.admin.tool.cardtool.RspInfo
import com.bf1.admin.tool.cardtool.ServerSlots
import com.bf1.admin.tool.cardtool.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 卡服功能网络层，对应 CardTool.js 的 loginB / loginG / gatewayRequest。
 *
 * 网关地址 `sparta-gw-bf1.battlelog.com` 与 [EAApiService] 的 `sparta-gw.battlelog.com`
 * 解析到同一组 IP（同一后端负载均衡的别名），换 session 走同一 RPC
 * （Authentication.getEnvIdViaAuthCode，同一 client sparta-backend-as-user-pc）——
 * 两者产生的 sessionId 通用，因此卡服直接复用 CredentialManager 缓存的 sessionId。
 * 本类保留 Blaze AuthCode 换取（GOS-BlazeServer-BFTUN-PC，供 Blaze socket 登录用）。
 */
class CardToolApiService {

    companion object {
        private const val GATEWAY_URL = "https://sparta-gw-bf1.battlelog.com/jsonrpc/pc/api"
        private const val OAUTH_URL = "https://accounts.ea.com/connect/auth"
        private const val GOS_CLIENT_ID = "GOS-BlazeServer-BFTUN-PC"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 信任所有证书的客户端，仅用于 gosredirector（EA 旧端点自签证书，CardTool 同样关闭校验）。 */
    private val trustAllClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .sslSocketFactory(TrustAllSsl.socketFactory, TrustAllSsl.trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    // ═══════════════════════════════════════════════════
    // 登录
    // ═══════════════════════════════════════════════════

    /** loginB 结果：Blaze AuthCode + 本次轮换出的新 cookie。 */
    data class BlazeAuthResult(val authCode: String, val rotated: RotatedCookies)

    /**
     * 用 remid/sid 换取 Blaze AuthCode（对应 CardTool loginB）。
     * authCode 单次有效，供 Blaze socket 的 Authentication.login 使用。
     */
    suspend fun getBlazeAuthCode(remid: String, sid: String): Result<BlazeAuthResult> = runCatching {
        withContext(Dispatchers.IO) {
            val url = "$OAUTH_URL?client_id=$GOS_CLIENT_ID&response_type=code&prompt=none"
            val request = Request.Builder()
                .url(url)
                .header("Cookie", "remid=$remid; sid=$sid;")
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Connection", "close")
                .build()
            client.newCall(request).execute().use { response ->
                val location = response.header("Location")
                    ?: throw Exception("Blaze OAuth 未返回重定向（HTTP ${response.code}）")
                val rotated = accumulateRotatedCookies(RotatedCookies(), response.headers("Set-Cookie"))
                checkOAuthRedirect(location)
                val authCode = location.substringAfterLast("code=")
                if (authCode.isEmpty()) throw Exception("重定向中未找到 code: $location")
                BlazeAuthResult(authCode, rotated)
            }
        }
    }

    private fun checkOAuthRedirect(location: String) {
        when {
            location.contains("fid=") -> throw EAApiService.CredentialsExpiredException("Cookie失效")
            location.contains("login_require") -> throw EAApiService.CredentialsExpiredException("Cookie失效")
            location.contains("error_code=user_status_invalid") -> throw Exception("账号被封禁")
        }
    }

    // ═══════════════════════════════════════════════════
    // Gateway JSON-RPC
    // ═══════════════════════════════════════════════════

    /**
     * 调用 BF1 Gateway JSON-RPC（对应 CardTool gatewayRequest）。
     * 自动附加 game:"tunguska"，并在响应含 error 时抛出 [GatewayError]。
     */
    fun gatewayRequest(sessionId: String?, method: String, params: JSONObject): JSONObject {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params.apply { put("game", "tunguska") })
            put("id", UUID.randomUUID().toString())
        }
        val builder = Request.Builder()
            .url(GATEWAY_URL)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (sessionId != null) builder.header("X-GatewaySession", sessionId)

        client.newCall(builder.build()).execute().use { response ->
            val respBody = response.body?.string() ?: throw GatewayError(null, "空响应", method)
            val json = try {
                JSONObject(respBody)
            } catch (e: Exception) {
                // 非 JSON 响应体（如 HTML）→ 包装为网关错误
                JSONObject().apply {
                    put("error", JSONObject().apply {
                        put("message", respBody.take(200))
                        put("code", 0)
                    })
                }
            }
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                throw GatewayError(err.optInt("code"), err.optString("message"), method)
            }
            return json.getJSONObject("result")
        }
    }

    // ═══════════════════════════════════════════════════
    // 服务器地址
    // ═══════════════════════════════════════════════════

    /** 从 gosredirector 获取 Blaze 服务器 host/port（CardTool 同款 XML 请求）。 */
    fun getBlazeServerAddress(): Pair<String, Int> {
        val body = "<serverinstancerequest><name>battlefield-1-pc</name>" +
            "<connectionprofile>standardSecure_v4</connectionprofile></serverinstancerequest>"
        val request = Request.Builder()
            .url("https://winter15.gosredirector.ea.com:42230/redirector/getServerInstance")
            .header("Content-Type", "application/xml")
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/xml".toMediaType()))
            .build()
        trustAllClient.newCall(request).execute().use { response ->
            val respBody = response.body?.string()
                ?: throw Exception("获取 Blaze 服务器地址失败：空响应")
            val json = try {
                JSONObject(respBody)
            } catch (e: Exception) {
                throw Exception("获取 Blaze 服务器地址失败：非 JSON 响应")
            }
            val address = json.optJSONObject("address") ?: throw Exception("获取 Blaze 服务器地址失败：无 address")
            val ip = address.optJSONObject("ipAddress") ?: throw Exception("获取 Blaze 服务器地址失败：无 ipAddress")
            val host = ip.optString("hostname", "")
            val port = ip.optInt("port", 0)
            if (host.isEmpty() || port == 0) throw Exception("获取 Blaze 服务器地址失败：host/port 缺失")
            return host to port
        }
    }

    // ═══════════════════════════════════════════════════
    // 服务器 RPC
    // ═══════════════════════════════════════════════════

    /** 全量服务器信息（含 adminList / owner / serverSettings），用于管理员校验与更新基准。 */
    /** 全量服务器信息（含 adminList / owner / serverSettings），用于管理员校验与更新基准。 */
    fun getFullServerDetails(sessionId: String, gameId: String): RspInfo {
        val result = gatewayRequest(
            sessionId,
            "GameServer.getFullServerDetails",
            JSONObject().apply { put("gameId", gameId) }
        )
        return parseFullServerDetails(result)
    }

    /** 实时服务器状态（轮换/模式/槽位），用于判断是否达到卡服条件。 */
    fun getServerDetails(sessionId: String, gameId: String): ServerState {
        val result = gatewayRequest(
            sessionId,
            "GameServer.getServerDetails",
            JSONObject().apply { put("gameId", gameId) }
        )
        val rotation = result.optJSONArray("rotation")
        val entries = if (rotation != null) {
            (0 until rotation.length()).map { i ->
                val e = rotation.getJSONObject(i)
                MapRotationEntry(
                    mapImage = e.optString("mapImage"),
                    mapPrettyName = e.optString("mapPrettyName"),
                    modePrettyName = e.optString("modePrettyName")
                )
            }
        } else {
            emptyList()
        }
        val slots = result.optJSONObject("slots")
        val soldier = slots?.optJSONObject("Soldier")
        val spectator = slots?.optJSONObject("Spectator")
        return ServerState(
            guid = result.optString("guid"),
            mapMode = result.optString("mapMode"),
            rotation = entries,
            slots = ServerSlots(
                soldierCurrent = soldier?.optInt("current") ?: 0,
                soldierMax = soldier?.optInt("max") ?: 0,
                spectatorCurrent = spectator?.optInt("current") ?: 0,
                spectatorMax = spectator?.optInt("max") ?: 0
            )
        )
    }

    /** 更新服务器设置/轮换（payload 由 cardtool.buildServerUpdatePayload 构造）。 */
    fun updateServer(sessionId: String, payload: Map<String, Any?>) {
        gatewayRequest(sessionId, "RSP.updateServer", JSONObject(payload))
    }

    /** 锚定地图：把当前轮换切到 levelIndex（配合 updateServer 使用）。 */
    fun chooseLevel(sessionId: String, persistedGameId: String, levelIndex: Int = 0) {
        gatewayRequest(
            sessionId,
            "RSP.chooseLevel",
            JSONObject().apply {
                put("persistedGameId", persistedGameId)
                put("levelIndex", levelIndex)
            }
        )
    }

    /** 预留观战位（cardtool 进服方式的前置步骤）。 */
    fun reserveSlot(sessionId: String, gameId: String): String {
        val result = gatewayRequest(
            sessionId,
            "Game.reserveSlot",
            JSONObject().apply {
                put("gameId", gameId)
                put("settings", JSONObject().apply { put("role", "spectator") })
            }
        )
        return result.optString("status", "")
    }

    /** 离开服务器（Game.leaveGame）。 */
    fun leaveGame(sessionId: String, gameId: String) {
        gatewayRequest(sessionId, "Game.leaveGame", JSONObject().apply { put("gameId", gameId) })
    }
}

/** JSONObject → 纯 Map（用于把 serverSettings.customGameSettings 传给 payload 构造）。 */
fun JSONObject.toMap(): Map<String, Any?> {
    val map = LinkedHashMap<String, Any?>()
    keys().forEach { key ->
        map[key.toString()] = opt(key.toString())
    }
    return map
}
