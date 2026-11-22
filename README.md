# RemFT (native Android)

Native Android task, reminder and notes app (Kotlin, Jetpack Compose, Hilt, Room).

The app has one identity: application ID `com.ftapps.remft`, label "RemFT". It starts empty on a fresh install; data comes back only from a RemFT backup (format 1).

## Layout

- `domain/` is pure Kotlin with no Android types, unit-tested on the JVM.
  - `alarm`: alarm selection, re-ring chains, the planner (collision spacing, held rings, notification ids, texts), sounds and channel ids.
  - `backup`: zip format, names, validation, writer; `auto` (schedule, retention, pause rule), `bin` (bin payloads), `lock` (cross-process work locks), `mirror` (OneDrive mirror paths, QuickXorHash), `restore` (journalled swap and recovery, scratch sweep).
  - `identity`: workspace id decision. `quickadd`: quick-add grammar. `repeat`: rules, expansion, series engine, editor, rule text. `search`: fold and ranking. `settings`: keys and defaults. `tasks`: task rules, default times. `text`, `time`: whitespace, placeholders and ISO-8601 UTC helpers.
  - `notes`: Markdown dialect (`markdown`), editing helpers (`editing`), export to PDF/DOCX/MD (`export`), code highlighting (`highlight`), Mermaid layout (`mermaid`).
- `app/` is the Android app.
  - `app`: `AppStartup`, the ordered start sequence. `di`: Hilt modules.
  - `data/alarm`: AlarmManager gateway, scheduler and pending set, receivers, ring controller, sounds, permissions health, alarm log and app log, background refresh worker.
  - `data/backup`: Back up now, validation, restore, startup recovery, settings in backups. `data/onedrive`: AppAuth sign-in, token store, Graph client, automatic backup, wake, restore from OneDrive. `data/mirror`: per-file OneDrive mirror and downloads.
  - `data/db`: Room database, entities and DAOs. `data/prefs`: DataStore settings. `data/identity`, `data/tasks`, `data/repeat`, `data/notes`, `data/files`, `data/bin`, `data/search`.
  - `ui`: `theme`, `shell` (navigation), `components`, and one package per screen family (`tasks`, `quickadd`, `repeat`, `search`, `notes` with `editor`, `markdown`, `export`, `files`, `bin`, `completed`, `alarm`, `backup`, `onedrive`, `settings`, `screens`).
- `fixtures/` holds shared test data (fold cases, recurrence cases, quick-add cases, Markdown corpora). It contains no personal data.
- `keys/` holds the debug keystore and the release key. The release key and `key.properties` are git-ignored.

## Commands

| What | Command |
| --- | --- |
| All unit tests | `./gradlew :domain:test :app:testDebugUnitTest` |
| Lint | `./gradlew :app:lintDebug` |
| Debug APK, for the test phone (V2443) | `./gradlew :app:assembleDebug` |
| Release APK, arm64, for the owner's phone (V60) | `./gradlew :app:assembleRelease` (copied to `builds-ft/`, newest 3 kept) |
| Instrumented alarm tests (device or emulator attached) | `./gradlew :app:connectedDebugAndroidTest` |

## Design decisions

### Look and feel (Lapis and Marigold)
- **Tokens live in `ui/theme/`.** `Palette.kt` holds the light and dark colour schemes, the semantic colours (urgent, overdue, today, done, repeat, note, file, success, warning) and the four tonal levels; `Type.kt` the type scale; `Shapes.kt` the marked-corner shapes; `Motion.kt` the motion tokens; `Gradients.kt` the brand, today, urgent and alarm-ring gradients. Screens read them through `RemftTheme.colors`, `RemftTheme.tonal`, `RemftTheme.gradients`, `RemftTheme.motion` and `MarkedShapes`.
- **Fonts are bundled** in `res/font`: Bricolage Grotesque (600, 700), Instrument Sans (400, 500, 600) and JetBrains Mono (400), static instances cut from the variable fonts in the google/fonts repository. All three are under the SIL Open Font Licence; the licence texts are in `app/src/main/assets/licenses/`.
- **Wallpaper colours** (Material You) are off by default and only offered on Android 12 and later (Settings, General). The semantic colours and gradients stay on the brand palette either way. The choice is a device preference and is not backed up.
- **Icon:** the RemFT bell, a white bell with a lapis check mark and a marigold notification dot on a lapis gradient, drawn as vectors: an adaptive icon with a monochrome layer for themed icons, legacy PNGs, a 24 dp bell notification icon, and the same bell as the name-mark in the Home top bar.

### Data and settings
- **Room owns the schema.** The database (`remft.sqlite`) is Room version 1, created by Room from the entities; the exported schema is `app/schemas/com.ftapps.remft.data.db.RemftDatabase/1.json`. There are no migrations yet; a schema change becomes version 2 with a migration and a test.
- **Dates are ISO-8601 UTC text** with milliseconds (`2026-11-02T10:00:00.000Z`), written by `UtcText`. JSON (backup manifest, settings, bin payloads, journals) is written with kotlinx.serialization.
- **Strings** live in `strings.xml` with `area_name` keys; `{name}` placeholders are filled with `fill(...)`. Every string is used: `lint.xml` makes `UnusedResources` an error, so a dead string fails the build.
- **Task deletes wait 5 seconds for Undo.** One app-level Undo snackbar sits in the shell above the navigation host, so it survives leaving a screen and configuration changes. The commit is timed by `PendingDeletes` on its own scope, never by the snackbar; a closed snackbar never commits. A row stays hidden while its delete is pending and for a moment after the commit, then the id is released so a restore from the bin shows again.
- **"This and future ones" on a repeating task is undoable and binned.** The deleted occurrence, every later occurrence it removes, the series rule and its skips go into one bin entry (kind `task`, payload keys `series` and `seriesSkips`); restoring it brings the series back as it was.
- **`ACCESS_NETWORK_STATE` stays in the manifest.** WorkManager needs it for the network-required backup jobs. It is the only permission not tied to a visible feature.

### Startup
- **Order:** restore recovery, then the database open, then identity, then (in the background) files maintenance, series roll and the OneDrive triggers.
- **Startup** finishes or rolls back an interrupted restore before the database opens. If recovery itself fails while a journal is still there, startup stops on the error screen instead of opening (or creating) a database. `DatabaseHolder` itself refuses to open while an unfinished restore journal (or a parked copy without a live one) is there (`DatabaseRecoveryPending`), background passes such as the alarm scheduler only run once startup is `Ready`, Retry closes the database before running startup again, and the attachment sweep deletes nothing when the database has no file rows but the files folder is not empty.
- **Splash** shows the launcher bell on the lapis brand colour, and on Android 12 and later the bell swings like it is ringing (about 0.8 s) while the dot pops. The animation never holds the app back: the splash stays only while startup needs it, so on a fast start the swing is cut short. Older Android versions show the still bell. A cold start for a ringing alarm keeps the splash until the alarm screen is decided.

### Alarms
- **Notification channels use importance HIGH** (Android treats max as high for channels).
- **Notification ids.** Ids below `NotificationIds.FIRST_RING` (2^20) are reserved for fixed app notifications; the test alarm is `1`. Every ring of an alarm gets an id at or above `FIRST_RING`, from a 64-bit FNV-1a hash of its ring key (format version, reminder id, slot, fire time, urgency, sound, title, body), so a change to what the notification shows books it again. The planner hands ids out in fire-time order and re-hashes with the next attempt number if an id is already taken, so one plan never holds the same id twice. Matching a shown notification back to its alarm checks the first four attempts.
- **Notification channels** are `alarm.<sound>` and `reminder.<sound>`, where `<sound>` is `system`, `raw.<name>` for a bundled sound, or `file.<id>` for an uploaded one. A channel's sound cannot change after it is created, so each sound has its own channel, and channels for sounds no longer chosen are deleted once nothing shown uses them.
- **Notification buttons answer in the background** through a broadcast receiver, without opening the app. A plain tap only navigates.
- **Snooze options are a user-editable list** in `remft_snooze_presets` (comma-separated, in order): the built-ins `5_min`, `30_min`, `1_hour`, `tomorrow_morning`, plus custom lengths stored as `m:<minutes>` (1 minute to 365 days, at most 8 options, never fewer than 1; `m:5`, `m:30` and `m:60` fold into the built-ins). An empty value means the default four. The ringing screen shows every option in order; a notification has room for three buttons, so an urgent alarm shows Acknowledge plus the first option and a reminder shows Complete plus the first two. The shown notification reads the order when it is posted.
- **After a restore the alarm data watcher is re-attached to the new database and every alarm is re-booked** (`AlarmStartup.afterRestore`, called by `AppStartup` once the restarted data layer is ready).
- **Alarms survive a reboot that stays locked (direct boot).** Each alarm pass mirrors the next 48 hours of booked rings (at most 24) into device-protected storage (`alarms-boot/mirror.json`): fire time, id, payload, sound and the shown text, or only the generic text when private notifications are on; uploaded sounds fall back to the system sound. The fire, action and system receivers are direct-boot aware: on `LOCKED_BOOT_COMPLETED` (or any receiver call before first unlock) they re-book from the mirror and post the insistent ringing notification without touching the database, settings or other credential-encrypted files, and button presses are queued. After unlock the application starts its data layer, applies queued answers, and the normal full re-book replaces the mirror-booked alarms under the same ids, cancelling any the plan no longer wants.
- **Custom alarm sounds after a restore.** Zips carry the sound's file row and bytes but not the `Sounds/` folder. Every alarm pass reinstalls a chosen `file:<id>` sound from its file row into `Sounds/` before it plans, so the restored choice keeps working.

### Backup and restore
- **Back up now** snapshots the live database with `VACUUM INTO` (on SQLite builds without it, API < 30, it copies the file under a write lock after a WAL checkpoint), builds the zip with the shared domain writer into `cache/backup-share/` and hands it to the share sheet through the `FileProvider`. A chooser callback (`BackupShareReceiver`) tells whether a target was picked; only then is `remft_last_backup_at` set. The zip stays until the next backup or the next start, because share targets often read it after the chooser has closed.
- **The workspace id is resolved in one place**, `LocalIdentityRepository`, which caches a confirmed id for the process; every repository and search asks it, and `forgetSession()` (called by `AppStartup` on restart after a restore and on Retry) drops the cache.
- **The automatic backup wake never cancels itself.** Its slot is booked under one of two unique work names (`remft-backup-wake`, `remft-backup-wake-next`); a booking made while one of them is running goes to the other, and a running wake is never cancelled. The turn waits up to 30 s for the `onedrive-work` lock (for example while the start-up sweep holds it).
- **A successful automatic OneDrive backup also counts as a backup** for `remft_last_backup_at`.
- **A restore records the backup's date as the last backup** when nothing newer is recorded (clamped to now). The Home reminder therefore does not nag right after a restore; it comes back 30 days after the zip was made.
- **Restore** validates a copy of the picked zip, asks for confirmation, takes the `restore` lock, waits for any background turn and takes the `onedrive-work` lock (45 s each, otherwise it refuses as busy with nothing touched), closes the database and runs the journalled swap. If the database does not close within 10 s the restore stops before the swap (`RestoreDatabaseInUse`). Automatic backup, mirror catch-up and mirror downloads check the `restore` lock between steps and stop cleanly. While the swap runs, `DatabaseHolder` refuses to reopen the file (`DatabaseRestoring`). Afterwards the data layer restarts through `AppStartup.restartAfterRestore()`, which recreates the whole UI tree.
- **Backup format 1.** A zip holds `manifest.json` (`"app": "remft"`, `"format": 1`, `schemaVersion`, `createdAt`, database size and SHA-256, and a `files` list with size and SHA-256 per file, or a OneDrive reference for a file that is only listed), optional `settings.json`, `remft.sqlite` and `files/`. Restore accepts only format 1 with the current schema version; anything else is refused before the live data is touched.
- **Backup UI hosts:** `BackupHost` sits in `MainActivity` above the shell, so snackbars, the share sheet and the blocking restore dialogs survive navigation and the post-restore restart. Home places `BackupReminderCard()` in its banner column.

### Notes and files
- **Math is shown as styled source**, not typeset: there is no native LaTeX renderer that meets the no-WebView rule without a heavy dependency.
- **PDF export uses the system fonts** through `PdfDocument`; no fonts are bundled.
- **Coil is pinned to 3.3.0** for thumbnails.
- **Glass blur** comes from Haze: a RenderEffect blur on API 31+ and a translucent tint below.

## OneDrive

### Azure app registration

OneDrive sign-in for `com.ftapps.remft` needs these Android redirect URIs on the "RemFT" registration (client id `ba03908f-a139-4723-85d9-f45e5b412974`), added by the owner:

- release key: `msauth://com.ftapps.remft/oO1NXGPKCxPLZtYrmG6TFOENHGI%3D`
- debug key: `msauth://com.ftapps.remft/1voKiGFm5vIWyJNpUr5qKujCQDE%3D`

## Device test script (V2443, debug build)

Run each item in light and in dark mode where a screen is involved. Before starting: allow notifications, "Alarms & reminders", full-screen notifications, and set battery to "No restrictions".

### Alarms
1. **Urgent alarm on a locked phone.** Create an urgent task with a reminder 2 minutes ahead (Standard profile). Lock the phone. It must ring full screen over the lock screen with sound and vibration; Acknowledge opens the task once.
2. **Re-rings.** Repeat, but do not answer. It must ring again at +30 and +60 minutes; the missed card appears on Home after 5 minutes without an answer.
3. **Snooze.** From the notification press "5 min"; it rings again 5 minutes later and the alarm log shows the snooze.
4. **Force-stop.** Book an alarm 5 minutes ahead, then Settings → Apps → RemFT → Force stop. Open RemFT once (re-book on first pass), lock the phone; it must still ring on time. Also try Vivo's "clean up" from recents.
5. **Reboot.** Book an alarm 5 minutes ahead and reboot without opening the app. It must ring. Then book an urgent alarm 5 minutes ahead, reboot and leave the phone locked (do not enter the PIN): it must still ring with sound on the lock screen; after unlocking it must not ring twice, and an Acknowledge pressed while locked is applied once unlocked.
6. **Two alarms in the same minute.** Book two urgent tasks for the same minute. Both must ring (the second 15 minutes later by design); answering one must not cancel the other.
7. **Normal reminder.** A non-urgent reminder rings once with Complete, 30 min, 1 hour; the buttons work without opening the app.
8. **Test alarm** (Settings → Notifications) rings within 15 s. **Alarm volume 0** shows the Home warning when an urgent ring is ahead.
9. **Custom sound.** Pick an audio file as alarm sound, preview it, and let an alarm ring with it.

### Pickers
10. Attach a photo (Photo Picker), a camera capture, and a document to a task and to a note; open each in the viewer. Pick a custom date and time for a snooze and for a due date; the past-time message appears for a past time.

### Backup and restore
11. **Round trip:** with test data, "Back up now", change a few tasks, notes, files and settings, then restore the zip. Check tasks and their reminders, notes, files and folders, the recycle bin, and the backed-up settings (date format, sounds, snooze buttons, reminder profile, bin, sorts). Urgent alarms re-book on their own.
12. **Fresh install:** restore that zip on a fresh install of the debug build and run the same checks.
13. Restore from OneDrive after connecting; a file listed only in OneDrive downloads in the background and the Home progress card finishes.

### Light and dark
14. Walk every screen (Home, Inbox, task detail, create sheet, notes list/grid, editor and reading mode, Files, bin, completed archive, search, alarm screen, missed card, every settings page, backup and automatic backup) in light and in dark mode; check contrast and that nothing is cut off at the largest font size.
