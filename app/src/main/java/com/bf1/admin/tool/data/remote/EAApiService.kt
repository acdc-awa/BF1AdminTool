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

    // Mutex 防止多个协程同时触发完整兑换流程。
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

    suspend fun refreshSessionId(remid: String, sid: String): String = refreshMutex.withLock {
        val cookieHeader = "remid=$remid; sid=$sid"
        val accessToken = getAccessToken(cookieHeader)
        val authCode = getAuthCode(accessToken, cookieHeader)
        getSessionId(authCode)
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
            if (!response.isSuccessful) {
                throw ServerLookupException(response.code)
            }
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
