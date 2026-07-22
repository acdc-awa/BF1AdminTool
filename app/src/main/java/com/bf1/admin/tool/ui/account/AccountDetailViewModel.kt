package com.bf1.admin.tool.ui.account

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bf1.admin.tool.BF1AdminApp
import com.bf1.admin.tool.data.local.entity.EncryptedAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AccountDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BF1AdminApp
    private val accountRepo = app.accountRepository
    private val adminRepo = app.adminRepository
    private val sessionManager = app.sessionManager

    private val _account = MutableStateFlow<EncryptedAccount?>(null)
    val account: StateFlow<EncryptedAccount?> = _account.asStateFlow()

    private val _remid = MutableStateFlow("")
    val remid: StateFlow<String> = _remid.asStateFlow()

    private val _sid = MutableStateFlow("")
    val sid: StateFlow<String> = _sid.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _message = MutableSharedFlow<String>()
    val message: SharedFlow<String> = _message.asSharedFlow()

    private var accountId: Long = -1

    fun loadAccount(id: Long) {
        accountId = id
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val enc = accountRepo.getDecryptedById(id)
                _account.value = enc
                if (enc != null) {
                    _remid.value = enc.remid
                    _sid.value = enc.sid
                }
            } catch (e: Exception) {
                _message.emit("加载账号失败: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateRemid(value: String) { _remid.value = value }
    fun updateSid(value: String) { _sid.value = value }

    fun saveAndVerify() {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                accountRepo.updateCredentials(accountId, _remid.value, _sid.value)

                val session = withContext(Dispatchers.IO) {
                    adminRepo.authenticate(_remid.value, _sid.value).getOrThrow()
                }
                sessionManager.recordSession(accountId, _remid.value, session.sessionId)
                _message.emit("保存成功，验证通过")
            } catch (e: Exception) {
                _message.emit("验证失败，已保存但凭证可能已失效: ${e.message}")
            } finally {
                _isSaving.value = false
            }
        }
    }
}
