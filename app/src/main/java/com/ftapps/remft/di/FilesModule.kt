package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.BuildConfig
import com.ftapps.remft.data.bin.RecycleBin
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.files.AttachmentStore
import com.ftapps.remft.data.files.FilesMaintenance
import com.ftapps.remft.data.files.FilesRepository
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.mirror.MirrorDownloads
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.ui.files.FilesEnvironment
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
object FilesModule {
    private fun attachmentsDir(context: Context): File = File(context.filesDir, "files")

    @Provides
    @Singleton
    fun recycleBin(
        @ApplicationContext context: Context,
        holder: DatabaseHolder,
        identity: LocalIdentityRepository,
        settings: SettingsStore,
    ): RecycleBin = RecycleBin(
        database = { holder.db },
        settings = settings,
        attachmentsDir = attachmentsDir(context),
        workspaceId = { identity.resolve().workspaceId },
    )

    @Provides
    @Singleton
    fun attachmentStore(
        @ApplicationContext context: Context,
        holder: DatabaseHolder,
        bin: RecycleBin,
        downloads: Provider<MirrorDownloads>,
    ): AttachmentStore = AttachmentStore(
        database = { holder.db },
        directory = attachmentsDir(context),
        bin = bin,
        fetchMissing = { id -> downloads.get().fetchNow(id) },
        canFetch = { id -> downloads.get().isMissingButFetchable(id) },
    )

    @Provides
    @Singleton
    fun filesRepository(
        holder: DatabaseHolder,
        identity: LocalIdentityRepository,
        bin: RecycleBin,
        attachments: AttachmentStore,
    ): FilesRepository = FilesRepository(
        database = { holder.db },
        workspaceId = { identity.resolve().workspaceId },
        bin = bin,
        attachments = attachments,
    )

    @Provides
    @Singleton
    fun filesMaintenance(bin: RecycleBin, attachments: AttachmentStore): FilesMaintenance = FilesMaintenance(bin, attachments)

    @Provides
    @Singleton
    fun filesEnvironment(
        attachments: AttachmentStore,
        files: FilesRepository,
        bin: RecycleBin,
        settings: SettingsStore,
    ): FilesEnvironment = FilesEnvironment(
        attachments = attachments,
        files = files,
        bin = bin,
        settings = settings,
        authority = BuildConfig.FILES_AUTHORITY,
    )
}
