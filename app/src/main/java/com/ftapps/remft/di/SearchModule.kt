package com.ftapps.remft.di

import com.ftapps.remft.data.db.DatabaseHolder
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.data.search.SearchDao
import com.ftapps.remft.data.search.SearchRecentsStore
import com.ftapps.remft.data.search.SearchRepository
import com.ftapps.remft.data.tasks.TasksRepository
import com.ftapps.remft.ui.search.SearchEnvironment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SearchModule {
    @Provides
    @Singleton
    fun searchDao(holder: DatabaseHolder): SearchDao = SearchDao { holder.db }

    @Provides
    @Singleton
    fun searchRepository(dao: SearchDao, tasks: TasksRepository): SearchRepository =
        SearchRepository(dao, tasks::workspaceId)

    @Provides
    @Singleton
    fun searchEnvironment(repository: SearchRepository, settings: SettingsStore): SearchEnvironment =
        SearchEnvironment(repository, SearchRecentsStore(settings))
}
