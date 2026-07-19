package com.bf1.admin.tool.data.local

import androidx.room.*
import com.bf1.admin.tool.data.local.entity.AccountEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY isActive DESC, name ASC")
    fun getAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): AccountEntity?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE personaId = :personaId LIMIT 1")
    suspend fun getByPersonaId(personaId: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)

    @Query("UPDATE accounts SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE accounts SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Transaction
    suspend fun switchActive(id: Long) {
        deactivateAll()
        activate(id)
    }
}
