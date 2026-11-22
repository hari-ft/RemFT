package com.ftapps.remft.di

import com.ftapps.remft.data.alarm.BackgroundTurn
import com.ftapps.remft.data.files.FilesMaintenance
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

class FilesMaintenanceTurn(private val maintenance: FilesMaintenance) : BackgroundTurn {
    override val name: String = "files_maintenance"

    override suspend fun run() {
        maintenance.purgeExpired()
        maintenance.sweepOrphans()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object FilesTurnModule {
    @Provides
    @IntoSet
    fun filesMaintenanceTurn(maintenance: FilesMaintenance): BackgroundTurn = FilesMaintenanceTurn(maintenance)
}
