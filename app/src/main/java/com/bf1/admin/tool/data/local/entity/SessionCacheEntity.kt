package com.bf1.admin.tool.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_cache",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SessionCacheEntity(
    @PrimaryKey val accountId: Long,
    val encryptedSessionId: String,
    val remidFingerprint: String,
    val refreshedAt: Long
)
