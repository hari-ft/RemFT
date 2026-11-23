package com.ftapps.remft.domain.settings

data class SettingKey(val name: String, val default: String?, val inBackup: Boolean)

object SettingKeys {
    val AlarmSound = SettingKey("remft_alarm_sound", "bundled:alarm", true)
    val ReminderSound = SettingKey("remft_reminder_sound", "os", true)
    val ReminderProfile = SettingKey("remft_reminder_profile", null, true)
    val SnoozePresets = SettingKey("remft_snooze_presets", "5_min,30_min,1_hour,tomorrow_morning", true)
    val NotificationPrivacy = SettingKey("notification_privacy", "false", true)
    val DateFormat = SettingKey("remft_date_format", "system", true)
    val NoteSourceStyling = SettingKey("remft_note_source_styling", "markersOnly", true)
    val BinEnabled = SettingKey("remft_bin_enabled", "true", true)
    val BinAutoDelete = SettingKey("remft_bin_auto_delete", "true", true)
    val BinDays = SettingKey("remft_bin_days", "30", true)
    val AutoBackupSchedule = SettingKey("remft_auto_backup_schedule", null, true)
    val AutoBackupKeepDays = SettingKey("remft_auto_backup_keep_days", "7", true)
    val HomeCalendarVisible = SettingKey("remft_home_calendar_visible", "true", true)
    val TasksSort = SettingKey("remft_tasks_sort", "date:asc", true)
    val InboxSort = SettingKey("remft_inbox_sort", "added:desc", true)
    val NotesSort = SettingKey("remft_notes_sort", "updated:desc", true)
    val FilesSort = SettingKey("remft_files_sort", "date:desc", true)
    val NotesViewMode = SettingKey("remft_notes_view_mode", "list", true)
    val OnboardingSeen = SettingKey("remft_onboarding_seen_v1", null, true)

    val LocalWorkspaceId = SettingKey("remft_local_workspace_id", null, false)
    val LastBackupAt = SettingKey("remft_last_backup_at", null, false)
    val BackupReminderDismissed = SettingKey("remft_backup_reminder_dismissed", null, false)
    val OneDriveOn = SettingKey("remft_auto_backup_onedrive_on", null, false)
    val OneDriveAccount = SettingKey("remft_auto_backup_onedrive_account", null, false)
    val OneDriveReconnect = SettingKey("remft_auto_backup_onedrive_reconnect", null, false)
    val AutoBackupLastOk = SettingKey("remft_auto_backup_last_ok", null, false)
    val AutoBackupLastSize = SettingKey("remft_auto_backup_last_size", null, false)
    val AutoBackupPaused = SettingKey("remft_auto_backup_paused", null, false)
    val AutoBackupDropAccepted = SettingKey("remft_auto_backup_drop_accepted", null, false)
    val AutoBackupUnavailable = SettingKey("remft_auto_backup_unavailable", null, false)
    val AutoBackupStatusOneDrive = SettingKey("remft_auto_backup_status_onedrive", null, false)
    val AutoBackupWakeSlots = SettingKey("remft_auto_backup_wake_slots", null, false)
    val MirrorDownloadsPending = SettingKey("remft_mirror_downloads_pending", null, false)
    val MirrorReviewSweptAt = SettingKey("remft_mirror_review_swept_at", null, false)
    val RestorePendingSettings = SettingKey("remft_restore_pending_settings", null, false)
    val RestoreRolledBackAt = SettingKey("remft_restore_rolled_back_at", null, false)
    val ExternalRecents = SettingKey("remft_external_recents", null, false)
    val SearchRecent = SettingKey("search.recent", null, false)
    val DynamicColor = SettingKey("remft_dynamic_color", "false", false)

    val all: List<SettingKey> = listOf(
        AlarmSound, ReminderSound, ReminderProfile, SnoozePresets, NotificationPrivacy, DateFormat,
        NoteSourceStyling, BinEnabled, BinAutoDelete, BinDays, AutoBackupSchedule, AutoBackupKeepDays,
        HomeCalendarVisible, TasksSort, InboxSort, NotesSort, FilesSort, NotesViewMode, OnboardingSeen,
        LocalWorkspaceId, LastBackupAt, BackupReminderDismissed, OneDriveOn, OneDriveAccount,
        OneDriveReconnect, AutoBackupLastOk, AutoBackupLastSize, AutoBackupPaused, AutoBackupDropAccepted,
        AutoBackupUnavailable, AutoBackupStatusOneDrive, AutoBackupWakeSlots, MirrorDownloadsPending,
        MirrorReviewSweptAt, RestorePendingSettings, RestoreRolledBackAt, ExternalRecents, SearchRecent,
        DynamicColor,
    )

    val backedUp: Set<String> = all.filter { it.inBackup }.map { it.name }.toSet()

    fun byName(name: String): SettingKey? = all.firstOrNull { it.name == name }
}

enum class DateFormatId(val id: String) {
    System("system"), DmyDot("dmy_dot"), DmySlash("dmy_slash"), Mdy12h("mdy_12h"), Iso("iso"), DmyLong("dmy_long");

    companion object {
        fun parse(value: String?): DateFormatId = entries.firstOrNull { it.id == value } ?: System
    }
}

enum class NoteSourceStyling(val id: String) {
    Plain("plain"), MarkersOnly("markersOnly"), Live("live");

    companion object {
        fun parse(value: String?): NoteSourceStyling = entries.firstOrNull { it.id == value } ?: MarkersOnly
    }
}
