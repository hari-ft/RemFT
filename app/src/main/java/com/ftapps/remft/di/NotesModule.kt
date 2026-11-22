package com.ftapps.remft.di

import android.content.Context
import com.ftapps.remft.R
import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.identity.LocalIdentityRepository
import com.ftapps.remft.data.notes.ExternalDocumentStore
import com.ftapps.remft.data.notes.NotesRepository
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.ui.notes.export.NoteExporter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotesModule {
    @Provides
    @Singleton
    fun notesRepository(
        @ApplicationContext context: Context,
        holder: DatabaseHolder,
        identity: LocalIdentityRepository,
        settings: SettingsStore,
    ): NotesRepository = NotesRepository(
        holder = holder,
        workspaceId = { identity.resolve().workspaceId },
        settings = settings,
        attachmentsDir = File(context.filesDir, "files"),
        untitled = { context.getString(R.string.note_untitled) },
    )

    @Provides
    @Singleton
    fun noteExporter(@ApplicationContext context: Context, repository: NotesRepository): NoteExporter = NoteExporter(context, repository)

    @Provides
    @Singleton
    fun externalDocumentStore(@ApplicationContext context: Context, settings: SettingsStore): ExternalDocumentStore =
        ExternalDocumentStore(context, settings)
}
