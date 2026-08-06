package com.bf1.admin.tool.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val personaId: String,
    val remid: String,
    val sid: String,
    /** Juno OAuth refresh_token（加密存储）；有值时 [CredentialManager] 可静默换 access_token 查 PID。 */
    val junoRefreshToken: String? = null,
    val isActive: Boolean = false
)
