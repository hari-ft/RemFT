package com.ftapps.remft.di

import com.ftapps.remft.data.alarm.BackgroundTurn
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.onedrive.AutoBackupTurn
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

class AutoBackupBackgroundTurn(private val backup: AutoBackupTurn) : BackgroundTurn {
    override val name: String = "auto_backup"

    override suspend fun run() {
        backup.run(force = false)
    }
}

class MirrorDownloadsBackgroundTurn(private val downloads: MirrorDownloads) : BackgroundTurn {
    override val name: String = "mirror_downloads"

    override suspend fun run() {
        downloads.startIfPending()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object OneDriveTurnModule {
    @Provides
    @IntoSet
    fun autoBackupTurn(backup: AutoBackupTurn): BackgroundTurn = AutoBackupBackgroundTurn(backup)

    @Provides
    @IntoSet
    fun mirrorDownloadsTurn(downloads: MirrorDownloads): BackgroundTurn = MirrorDownloadsBackgroundTurn(downloads)
}
