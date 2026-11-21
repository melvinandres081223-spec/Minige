package com.melvin.minige.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DeliveryEntity::class,
        UtangPaymentEntity::class,
        ExpenseEntity::class,
        DailyLedgerEntity::class,
        AppSettingsEntity::class,
        CustomerEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MinigeDatabase : RoomDatabase() {
    abstract fun minigeDao(): MinigeDao

    companion object {
        @Volatile
        private var instance: MinigeDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `customers` (
                        `id` TEXT NOT NULL,
                        `userId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `lastUnitPrice` REAL,
                        `pendingSync` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context): MinigeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MinigeDatabase::class.java,
                    "minige_database",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
