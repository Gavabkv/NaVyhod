package com.example.dovykhoda

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.UserManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Locale
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmLifecycleTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var controller: ServiceController<AlarmService>? = null

    @Before
    fun resetSettings() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        context.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun stopService() {
        controller?.destroy()
        controller = null
    }

    private fun service(): AlarmService = Robolectric.buildService(AlarmService::class.java)
        .create().also { controller = it }.get()

    private fun settings() = AlarmSettings(
        enabled = true, speakEnabled = false, ringSeconds = 60,
        announceTimeOnSignals = false, announceTimeOnDeparture = false, announceTimeOnLate = false,
    )

    private fun startDeparture(service: AlarmService, delayMillis: Long = 0L): Long {
        val now = System.currentTimeMillis() - delayMillis
        service.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, now - 3_600_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, now), 0, 1)
        return now
    }

    private fun startBeforeDeparture(alarm: AlarmService, leadMinutes: Int): Long {
        val now = System.currentTimeMillis()
        val leave = now + leadMinutes * 60_000L
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, now - 3_600_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave), 0, 1)
        return leave
    }

    private fun deliverDepartureReminder(service: AlarmService, minutes: Int): List<String> {
        val leave = SettingsStore(context).activeCycle()!!.leaveMillis
        service.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_DEPARTURE_REMINDER)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave)
            .putExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, minutes)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, leave - minutes * 60_000L), 0, 3)
        return completeSpeech()
    }

    private fun completeSpeech(repeats: Int = 1): List<String> {
        ShadowTextToSpeech.addLanguageAvailability(Locale("ru", "RU"))
        // Service work launches on the main looper; let it construct VoiceSpeaker first.
        shadowOf(Looper.getMainLooper()).idle()
        val instance = ShadowTextToSpeech.getLastTextToSpeechInstance()
            ?: throw AssertionError("Expected a TTS instance; service speech did not start")
        val tts = shadowOf(instance)
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        repeat(repeats - 1) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        }
        // Движок речи живёт весь сеанс, поэтому список копит все реплики:
        // возвращаем только последние, относящиеся к текущему напоминанию.
        return tts.spokenTextList.toList().takeLast(repeats)
    }

    @Test
    fun `ordinary voice says current time before remaining minutes and setting can disable it`() {
        val store = SettingsStore(context)
        val alarm = service()
        for (enabled in listOf(true, false)) {
            store.save(settings().copy(speakEnabled = true, announceTimeOnSignals = enabled))
            val before = java.time.LocalTime.now()
            startBeforeDeparture(alarm, 20)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
            val spoken = completeSpeech().single()
            val after = java.time.LocalTime.now()
            val remaining = SpeechText.remaining(20)
            if (enabled) {
                assertTrue(spoken in listOf(before, after).map { "${SpeechText.currentTime(it)}. $remaining" })
            } else {
                assertEquals(remaining, spoken)
            }
            alarm.onStartCommand(Intent(context, AlarmService::class.java)
                .setAction(AlarmService.ACTION_SILENCE), 0, 2)
        }
    }

    @Test
    fun `clock speech never starts music phase voice before its lead window`() {
        SettingsStore(context).save(settings().copy(speakEnabled = true, announceTimeOnSignals = true))
        startBeforeDeparture(service(), 60)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun `late reminders announce actual time once before three repetitions with pauses`() {
        val store = SettingsStore(context)
        store.save(settings().copy(announceTimeOnLate = true))
        val before = java.time.LocalTime.now()
        startDeparture(service(), delayMillis = 61_000L)
        val spoken = completeSpeech().single()
        val after = java.time.LocalTime.now()
        assertTrue(spoken in listOf(before, after).map {
            "${SpeechText.currentTime(it)}. ${SpeechText.late(1)}"
        })
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999))
        assertEquals(listOf(spoken), tts.spokenTextList)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(listOf(spoken, SpeechText.late(1)), tts.spokenTextList)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(listOf(spoken, SpeechText.late(1), SpeechText.late(1)), tts.spokenTextList)
        store.save(store.load().copy(announceTimeOnLate = false))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(53))
        assertEquals(List(3) { SpeechText.late(2) }, completeSpeech(repeats = 3))
    }

    @Test
    fun `ringing acquires a screen wake lock before showing the window`() {
        SettingsStore(context).save(settings())
        val alarm = service()
        startBeforeDeparture(alarm, 5)
        val screenLock = ShadowPowerManager.getLatestWakeLock()
        assertEquals("NaVykhod:screen", shadowOf(screenLock).tag)
        assertTrue(screenLock.isHeld)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 2)
        assertFalse(screenLock.isHeld)
    }

    @Test
    fun `reminder only announcements announce current time before departure reminder`() {
        val store = SettingsStore(context)
        store.save(settings().copy(announceTimeOnDeparture = true))
        val alarm = service()
        val leave = startBeforeDeparture(alarm, 5)
        SettingsStore(context).setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        val before = java.time.LocalTime.now()
        val spoken = deliverDepartureReminder(alarm, 5).single()
        val after = java.time.LocalTime.now()
        assertTrue(spoken in listOf(before, after).map {
            "${SpeechText.currentTime(it)}. ${SpeechText.departure(5)}"
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        val exit = deliverDepartureReminder(alarm, 0).single()
        assertTrue(exit.startsWith("Сейчас "))
        assertTrue(exit.endsWith("Пора выходить"))
        // Остановились только сегодняшние сигналы; будильник остаётся заведённым.
        assertTrue(store.load().enabled)
    }

    @Test
    fun `departure reminders can omit the current time`() {
        val store = SettingsStore(context)
        store.save(settings().copy(announceTimeOnDeparture = false))
        val alarm = service()
        val leave = startBeforeDeparture(alarm, 5)
        SettingsStore(context).setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        assertEquals(listOf(SpeechText.departure(5)), deliverDepartureReminder(alarm, 5))
        assertEquals(listOf("Пора выходить"), deliverDepartureReminder(alarm, 0))
        assertTrue(store.load().enabled)
    }

    @Test
    fun `late reminders can omit the current time`() {
        val store = SettingsStore(context)
        store.save(settings().copy(announceTimeOnLate = false))
        val alarm = service()
        startDeparture(alarm)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertEquals(List(3) { SpeechText.late(1) }, completeSpeech(repeats = 3))
        assertTrue(AlarmService.isRinging)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 2)
    }

    @Test
    fun `time announcement settings default to on and persist through locked boot`() {
        val store = SettingsStore(context)
        val defaults = store.load()
        assertTrue(defaults.announceTimeOnSignals)
        assertTrue(defaults.announceTimeOnDeparture)
        assertTrue(defaults.announceTimeOnLate)
        store.save(store.load().copy(
            enabled = true,
            announceTimeOnSignals = false,
            announceTimeOnDeparture = false,
            announceTimeOnLate = false,
        ))
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        val loaded = SettingsStore(context).load()
        assertFalse(loaded.announceTimeOnSignals)
        assertFalse(loaded.announceTimeOnDeparture)
        assertFalse(loaded.announceTimeOnLate)
        store.save(store.load().copy(announceTimeOnSignals = true))
        assertTrue(SettingsStore(context).load().announceTimeOnSignals)
        assertFalse(SettingsStore(context).load().announceTimeOnLate)
    }

    @Test
    fun `legacy global clock setting migrates to all three categories`() {
        SettingsStore(context) // выполняет перенос настроек в device-protected storage
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("speak_time_enabled", false).commit()
        val loaded = SettingsStore(context).load()
        assertFalse(loaded.announceTimeOnSignals)
        assertFalse(loaded.announceTimeOnDeparture)
        assertFalse(loaded.announceTimeOnLate)
    }

    @Test
    fun `late reminders repeat three times every minute beyond ten minutes`() {
        SettingsStore(context).save(settings())
        val alarm = service()
        startDeparture(alarm)
        for (minute in 1..12) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(if (minute == 1) 60 else 54))
            assertEquals(List(3) { SpeechText.late(minute) }, completeSpeech(repeats = 3))
            assertTrue(AlarmService.isRinging)
            assertFalse(shadowOf(alarm).isStoppedBySelf)
            assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        }
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 2)
        assertFalse(AlarmService.isRinging)
        assertNull(SettingsStore(context).activeCycle())
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        val last = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue(last === ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun `late speech waits exactly three seconds between completed utterances`() {
        SettingsStore(context).save(settings())
        startDeparture(service(), delayMillis = 61_000L)
        assertEquals(listOf(SpeechText.late(1)), completeSpeech())
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999))
        assertEquals(1, tts.spokenTextList.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(List(2) { SpeechText.late(1) }, tts.spokenTextList)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_999))
        assertEquals(2, tts.spokenTextList.size)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(List(3) { SpeechText.late(1) }, tts.spokenTextList)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(3, tts.spokenTextList.size)
    }

    @Test
    fun `full shutdown during the pause prevents the remaining repetitions`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startDeparture(alarm, delayMillis = 61_000L)
        assertEquals(listOf(SpeechText.late(1)), completeSpeech())
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf(SpeechText.late(1)), tts.spokenTextList)
        assertTrue(store.load().enabled)
        assertNull(store.activeCycle())
        assertFalse(AlarmService.isRinging)
    }

    @Test
    fun `silence during the pause cancels repeats without disabling the schedule`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startDeparture(alarm, delayMillis = 61_000L)
        assertEquals(listOf(SpeechText.late(1)), completeSpeech())
        val tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_SILENCE), 0, 2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
        assertEquals(listOf(SpeechText.late(1)), tts.spokenTextList)
        assertTrue(store.load().enabled)
        assertTrue(AlarmService.isRinging)
        assertTrue(store.activeCycle() != null)
    }

    @Test
    fun `reminder only mode speaks five times then shuts down without late reminders`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        val leave = startBeforeDeparture(alarm, 20)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        assertEquals(SettingsStore.ActiveCycle(leave - 80 * 60_000L, leave, true), store.activeCycle())
        for (minutes in listOf(20, 15, 10, 5, 0)) {
            assertEquals(listOf(SpeechText.departure(minutes)), deliverDepartureReminder(alarm, minutes))
            for (fieldName in listOf("player", "ringJob", "vibrator")) {
                val field = AlarmService::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
                assertNull(field.get(alarm))
            }
        }
        assertNull(store.activeCycle())
        assertTrue(store.load().enabled)
        assertFalse(AlarmService.isRinging)
        assertTrue(shadowOf(alarm).isStoppedBySelf)
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        val lastSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(12))
        assertTrue(lastSpeech === ShadowTextToSpeech.getLastTextToSpeechInstance())
        // Движок один на весь сеанс, поэтому проверяем последнюю реплику.
        assertEquals("Пора выходить", shadowOf(lastSpeech).spokenTextList.last())
        assertEquals(5, shadowOf(lastSpeech).spokenTextList.size)
        // Следующий день заведён: остановка не убрала будильник из расписания.
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_TRIGGER
        })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `reminder alarms are scheduled separately for every departure prompt`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() + 30 * 60_000L + 30_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        assertEquals(leave, store.activeCycle()!!.leaveMillis)
        AlarmScheduler.scheduleDepartureReminders(context, store.activeCycle()!!)
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val alarms = manager.scheduledAlarms.filter {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_DEPARTURE_REMINDER &&
                !saved.getBooleanExtra(AlarmScheduler.EXTRA_REMINDER_RETRY, false)
        }.sortedBy { it.triggerAtMs }
        assertEquals(listOf(20, 15, 10, 5, 0), alarms.map {
            shadowOf(it.operation).savedIntent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1)
        })
        assertEquals(listOf(leave - 20 * 60_000L, leave - 15 * 60_000L, leave - 10 * 60_000L,
            leave - 5 * 60_000L, leave), alarms.map { it.triggerAtMs })
        val retries = manager.scheduledAlarms.filter {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_DEPARTURE_REMINDER &&
                saved.getBooleanExtra(AlarmScheduler.EXTRA_REMINDER_RETRY, false)
        }.sortedBy { it.triggerAtMs }
        assertEquals(listOf(20, 15, 10, 5, 0), retries.map {
            shadowOf(it.operation).savedIntent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1)
        })
        assertEquals(listOf(leave - 20 * 60_000L, leave - 15 * 60_000L, leave - 10 * 60_000L,
            leave - 5 * 60_000L, leave).map { it + 30_000L }, retries.map { it.triggerAtMs })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `early reminder only mode replaces music alarms with the first twenty minute reminder`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startBeforeDeparture(alarm, 90)
        // Looper-idle steps in Robolectric advance wall clock time; leave enough
        // margin that the last few test assertions remain future alarms.
        val leave = System.currentTimeMillis() + 90 * 60_000L + 30_000L
        SettingsStore(context).setActiveCycle(SettingsStore.ActiveCycle(
            leave - 90 * 60_000L, leave,
        ))
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        assertFalse(AlarmService.isRinging)
        assertTrue(shadowOf(alarm).isStoppedBySelf)
        AlarmScheduler.scheduleDepartureReminders(context, store.activeCycle()!!)
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertFalse(manager.scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_TRIGGER
        })
        val recovery = manager.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_LATE
        }
        assertEquals(leave - 20 * 60_000L, recovery.triggerAtMs)
        val reminderAlarms = manager.scheduledAlarms.filter {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_DEPARTURE_REMINDER &&
                !saved.getBooleanExtra(AlarmScheduler.EXTRA_REMINDER_RETRY, false)
        }
        assertEquals(listOf(20, 15, 10, 5, 0), reminderAlarms.map {
            shadowOf(it.operation).savedIntent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1)
        })
        assertTrue(store.activeCycle()!!.remindersOnly)
    }

    @Test
    fun `late selection of reminder only mode skips already missed announcements`() {
        SettingsStore(context).save(settings())
        val alarm = service()
        startBeforeDeparture(alarm, 12)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        val cycle = SettingsStore(context).activeCycle()!!
        assertEquals(listOf(SpeechText.departure(10)), deliverDepartureReminder(alarm, 10))
        assertEquals(cycle.leaveMillis, SettingsStore(context).activeCycle()!!.leaveMillis)
        assertEquals(listOf(SpeechText.departure(5)), deliverDepartureReminder(alarm, 5))
    }

    @Test
    fun `null intent restarts saved late reminders even a day after departure`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() - 1440 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 3_600_000L, leave))
        assertEquals(Service.START_STICKY, service().onStartCommand(null, 0, 1))
        assertEquals(List(3) { SpeechText.late(1440) }, completeSpeech(repeats = 3))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `locked boot recovers ordinary late mode after the old three minute limit`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() - 20 * 60_000L
        val active = SettingsStore.ActiveCycle(leave - 3_600_000L, leave)
        store.setActiveCycle(active)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertEquals(active, SettingsStore(context).activeCycle())
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val recovery = manager.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_LATE
        }
        assertEquals(leave, shadowOf(recovery.operation).savedIntent
            .getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L))
    }

    @Test
    fun `late cycle cannot swallow the next cycle of its alarm`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() - 60 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 3_600_000L, leave, alarmId = 1))
        val alarm = service()
        val now = System.currentTimeMillis()
        val nextLeave = now + 90 * 60_000L
        val nextWake = nextLeave - 90 * 60_000L
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, nextWake)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, nextLeave)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 1L), 0, 2)
        val ringJob = AlarmService::class.java.getDeclaredField("ringJob").apply { isAccessible = true }
        assertTrue(ringJob.get(alarm) != null)
        assertTrue(AlarmService.isRinging)
        assertEquals(
            SettingsStore.ActiveCycle(nextWake, nextLeave, alarmId = 1),
            store.activeCycle(),
        )
    }

    @Test
    fun `service does not mute another alarm during reminder mode`() {
        val store = SettingsStore(context)
        store.save(settings())
        store.addAlarm(AlarmItem(
            id = 2, wakeHour = 9, wakeMinute = 30, leaveHour = 11, leaveMinute = 0, enabled = true,
        ))
        val now = System.currentTimeMillis()
        store.setActiveCycle(SettingsStore.ActiveCycle(now - 3_600_000L, now + 30 * 60_000L, true, 1))
        val alarm = service()
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, now - 10 * 60_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, now + 20 * 60_000L)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 2L), 0, 2)
        val ringJob = AlarmService::class.java.getDeclaredField("ringJob").apply { isAccessible = true }
        assertTrue(ringJob.get(alarm) != null)
        assertTrue(AlarmService.isRinging)
        assertEquals(
            SettingsStore.ActiveCycle(now - 10 * 60_000L, now + 20 * 60_000L, alarmId = 2),
            store.activeCycle(),
        )
    }

    @Test
    fun `receiver does not mute another alarm during reminder mode`() {
        val store = SettingsStore(context)
        store.save(settings())
        store.addAlarm(AlarmItem(
            id = 2, wakeHour = 9, wakeMinute = 30, leaveHour = 11, leaveMinute = 0, enabled = true,
        ))
        val now = System.currentTimeMillis()
        store.setActiveCycle(SettingsStore.ActiveCycle(now - 3_600_000L, now + 30 * 60_000L, true, 1))
        AlarmReceiver().onReceive(context, Intent(AlarmScheduler.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, now - 10 * 60_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, now + 20 * 60_000L)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, 2L))
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AlarmService.ACTION_TRIGGER, started.action)
        assertEquals(
            SettingsStore.ActiveCycle(now - 10 * 60_000L, now + 20 * 60_000L, alarmId = 2),
            store.activeCycle(),
        )
    }

    @Test
    fun `every planned signal gets a thirty second retry`() {
        val store = SettingsStore(context)
        store.save(settings())
        AlarmScheduler.reschedule(context, store.load())
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val normal = manager.scheduledAlarms.single {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_TRIGGER &&
                !saved.getBooleanExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, false)
        }
        val retry = manager.scheduledAlarms.single {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_TRIGGER &&
                saved.getBooleanExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, false)
        }
        assertEquals(normal.triggerAtMs + 30_000L, retry.triggerAtMs)
        val normalIntent = shadowOf(normal.operation).savedIntent
        val retryIntent = shadowOf(retry.operation).savedIntent
        assertEquals(
            normalIntent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L),
            retryIntent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L),
        )
        assertEquals(1L, retryIntent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0L))
        // Разные PendingIntent: иначе повтор перезаписал бы основной сигнал.
        assertTrue(normal.operation != retry.operation)
    }

    @Test
    fun `retry signal is ignored once its exact time was delivered`() {
        val store = SettingsStore(context)
        store.save(settings())
        val now = System.currentTimeMillis()
        val wake = now - 10 * 60_000L
        val leave = now + 50 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(wake, leave))
        store.markSignalDelivered(now)
        val retry = Intent(AlarmScheduler.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, wake)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, true)
        AlarmReceiver().onReceive(context, retry)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)

        // Без отметки о доставке тот же повтор обязан разбудить сервис.
        val fresh = SettingsStore(context)
        fresh.clearActiveCycle()
        fresh.setActiveCycle(SettingsStore.ActiveCycle(wake, leave))
        AlarmReceiver().onReceive(context, retry)
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AlarmService.ACTION_TRIGGER, started.action)
        assertEquals(SettingsStore.ActiveCycle(wake, leave), store.activeCycle())
    }

    @Test
    fun `two alarms keep their own scheduled signals`() {
        val store = SettingsStore(context)
        store.save(settings())
        store.addAlarm(AlarmItem(
            id = 2, wakeHour = 9, wakeMinute = 30, leaveHour = 11, leaveMinute = 0, enabled = true,
        ))
        AlarmScheduler.reschedule(context, store.load())
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val normal = manager.scheduledAlarms.filter {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_TRIGGER &&
                !saved.getBooleanExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, false)
        }
        // Каждый будильник должен иметь собственный PendingIntent, иначе
        // второй сигнал перезаписывает первый и остаётся только один.
        assertEquals(2, normal.size)
        assertEquals(setOf(1L, 2L), normal.map {
            shadowOf(it.operation).savedIntent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0L)
        }.toSet())
    }

    @Test
    fun `new day signal replaces a stuck reminders only cycle`() {
        val store = SettingsStore(context)
        store.save(settings())
        val now = System.currentTimeMillis()
        val staleLeave = now - 24 * 60 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(staleLeave - 90 * 60_000L, staleLeave, true))
        val wake = now - 60 * 60_000L
        val leave = now + 30 * 60_000L
        AlarmReceiver().onReceive(context, Intent(AlarmScheduler.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, now - 3_600_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, wake)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave))
        assertEquals(SettingsStore.ActiveCycle(wake, leave), store.activeCycle())
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertEquals(AlarmService.ACTION_TRIGGER, started.action)
        assertEquals(wake, started.getLongExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, 0L))
    }

    @Test
    fun `late sequence keeps an exact system backstop for the next minute`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        val leave = startDeparture(alarm)
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val backstop = manager.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_LATE
        }
        assertEquals(leave + 60_000L, backstop.triggerAtMs)
        assertEquals(leave, shadowOf(backstop.operation).savedIntent
            .getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L))
    }

    @Test
    fun `late backstop is rearmed on reschedule but never in the past`() {
        val store = SettingsStore(context)
        store.save(settings())
        val now = System.currentTimeMillis()
        val leave = now - 90_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 3_600_000L, leave))
        AlarmScheduler.reschedule(context, store.load(), recoverLate = false)
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val backstop = manager.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_LATE
        }
        assertTrue(backstop.triggerAtMs > now)
        assertTrue(manager.scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_TRIGGER
        })
    }

    @Test
    fun `reminder recovery at departure says it is time to leave once and stops`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis()
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        val alarm = service()
        alarm.onStartCommand(null, 0, 1)
        assertEquals(listOf("Пора выходить"), deliverDepartureReminder(alarm, 0))
        assertTrue(store.load().enabled)
        assertNull(store.activeCycle())
        assertNull(shadowOf(alarm).nextStartedActivity)
        assertFalse(AlarmService.isRinging)
    }

    @Test
    fun `expired reminder recovery cannot start late speech`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() - 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        val alarm = service()
        assertEquals(Service.START_NOT_STICKY, alarm.onStartCommand(null, 0, 1))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNull(shadowOf(alarm).nextStartedActivity)
        assertTrue(store.load().enabled)
        assertNull(store.activeCycle())
        assertFalse(AlarmService.isRinging)
    }

    @Test
    fun `boot after reminder departure shuts down rather than recovering late mode`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() - 20 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue(store.load().enabled)
        assertNull(store.activeCycle())
        // После завершения напоминаний будильник заводится на следующий день.
        assertTrue(shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_TRIGGER
        })
    }

    @Test
    fun `choosing remind at the departure signal cancels the ordinary late sequence`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startDeparture(alarm)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertEquals(listOf("Пора выходить"), deliverDepartureReminder(alarm, 0))
        val lastSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(5))
        assertTrue(lastSpeech === ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertTrue(store.load().enabled)
        assertNull(store.activeCycle())
        assertFalse(AlarmService.isRinging)
    }

    @Test
    fun `notification action titles are short and preserve their actions`() {
        val notification = StatusNotifier.buildRinging(context, 15)
        assertEquals(listOf("Отложить", "Напомнить", "Остановить"),
            notification.actions.map { it.title.toString() })
        assertEquals(AlarmService.ACTION_SILENCE,
            shadowOf(notification.actions[0].actionIntent).savedIntent.action)
        val remind = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertEquals(AlarmRingingActivity::class.java.name, remind.component!!.className)
        assertTrue(remind.getBooleanExtra(AlarmRingingActivity.EXTRA_CONFIRM_REMIND, false))
        assertEquals(15, remind.getIntExtra(AlarmRingingActivity.EXTRA_REMAINING, -1))
        assertTrue(shadowOf(notification.actions[2].actionIntent).savedIntent
            .getBooleanExtra(AlarmRingingActivity.EXTRA_CONFIRM_STOP, false))
        assertEquals(listOf("Отложить", "Остановить"),
            StatusNotifier.buildLate(context, 1).actions.map { it.title.toString() })
        val reminder = StatusNotifier.buildReminders(context, System.currentTimeMillis() + 60_000L)
        assertEquals(listOf("Остановить"), reminder.actions.map { it.title.toString() })
        assertFalse(reminder.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            .contains("каждую минуту"))
    }

    @Test
    fun `stop clears the cycle but keeps the alarm, disable clears everything`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startBeforeDeparture(alarm, 20)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 3)
        assertNull(store.activeCycle())
        assertTrue(store.load().enabled)
        store.save(settings())
        store.setActiveCycle(SettingsStore.ActiveCycle(1L, 2L, true))
        store.save(settings().copy(enabled = false))
        assertNull(store.activeCycle())
        assertFalse(store.load().enabled)
    }

    @Test
    fun `stop command without confirmation cannot disable the alarm`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startDeparture(alarm)
        assertTrue(AlarmService.isRinging)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL), 0, 2)
        assertTrue(AlarmService.isRinging)
        assertTrue(store.load().enabled)
        assertTrue(store.activeCycle() != null)
    }

    @Test
    fun `reminder notification allows stopping when pre notification is disabled`() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val store = SettingsStore(context)
        val saved = settings().copy(preNotifyEnabled = false)
        store.save(saved)
        store.setActiveCycle(SettingsStore.ActiveCycle(System.currentTimeMillis(),
            System.currentTimeMillis() + 90 * 60_000L, true))
        StatusNotifier.refresh(context, saved)
        val notification = shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .getNotification(StatusNotifier.NOTIFICATION_ID)
        assertEquals("Напоминания о выходе", notification.extras.getString(Notification.EXTRA_TITLE))
        assertTrue(notification.actions.any { it.title == "Остановить" })
    }

    @Test
    fun `saved reminder mode survives service destruction without restarting music`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() + 15 * 60_000L
        val active = SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true)
        store.setActiveCycle(active)
        val first = service()
        first.onStartCommand(null, 0, 1)
        val playerField = AlarmService::class.java.getDeclaredField("player").apply { isAccessible = true }
        assertNull(playerField.get(first))
        controller?.destroy()
        controller = null
        assertEquals(active, store.activeCycle())
        val restarted = service()
        assertEquals(Service.START_STICKY, restarted.onStartCommand(null, 0, 1))
        assertEquals(listOf(SpeechText.departure(15)), deliverDepartureReminder(restarted, 15))
        assertNull(playerField.get(restarted))
    }

    @Test
    fun `spoken departure reminder is not repeated by the retry alarm`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startBeforeDeparture(alarm, 20)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        assertEquals(listOf(SpeechText.departure(15)), deliverDepartureReminder(alarm, 15))
        // Страховочный будильник через 30 секунд будит тот же живой процесс.
        assertEquals(listOf(SpeechText.departure(15)), deliverDepartureReminder(alarm, 15))
        assertEquals(setOf(15), store.spokenDepartureReminders(store.activeCycle()!!.leaveMillis))
    }

    @Test
    fun `spoken departure reminder survives service recreation`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() + 15 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, true))
        val first = service()
        assertEquals(listOf(SpeechText.departure(15)), deliverDepartureReminder(first, 15))
        controller?.destroy()
        controller = null
        val restarted = service()
        restarted.onStartCommand(null, 0, 1)
        assertEquals(listOf(SpeechText.departure(15)), deliverDepartureReminder(restarted, 15))
    }

    @Test
    fun `announced reminder keeps a foreground standby instead of stopping the service`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startBeforeDeparture(alarm, 20)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_REMIND_ONLY), 0, 2)
        assertEquals(listOf(SpeechText.departure(15)), deliverDepartureReminder(alarm, 15))
        assertFalse(AlarmService.isRinging)
        assertFalse(shadowOf(alarm).isStoppedBySelf)
        assertFalse(shadowOf(alarm).isForegroundStopped)
        assertEquals(StatusNotifier.NOTIFICATION_ID, shadowOf(alarm).lastForegroundNotificationId)
    }

    @Test
    fun `departure reminder becomes foreground before any settings are applied`() {
        val alarm = service()
        val leave = System.currentTimeMillis() + 15 * 60_000L
        assertEquals(Service.START_NOT_STICKY, alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_DEPARTURE_REMINDER)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave)
            .putExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, 15)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, System.currentTimeMillis()), 0, 1))
        assertEquals(StatusNotifier.NOTIFICATION_ID, shadowOf(alarm).lastForegroundNotificationId)
        assertTrue(shadowOf(alarm).isStoppedBySelf)
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun `editing departure time preserves reminder mode and moves its cycle`() {
        val store = SettingsStore(context)
        store.save(settings())
        val now = java.time.LocalDateTime.now()
        val wake = now.minusMinutes(30)
        val leave = now.plusHours(1)
        store.setActiveCycle(SettingsStore.ActiveCycle(SchedulePlanner.toMillis(wake),
            SchedulePlanner.toMillis(leave), true))
        val changed = settings().copy(wakeHour = wake.hour, wakeMinute = wake.minute,
            leaveHour = leave.plusMinutes(30).hour, leaveMinute = leave.plusMinutes(30).minute)
        store.save(changed)
        val expectedWake = wake.toLocalDate().atTime(SchedulePlanner.wakeTime(changed))
        assertEquals(SettingsStore.ActiveCycle(SchedulePlanner.toMillis(expectedWake),
            SchedulePlanner.toMillis(SchedulePlanner.leaveFor(expectedWake, changed)), true), store.activeCycle())
    }

    @Test
    fun `editing the ringing alarm moves its active cycle to the new time`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = store.alarm(1L)!!.copy(
            wakeHour = 7, wakeMinute = 0, leaveHour = 8, leaveMinute = 30, enabled = true,
        )
        store.updateAlarm(alarm)
        val date = java.time.LocalDate.now()
        val wake = date.atTime(7, 0)
        store.setActiveCycle(SettingsStore.ActiveCycle(
            SchedulePlanner.toMillis(wake), SchedulePlanner.toMillis(date.atTime(8, 30)), alarmId = 1,
        ))
        store.updateAlarm(alarm.copy(leaveHour = 9, leaveMinute = 15))
        assertEquals(
            SettingsStore.ActiveCycle(
                SchedulePlanner.toMillis(wake),
                SchedulePlanner.toMillis(date.atTime(9, 15)),
                alarmId = 1,
            ),
            store.activeCycle(),
        )
    }

    @Test
    fun `stale recovery after confirmed stop cannot restart reminders`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        val leave = startDeparture(alarm)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 2)
        val stale = Intent(AlarmScheduler.ACTION_LATE)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, System.currentTimeMillis())
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, leave - 3_600_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave)
        AlarmReceiver().onReceive(context, stale)
        assertNull(store.activeCycle())
        assertFalse(AlarmService.isRinging)
        store.save(settings())
        AlarmReceiver().onReceive(context, stale)
        assertNull(store.activeCycle())
        assertFalse(AlarmService.isRinging)
    }

    @Test
    fun `ordinary departure still rings before its first late reminder`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis()
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 3_600_000L, leave))
        val alarm = service()
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_TRIGGER)
            .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, leave)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, leave - 3_600_000L)
            .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leave), 0, 1)
        val ringJob = AlarmService::class.java.getDeclaredField("ringJob").apply { isAccessible = true }
        assertTrue(ringJob.get(alarm) != null)
        assertEquals(StatusNotifier.RING_NOTIFICATION_ID, shadowOf(alarm).lastForegroundNotificationId)
        assertEquals(3, shadowOf(alarm).lastForegroundNotification.actions.size)
    }

    @Test
    fun `battery exemption requests this application instead of a filtered app list`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val method = MainActivity::class.java.getDeclaredMethod("askBatteryExemption")
                .apply { isAccessible = true }
            method.invoke(activity.get())
            val intent = shadowOf(activity.get()).nextStartedActivity
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
            assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
            assertEquals("На выход", context.getString(R.string.app_name))
            assertEquals("На выход", context.applicationInfo.loadLabel(context.packageManager).toString())
            for (activityClass in listOf(MainActivity::class.java, AlarmRingingActivity::class.java)) {
                val info = context.packageManager.getActivityInfo(
                    android.content.ComponentName(context, activityClass), 0,
                )
                assertEquals("На выход", info.loadLabel(context.packageManager).toString())
            }
            shadowOf(context.getSystemService(PowerManager::class.java))
                .setIgnoringBatteryOptimizations(context.packageName, true)
            method.invoke(activity.get())
            assertNull(shadowOf(activity.get()).nextStartedActivity)
        } finally {
            activity.destroy()
        }
    }

    @Test
    fun `manifest asks for notification policy access to detect do not disturb`() {
        val info = context.packageManager.getPackageInfo(
            context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS,
        )
        assertTrue(info.requestedPermissions!!.contains(
            android.Manifest.permission.ACCESS_NOTIFICATION_POLICY,
        ))
    }

    @Test
    fun `startup asks for do not disturb access once`() {
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationPolicyAccessGranted(false)
        val store = SettingsStore(context)
        // Остальные разрешения будто уже предлагались: проверяем именно DND.
        listOf(
            "notifications", "exact_alarms", "full_screen_intent",
            "turn_screen_on", "ring_channel", "battery_exemption",
        ).forEach { store.markPermissionAsked(it) }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val activity = controller.resume().get()
        try {
            val requested = shadowOf(activity).nextStartedActivity
            assertTrue(
                requested != null && (
                    requested.action == "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS" ||
                        requested.action == Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                    )
            )
            assertTrue(store.permissionAsked("dnd_access"))
            // Возврат из настроек не должен открывать тот же экран снова.
            controller.pause().resume()
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `existing settings migrate to device protected storage`() {
        context.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE).edit()
            .putInt("wake_hour", 9).putBoolean("enabled", true).commit()
        val store = SettingsStore(context)
        assertEquals(9, store.load().wakeHour)
        assertTrue(store.load().enabled)
        assertTrue(context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_settings", Context.MODE_PRIVATE).getBoolean("enabled", false))
    }

    @Test
    fun `stopping the signals keeps the alarm for the next day`() {
        val store = SettingsStore(context)
        store.save(settings())
        val now = System.currentTimeMillis()
        val leave = now + 60 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, alarmId = 1))
        AlarmScheduler.reschedule(context, store.load())
        service().onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 1)
        assertTrue(SettingsStore(context).load().enabled)
        assertNull(store.activeCycle())
        AlarmScheduler.reschedule(context, store.load())
        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val triggers = manager.scheduledAlarms.filter {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_TRIGGER &&
                !saved.getBooleanExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, false)
        }
        assertTrue(triggers.isNotEmpty())
        // Сегодняшний цикл остановлен, поэтому все сигналы — после старого выхода.
        assertTrue(triggers.all { it.triggerAtMs > leave })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `locked boot restores the saved alarm without unlocking`() {
        SettingsStore(context).save(settings())
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertTrue(SettingsStore(context).load().enabled)
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertTrue(manager.scheduledAlarms.any {
            shadowOf(it.operation).savedIntent.action == AlarmScheduler.ACTION_TRIGGER
        })
    }

    @Test
    @Suppress("DEPRECATION")
    fun `evening wake on the same day is scheduled after reschedule`() {
        val store = SettingsStore(context)
        store.save(settings())
        val evening = store.load().withWakeTime(18, 0)
        store.save(evening)
        assertTrue(store.load().enabled)
        store.save(evening.copy(enabled = true))
        val date = java.time.LocalDate.now()
        AlarmScheduler.reschedule(context, store.load(), SchedulePlanner.toMillis(date.atTime(9, 0)))
        val manager = shadowOf(context.getSystemService(AlarmManager::class.java))
        val trigger = manager.scheduledAlarms.single {
            val saved = shadowOf(it.operation).savedIntent
            saved.action == AlarmScheduler.ACTION_TRIGGER &&
                !saved.getBooleanExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, false)
        }
        assertEquals(SchedulePlanner.toMillis(date.atTime(18, 0)), trigger.triggerAtMs)
        assertEquals("19:30", store.load().leaveLabel)
        assertTrue(store.load().enabled)
        assertNull(store.activeCycle())
    }

    @Test
    fun `changing only wake minutes saves departure ninety minutes later`() {
        val store = SettingsStore(context)
        val original = settings().copy(intervalMinutes = 10, leaveHour = 12, leaveMinute = 15)
        store.save(original)
        store.save(store.load().withWakeTime(7, 45))
        assertEquals(original.copy(wakeMinute = 45, leaveHour = 9, leaveMinute = 15), store.load())
    }

    @Test
    fun `automatic departure wraps past midnight`() {
        val store = SettingsStore(context)
        for ((hour, minute, expected) in listOf(
            Triple(22, 30, "00:00"),
            Triple(23, 0, "00:30"),
            Triple(23, 50, "01:20"),
        )) {
            store.save(store.load().withWakeTime(hour, minute))
            assertEquals(expected, store.load().leaveLabel)
            assertEquals(AlarmSettings.formatTime(hour, minute), store.load().wakeLabel)
        }
    }

    @Test
    fun `manual departure survives unchanged wake and unrelated edits`() {
        val store = SettingsStore(context)
        store.save(settings().withWakeTime(8, 15))
        val manual = store.load().copy(leaveHour = 12, leaveMinute = 10)
        store.save(manual)
        store.save(store.load().withWakeTime(8, 15))
        assertEquals(manual, store.load())
        store.save(store.load().copy(ringSeconds = 40))
        assertEquals("12:10", store.load().leaveLabel)
        store.save(store.load().withWakeTime(8, 30))
        assertEquals("10:00", store.load().leaveLabel)
    }

    @Test
    fun `preparation duration and departure mode persist and share the same interval`() {
        val store = SettingsStore(context)
        for (minutes in 40..150 step 5) {
            val wakeBased = settings().withPreparationMinutes(minutes).withWakeTime(23, 50)
            store.save(wakeBased)
            assertEquals(wakeBased, SettingsStore(context).load())
            val leaveBased = store.load().withDepartureTimeOnly(true).withLeaveTime(0, 15)
            store.save(leaveBased)
            assertEquals(leaveBased, SettingsStore(context).load())
            val wake = java.time.LocalTime.of(0, 15).minusMinutes(minutes.toLong())
            assertEquals(wake.hour, store.load().wakeHour)
            assertEquals(wake.minute, store.load().wakeMinute)
            assertEquals(minutes, store.load().preparationMinutes)
        }
    }

    @Test
    fun `new settings default to ninety minutes and preserve old times`() {
        val prefs = context.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("wake_hour", 9).putInt("leave_hour", 11).commit()
        val saved = SettingsStore(context).load()
        assertEquals(90, saved.preparationMinutes)
        assertFalse(saved.departureTimeOnly)
        assertEquals("09:00", saved.wakeLabel)
        assertEquals("11:00", saved.leaveLabel)
    }

    @Test
    fun `duration boundaries clamp old preferences and newly saved values`() {
        val store = SettingsStore(context)
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_settings", Context.MODE_PRIVATE)
        for ((seconds, expected) in listOf(10 to 20, 20 to 20, 40 to 40, 60 to 60, 90 to 60)) {
            prefs.edit().putInt("ring_seconds", seconds).commit()
            assertEquals(expected, store.load().ringSeconds)
            store.save(settings().copy(ringSeconds = seconds))
            assertEquals(expected, store.load().ringSeconds)
        }
        for ((minutes, expected) in listOf(30 to 40, 40 to 40, 90 to 90, 150 to 150, 160 to 150)) {
            store.save(settings().copy(preparationMinutes = minutes))
            assertEquals(expected, store.load().preparationMinutes)
        }
    }

    @Test
    fun `departure based preparation edit preserves departure date across midnight`() {
        val store = SettingsStore(context)
        val date = java.time.LocalDate.now().plusDays(1)
        val leave = date.atTime(1, 0)
        val original = settings().withDepartureTimeOnly(true)
            .withPreparationMinutes(40).withLeaveTime(1, 0)
        store.save(original)
        store.setActiveCycle(SettingsStore.ActiveCycle(
            SchedulePlanner.toMillis(leave.minusMinutes(40)), SchedulePlanner.toMillis(leave), true,
        ))
        store.save(store.load().withPreparationMinutes(150))
        val expected = SettingsStore.ActiveCycle(
            SchedulePlanner.toMillis(leave.minusMinutes(150)), SchedulePlanner.toMillis(leave), true,
        )
        assertEquals(expected, store.activeCycle())
        assertEquals("22:30", store.load().wakeLabel)
        assertEquals("01:00", store.load().leaveLabel)
        store.save(store.load().withDepartureTimeOnly(false))
        assertEquals(expected, store.activeCycle())
    }

    @Test
    fun `ring stops at the selected duration without disabling the next signal`() {
        val store = SettingsStore(context)
        val alarm = service()
        for (seconds in listOf(20, 40, 60)) {
            store.save(settings().copy(ringSeconds = seconds))
            startBeforeDeparture(alarm, 30)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds((seconds - 1).toLong()))
            assertTrue(AlarmService.isRinging)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(AlarmService.isRinging)
            assertTrue(store.load().enabled)
        }
    }

    @Test
    fun `voice lead settings preserve values from zero to ninety minutes`() {
        val store = SettingsStore(context)
        for (lead in 0..90 step 5) {
            val saved = settings().copy(speakEnabled = true, ttsLeadMinutes = lead)
            store.save(saved)
            assertEquals(saved, SettingsStore(context).load())
        }
    }

    @Test
    fun `disabling schedule clears the active cycle and stops the service`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        startDeparture(alarm)
        assertTrue(AlarmService.isRinging)
        store.save(settings().copy(enabled = false))
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_DISABLE), 0, 2)
        assertFalse(AlarmService.isRinging)
        assertTrue(shadowOf(alarm).isStoppedBySelf)
    }

    @Test
    fun `first late reminder replaces a sixty second ring with a stop window`() {
        SettingsStore(context).save(settings())
        val alarm = service()
        startDeparture(alarm)
        shadowOf(alarm).nextStartedActivity
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        val window = shadowOf(alarm).nextStartedActivity
        assertEquals(AlarmRingingActivity::class.java.name, window.component!!.className)
        assertEquals(1, window.getIntExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
        val ringJob = AlarmService::class.java.getDeclaredField("ringJob").apply { isAccessible = true }
        assertNull(ringJob.get(alarm))
        assertTrue(AlarmService.isRinging)
        assertFalse(shadowOf(alarm).isStoppedBySelf)
    }

    @Test
    fun `late window reopens next minute after silence and never after full stop`() {
        val store = SettingsStore(context)
        store.save(settings())
        val leave = System.currentTimeMillis() - 10 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave))
        val alarm = service()
        alarm.onStartCommand(null, 0, 1)
        assertEquals(10, shadowOf(alarm).nextStartedActivity
            .getIntExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
        completeSpeech()
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_SILENCE), 0, 2)
        assertTrue(AlarmService.isRinging)
        assertNull(shadowOf(alarm).lastForegroundNotification.fullScreenIntent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
        val window = shadowOf(alarm).nextStartedActivity
        assertEquals(11, window.getIntExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
        assertEquals(List(3) { SpeechText.late(11) }, completeSpeech(repeats = 3))
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3))
        assertNull(shadowOf(alarm).nextStartedActivity)
        assertNull(store.activeCycle())
        assertFalse(AlarmService.isRinging)
    }

    @Test
    fun `late alarm notification can open the stop window on the lock screen`() {
        val alarm = StatusNotifier.buildLate(context, 12, showWindow = true)
        assertEquals(StatusNotifier.CHANNEL_RINGING, alarm.channelId)
        assertTrue(alarm.fullScreenIntent != null)
        assertEquals(alarm.contentIntent, alarm.fullScreenIntent)
        val window = shadowOf(alarm.fullScreenIntent).savedIntent
        assertEquals(AlarmRingingActivity::class.java.name, window.component!!.className)
        assertEquals(12, window.getIntExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
        assertTrue(window.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        val confirm = shadowOf(alarm.actions.single { it.title == "Остановить" }.actionIntent).savedIntent
        assertTrue(confirm.getBooleanExtra(AlarmRingingActivity.EXTRA_CONFIRM_STOP, false))
        assertEquals(12, confirm.getIntExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
        assertNull(StatusNotifier.buildLate(context, 12).fullScreenIntent)
    }

    @Test
    fun `delayed departure cannot restart its ring timer after switching to late speech`() {
        SettingsStore(context).save(settings())
        val alarm = service()
        startDeparture(alarm, delayMillis = 61_000L)
        val ringJob = AlarmService::class.java.getDeclaredField("ringJob").apply { isAccessible = true }
        assertNull(ringJob.get(alarm))
        assertTrue(AlarmService.isRinging)
    }

    @Test
    fun `silencing departure preserves the late sequence`() {
        SettingsStore(context).save(settings())
        val alarm = service()
        startDeparture(alarm)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_SILENCE), 0, 2)
        assertTrue(AlarmService.isRinging)
        assertFalse(shadowOf(alarm).isStoppedBySelf)
        assertTrue(shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents
            .any { it.action == AlarmService.ACTION_RING_FINISHED })
    }

    @Test
    fun `turning off a test signal does not skip the real schedule`() {
        val store = SettingsStore(context)
        store.save(settings())
        val alarm = service()
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_TRIGGER).putExtra("extra_test", true), 0, 1)
        alarm.onStartCommand(Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_STOP_ALL)
            .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 2)
        assertFalse(AlarmService.isRinging)
    }
}
