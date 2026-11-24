package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.data.backup.BackupPaths
import com.ftapps.remft.data.backup.BackupService
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.mirror.FileMirror
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.mirror.SupportMirrorSql
import com.ftapps.remft.data.onedrive.AppAuthSignIn
import com.ftapps.remft.data.onedrive.AutoBackupBuilder
import com.ftapps.remft.data.onedrive.AutoBackupRunner
import com.ftapps.remft.data.onedrive.AutoBackupTurn
import com.ftapps.remft.data.onedrive.BackupWake
import com.ftapps.remft.data.onedrive.CloudBackups
import com.ftapps.remft.data.onedrive.GraphClient
import com.ftapps.remft.data.onedrive.KeystoreTokenCipher
import com.ftapps.remft.data.onedrive.MirrorSyncNow
import com.ftapps.remft.data.onedrive.OneDriveAuth
import com.ftapps.remft.data.onedrive.OneDriveBackups
import com.ftapps.remft.data.onedrive.OneDriveRestoredFiles
import com.ftapps.remft.data.onedrive.OneDriveSignIn
import com.ftapps.remft.data.onedrive.OneDriveTokenStore
import com.ftapps.remft.data.onedrive.OneDriveTriggers
import com.ftapps.remft.data.onedrive.PrefsTokenSlot
import com.ftapps.remft.data.onedrive.WorkManagerWakeBooker
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.backup.lock.WorkLockNames
import com.ftapps.remft.domain.backup.lock.WorkLocks
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OneDriveModule {
    private val TIMEOUT: Duration = Duration.ofMinutes(2)

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(60))
        .readTimeout(TIMEOUT)
        .writeTimeout(TIMEOUT)
        .callTimeout(Duration.ofMinutes(10))
        .build()

    @Provides
    @Singleton
    fun tokenStore(@ApplicationContext context: Context): OneDriveTokenStore =
        OneDriveTokenStore(PrefsTokenSlot(context), KeystoreTokenCipher())

    @Provides
    @Singleton
    fun auth(tokens: OneDriveTokenStore, store: SettingsStore, http: OkHttpClient): OneDriveAuth =
        OneDriveAuth(tokens, store, http)

    @Provides
    @Singleton
    fun graph(auth: OneDriveAuth, http: OkHttpClient): GraphClient = GraphClient(auth, http)

    @Provides
    @Singleton
    fun signIn(@ApplicationContext context: Context): OneDriveSignIn = AppAuthSignIn(context)

    @Provides
    @Singleton
    fun fileMirror(holder: DatabaseHolder, graph: GraphClient, paths: BackupPaths, store: SettingsStore, locks: WorkLocks): FileMirror =
        FileMirror(
            SupportMirrorSql(changed = { holder.db.invalidationTracker.refreshAsync() }) { holder.db.openHelper.writableDatabase },
            graph,
            paths.filesDir,
            store,
            stopRequested = { locks.isHeld(WorkLockNames.RESTORE) },
        )

    @Provides
    @Singleton
    fun mirrorDownloads(mirror: FileMirror, auth: OneDriveAuth, store: SettingsStore, locks: WorkLocks): MirrorDownloads =
        MirrorDownloads(mirror, auth, store, { locks }, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    @Provides
    @Singleton
    fun backupWake(@ApplicationContext context: Context, store: SettingsStore, holder: DatabaseHolder): BackupWake =
        BackupWake(store, WorkManagerWakeBooker(context), restoring = { holder.isRestoring })

    @Provides
    @Singleton
    fun autoBackupTurn(
        store: SettingsStore,
        auth: OneDriveAuth,
        graph: GraphClient,
        mirror: FileMirror,
        backup: BackupService,
        paths: BackupPaths,
        wake: BackupWake,
        locks: WorkLocks,
        holder: DatabaseHolder,
    ): AutoBackupTurn = AutoBackupTurn(
        store = store,
        auth = auth,
        mirror = mirror,
        runner = { deadline ->
            AutoBackupRunner(
                store = store,
                destination = graph,
                builder = AutoBackupBuilder { out, now, listOnly -> backup.buildTo(out, now, listOnly) },
                scratch = AutoBackupTurn.scratch(paths.cacheDir),
                mirror = mirror,
                dataSize = { backup.dataSize() },
                onSuccess = { backup.recordShared(it) },
                catchUpDeadline = deadline,
                stopRequested = { locks.isHeld(WorkLockNames.RESTORE) },
            )
        },
        wake = wake,
        locks = { locks },
        restoring = { holder.isRestoring },
    )

    @Provides
    @Singleton
    fun cloudBackups(graph: GraphClient, auth: OneDriveAuth, store: SettingsStore): CloudBackups =
        OneDriveBackups(graph, auth, store)

    @Provides
    @Singleton
    fun restoredFiles(graph: GraphClient, auth: OneDriveAuth, store: SettingsStore, paths: BackupPaths): OneDriveRestoredFiles =
        OneDriveRestoredFiles(graph, auth, store, paths.filesDir)

    @Provides
    @Singleton
    fun triggers(
        store: SettingsStore,
        auth: OneDriveAuth,
        turn: AutoBackupTurn,
        downloads: MirrorDownloads,
        holder: DatabaseHolder,
        locks: WorkLocks,
        paths: BackupPaths,
    ): OneDriveTriggers = OneDriveTriggers(store, auth, turn, downloads, holder, { locks }, paths.cacheDir)

    @Provides
    @Singleton
    fun syncNow(
        store: SettingsStore,
        auth: OneDriveAuth,
        turn: AutoBackupTurn,
        mirror: FileMirror,
        downloads: MirrorDownloads,
        triggers: OneDriveTriggers,
    ): MirrorSyncNow = MirrorSyncNow(
        store = store,
        auth = auth,
        turn = turn,
        mirror = mirror,
        downloads = downloads,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        continueLater = { triggers.mirrorSoon() },
    )
}
