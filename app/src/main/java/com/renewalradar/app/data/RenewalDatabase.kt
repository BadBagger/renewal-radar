package com.renewalradar.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RenewalItem::class], version = 1, exportSchema = false)
@TypeConverters(DateConverters::class)
abstract class RenewalDatabase : RoomDatabase() {
    abstract fun renewalDao(): RenewalDao

    companion object {
        @Volatile
        private var instance: RenewalDatabase? = null

        fun get(context: Context): RenewalDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RenewalDatabase::class.java,
                    "renewal-radar.db"
                ).build().also { instance = it }
            }
    }
}
