package com.ftapps.remft.domain.alarm

import com.ftapps.remft.domain.settings.SettingKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class AlarmSoundsTest {

    @Test
    fun bundledAndDefaultSoundsKeepTheirChannelIds() {
        assertEquals("alarm.system", AlarmSounds.channelIdFor(urgent = true, soundName = null))
        assertEquals("alarm.raw.remft_alarm", AlarmSounds.channelIdFor(urgent = true, soundName = "remft_alarm"))
        assertEquals("reminder.raw.remft_chime", AlarmSounds.channelIdFor(urgent = false, soundName = "remft_chime"))
    }

    @Test
    fun anUploadedSoundGetsAChannelOfItsOwnKeyedByFileId() {
        val uri = AlarmSounds.contentUri("01HZX9.mp3")
        assertEquals("alarm.file.01HZX9", AlarmSounds.channelIdFor(urgent = true, soundName = uri))
        assertEquals("reminder.file.01HZX9", AlarmSounds.channelIdFor(urgent = false, soundName = uri))
        assertNotEquals(
            AlarmSounds.channelIdFor(urgent = true, soundName = uri),
            AlarmSounds.channelIdFor(urgent = true, soundName = AlarmSounds.contentUri("01OTHER.m4a")),
        )
    }

    @Test
    fun oddCharactersNeverReachTheChannelId() {
        assertEquals(
            "alarm.file.a_b_c",
            AlarmSounds.channelIdFor(urgent = true, soundName = AlarmSounds.contentUri("a b-c.mp3")),
        )
    }

    @Test
    fun choicesRoundTripEveryShape() {
        for (choice in listOf(SoundChoice.Os, SoundChoice.Bundled("chime"), SoundChoice.File("01FILE"))) {
            val parsed = SoundChoice.parse(choice.encode())
            assertEquals(choice.encode(), parsed.encode())
            assertEquals(choice, parsed)
        }
    }

    @Test
    fun junkAndEmptiesResolveToTheOsSound() {
        for (raw in listOf(null, "", "nope", "bundled:", "file:")) {
            assertTrue("raw=$raw", SoundChoice.parse(raw).isOsDefault)
        }
    }

    @Test
    fun unknownBundledIdsFallBackToTheDefaultAlarmSound() {
        for (raw in listOf("bundled:gone", "bundled:retired_bed")) {
            val parsed = SoundChoice.parse(raw)
            assertEquals("raw=$raw", AlarmSounds.DefaultAlarmChoice, parsed)
            assertEquals(AlarmSounds.ALARM, AlarmSounds.resolve(parsed, emptyList()).name)
        }
    }

    @Test
    fun theDefaultAlarmSettingParsesToTheBundledAlarm() {
        assertEquals(SoundChoice.Bundled("alarm"), SoundChoice.parse(SettingKeys.AlarmSound.default))
    }

    @Test
    fun bundledSoundsResolveToRawResourcesAndUploadsToTheirInstalledCopy() {
        assertEquals("remft_chime", AlarmSounds.resolve(SoundChoice.Bundled("chime"), emptyList()).name)

        val missing = AlarmSounds.resolve(SoundChoice.File("01FILE"), emptyList())
        assertNull(missing.name)
        assertEquals("custom-sound-missing", missing.degradedReason)

        val installed = AlarmSounds.resolve(SoundChoice.File("01FILE"), listOf("01FILE.mp3"))
        assertEquals("content://com.ftapps.remft.sounds/sounds/01FILE.mp3", installed.name)
        assertNull(installed.degradedReason)
    }

    @Test
    fun anInstalledCopyMatchesOnlyItsOwnFileId() {
        assertEquals("01FILE.mp3", AlarmSounds.installedNameFor("01FILE", listOf("01FILEX.mp3", "01FILE.mp3")))
        assertNull(AlarmSounds.installedNameFor("01FILE", listOf("01FILEX.mp3", "01FILE")))
    }

    @Test
    fun theContentUriMatchesWhatFileProviderBuilds() {
        assertEquals(
            "content://${AlarmSounds.SOUND_AUTHORITY}/sounds/01ABC.m4a",
            AlarmSounds.contentUri("01ABC.m4a"),
        )
        assertEquals(
            "content://${AlarmSounds.SOUND_AUTHORITY}/sounds/a%20b.mp3",
            AlarmSounds.contentUri("a b.mp3"),
        )
    }

    @Test
    fun theRingScreenPlaysTheChosenBundledSoundElseTheBed() {
        assertEquals("remft_alarm", AlarmSounds.inAppRawFor(SoundChoice.Os))
        assertEquals("remft_ping", AlarmSounds.inAppRawFor(SoundChoice.Bundled("ping")))
        assertEquals("remft_alarm", AlarmSounds.inAppRawFor(SoundChoice.File("01F")))
    }

    @Test
    fun theOsDefaultAsksForNoFileAtAll() {
        val resolved = AlarmSounds.resolve(SoundChoice.Os, listOf("01FILE.mp3"))
        assertNull(resolved.name)
        assertNull(resolved.degradedReason)
    }

    private val now = utc(2026, 7, 15, 12)

    private fun laneAlarm(urgent: Boolean) = AlarmInput(
        reminderId = pad26("R1"),
        taskId = pad26("T1"),
        taskTitle = "Task",
        remindAt = now.plus(Duration.ofHours(1)),
        status = ReminderStatus.SCHEDULED,
        urgent = urgent,
        requiresAcknowledgement = false,
    )

    @Test
    fun eachLaneGetsItsOwnSound() {
        val plan = EnglishAlarmTexts.planner.planNotifications(
            alarms = listOf(laneAlarm(urgent = true), laneAlarm(urgent = false)),
            now = now,
            privacyMode = false,
            alarmSoundName = "remft_alarm",
            reminderSoundName = "remft_chime",
        )
        assertEquals("remft_alarm", plan.first { it.urgent }.soundName)
        assertEquals("remft_chime", plan.first { !it.urgent }.soundName)
    }

    @Test
    fun changingTheSoundChangesTheIdSoItReschedules() {
        fun idsWith(sound: String?): List<Int> = EnglishAlarmTexts.planner.planNotifications(
            alarms = listOf(laneAlarm(urgent = true)),
            now = now,
            privacyMode = false,
            alarmSoundName = sound,
        ).map { it.id }

        assertNotEquals(idsWith("remft_alarm"), idsWith("remft_chime"))
        assertEquals(idsWith("remft_alarm"), idsWith("remft_alarm"))
    }

    @Test
    fun theDeliveryDescriptionReportsTheSoundTheLogWillShow() {
        assertEquals("01FILE.mp3", AlarmBooking.deliveryFor(urgent = true, soundName = "01FILE.mp3").sound)
        assertEquals(AlarmSounds.ALARM, AlarmBooking.deliveryFor(urgent = true, soundName = null).sound)
        assertEquals("os-default", AlarmBooking.deliveryFor(urgent = false, soundName = null).sound)
        assertEquals("timeSensitive", AlarmBooking.deliveryFor(urgent = false, soundName = null).level)
    }

    @Test
    fun onlyOurPerSoundChannelsCountAsOurs() {
        assertTrue(AlarmSounds.isSoundChannelId("alarm.raw.remft_alarm"))
        assertTrue(AlarmSounds.isSoundChannelId("alarm.file.01OLD"))
        assertTrue(AlarmSounds.isSoundChannelId("reminder.system"))
        assertFalse(AlarmSounds.isSoundChannelId("alarms"))
        assertFalse(AlarmSounds.isSoundChannelId("miscellaneous"))
    }

    private val oldUpload = AlarmSounds.contentUri("01OLD.mp3")
    private val newUpload = AlarmSounds.contentUri("01NEW.m4a")

    @Test
    fun staleSoundChannelsAreDeletedAndTheCurrentOnesStay() {
        val existing = listOf(
            "alarm.raw.remft_alarm",
            "alarm.file.01OLD",
            "alarm.file.01NEW",
            "reminder.system",
            "reminder.raw.remft_chime",
            "some_other_channel",
        )
        val keep = AlarmSounds.channelsToKeep(alarmSoundName = newUpload, reminderSoundName = "remft_chime")
        assertEquals(setOf("alarm.file.01NEW", "reminder.raw.remft_chime"), keep)
        val stale = AlarmSounds.staleChannels(existing, keep, showing = emptySet())
        assertEquals(
            setOf("alarm.raw.remft_alarm", "alarm.file.01OLD", "reminder.system"),
            stale.toSet(),
        )
        assertEquals(
            setOf("alarm.file.01NEW", "reminder.raw.remft_chime", "some_other_channel"),
            existing.filter { it !in stale }.toSet(),
        )
    }

    @Test
    fun theOsDefaultSoundKeepsItsOwnDefaultChannel() {
        val existing = listOf("alarm.system", "alarm.raw.remft_alarm", "reminder.system")
        val keep = AlarmSounds.channelsToKeep(alarmSoundName = null, reminderSoundName = null)
        assertEquals(listOf("alarm.raw.remft_alarm"), AlarmSounds.staleChannels(existing, keep, emptySet()))
    }

    @Test
    fun aChannelWithANotificationOnScreenIsSparedThenPrunedLater() {
        val existing = listOf("alarm.file.01OLD", "alarm.file.01NEW")
        val keep = AlarmSounds.channelsToKeep(alarmSoundName = newUpload, reminderSoundName = null)
        assertTrue(AlarmSounds.staleChannels(existing, keep, showing = setOf("alarm.file.01OLD")).isEmpty())
        assertEquals(
            listOf("alarm.file.01OLD"),
            AlarmSounds.staleChannels(existing, keep, showing = emptySet()),
        )
    }

    @Test
    fun aNewChoiceIsANewKeepSet() {
        assertNotEquals(
            AlarmSounds.channelsToKeep(alarmSoundName = newUpload, reminderSoundName = null),
            AlarmSounds.channelsToKeep(alarmSoundName = oldUpload, reminderSoundName = null),
        )
        assertEquals(
            AlarmSounds.channelsToKeep(alarmSoundName = newUpload, reminderSoundName = null),
            AlarmSounds.channelsToKeep(alarmSoundName = newUpload, reminderSoundName = null),
        )
    }

    @Test
    fun theSchedulerPrunesWithTheSoundsItJustPlanned() {
        val alarmSound = AlarmSounds.resolve(SoundChoice.Bundled("alarm"), emptyList()).name
        val reminderSound = AlarmSounds.resolve(SoundChoice.Os, emptyList()).name
        val plan = EnglishAlarmTexts.planner.planNotifications(
            alarms = listOf(
                AlarmInput(
                    reminderId = pad26("R1"),
                    taskId = pad26("T1"),
                    taskTitle = "Task",
                    remindAt = utc(2026, 7, 15, 13),
                    status = ReminderStatus.SCHEDULED,
                    urgent = true,
                    requiresAcknowledgement = true,
                ),
            ),
            now = utc(2026, 7, 15, 12),
            privacyMode = false,
            alarmSoundName = alarmSound,
            reminderSoundName = reminderSound,
        )
        val planned = plan.map { it.soundName }.toSet()
        assertEquals(setOf("remft_alarm"), planned)
        val keep = AlarmSounds.channelsToKeep(planned.single(), reminderSound)
        assertEquals(setOf("alarm.raw.remft_alarm", "reminder.system"), keep)
        assertTrue(plan.all { AlarmSounds.channelIdFor(it.urgent, it.soundName) in keep })
    }
}
