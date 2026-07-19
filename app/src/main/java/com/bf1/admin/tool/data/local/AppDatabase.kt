package com.bf1.admin.tool.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity

@Database(entities = [AccountEntity::class, ServerEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun serverDao(): ServerDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bf1_admin.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
