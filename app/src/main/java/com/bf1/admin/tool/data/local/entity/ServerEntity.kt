package com.bf1.admin.tool.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val serverName: String,
    val ownerPersonaId: String,
    val isActive: Boolean = false
)
