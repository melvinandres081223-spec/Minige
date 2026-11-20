package com.melvin.minige.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DeliveryEntity::class,
        UtangPaymentEntity::class,
        ExpenseEntity::class,
        DailyLedgerEntity::class,
        AppSettingsEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MinigeDatabase : RoomDatabase() {
    abstract fun minigeDao(): MinigeDao

    companion object {
        @Volatile
        private var instance: MinigeDatabase? = null

        fun getInstance(context: Context): MinigeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MinigeDatabase::class.java,
                    "minige_database",
                ).build().also { instance = it }
            }
        }
    }
}
