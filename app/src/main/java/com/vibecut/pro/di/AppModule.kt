package com.vibecut.pro.di

import android.content.Context
import androidx.room.Room
import com.vibecut.pro.data.local.VibeCutDao
import com.vibecut.pro.data.local.VibeCutDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VibeCutDatabase {
        return Room.databaseBuilder(
            context,
            VibeCutDatabase::class.java,
            "vibecut_pro_studio.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun provideVibeCutDao(db: VibeCutDatabase): VibeCutDao = db.vibeCutDao()
}
