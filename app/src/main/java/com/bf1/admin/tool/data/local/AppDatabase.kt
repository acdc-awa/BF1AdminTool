package com.bf1.admin.tool.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.bf1.admin.tool.data.local.entity.AccountEntity
import com.bf1.admin.tool.data.local.entity.SessionCacheEntity
import com.bf1.admin.tool.data.local.entity.ServerEntity

@Database(
    entities = [AccountEntity::class, ServerEntity::class, SessionCacheEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun serverDao(): ServerDao
    abstract fun sessionCacheDao(): SessionCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bf1_admin.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `session_cache` (" +
                        "`accountId` INTEGER NOT NULL, " +
                        "`encryptedSessionId` TEXT NOT NULL, " +
                        "`remidFingerprint` TEXT NOT NULL, " +
                        "`refreshedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`accountId`), " +
                        "FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
            }
        }
    }
}
