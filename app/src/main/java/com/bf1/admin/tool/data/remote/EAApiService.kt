package com.bf1.admin.tool.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val TOKEN_TTL_MS = 2 * 60 * 60 * 1000L // 2 小时

internal data class AccessTokenCache(
    val remid: String,
    val token: String,
    val refreshedAt: Long
) {
    fun isReusableFor(requestRemid: String, now: Long): Boolean {
        return remid == requestRemid && now - refreshedAt < TOKEN_TTL_MS
    }
}

class EAApiService {
    companion object {
        private const val API_URL = "https://sparta-gw.battlelog.com/jsonrpc/pc/api"
        private const val CLIENT_VERSION = "release-bf1-lsu35_26385_ad7bf56a_tunguska_all_prod"
        private const val DB_ID = "Tunguska.Shipping2PC.Win32"
    }

    /**
     * 未消费的 Cookie 更新。
     * getAccessToken / getAuthCode 的响应中如果检测到新 remid/sid，会写入这里。
     * 调用方（AdminRepository）在每次 ensureSessionId 后消费并持久化到 DB。
     */
    @Volatile var pendingNewRemid: String? = null
    @Volatile var pendingNewSid: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // ═══════════════════════════════════════════════════
    // Token 缓存（进程内存，不持久化 — 重启后从 remid/sid 重新获取）
    // ═══════════════════════════════════════════════════

    private var accessTokenCache: AccessTokenCache? = null

    // sessionId 缓存关联到账号（remid 变化时自动失效）
    private var cachedSessionId: String? = null
    private var cachedSessionRemid: String = ""
    private var sessionIdRefreshTime: Long = 0L

    /// Mutex 防止多个协程同时触发刷新
    private val refreshMutex = Mutex()

    // ═══════════════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════════════

    data class PersonaInfo(val displayName: String, val personaId: String)
    data class SessionInfo(val sessionId: String, val persona: PersonaInfo)
    data class AdminInfo(val personaId: String, val displayName: String, val avatar: String)

    /**
     * remid/sid 凭证已过期，需要用户重新登录。
     */
    class CredentialsExpiredException(message: String) : Exception(message)

    // ═══════════════════════════════════════════════════
    // 被动 Cookie 更新（对应 EAappEmulater 的 UpdateCookie）
    // ═══════════════════════════════════════════════════

    private fun updateCookiesFromResponse(response: okhttp3.Response) {
        val setCookieHeaders = response.headers("Set-Cookie")
        var newRemid: String? = null
        var newSid: String? = null

        for (header in setCookieHeaders) {
            val cookie = header.split(";").first().trim()
            val parts = cookie.split("=", limit = 2)
            if (parts.size == 2) {
                when (parts[0].trim().lowercase()) {
                    "remid" -> newRemid = parts[1].trim()
                    "sid" -> newSid = parts[1].trim()
                }
            }
        }
        if (newRemid != null || newSid != null) {
            pendingNewRemid = newRemid
            pendingNewSid = newSid
        }
    }

    // ═══════════════════════════════════════════════════
    // 对外接口
    // ═══════════════════════════════════════════════════

    /**
     * 完整认证流程：remid/sid → access_token → persona → auth_code → sessionId。
     * 用于首次登录 / 手动验证，返回 SessionInfo（含 persona 用于展示）。
     */
    suspend fun authenticate(remid: String, sid: String): Result<SessionInfo> = runCatching {
        val cookieHeader = "remid=$remid; sid=$sid"
        val accessToken = getAccessToken(cookieHeader)
        val persona = getPersonaInfo(accessToken)
        val authCode = getAuthCode(accessToken, cookieHeader)
        val sessionId = getSessionId(authCode)
        SessionInfo(sessionId, persona)
    }

    /**
     * 获取有效的 sessionId，优先使用缓存。
     * 缓存未过期时零网络请求直接返回；过期则自动用 remid/sid 完整走一遍
     * access_token → auth_code → sessionId 流程。
     *
     * 用于所有 Battlelog JSON-RPC 操作（管理员管理、服务器查询等）。
     *
     * 注意：切换账号（remid 变化）时缓存自动失效。
     */
    suspend fun ensureSessionId(remid: String, sid: String): String {
        return refreshMutex.withLock {
            // 切换账号 → 缓存失效
            if (cachedSessionRemid != remid) {
                cachedSessionId = null
                sessionIdRefreshTime = 0L
            }

            if (cachedSessionId != null &&
                (System.currentTimeMillis() - sessionIdRefreshTime) < TOKEN_TTL_MS
            ) {
                return cachedSessionId!!
            }

            // sessionId 过期，重新获取
            // 先检查 AccessToken 缓存是否可用（省一次 GetToken）
            val cookieHeader = "remid=$remid; sid=$sid"
            val accessToken = ensureAccessTokenInternal(remid, cookieHeader)
            val authCode = getAuthCode(accessToken, cookieHeader)
            val sessionId = getSessionId(authCode)

            cachedSessionId = sessionId
            cachedSessionRemid = remid
            sessionIdRefreshTime = System.currentTimeMillis()
            sessionId
        }
    }

    // ═══════════════════════════════════════════════════
    // AccessToken 缓存刷新
    // ═══════════════════════════════════════════════════

    private suspend fun ensureAccessTokenInternal(remid: String, cookieHeader: String): String {
        val now = System.currentTimeMillis()
        accessTokenCache?.takeIf { it.isReusableFor(remid, now) }?.let {
            return it.token
        }

        val token = getAccessToken(cookieHeader)
        accessTokenCache = AccessTokenCache(remid, token, now)
        return token
    }

    // ═══════════════════════════════════════════════════
    // EA API：remid/sid → access_token
    // ═══════════════════════════════════════════════════

    private fun getAccessToken(cookieHeader: String): String {
        val url = "https://accounts.ea.com/connect/auth" +
                "?client_id=ORIGIN_JS_SDK" +
                "&response_type=token" +
                "&redirect_uri=nucleus%3Arest" +
                "&prompt=none" +
                "&release_type=prod"

        val request = Request.Builder().url(url)
            .header("Cookie", cookieHeader)
            .build()

        client.newCall(request).execute().use { response ->
            // 被动更新 remid/sid（对应 EAappEmulater EaApi.UpdateCookie）
            updateCookiesFromResponse(response)

            val body = response.body?.string()
                ?: throw Exception("Empty response getting access token")
            val json = JSONObject(body)

            // login_required → remid/sid 已彻底过期
            if (json.optString("error") == "login_required") {
                // 清空所有缓存
                accessTokenCache = null
                cachedSessionId = null
                sessionIdRefreshTime = 0L
                throw CredentialsExpiredException(
                    "remid/sid 已过期 (${json.optString("error_code", "")}): " +
                    json.optString("error", body)
                )
            }

            return json.optString("access_token", "").ifEmpty {
                throw Exception("No access_token in response: $body")
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // EA API：access_token → 玩家身份
    // ═══════════════════════════════════════════════════

    private fun getPersonaInfo(accessToken: String): PersonaInfo {
        val request = Request.Builder()
            .url("https://gateway.ea.com/proxy/identity/pids/me/personas")
            .header("Authorization", "Bearer $accessToken")
            .header("X-Expand-Results", "true")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw Exception("Empty response getting persona")
            val json = JSONObject(body)
            val personas = json.optJSONObject("personas")
                ?.optJSONArray("persona")
                ?: throw Exception("No personas found")

            for (i in 0 until personas.length()) {
                val p = personas.getJSONObject(i)
                if (p.optString("namespaceName") == "cem_ea_id") {
                    return PersonaInfo(
                        displayName = p.optString("displayName"),
                        personaId = p.optString("personaId")
                    )
                }
            }
            throw Exception("No cem_ea_id persona found")
        }
    }

    // ═══════════════════════════════════════════════════
    // EA API：access_token → auth_code（换 Battlelog sessionId 用）
    // ═══════════════════════════════════════════════════

    private fun getAuthCode(accessToken: String, cookieHeader: String): String {
        val url = "https://accounts.ea.com/connect/auth" +
                "?access_token=$accessToken" +
                "&client_id=sparta-backend-as-user-pc" +
                "&response_type=code" +
                "&release_type=prod"

        val request = Request.Builder().url(url)
            .header("Cookie", cookieHeader)
            .build()

        client.newCall(request).execute().use { response ->
            // 被动更新 remid/sid（对应 EAappEmulater EaApi.UpdateCookie）
            updateCookiesFromResponse(response)

            val location = response.header("Location")
            if (response.code != 302)
                throw Exception("Expected 302 for auth code, got ${response.code}")
            if (location == null)
                throw Exception("No Location header for auth code")
            val code = location.substringAfter("code=", "").substringBefore("&")
            if (code.isEmpty())
                throw Exception("No code in redirect: $location")
            return code
        }
    }

    // ═══════════════════════════════════════════════════
    // Battlelog API：auth_code → sessionId
    // ═══════════════════════════════════════════════════

    private fun getSessionId(authCode: String): String {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "Authentication.getEnvIdViaAuthCode")
            put("params", JSONObject().apply {
                put("authCode", authCode)
                put("locale", "zh-tw")
            })
            put("id", UUID.randomUUID().toString())
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("X-ClientVersion", CLIENT_VERSION)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string()
                ?: throw Exception("Empty response getting session")
            val json = JSONObject(respBody)
            val result = json.optJSONObject("result")
                ?: throw Exception("No result: $respBody")
            return result.optString("sessionId", "").ifEmpty {
                throw Exception("No sessionId in result: $respBody")
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // Battlelog API：服务器 & 管理员操作
    // ═══════════════════════════════════════════════════

    fun getServerDetails(sessionId: String, serverId: String): Pair<String, String> {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "RSP.getServerDetails")
            put("params", JSONObject().apply {
                put("game", "tunguska")
                put("serverId", serverId)
            })
            put("id", UUID.randomUUID().toString())
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("X-GatewaySession", sessionId)
            .header("X-ClientVersion", CLIENT_VERSION)
            .header("X-DbId", DB_ID)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string()
                ?: throw Exception("Empty response getting server details")
            val json = JSONObject(respBody)
            val result = json.optJSONObject("result")
                ?: throw Exception("No result: $respBody")
            val server = result.optJSONObject("server")
            val name = server?.optString("name")?.ifEmpty { serverId } ?: serverId
            return Pair(serverId, name)
        }
    }

    fun getAdminList(sessionId: String, serverId: String): List<AdminInfo> {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "RSP.getServerDetails")
            put("params", JSONObject().apply {
                put("game", "tunguska")
                put("serverId", serverId)
            })
            put("id", UUID.randomUUID().toString())
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("X-GatewaySession", sessionId)
            .header("X-ClientVersion", CLIENT_VERSION)
            .header("X-DbId", DB_ID)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string()
                ?: throw Exception("Empty response getting admin list")
            val json = JSONObject(respBody)
            val result = json.optJSONObject("result")
                ?: throw Exception("No result: $respBody")
            val admins = result.optJSONArray("adminList") ?: return emptyList()

            val list = mutableListOf<AdminInfo>()
            for (i in 0 until admins.length()) {
                val a = admins.getJSONObject(i)
                list.add(
                    AdminInfo(
                        personaId = a.optString("personaId"),
                        displayName = a.optString("displayName"),
                        avatar = a.optString("avatar")
                    )
                )
            }
            return list
        }
    }

    fun addAdmin(sessionId: String, serverId: String, personaId: String): String {
        return adminRpcCall(sessionId, "RSP.addServerAdmin", serverId, personaId)
    }

    fun removeAdmin(sessionId: String, serverId: String, personaId: String): String {
        return adminRpcCall(sessionId, "RSP.removeServerAdmin", serverId, personaId)
    }

    private fun adminRpcCall(
        sessionId: String, method: String, serverId: String, personaId: String
    ): String {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", JSONObject().apply {
                put("game", "tunguska")
                put("serverId", serverId)
                put("personaId", personaId)
            })
            put("id", UUID.randomUUID().toString())
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("X-GatewaySession", sessionId)
            .header("X-ClientVersion", CLIENT_VERSION)
            .header("X-DbId", DB_ID)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respBody = response.body?.string()
                ?: throw Exception("Empty response from $method")
            val json = JSONObject(respBody)
            if (json.has("error")) {
                val error = json.getJSONObject("error")
                throw Exception("API error: ${error.optString("message", respBody)}")
            }
            return json.optJSONObject("result")?.toString() ?: "success"
        }
    }

    fun resolvePlayerName(playerName: String): String {
        val url = "https://api.gametools.network/bf1/player/?name=$playerName"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw Exception("Empty response resolving player name")
            val json = JSONObject(body)
            val id = json.optString("id", "")
            if (id.isEmpty()) throw Exception("Player not found: $playerName")
            return id
        }
    }

    fun getWelcomeMessage(sessionId: String): String? {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "Onboarding.welcomeMessage")
            put("params", JSONObject().apply {
                put("game", "tunguska")
                put("minutesToUTC", "-480")
            })
            put("id", UUID.randomUUID().toString())
        }

        val request = Request.Builder()
            .url(API_URL)
            .header("X-GatewaySession", sessionId)
            .header("X-ClientVersion", CLIENT_VERSION)
            .header("X-DbId", DB_ID)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string() ?: return null
                val json = JSONObject(respBody)
                val result = json.optJSONObject("result") ?: return null
                result.optString("firstMessage").takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
