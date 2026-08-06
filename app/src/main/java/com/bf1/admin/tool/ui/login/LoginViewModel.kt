package com.bf1.admin.tool.ui.login

import android.app.Application
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

    fun loginWithCookies(remid: String, sid: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(Dispatchers.IO) {
                    credentialManager.authenticate(remid, sid).getOrThrow()
                }
                val effectiveRemid = session.rotated.remid ?: remid
                val effectiveSid = session.rotated.sid ?: sid
                val accountId = accountRepo.addOrUpdateAccount(
                    name = session.persona.displayName,
                    personaId = session.persona.personaId,
                    remid = effectiveRemid,
                    sid = effectiveSid
                )
                accountRepo.switchActive(accountId)
                credentialManager.recordSession(accountId, effectiveRemid, session.sessionId)
                _message.emit("登录成功: ${session.persona.displayName}")
                _loginSuccess.emit(Unit)
            } catch (e: Exception) {
                _message.emit("登录失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * WebView Juno 登录完成后调用：同时处理 Juno code 交换（播种 refresh_token）
     * 和 remid/sid 提取。
     */
    fun onJunoWebViewLogin(code: String, rawCookies: String) {
        val cookiePair = CookieHelper.parseWebViewCookies(rawCookies)
        if (cookiePair == null) {
            viewModelScope.launch { _message.emit("未检测到 remid 或 sid cookie") }
            return
        }
        val (remid, sid) = cookiePair

        viewModelScope.launch {
            _isLoading.value = true
            try {
                // 先用 ORIGIN cookie 认证拿到 persona + session
                val session = withContext(Dispatchers.IO) {
                    credentialManager.authenticate(remid, sid).getOrThrow()
                }
                val effectiveRemid = session.rotated.remid ?: remid
                val effectiveSid = session.rotated.sid ?: sid
                val accountId = accountRepo.addOrUpdateAccount(
                    name = session.persona.displayName,
                    personaId = session.persona.personaId,
                    remid = effectiveRemid,
                    sid = effectiveSid
                )
                accountRepo.switchActive(accountId)
                credentialManager.recordSession(accountId, effectiveRemid, session.sessionId)

                // 同时播种 Juno refresh_token（后续 PID 查询静默续期用）
                try {
                    withContext(Dispatchers.IO) {
                        credentialManager.onJunoLoginComplete(
                            accountId, code, junoAuthParams.codeVerifier
                        )
                    }
                } catch (e: Exception) {
                    // refresh_token 播种失败不阻塞登录（PID 查询退化为 gametools）
                    _message.emit("登录成功: ${session.persona.displayName}（refresh_token 播种失败: ${e.message}）")
                    _loginSuccess.emit(Unit)
                    return@launch
                }

                _message.emit("登录成功: ${session.persona.displayName}")
                _loginSuccess.emit(Unit)
            } catch (e: Exception) {
                _message.emit("登录失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loginWithCookiesFromWebView(rawCookies: String) {
        val result = CookieHelper.parseWebViewCookies(rawCookies)
        if (result == null) {
            viewModelScope.launch { _message.emit("未检测到 remid 或 sid cookie") }
            return
        }
        loginWithCookies(result.first, result.second)
    }
}
