package com.bf1.admin.tool.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bf1.admin.tool.data.local.entity.SessionCacheEntity

@Dao
interface SessionCacheDao {
    @Query("SELECT * FROM session_cache WHERE accountId = :accountId LIMIT 1")
    suspend fun getByAccountId(accountId: Long): SessionCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cache: SessionCacheEntity)

    @Query("DELETE FROM session_cache WHERE accountId = :accountId")
    suspend fun deleteByAccountId(accountId: Long)
}
