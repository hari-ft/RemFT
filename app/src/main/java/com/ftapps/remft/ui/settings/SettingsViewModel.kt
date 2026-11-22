package com.ftapps.remft.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftapps.remft.data.prefs.SettingsStore
import com.ftapps.remft.domain.settings.DateFormatId
import com.ftapps.remft.domain.settings.NoteSourceStyling
import com.ftapps.remft.domain.settings.SettingKeys
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val store: SettingsStore) : ViewModel() {
    val dateFormat: StateFlow<DateFormatId> = store.flow(SettingKeys.DateFormat)
        .map { DateFormatId.parse(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DateFormatId.System)

    val noteSourceStyling: StateFlow<NoteSourceStyling> = store.flow(SettingKeys.NoteSourceStyling)
        .map { NoteSourceStyling.parse(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NoteSourceStyling.MarkersOnly)

    val dynamicColor: StateFlow<Boolean> = store.flow(SettingKeys.DynamicColor)
        .map { it == "true" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setDynamicColor(on: Boolean) {
        viewModelScope.launch { store.set(SettingKeys.DynamicColor.name, on.toString()) }
    }

    fun setDateFormat(id: DateFormatId) {
        viewModelScope.launch { store.set(SettingKeys.DateFormat.name, id.id) }
    }

    fun setNoteSourceStyling(styling: NoteSourceStyling) {
        viewModelScope.launch { store.set(SettingKeys.NoteSourceStyling.name, styling.id) }
    }
}
