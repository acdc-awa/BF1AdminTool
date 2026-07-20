package com.bf1.admin.tool.ui.login

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.repository.AccountRepository
import com.bf1.admin.tool.data.repository.AdminRepository
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
    private val db = (application as BF1AdminApp).database
    private val accountRepo = AccountRepository(db.accountDao(), application)
    private val adminRepo = AdminRepository(accountRepo)

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
                    adminRepo.authenticate(remid, sid).getOrThrow()
                }
                val accountId = accountRepo.addOrUpdateAccount(
                    name = session.persona.displayName,
                    personaId = session.persona.personaId,
                    remid = remid,
                    sid = sid
                )
                accountRepo.switchActive(accountId)
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
