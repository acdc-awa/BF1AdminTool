package com.bf1.admin.tool.ui.login

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.remote.EAApiService
import com.bf1.admin.tool.util.CookieHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BF1Debug"

class LoginViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BF1AdminApp
    private val accountRepo = app.accountRepository
    private val credentialManager = app.credentialManager

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private val _loginSuccess = MutableSharedFlow<Unit>()
    val loginSuccess: SharedFlow<Unit> = _loginSuccess.asSharedFlow()

    // ── Juno WebView 登录参数 ──
    /** 由 WebViewLoginScreen 在 Composition 时调用一次，生成 PKCE 授权 URL。 */
    val junoAuthParams: EAApiService.JunoAuthParams = credentialManager.buildJunoAuthUrl()

    /**
     * WebView Juno 登录完成后调用：
     * ① exchangeJunoCode → access_token + refresh_token
     * ② authenticate → persona + sessionId
     * ③ 建档 + recordSession
     * ④ saveJunoRefreshToken
     */
    fun onJunoWebViewLogin(code: String, rawCookies: String) {
        Log.d(TAG, "[LoginVM] ▶ onJunoWebViewLogin code=${code.take(20)}... rawCookies=${rawCookies.take(60)}...")
        val cookiePair = CookieHelper.parseWebViewCookies(rawCookies)
        if (cookiePair == null) {
            Log.w(TAG, "[LoginVM] ✘ onJunoWebViewLogin — no remid/sid in rawCookies")
            viewModelScope.launch { _message.emit("未检测到 remid 或 sid cookie") }
            return
        }
        val (remid, sid) = cookiePair
        Log.d(TAG, "[LoginVM] → onJunoWebViewLogin remid=${remid.take(8)}... sid=${sid.take(8)}...")

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Step 1: Juno code 换 access_token + refresh_token
                Log.d(TAG, "[LoginVM] → Step1: exchangeJunoCode...")
                val tokenResult = withContext(Dispatchers.IO) {
                    credentialManager.exchangeJunoCode(code, junoAuthParams.codeVerifier)
                }
                Log.d(TAG, "[LoginVM] ✔ Step1 OK — accessToken=${tokenResult.accessToken.take(16)}...")

                // Step 2: 用 Juno access_token + cookie 认证
                Log.d(TAG, "[LoginVM] → Step2: authenticate...")
                val session = withContext(Dispatchers.IO) {
                    credentialManager.authenticate(
                        tokenResult.accessToken, remid, sid
                    ).getOrThrow()
                }
                val effectiveRemid = session.rotated.remid ?: remid
                val effectiveSid = session.rotated.sid ?: sid
                Log.d(TAG, "[LoginVM] ✔ Step2 OK — persona=${session.persona.displayName} pid=${session.persona.personaId}")

                // Step 3: 建档 + 保存
                val accountId = accountRepo.addOrUpdateAccount(
                    name = session.persona.displayName,
                    personaId = session.persona.personaId,
                    remid = effectiveRemid,
                    sid = effectiveSid
                )
                accountRepo.switchActive(accountId)
                credentialManager.recordSession(accountId, effectiveRemid, session.sessionId)

                // Step 4: 播种 refresh_token
                Log.d(TAG, "[LoginVM] → Step4: saveJunoRefreshToken...")
                withContext(Dispatchers.IO) {
                    credentialManager.saveJunoRefreshToken(accountId, tokenResult.refreshToken)
                }
                Log.d(TAG, "[LoginVM] ✔ Step4 OK — refresh_token saved")

                _message.emit("登录成功: ${session.persona.displayName}")
                _loginSuccess.emit(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "[LoginVM] ✘ onJunoWebViewLogin FAILED: ${e.message}", e)
                _message.emit("登录失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
