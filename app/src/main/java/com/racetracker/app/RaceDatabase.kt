package com.racetracker.app

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room database for Race data
 */
@Database(entities = [RaceData::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class RaceDatabase : RoomDatabase() {
    
    abstract fun raceDao(): RaceDao
    
    companion object {
        @Volatile
        private var INSTANCE: RaceDatabase? = null
        
        fun getDatabase(context: Context): RaceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    RaceDatabase::class.java,
                    "race_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
