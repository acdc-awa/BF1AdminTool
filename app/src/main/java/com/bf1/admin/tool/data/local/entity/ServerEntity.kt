package com.bf1.admin.tool.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: String,
    val serverName: String,
    val ownerPersonaId: String,
    val isActive: Boolean = false,
    /** 14 位服务器 GUID（卡服功能进服/查服用）；8 位 serverId 无法直接推导，可空。 */
    val gameId: String? = null
)
