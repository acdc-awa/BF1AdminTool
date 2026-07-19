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
    val isActive: Boolean = false
)
