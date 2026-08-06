package com.bf1.admin.tool.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
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

    fun loginWithCookies(remid: String, sid: String) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val session = withContext(Dispatchers.IO) {
                    credentialManager.authenticate(remid, sid).getOrThrow()
                }
                // 认证过程中 EA 可能已轮换 cookie，账号从建档起就存最新值。
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

    fun loginWithCookiesFromWebView(rawCookies: String) {
        val result = CookieHelper.parseWebViewCookies(rawCookies)
        if (result == null) {
            viewModelScope.launch { _message.emit("未检测到 remid 或 sid cookie") }
            return
        }
        loginWithCookies(result.first, result.second)
    }
}
