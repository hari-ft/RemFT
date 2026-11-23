package com.ftapps.remft.domain.backup.oldapp

object RoomSchemaV1 {
    const val VERSION = 1
    const val IDENTITY_HASH = "c7e1e8975bf86d409d6ceb23ece848a1"

    val tables: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `tasks` (`id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT, `status` TEXT NOT NULL DEFAULT 'open', `priority` TEXT NOT NULL DEFAULT 'none', `due_at` TEXT, `remind_at` TEXT, `snoozed_until` TEXT, `alarms_muted_at` TEXT, `timezone` TEXT NOT NULL DEFAULT 'Asia/Kolkata', `is_urgent` INTEGER NOT NULL DEFAULT 0, `requires_acknowledgement` INTEGER NOT NULL DEFAULT 0, `series_id` TEXT, `occurrence_date` TEXT, `spawned_from_task_id` TEXT, `sort_order` INTEGER NOT NULL DEFAULT 0, `completed_at` TEXT, `title_fold` TEXT, `description_fold` TEXT, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `checklist_items` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `title` TEXT NOT NULL, `is_done` INTEGER NOT NULL DEFAULT 0, `sort_order` INTEGER NOT NULL DEFAULT 0, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `notes` (`id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `title` TEXT NOT NULL, `content_markdown` TEXT, `plain_text` TEXT, `is_pinned` INTEGER NOT NULL DEFAULT 0, `is_archived` INTEGER NOT NULL DEFAULT 0, `title_fold` TEXT, `body_fold` TEXT, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `note_link_rows` (`id` TEXT NOT NULL, `note_id` TEXT NOT NULL, `entity_type` TEXT NOT NULL, `entity_id` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `task_id` TEXT NOT NULL, `kind` TEXT NOT NULL DEFAULT 'remind', `snooze_count` INTEGER NOT NULL DEFAULT 0, `remind_at` TEXT NOT NULL, `alarm_level` TEXT NOT NULL DEFAULT 'normal', `requires_acknowledgement` INTEGER NOT NULL DEFAULT 0, `status` TEXT NOT NULL DEFAULT 'scheduled', `snoozed_until` TEXT, `acknowledged_at` TEXT, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `file_rows` (`id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `target_type` TEXT NOT NULL, `target_id` TEXT NOT NULL, `name` TEXT NOT NULL, `mime` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `folder_id` TEXT, `name_fold` TEXT, `local_path` TEXT, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `folders` (`id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `parent_id` TEXT, `name` TEXT NOT NULL, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `task_series` (`id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `rule_json` TEXT NOT NULL, `template_json` TEXT NOT NULL, `timezone` TEXT NOT NULL DEFAULT 'Asia/Kolkata', `anchor_at` TEXT NOT NULL, `created_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `series_skips` (`series_id` TEXT NOT NULL, `occurrence_date` TEXT NOT NULL, `created_at` TEXT, PRIMARY KEY(`series_id`, `occurrence_date`))",
        "CREATE TABLE IF NOT EXISTS `trash_items` (`id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `title` TEXT NOT NULL, `payload` TEXT NOT NULL, `local_paths` TEXT NOT NULL DEFAULT '[]', `size_bytes` INTEGER NOT NULL DEFAULT 0, `deleted_at` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `alarm_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `at` TEXT NOT NULL, `event` TEXT NOT NULL, `lane` TEXT NOT NULL, `kind` TEXT, `slot_index` INTEGER, `urgent` INTEGER NOT NULL DEFAULT 0, `sound` TEXT, `level` TEXT, `fire_at` TEXT, `task_id` TEXT, `reminder_id` TEXT, `detail` TEXT)",
        "CREATE TABLE IF NOT EXISTS `file_mirrors` (`local_path` TEXT NOT NULL, `sha256` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `quick_xor_hash` TEXT, `state` TEXT NOT NULL DEFAULT 'uploading', `remote_path` TEXT, `item_id` TEXT, `attempts` INTEGER NOT NULL DEFAULT 0, `last_error` TEXT, `mirrored_at` TEXT, `listed_at` TEXT, `updated_at` TEXT, PRIMARY KEY(`local_path`))",
    )

    val indices: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_tasks_completed` ON `tasks` (`workspace_id`, `status`, `completed_at`)",
    )

    val setup: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
        "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '$IDENTITY_HASH')",
    )

    fun create(db: SqlPort) {
        for (sql in tables) db.exec(sql)
        for (sql in indices) db.exec(sql)
        for (sql in setup) db.exec(sql)
        db.exec("PRAGMA user_version = $VERSION")
    }
}
