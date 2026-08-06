package com.bf1.admin.tool.data.local.entity

data class EncryptedAccount(
    val id: Long,
    val name: String,
    val personaId: String,
    val remid: String,
    val sid: String,
    /** Juno OAuth refresh_token（解密后）；null 表示未播种。 */
    val junoRefreshToken: String? = null,
    val isActive: Boolean
)
