package com.ftapps.remft.di

import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.repeat.SeriesRepository
import com.ftapps.remft.data.tasks.LiveZoneClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepeatModule {
    @Provides
    @Singleton
    fun seriesRepository(holder: DatabaseHolder): SeriesRepository = SeriesRepository(database = { holder.db }, clock = LiveZoneClock)
}
