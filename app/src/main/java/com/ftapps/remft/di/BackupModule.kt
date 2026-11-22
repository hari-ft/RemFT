package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.data.backup.AndroidBackupLog
import com.ftapps.remft.data.backup.BackupPaths
import com.ftapps.remft.data.backup.BackupService
import com.ftapps.remft.data.backup.StartupRestoreRecovery
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.onedrive.OneDriveRestoredFiles
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.lock.WorkLocks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object BackupModule {
    @Provides
    @Singleton
    fun backupPaths(@ApplicationContext context: Context, holder: DatabaseHolder): BackupPaths = BackupPaths(
        dbFile = holder.file,
        filesDir = File(context.filesDir, BackupPaths.FILES_FOLDER),
        cacheDir = context.cacheDir,
        locksDir = File(context.filesDir, BackupPaths.LOCKS_FOLDER),
    )

    @Provides
    @Singleton
    fun workLocks(paths: BackupPaths): WorkLocks =
        WorkLocks(paths.locksDir, android.os.Process.myPid().toLong(), log = AndroidBackupLog)

    @Provides
    @Singleton
    fun backupService(
        paths: BackupPaths,
        holder: DatabaseHolder,
        store: SettingsStore,
        locks: WorkLocks,
        mirror: Provider<FileMirror>,
        restoredFiles: Provider<OneDriveRestoredFiles>,
    ): BackupService = BackupService(
        paths = paths,
        holder = holder,
        store = store,
        locks = { locks },
        appVersion = BuildConfig.VERSION_NAME,
        missingLocalRefs = { mirror.get().refsForMissingLocal() },
        restoredFiles = { dbFile, onProgress -> restoredFiles.get().fetch(dbFile, onProgress) },
    )

    @Provides
    @Singleton
    fun startupRestoreRecovery(paths: BackupPaths, store: SettingsStore): StartupRestoreRecovery =
        StartupRestoreRecovery(paths, store)
}
