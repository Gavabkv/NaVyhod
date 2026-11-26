package com.example.dovykhoda

import android.content.Context
import android.os.UserManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.sp
import com.example.dovykhoda.ui.AppScreen
import com.example.dovykhoda.ui.RingingScreen
import com.example.dovykhoda.ui.theme.AlarmButtonDark
import com.example.dovykhoda.ui.theme.AlarmButtonLight
import com.example.dovykhoda.ui.theme.AlarmButtonMedium
import com.example.dovykhoda.ui.theme.NaVykhodTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ru")
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

    private fun showScreen(
        darkTheme: Boolean = false,
        onDarkThemeChange: (Boolean) -> Unit = {},
        onLanguageChange: (String?) -> Unit = {},
    ) {
        compose.setContent {
            NaVykhodTheme(darkTheme = darkTheme) {
                AppScreen(
                    onRequestExactAlarms = {},
                    onRequestNotifications = {},
                    onRequestBatteryExemption = {},
                    onRequestFullScreenIntent = {},
                    onRequestTurnScreenOn = {},
                    onRequestRingChannel = {},
                    onRequestDndAccess = {},
                    onExportLogs = {},
                    darkTheme = darkTheme,
                    onDarkThemeChange = onDarkThemeChange,
                    onLanguageChange = onLanguageChange,
                )
            }
        }
    }

    private fun openEditor() {
        compose.onNodeWithTag("alarm_card_1").performClick()
        compose.onNodeWithTag("editor_enabled").assertIsDisplayed()
    }

    private fun openTimeDialog(tag: String = "editor_wake_time") {
        compose.onNodeWithTag(tag).performClick()
    }

    @Test
    fun `two tabs start on alarms and settings tab shows knobs`() {
        showScreen()
        compose.onNodeWithTag("alarms_title").assertIsDisplayed()
        compose.onNodeWithTag("alarm_card_1").assertIsDisplayed()
        compose.onNodeWithText("07:00").assertIsDisplayed()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("settings_title").assertIsDisplayed()
        compose.onNodeWithTag("interval_chip_5").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("pre_notify").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("tab_alarms").performClick()
        compose.onNodeWithTag("alarm_card_1").assertIsDisplayed()
    }

    @Test
    fun `settings tab announces when the nearest alarm will fire`() {
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Ближайший будильник включится", substring = true)
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `alarm card shows departure and days`() {
        showScreen()
        compose.onNodeWithText("Выход в 08:00 · Каждый день").assertIsDisplayed()
    }

    @Test
    fun `switch disables only its own alarm`() {
        store.addAlarm(AlarmItem(
            id = 2, wakeHour = 9, wakeMinute = 30, leaveHour = 11, leaveMinute = 0, enabled = true,
        ))
        showScreen()
        compose.onNodeWithTag("alarm_switch_2").performClick()
        compose.runOnIdle {
            assertEquals(false, store.alarm(2L)?.enabled)
            assertEquals(true, store.alarm(1L)?.enabled)
        }
    }

    @Test
    fun `adding an alarm opens editor and returns to a two card list`() {
        showScreen()
        compose.onNodeWithTag("add_alarm").performClick()
        compose.onNodeWithTag("editor_enabled").assertIsDisplayed()
        compose.onNodeWithTag("editor_back").performClick()
        compose.onNodeWithTag("alarm_card_1").assertIsDisplayed()
        compose.onNodeWithTag("alarm_card_2").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(2, store.alarms().size)
            assertEquals(AlarmItem.ALL_DAYS, store.alarm(2L)?.days)
        }
    }

    @Test
    fun `editor day preset replaces the day set`() {
        store.updateAlarm(store.alarm(1L)!!.copy(days = setOf(DayOfWeek.MONDAY)))
        showScreen()
        openEditor()
        compose.onNodeWithTag("day_chip_MONDAY").assertIsSelected()
        compose.onNodeWithTag("day_preset_weekend").performScrollTo().assertIsEnabled()
            .assertIsNotSelected()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(AlarmItem.WEEKEND, store.alarm(1L)?.days) }
        compose.onNodeWithTag("day_preset_weekend").assertIsSelected()
        compose.onNodeWithTag("day_chip_MONDAY").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(AlarmItem.WEEKEND + DayOfWeek.MONDAY, store.alarm(1L)?.days)
        }
    }

    @Test
    fun `editor day chip adds one day to the schedule`() {
        store.updateAlarm(store.alarm(1L)!!.copy(days = setOf(DayOfWeek.MONDAY)))
        showScreen()
        openEditor()
        compose.onNodeWithTag("day_chip_TUESDAY").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {
            assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), store.alarm(1L)?.days)
        }
    }

    @Test
    fun `editor keeps the last remaining day selected`() {
        store.updateAlarm(store.alarm(1L)!!.copy(days = setOf(DayOfWeek.MONDAY)))
        showScreen()
        openEditor()
        compose.onNodeWithTag("day_chip_MONDAY").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(setOf(DayOfWeek.MONDAY), store.alarm(1L)?.days) }
    }

    @Test
    fun `turning the alarm off disables its time editing`() {
        showScreen()
        openEditor()
        compose.onNodeWithTag("editor_wake_time").assertIsEnabled()
        compose.onNodeWithTag("editor_enabled").performClick()
        compose.runOnIdle { assertEquals(false, store.alarm(1L)?.enabled) }
        compose.onNodeWithTag("editor_wake_time").assertIsNotEnabled()
        compose.onNodeWithTag("editor_leave_time").assertIsNotEnabled()
        compose.onNodeWithTag("editor_enabled").performClick()
        compose.onNodeWithTag("editor_wake_time").assertIsEnabled()
    }

    @Test
    fun `departure time mode locks wake time in the editor`() {
        showScreen()
        openEditor()
        compose.onNodeWithTag("editor_departure_only").performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("editor_wake_time").assertIsNotEnabled()
        compose.onNodeWithTag("editor_leave_time").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(true, store.alarm(1L)?.departureTimeOnly)
            assertEquals("06:30", store.alarm(1L)?.wakeLabel)
        }
    }

    @Test
    fun `time is typed with the built-in keypad`() {
        showScreen()
        openEditor()
        openTimeDialog()
        for (digit in listOf("0", "9", "4", "5")) {
            compose.onNodeWithTag("time_digit_$digit").performClick()
        }
        compose.onNodeWithTag("time_confirm").performClick()
        compose.runOnIdle {
            assertEquals("09:45", store.alarm(1L)?.wakeLabel)
            assertEquals("11:15", store.alarm(1L)?.leaveLabel)
        }
    }

    @Test
    fun `keypad blocks digits that would make the time invalid`() {
        showScreen()
        openEditor()
        openTimeDialog()
        // Часы начинаются только с 0, 1 или 2.
        compose.onNodeWithTag("time_digit_3").assertIsNotEnabled()
        compose.onNodeWithTag("time_digit_2").assertIsEnabled()
        compose.onNodeWithTag("time_digit_2").performClick()
        // После «2» допустимы только 0–3.
        compose.onNodeWithTag("time_digit_4").assertIsNotEnabled()
        compose.onNodeWithTag("time_digit_3").performClick()
        // Минуты начинаются только с 0–5.
        compose.onNodeWithTag("time_digit_6").assertIsNotEnabled()
        compose.onNodeWithTag("time_digit_5").performClick()
        compose.onNodeWithTag("time_digit_9").assertIsEnabled()
        compose.onNodeWithTag("time_digit_9").performClick()
        compose.onNodeWithTag("time_confirm").performClick()
        compose.runOnIdle {
            assertEquals("23:59", store.alarm(1L)?.wakeLabel)
            assertEquals("01:29", store.alarm(1L)?.leaveLabel)
        }
    }

    @Test
    fun `two hour digits move to minutes and the next digit replaces the old value`() {
        showScreen()
        openEditor()
        openTimeDialog()
        compose.onNodeWithTag("time_digit_0").performClick()
        compose.onNodeWithTag("time_digit_9").performClick()
        // Часы заполнены — ввод перешёл к минутам, первая цифра заменяет «00».
        compose.onNodeWithTag("time_digit_3").performClick()
        compose.onNodeWithTag("time_digit_0").performClick()
        compose.onNodeWithTag("time_confirm").performClick()
        compose.runOnIdle { assertEquals("09:30", store.alarm(1L)?.wakeLabel) }
    }

    @Test
    fun `tapping the hours box switches input back and replaces the hours`() {
        showScreen()
        openEditor()
        openTimeDialog()
        for (digit in listOf("0", "9", "3", "0")) {
            compose.onNodeWithTag("time_digit_$digit").performClick()
        }
        compose.onNodeWithTag("time_hour_input").performClick()
        compose.onNodeWithTag("time_digit_1").performClick()
        compose.onNodeWithTag("time_digit_0").performClick()
        compose.onNodeWithTag("time_confirm").performClick()
        compose.runOnIdle {
            assertEquals("10:30", store.alarm(1L)?.wakeLabel)
            assertEquals("12:00", store.alarm(1L)?.leaveLabel)
        }
    }

    @Test
    fun `backspace clears digits one by one`() {
        showScreen()
        openEditor()
        openTimeDialog()
        compose.onNodeWithTag("time_backspace").performClick()
        compose.onNodeWithTag("time_digit_1").performClick()
        compose.onNodeWithTag("time_backspace").performClick()
        compose.onNodeWithTag("time_confirm").assertIsNotEnabled()
    }

    @Test
    fun `deleting an alarm from the editor empties the list`() {
        showScreen()
        openEditor()
        compose.onNodeWithTag("delete_alarm").performScrollTo().performClick()
        compose.onNodeWithText("Удалить", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertTrue(store.alarms().isEmpty()) }
        compose.onNodeWithText("Пока нет будильников. Нажмите «Добавить», чтобы создать первый.")
            .assertIsDisplayed()
    }

    @Test
    fun `interval chip saves without changing alarm times`() {
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("interval_chip_10").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(10, store.load().intervalMinutes)
            assertEquals(true, store.alarm(1L)?.enabled)
            assertEquals("07:00", store.alarm(1L)?.wakeLabel)
        }
    }

    @Test
    fun `voice and pre notify toggles persist from settings tab`() {
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("pre_notify").performScrollTo().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(store.load().preNotifyEnabled) }
        compose.onNodeWithTag("time_departure").performScrollTo().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(store.load().announceTimeOnDeparture) }
    }

    @Test
    fun `settings does not list granted permissions`() {
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Разрешено").assertDoesNotExist()
    }

    @Test
    fun `settings theme switch reports the choice`() {
        var choice: Boolean? = null
        showScreen(onDarkThemeChange = { choice = it })
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("theme_dark").performScrollTo().assertIsOff().performClick()
        compose.runOnIdle { assertEquals(true, choice) }
    }

    @Test
    fun `language card sits between theme and diagnostics`() {
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("theme_dark").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("language_picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Системный").assertIsDisplayed()
        compose.onNodeWithText("Отправить логи").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `language picker reports the chosen language`() {
        var choice: String? = "unset"
        showScreen(onLanguageChange = { choice = it })
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("language_picker").performScrollTo().performClick()
        compose.onNodeWithTag("language_option_system").assertIsDisplayed()
        compose.onNodeWithTag("language_option_de").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("de", choice) }
    }

    @Test
    fun `language picker lists all supported languages`() {
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithTag("language_picker").performScrollTo().performClick()
        for (tag in listOf("de", "en", "es", "fr", "it", "ja", "pt", "ru")) {
            compose.onNodeWithTag("language_option_$tag").performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun `reminders banner offers a confirmed stop of the running cycle`() {
        val leave = System.currentTimeMillis() + 10 * 60_000L
        store.setActiveCycle(SettingsStore.ActiveCycle(leave - 90 * 60_000L, leave, remindersOnly = true))
        showScreen()
        compose.onNodeWithText("Идут напоминания о выходе").assertIsDisplayed()
        compose.onNodeWithTag("stop_cycle").performClick()
        compose.onNodeWithText("Да, остановить").performClick()
        val started = shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertNotNull(started)
        assertEquals(AlarmService.ACTION_STOP_ALL, started?.action)
        assertTrue(started!!.getBooleanExtra(AlarmService.EXTRA_CONFIRMED_STOP, false))
    }

    @Test
    fun `empty list asks to add the first alarm`() {
        store.deleteAlarm(1L)
        showScreen()
        compose.onNodeWithText("Пока нет будильников. Нажмите «Добавить», чтобы создать первый.")
            .assertIsDisplayed()
        compose.onNodeWithTag("add_alarm").performClick()
        compose.onNodeWithTag("editor_enabled").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, store.alarms().size)
            assertEquals(AlarmItem.ALL_DAYS, store.alarms().single().days)
        }
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
        compose.onNodeWithText("Остановить музыку, но напомнить о выходе", useUnmergedTree = true)
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
                "Остановить музыку, но напомнить о выходе" to Color.White,
                "Остановить сигналы" to AlarmButtonLight,
            )) {
                val layout = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(title, useUnmergedTree = true).performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
                assertEquals(foreground, layout.single().layoutInput.style.color)
            }
        }
    }

    @Test
    fun `muted alarm volume is reported under permissions with a settings shortcut`() {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio.setStreamVolume(android.media.AudioManager.STREAM_ALARM, 0, 0)
        showScreen()
        compose.onNodeWithTag("tab_settings").performClick()
        compose.onNodeWithText("Звук будильника выключен — сигнала не будет слышно")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("alarm_volume_muted").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertEquals(android.provider.Settings.ACTION_SOUND_SETTINGS, intent?.action)
        }
    }
}
