package com.ftapps.remft.di

import com.ftapps.remft.data.alarm.BackgroundTurn
import com.ftapps.remft.data.repeat.SeriesRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

class SeriesRollTurn(private val series: SeriesRepository) : BackgroundTurn {
    override val name: String = "series_roll"
    override val beforeAlarms: Boolean = true

    override suspend fun run() {
        series.rollAll()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object RepeatTurnModule {
    @Provides
    @IntoSet
    fun seriesRollTurn(series: SeriesRepository): BackgroundTurn = SeriesRollTurn(series)
}
