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
    /** 14 位服务器 GameID（卡服功能进服/查服用，会随重启变化，可空）。 */
    val gameId: String? = null,
    /** 服务器永久 GUID（persistedGameId，UUID 格式，权威不变标识；旧数据首次刷新自动回填）。 */
    val guid: String? = null
)
