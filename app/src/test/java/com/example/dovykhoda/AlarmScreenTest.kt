package com.example.dovykhoda

import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.example.dovykhoda.ui.theme.AlarmButtonDark
import com.example.dovykhoda.ui.theme.AlarmButtonMedium
import com.example.dovykhoda.ui.theme.AlarmButtonLight
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import com.example.dovykhoda.ui.RingingScreen
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import com.example.dovykhoda.ui.AlarmScreen
import com.example.dovykhoda.ui.theme.NaVykhodTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store: SettingsStore get() = SettingsStore(context)

    @Before
    fun resetSettings() {
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        context.getSharedPreferences("alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("alarm_settings", Context.MODE_PRIVATE).edit().clear().commit()
        store.save(AlarmSettings(enabled = true, preNotifyEnabled = false))
    }

    private fun showScreen() {
        compose.setContent {
            NaVykhodTheme {
                AlarmScreen(
                    onRequestExactAlarms = {},
                    onRequestNotifications = {},
                    onRequestBatteryExemption = {},
                    onRequestFullScreenIntent = {},
                    onRequestTurnScreenOn = {},
                    onRequestRingChannel = {},
                    onExportLogs = {},
                )
            }
        }
    }

    private fun activeCycle(minutesToLeave: Int, remindersOnly: Boolean = false) {
        val leave = System.currentTimeMillis() + minutesToLeave * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, remindersOnly))
    }

    @Test
    fun `late alarm can be stopped from main screen with confirmation without notifications`() {
        activeCycle(-10)
        val saved = store.activeCycle()
        showScreen()
        compose.onNodeWithText("Отключить будильник").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertEquals(AlarmRingingActivity::class.java.name, intent.component!!.className)
            assertTrue(intent.getBooleanExtra(AlarmRingingActivity.EXTRA_CONFIRM_STOP, false))
            assertEquals(0, intent.getIntExtra(AlarmRingingActivity.EXTRA_REMAINING, -1))
            assertEquals(saved, store.activeCycle())
            assertTrue(store.load().enabled)
            val activity = Robolectric.buildActivity(AlarmRingingActivity::class.java, intent).setup()
            try {
                assertFalse(activity.get().isFinishing)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun `confirmed stop after departure clears reminders and disables the schedule`() {
        activeCycle(-10)
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        alarm.get().onStartCommand(null, 0, 1)
        shadowOf(alarm.get()).nextStartedActivity
        try {
            showScreen()
            compose.onNodeWithText("Отключить будильник").performClick()
            var activity: org.robolectric.android.controller.ActivityController<AlarmRingingActivity>? = null
            try {
                compose.runOnIdle {
                    val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
                    activity = Robolectric.buildActivity(AlarmRingingActivity::class.java, intent).setup()
                }
                compose.onNodeWithText("Вы уверены?").assertIsDisplayed()
                compose.onNodeWithText("Да, отключить").performClick()
                compose.runOnIdle {
                    val stop = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
                    assertNotNull(stop)
                    assertEquals(AlarmService.ACTION_STOP_ALL, stop.action)
                    alarm.get().onStartCommand(stop, 0, 2)
                    assertNull(store.activeCycle())
                    assertFalse(store.load().enabled)
                    assertFalse(AlarmService.isRinging)
                }
            } finally {
                compose.runOnIdle { activity?.pause()?.stop()?.destroy() }
            }
        } finally {
            alarm.destroy()
        }
    }

    @Test
    fun `remind departure asks for confirmation before switching to reminders`() {
        activeCycle(15)
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        var activity: org.robolectric.android.controller.ActivityController<AlarmRingingActivity>? = null
        try {
            compose.runOnIdle {
                val cycle = store.activeCycle()!!
                alarm.get().onStartCommand(Intent(context, AlarmService::class.java)
                    .setAction(AlarmService.ACTION_TRIGGER)
                    .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, System.currentTimeMillis())
                    .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, cycle.wakeMillis)
                    .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, cycle.leaveMillis), 0, 1)
                val window = shadowOf(alarm.get()).nextStartedActivity
                activity = Robolectric.buildActivity(AlarmRingingActivity::class.java, window).setup()
            }
            val remindButton = "Выключить будильник, но напомнить о выходе"

            compose.onNodeWithText(remindButton).performScrollTo().performClick()
            compose.onNodeWithText("Вы уверены?").assertIsDisplayed()
            compose.onNodeWithText("Да, напомнить").assertIsDisplayed()
            compose.onNodeWithText("Да, отключить").assertDoesNotExist()
            compose.runOnIdle {
                assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
                assertFalse(activity!!.get().isFinishing)
            }

            compose.onNodeWithText("Отмена").performClick()
            compose.runOnIdle {
                assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
                assertFalse(activity!!.get().isFinishing)
            }

            compose.onNodeWithText(remindButton).performScrollTo().performClick()
            compose.onNodeWithText("Вы уверены?").assertIsDisplayed()
            compose.runOnIdle {
                // Новый сигнал перед выходом не должен закрывать вопрос.
                activity!!.newIntent(Intent(context, AlarmRingingActivity::class.java)
                    .putExtra(AlarmRingingActivity.EXTRA_REMAINING, 14)
                    .putExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
            }
            compose.onNodeWithText("Вы уверены?").assertIsDisplayed()

            compose.onNodeWithText("Да, напомнить").performClick()
            compose.runOnIdle {
                val remind = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
                assertNotNull(remind)
                assertEquals(AlarmService.ACTION_REMIND_ONLY, remind.action)
                assertTrue(activity!!.get().isFinishing)
            }
        } finally {
            compose.runOnIdle {
                activity?.pause()?.stop()?.destroy()
                alarm.destroy()
            }
        }
    }

    private fun withLateWindow(
        test: (AlarmService, org.robolectric.android.controller.ActivityController<AlarmRingingActivity>) -> Unit,
    ) {
        activeCycle(-10)
        val alarm = Robolectric.buildService(AlarmService::class.java).create()
        var activity: org.robolectric.android.controller.ActivityController<AlarmRingingActivity>? = null
        try {
            compose.runOnIdle {
                alarm.get().onStartCommand(null, 0, 1)
                val intent = shadowOf(alarm.get()).nextStartedActivity
                assertEquals(10, intent.getIntExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 0))
                activity = Robolectric.buildActivity(AlarmRingingActivity::class.java, intent).setup()
            }
            test(alarm.get(), activity!!)
        } finally {
            compose.runOnIdle {
                activity?.pause()?.stop()?.destroy()
                alarm.destroy()
            }
        }
    }

    @Test
    fun `automatic late window offers shutdown and allows cancelling without stopping reminders`() {
        withLateWindow { _, _ ->
            val saved = store.activeCycle()
            compose.onNodeWithText(SpeechText.late(10)).assertIsDisplayed()
            compose.onNodeWithText("Отключить будильник?").assertIsDisplayed()
            compose.onNodeWithText("Выключить будильник, но напомнить о выходе").assertDoesNotExist()
            compose.onNodeWithText("Отключить будильник").performScrollTo().performClick()
            compose.onNodeWithText("Вы уверены?").assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            compose.runOnIdle {
                assertEquals(saved, store.activeCycle())
                assertTrue(store.load().enabled)
                assertTrue(AlarmService.isRinging)
                assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            }
        }
    }

    @Test
    fun `automatic late window stops current reminders after explicit confirmation`() {
        withLateWindow { alarm, activity ->
            compose.onNodeWithText("Отключить будильник").performScrollTo().performClick()
            compose.onNodeWithText("Да, отключить").performClick()
            compose.runOnIdle {
                val stop = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
                assertEquals(AlarmService.ACTION_STOP_ALL, stop.action)
                alarm.onStartCommand(stop, 0, 2)
                assertTrue(activity.get().isFinishing)
                assertNull(store.activeCycle())
                assertFalse(store.load().enabled)
                assertFalse(AlarmService.isRinging)
            }
        }
    }

    @Test
    fun `next late minute updates the open window without dismissing shutdown confirmation`() {
        withLateWindow { _, activity ->
            compose.onNodeWithText("Отключить будильник").performScrollTo().performClick()
            compose.onNodeWithText("Вы уверены?").assertIsDisplayed()
            compose.runOnIdle {
                activity.newIntent(Intent(context, AlarmRingingActivity::class.java)
                    .putExtra(AlarmRingingActivity.EXTRA_REMAINING, 0)
                    .putExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, 11))
                assertFalse(activity.get().isFinishing)
            }
            compose.onNodeWithText("Вы уверены?").assertIsDisplayed()
            compose.onNodeWithText("Отмена").performClick()
            compose.onNodeWithText(SpeechText.late(11)).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `departure reminder mode also exposes stop before departure`() {
        activeCycle(15, remindersOnly = true)
        showScreen()
        compose.onNodeWithText("Отключить будильник").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertTrue(intent.getBooleanExtra(AlarmRingingActivity.EXTRA_CONFIRM_STOP, false))
            assertTrue(intent.getIntExtra(AlarmRingingActivity.EXTRA_REMAINING, -1) > 0)
        }
    }

    @Test
    fun `ordinary schedule does not display reminder stop button`() {
        activeCycle(30)
        showScreen()
        compose.onNodeWithText("Отключить будильник").assertDoesNotExist()
    }

    @Test
    fun `main screen immediately reflects shutdown performed by the service`() {
        activeCycle(-10)
        showScreen()
        compose.onNodeWithText("Отключить будильник").assertIsDisplayed()
        compose.runOnIdle {
            val alarm = Robolectric.buildService(AlarmService::class.java).create()
            try {
                alarm.get().onStartCommand(Intent(context, AlarmService::class.java)
                    .setAction(AlarmService.ACTION_STOP_ALL)
                    .putExtra(AlarmService.EXTRA_CONFIRMED_STOP, true), 0, 1)
            } finally {
                alarm.destroy()
            }
        }
        compose.onNodeWithText("Отключить будильник").assertDoesNotExist()
        compose.onNodeWithText("Будильник выключен").assertIsDisplayed()
    }

    @Test
    fun `digital wake entry focuses hours and updates departure across midnight`() {
        showScreen()
        compose.onNodeWithText("Пробуждение").performScrollTo().performClick()
        compose.onNodeWithText("Время пробуждения").assertIsDisplayed()
        compose.onNodeWithText("Часы").assertIsFocused().performTextReplacement("23")
        compose.onNodeWithText("Часы").performImeAction()
        compose.onNodeWithText("Минуты").assertIsFocused().performTextReplacement("50")
        compose.onNodeWithText("Минуты").performImeAction()
        compose.onNodeWithText("Время пробуждения").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("23:50", store.load().wakeLabel)
            assertEquals("01:20", store.load().leaveLabel)
        }
    }

    @Test
    fun `digital departure entry saves midnight minutes without changing wake`() {
        showScreen()
        compose.onNodeWithText("Выход").performScrollTo().performClick()
        compose.onNodeWithText("Время выхода").assertIsDisplayed()
        compose.onNodeWithText("Часы").performTextReplacement("0")
        compose.onNodeWithText("Минуты").performTextReplacement("5")
        compose.onNodeWithText("Готово").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("00:05", store.load().leaveLabel)
            assertEquals("07:00", store.load().wakeLabel)
        }
    }

    @Test
    fun `digital entry rejects out of range and empty values`() {
        val original = store.load()
        showScreen()
        compose.onNodeWithText("Пробуждение").performScrollTo().performClick()
        compose.onNodeWithText("Часы").performTextReplacement("24")
        compose.onNodeWithText("Готово").assertIsNotEnabled()
        compose.onNodeWithText("Часы").performTextReplacement("23")
        compose.onNodeWithText("Минуты").performTextReplacement("60")
        compose.onNodeWithText("Готово").assertIsNotEnabled()
        compose.onNodeWithText("Минуты").performTextReplacement("")
        compose.onNodeWithText("Готово").assertIsNotEnabled()
        compose.onNodeWithText("Минуты").performTextReplacement("59")
        compose.onNodeWithText("Готово").assertIsEnabled()
        compose.onNodeWithText("Отмена").performClick()
        compose.runOnIdle { assertEquals(original, store.load()) }
    }

    @Test
    fun `confirming unchanged wake preserves manual departure`() {
        store.save(store.load().copy(leaveHour = 12, leaveMinute = 10))
        showScreen()
        compose.onNodeWithText("Пробуждение").performScrollTo().performClick()
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { assertEquals("12:10", store.load().leaveLabel) }
    }

    @Test
    fun `large window uses readable line spacing for multiline button text`() {
        compose.setContent {
            NaVykhodTheme {
                RingingScreen(
                    remainingMinutes = 15,
                    wakeLabel = "07:00",
                    leaveLabel = "08:30",
                    confirmation = null,
                    onSilence = {},
                    onRequestStop = {},
                    onRequestRemindDeparture = {},
                    onConfirm = {},
                    onDismissConfirm = {},
                )
            }
        }
        val titleLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Выключить будильник, но напомнить о выходе", useUnmergedTree = true)
            .performScrollTo().performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
                it(titleLayout)
            }
        val hintLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Голос за 20, 15, 10 и 5 минут и в момент выхода", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(hintLayout) }
        assertEquals(36.sp, titleLayout.single().layoutInput.style.lineHeight)
        assertEquals(20.sp, hintLayout.single().layoutInput.style.lineHeight)
        assertEquals(27.sp, titleLayout.single().layoutInput.style.fontSize)
        assertEquals(14.sp, hintLayout.single().layoutInput.style.fontSize)
    }

    @Test
    fun `departure time mode disables wake editor and recalculates wake from digital input`() {
        showScreen()
        compose.onNodeWithTag("departure_time_only").performClick().assertIsOn()
        compose.onNodeWithText("Пробуждение").assertIsDisplayed()
        compose.onNodeWithTag("wake_time").assertIsNotEnabled()
        compose.onNodeWithText("06:30").assertIsDisplayed()
        compose.onNodeWithTag("leave_time").assertIsEnabled()
        compose.onNodeWithText("Выход").performScrollTo().performClick()
        compose.onNodeWithText("Часы").performTextReplacement("0")
        compose.onNodeWithText("Минуты").performTextReplacement("30")
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle {
            assertTrue(store.load().departureTimeOnly)
            assertEquals("00:30", store.load().leaveLabel)
            assertEquals("23:00", store.load().wakeLabel)
        }
        compose.onNodeWithTag("departure_time_only").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithText("Пробуждение").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertFalse(store.load().departureTimeOnly)
            assertEquals("00:30", store.load().leaveLabel)
            assertEquals("23:00", store.load().wakeLabel)
        }
    }

    @Test
    fun `preparation slider has forty to one hundred fifty minute bounds and keeps selected anchor`() {
        showScreen()
        val slider = compose.onNodeWithTag("preparation_minutes").performScrollTo()
        val info = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(40f..150f, info.range)
        assertEquals(21, info.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(40f) }
        compose.runOnIdle {
            assertEquals(40, store.load().preparationMinutes)
            assertEquals("07:00", store.load().wakeLabel)
            assertEquals("07:40", store.load().leaveLabel)
        }
        compose.onNodeWithTag("departure_time_only").performScrollTo().performClick()
        slider.performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(150f) }
        compose.runOnIdle {
            assertEquals(150, store.load().preparationMinutes)
            assertEquals("07:40", store.load().leaveLabel)
            assertEquals("05:10", store.load().wakeLabel)
        }
        compose.onNodeWithText("Время на сборы: 2 часа 30 минут").assertIsDisplayed()
    }

    @Test
    fun `ring duration slider offers twenty to sixty seconds and saves endpoint values`() {
        showScreen()
        val slider = compose.onNodeWithTag("ring_seconds").performScrollTo()
        val info = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(20f..60f, info.range)
        assertEquals(3, info.steps)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(20f) }
        compose.runOnIdle { assertEquals(20, store.load().ringSeconds) }
        compose.onNodeWithText("20 секунд музыки на каждый сигнал").assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        compose.runOnIdle { assertEquals(60, store.load().ringSeconds) }
        compose.onNodeWithText("60 секунд музыки на каждый сигнал").assertIsDisplayed()
    }

    @Test
    fun `alarm buttons use ordered warm shades with contrasting text in both themes`() {
        assertEquals(Color(0xFF3B1B00), AlarmButtonDark)
        assertEquals(Color(0xFFB45309), AlarmButtonMedium)
        assertEquals(Color(0xFFFFE3C4), AlarmButtonLight)
        assertTrue(AlarmButtonDark.luminance() < AlarmButtonMedium.luminance())
        assertTrue(AlarmButtonMedium.luminance() < AlarmButtonLight.luminance())
        for ((background, foreground) in listOf(
            AlarmButtonDark to AlarmButtonLight,
            AlarmButtonMedium to Color.White,
            AlarmButtonLight to AlarmButtonDark,
        )) {
            val ratio = (maxOf(background.luminance(), foreground.luminance()) + 0.05f) /
                (minOf(background.luminance(), foreground.luminance()) + 0.05f)
            assertTrue("Contrast ratio must be at least 4.5: $ratio", ratio >= 4.5f)
        }
        val darkTheme = mutableStateOf(false)
        compose.setContent {
            NaVykhodTheme(darkTheme = darkTheme.value) {
                RingingScreen(
                    remainingMinutes = 15, wakeLabel = "07:00", leaveLabel = "08:30",
                    confirmation = null, onSilence = {}, onRequestStop = {},
                    onRequestRemindDeparture = {}, onConfirm = {}, onDismissConfirm = {},
                )
            }
        }
        for (dark in listOf(false, true)) {
            compose.runOnIdle { darkTheme.value = dark }
            for ((title, foreground) in listOf(
                "Тишина" to AlarmButtonDark,
                "Выключить будильник, но напомнить о выходе" to Color.White,
                "Отключить будильник" to AlarmButtonLight,
            )) {
                val layout = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertEquals(foreground, layout.single().layoutInput.style.color)
            }
        }
    }

    @Test
    fun `main screen starts with short alarm state and time tiles above time settings`() {
        showScreen()
        compose.onNodeWithText("На выход").assertDoesNotExist()
        compose.onNodeWithText("Будильник, который доведёт до двери").assertDoesNotExist()
        compose.onNodeWithText("Расписание").assertDoesNotExist()
        compose.onNodeWithText("Включено: сигналы каждые 5 мин").assertDoesNotExist()
        compose.onNodeWithText("Будильник включен").assertIsDisplayed()
        val wakeBounds = compose.onNodeWithTag("wake_time").fetchSemanticsNode().boundsInRoot
        val leaveBounds = compose.onNodeWithTag("leave_time").fetchSemanticsNode().boundsInRoot
        val settingsBounds = compose.onNodeWithText("Настройка времени").fetchSemanticsNode().boundsInRoot
        assertEquals(wakeBounds.top, leaveBounds.top)
        assertTrue(wakeBounds.bottom < settingsBounds.top)
        compose.onNodeWithTag("alarm_enabled").performClick().assertIsOn()
        compose.onNodeWithText("Выключить будильник?").assertIsDisplayed()
        compose.onNodeWithText("Выключить").performClick()
        compose.onNodeWithText("Будильник выключен").assertIsDisplayed()
        compose.runOnIdle { assertFalse(store.load().enabled) }
        compose.onNodeWithTag("alarm_enabled").performClick().assertIsOn()
        compose.onNodeWithText("Будильник включен").assertIsDisplayed()
        compose.runOnIdle { assertTrue(store.load().enabled) }
    }

    @Test
    fun `cancelling the disable dialog keeps the alarm on`() {
        showScreen()
        compose.onNodeWithTag("alarm_enabled").performClick().assertIsOn()
        compose.onNodeWithText("Выключить будильник?").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Выключить будильник?").assertDoesNotExist()
        compose.onNodeWithTag("alarm_enabled").assertIsOn()
        compose.onNodeWithText("Будильник включен").assertIsDisplayed()
        compose.runOnIdle { assertTrue(store.load().enabled) }
    }

    @Test
    fun `disabled schedule greys out and locks settings until enabled again`() {
        store.save(store.load().copy(enabled = false))
        showScreen()
        compose.onNodeWithText("Будильник выключен").assertIsDisplayed()
        compose.onNodeWithTag("wake_time").assertIsNotEnabled()
        compose.onNodeWithTag("leave_time").assertIsNotEnabled()
        compose.onNodeWithTag("departure_time_only").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("preparation_minutes").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("ring_seconds").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("time_signals").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("time_departure").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("time_late").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("alarm_enabled").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("wake_time").assertIsEnabled()
        compose.onNodeWithTag("ring_seconds").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertTrue(store.load().enabled) }
    }

    @Test
    fun `automatic wake time stays visible and gray and cannot be opened by touch`() {
        store.save(store.load().withDepartureTimeOnly(true))
        showScreen()
        val wake = compose.onNodeWithTag("wake_time").assertIsDisplayed().assertIsNotEnabled()
        wake.performTouchInput { click() }
        compose.onNodeWithText("Время пробуждения").assertDoesNotExist()
        compose.onNodeWithText("Пробуждение").assertIsDisplayed()
        compose.onNodeWithText("Выход").assertIsDisplayed()
        val labelLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Пробуждение", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(labelLayout) }
        val timeLayout = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("06:30", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(timeLayout) }
        assertEquals(Color.Gray, labelLayout.single().layoutInput.style.color)
        assertEquals(Color.Gray, timeLayout.single().layoutInput.style.color)
        compose.onNodeWithTag("departure_time_only").performClick().assertIsOff()
        wake.assertIsEnabled().performClick()
        compose.onNodeWithText("Время пробуждения").assertIsDisplayed()
    }

    @Test
    fun `preparation interval changes its label and formats whole hours and minutes`() {
        showScreen()
        val slider = compose.onNodeWithTag("preparation_minutes").performScrollTo()
        for ((minutes, duration) in listOf(
            50 to "50 минут", 55 to "55 минут", 60 to "1 час", 65 to "1 час 5 минут",
            90 to "1 час 30 минут", 120 to "2 часа", 125 to "2 часа 5 минут", 150 to "2 часа 30 минут",
        )) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(minutes.toFloat()) }
            compose.onNodeWithText("До выхода: $duration").assertIsDisplayed()
        }
        compose.onNodeWithText("При смене пробуждения выход = пробуждение + время на сборы")
            .assertDoesNotExist()
        compose.onNodeWithTag("departure_time_only").performClick()
        compose.onNodeWithText("Начало будильника = время выхода − время на сборы").assertDoesNotExist()
        for ((minutes, duration) in listOf(50 to "50 минут", 60 to "1 час", 90 to "1 час 30 минут")) {
            slider.performSemanticsAction(SemanticsActions.SetProgress) { it(minutes.toFloat()) }
            compose.onNodeWithText("Время на сборы: $duration").assertIsDisplayed()
        }
    }

    @Test
    fun `time announcements are toggled for each entity independently`() {
        showScreen()
        val signals = compose.onNodeWithTag("time_signals").performScrollTo().assertIsOn()
        val departure = compose.onNodeWithTag("time_departure").assertIsOn()
        val late = compose.onNodeWithTag("time_late").assertIsOn()

        departure.performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            assertTrue(store.load().announceTimeOnSignals)
            assertFalse(store.load().announceTimeOnDeparture)
            assertTrue(store.load().announceTimeOnLate)
        }

        late.performScrollTo().performClick().assertIsOff()
        signals.performScrollTo().performClick().assertIsOff()
        compose.runOnIdle {
            assertFalse(store.load().announceTimeOnSignals)
            assertFalse(store.load().announceTimeOnDeparture)
            assertFalse(store.load().announceTimeOnLate)
        }

        signals.performScrollTo().performClick().assertIsOn()
        compose.runOnIdle {
            assertTrue(store.load().announceTimeOnSignals)
            assertFalse(store.load().announceTimeOnLate)
        }
    }

    @Test
    fun `disabled schedule does not display reminder stop button`() {
        store.save(store.load().copy(enabled = false))
        showScreen()
        compose.onNodeWithText("Отключить будильник").assertDoesNotExist()
    }
}
