package com.bf1.admin.tool.data.local.entity

data class EncryptedAccount(
    val id: Long,
    val name: String,
    val personaId: String,
    val remid: String,
    val sid: String,
    val isActive: Boolean
)
