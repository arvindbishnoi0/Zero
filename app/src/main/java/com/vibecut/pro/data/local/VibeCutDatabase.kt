package com.vibecut.pro.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        ProjectRevisionEntity::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class VibeCutDatabase : RoomDatabase() {
    abstract fun vibeCutDao(): VibeCutDao
}
