package com.bf1.admin.tool.data.remote

import com.bf1.admin.tool.cardtool.RspInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

class EAApiService {
    companion object {
        private const val API_URL = "https://sparta-gw.battlelog.com/jsonrpc/pc/api"
        private const val CLIENT_VERSION = "release-bf1-lsu35_26385_ad7bf56a_tunguska_all_prod"
        private const val DB_ID = "Tunguska.Shipping2PC.Win32"

        // ---- EA app (Juno) 认证参数，复刻 EAappEmulater / eaid_to_pid.py ----
        private const val JUNO_TOKEN_ENDPOINT = "https://accounts.ea.com/connect/token"
        private const val JUNO_CLIENT_ID = "JUNO_PC_CLIENT"
        private const val JUNO_CLIENT_SECRET =
            "4mRLtYMb6vq9qglomWEaT4ChxsXWcyqbQpuBNfMPOYOiDmYYQmjuaBsF2Zp0RyVeWkfqhE9TuGgAw7te"
        private const val JUNO_REDIRECT_URI = "qrc:///html/login_successful.html"
    }

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
    data class SessionInfo(
        val sessionId: String,
        val persona: PersonaInfo,
        val rotated: RotatedCookies
    )
    data class RefreshResult(val sessionId: String, val rotated: RotatedCookies)
    data class AdminInfo(val personaId: String, val displayName: String, val avatar: String)
    data class EaPidResult(val personaId: String, val rotated: RotatedCookies)

    /**
     * EA 原生查 PID 失败（含 Juno 重试失败），携带本轮累积的轮换 cookie。
     * 403 insufficient_scope 恒定触发，Juno 重试失败时若丢弃轮换，连续失败会让旧凭证
     * 逐渐失效；上层兜底 gametools 前应先 persistRotation 落库。
     */
    class EaPidQueryException(
        message: String,
        cause: Throwable?,
        val rotated: RotatedCookies
    ) : Exception(message, cause)

    /** ORIGIN token 缺少 dp.server.default scope（脚本中触发 Juno 回退的 403）。 */
    class InsufficientScopeException(message: String) : Exception(message)

    /**
     * remid/sid 凭证已过期，需要用户重新登录。
     */
    class CredentialsExpiredException(message: String) : Exception(message)

    // ═══════════════════════════════════════════════════
    // 被动 Cookie 更新（对应 EAappEmulater 的 UpdateCookie）
    // ═══════════════════════════════════════════════════

    /**
     * 单次认证流程内累积 Set-Cookie。
     *
     * 刻意做成按次创建的局部对象而非 service 上的共享字段：轮换结果必须与产生它的
     * 那次调用绑定，否则会被写到别的账号头上。
     */
    private class CookieCollector {
        var rotated: RotatedCookies = RotatedCookies()
            private set

        fun absorb(response: okhttp3.Response) {
            rotated = accumulateRotatedCookies(rotated, response.headers("Set-Cookie"))
        }
    }

    // ═══════════════════════════════════════════════════
    // 对外接口
    // ═══════════════════════════════════════════════════

    /**
     * 完整认证流程：remid/sid → access_token → persona → auth_code → sessionId。
     * 用于首次登录 / 手动验证，返回 SessionInfo（含 persona 用于展示、rotated 用于落库）。
     */
    suspend fun authenticate(remid: String, sid: String): Result<SessionInfo> = runCatching {
        val cookies = CookieCollector()
        val cookieHeader = "remid=$remid; sid=$sid"
        val accessToken = getAccessToken(cookieHeader, cookies)
        val persona = getPersonaInfo(accessToken)
        val authCode = getAuthCode(accessToken, cookieHeader, cookies)
        val sessionId = getSessionId(authCode)
        SessionInfo(sessionId, persona, cookies.rotated)
    }

    suspend fun refreshSessionId(remid: String, sid: String): RefreshResult = refreshMutex.withLock {
        val cookies = CookieCollector()
        val cookieHeader = "remid=$remid; sid=$sid"
        val accessToken = getAccessToken(cookieHeader, cookies)
        val authCode = getAuthCode(accessToken, cookieHeader, cookies)
        RefreshResult(getSessionId(authCode), cookies.rotated)
    }

    // ═══════════════════════════════════════════════════
    // EA API：remid/sid → access_token
    // ═══════════════════════════════════════════════════

    private fun getAccessToken(cookieHeader: String, cookies: CookieCollector): String {
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
            cookies.absorb(response)

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

    /**
     * Juno（EA app）OAuth 完整流程：PKCE + pc_sign 授权 → code → token 交换。
     * ORIGIN token 缺 dp.server.default scope（403 insufficient_scope）时自动调用。
     */
    private fun getAccessTokenJuno(remid: String, sid: String, cookies: CookieCollector): String {
        val random = SecureRandom()
        val codeVerifier = b64url(ByteArray(32).also { random.nextBytes(it) })
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        val codeChallenge = b64url(digest)
        val nonce = ByteBuffer.wrap(ByteArray(4).also { random.nextBytes(it) }).int.toString()
        val ts = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss:SSS")
            .withZone(ZoneOffset.UTC).format(Instant.now())
        val pcSign = buildPcSign(if (random.nextBoolean()) "v1" else "v2", ts)

        val authUrl = "https://accounts.ea.com/connect/auth".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", JUNO_CLIENT_ID)
            .addQueryParameter("sbiod_enabled", "false")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("locale", "en_US")
            .addQueryParameter("pc_sign", pcSign)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", codeChallenge)
            .build()

        val authRequest = Request.Builder().url(authUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/118.0.0.0 Safari/537.36")
            .header("Cookie", "remid=$remid; sid=$sid")
            .build()

        client.newCall(authRequest).execute().use { response ->
            cookies.absorb(response)
            val location = response.header("Location")
            val isRedirect = response.code in 301..308
            if (!isRedirect || location == null || !location.contains("login_successful.html")) {
                throw Exception("Juno 授权未成功: HTTP ${response.code}, Location=$location")
            }
            // qrc:/// 是自定义 scheme，不走 HttpUrl 解析；且与 getAuthCode 的取法保持一致
            val callback = if ('#' in location) location.replaceFirst('#', '&') else location
            if (callback.contains("error=")) throw Exception("Juno 授权返回错误: $callback")
            val code = callback.substringAfter("code=", "").substringBefore("&")
            if (code.isEmpty()) throw Exception("Juno 回调里没有 code: $location")

            val tokenBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("code_verifier", codeVerifier)
                .add("client_id", JUNO_CLIENT_ID)
                .add("client_secret", JUNO_CLIENT_SECRET)
                .add("redirect_uri", JUNO_REDIRECT_URI)
                .add("token_format", "JWS")
                .build()
            val tokenRequest = Request.Builder().url(JUNO_TOKEN_ENDPOINT)
                .header("User-Agent", "EAApp/13.0 (Windows)")
                .post(tokenBody)
                .build()

            client.newCall(tokenRequest).execute().use { tokenResponse ->
                cookies.absorb(tokenResponse)
                val tokenBodyStr = tokenResponse.body?.string()
                    ?: throw Exception("Empty response exchanging juno token")
                if (tokenResponse.code != 200) {
                    throw Exception("Juno token 交换失败: HTTP ${tokenResponse.code}: $tokenBodyStr")
                }
                return JSONObject(tokenBodyStr).optString("access_token", "").ifEmpty {
                    throw Exception("Juno token 响应没有 access_token: $tokenBodyStr")
                }
            }
        }
    }

    /**
     * EA 原生查 PID：remid/sid → access_token → gateway personas（按 displayName）。
     *
     * 脚本 eaid_to_pid.py 的 ORIGIN 路径；ORIGIN token 缺 dp.server.default scope
     * （403 insufficient_scope，实测恒定触发）时自动换 Juno（EA app）token 重试一次，
     * 仍失败则抛异常，由上层兜底 gametools。
     */
    fun resolvePlayerNameByEAID(remid: String, sid: String, eaid: String): EaPidResult {
        val cookies = CookieCollector()
        val cookieHeader = "remid=$remid; sid=$sid"
        val accessToken = getAccessToken(cookieHeader, cookies)
        return try {
            queryPersonas(accessToken, eaid, cookies)
        } catch (e: InsufficientScopeException) {
            // ORIGIN token 缺 dp.server.default scope（实测恒定触发）：换 Juno token 重试一次
            try {
                val junoToken = getAccessTokenJuno(remid, sid, cookies)
                return queryPersonas(junoToken, eaid, cookies)
            } catch (e2: Exception) {
                // Juno 重试失败：不归因 ORIGIN（e2 才是真实异常，可能恰是 Juno token 也缺
                // scope），且把本轮累积的轮换 cookie 带出去，避免 403 恒定场景下每次失败丢一次轮换
                throw EaPidQueryException(
                    "EA 原生查询失败（ORIGIN 403 后 Juno 重试也失败）: ${e2.message}",
                    e2,
                    cookies.rotated
                )
            }
        }
    }

    /**
     * gateway personas 查询（按 displayName）。403 insufficient_scope（ORIGIN token
     * 缺 dp.server.default scope）时抛 [InsufficientScopeException]，由
     * [resolvePlayerNameByEAID] 换 Juno token 重试一次。
     */
    private fun queryPersonas(accessToken: String, eaid: String, cookies: CookieCollector): EaPidResult {
        val url = "https://gateway.ea.com/proxy/identity/personas".toHttpUrl()
            .newBuilder()
            .addQueryParameter("namespaceName", "cem_ea_id")
            .addQueryParameter("displayName", eaid)
            .build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Expand-Results", "true")
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { response ->
            cookies.absorb(response)
            val body = response.body?.string()
                ?: throw Exception("Empty response getting personas")
            if (!response.isSuccessful) {
                if (isInsufficientScope(response.code, body)) {
                    throw InsufficientScopeException("ORIGIN token 缺少 scope (HTTP ${response.code})")
                }
                throw Exception("personas 请求失败: HTTP ${response.code}: $body")
            }
            return EaPidResult(parsePersonaId(JSONObject(body)), cookies.rotated)
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

    private fun getAuthCode(
        accessToken: String,
        cookieHeader: String,
        cookies: CookieCollector
    ): String {
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
            cookies.absorb(response)

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

    /**
     * GameServer.getFullServerDetails：按 14 位 gameId 查询服务器完整信息。
     * 响应同时含 gameId（serverInfo）与 serverId（rspInfo.server），
     * 是添加服务器时同时拿到两个 ID 的唯一可靠途径（RSP.getServerDetails 只返回 serverId）。
     */
    fun getFullServerDetails(sessionId: String, gameId: String): RspInfo {
        val body = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", "GameServer.getFullServerDetails")
            put("params", JSONObject().apply {
                put("game", "tunguska")
                put("gameId", gameId)
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
                ?: throw Exception("Empty response getting full server details")
            val json = JSONObject(respBody)
            if (json.has("error")) {
                val err = json.getJSONObject("error")
                throw GatewayError(err.optInt("code"), err.optString("message"), "GameServer.getFullServerDetails")
            }
            val result = json.optJSONObject("result")
                ?: throw Exception("No result: $respBody")
            return parseFullServerDetails(result)
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

    /** gametools 兜底查 PID：EA 原生查询（[resolvePlayerNameByEAID]）失败时使用。 */
    fun resolvePlayerNameGametools(playerName: String): String {
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

/** EA gateway personas 请求是否因 ORIGIN token scope 不足而 403。 */
internal fun isInsufficientScope(code: Int, body: String): Boolean =
    code == 403 && body.contains("insufficient_scope")
