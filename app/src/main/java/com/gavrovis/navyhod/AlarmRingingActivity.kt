package com.gavrovis.navyhod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.gavrovis.navyhod.ui.AlarmConfirmation
import com.gavrovis.navyhod.ui.RingingScreen
import com.gavrovis.navyhod.ui.theme.NaVykhodTheme
import java.time.LocalDateTime

/**
 * Всплывающее окно сигнала. Открывается full-screen intent'ом уведомления,
 * показывается поверх блокировки и содержит крупные кнопки.
 */
class AlarmRingingActivity : ComponentActivity() {

    private var confirmation by mutableStateOf<AlarmConfirmation?>(null)
    private var remainingMinutes by mutableIntStateOf(-1)
    private var lateMinutes by mutableIntStateOf(0)
    private var receiverRegistered = false

    private val ringFinishedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishAndRemoveTask()
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlarmLog.event(
            "ring_screen_create",
            "remaining" to intent?.getIntExtra(EXTRA_REMAINING, -1),
            "late" to intent?.getIntExtra(EXTRA_LATE_MINUTES, 0),
            "confirmStop" to intent?.getBooleanExtra(EXTRA_CONFIRM_STOP, false),
            "confirmRemind" to intent?.getBooleanExtra(EXTRA_CONFIRM_REMIND, false),
            "ringing" to AlarmService.isRinging,
            "recreated" to (savedInstanceState != null),
            "locked" to {
                getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked
            }(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        readIntent(intent)
        if (savedInstanceState != null) {
            remainingMinutes = savedInstanceState.getInt(EXTRA_REMAINING, remainingMinutes)
            lateMinutes = savedInstanceState.getInt(EXTRA_LATE_MINUTES, lateMinutes)
            // Не теряем вопрос из нового интента (например, кнопку «Выключить» в шторке),
            // если в сохранённом состоянии его не было.
            savedInstanceState.getString(STATE_CONFIRMATION)
                ?.let { name -> runCatching { AlarmConfirmation.valueOf(name) }.getOrNull() }
                ?.takeIf { it != AlarmConfirmation.RemindDeparture || lateMinutes <= 0 }
                ?.let { confirmation = it }
        }

        setContent {
            NaVykhodTheme(darkTheme = true) {
                val settings = SettingsStore(this).load()
                val shownRemaining = if (remainingMinutes >= 0) {
                    remainingMinutes
                } else {
                    SchedulePlanner.remainingMinutesNow(LocalDateTime.now(), settings)
                }
                RingingScreen(
                    remainingMinutes = shownRemaining,
                    lateMinutes = lateMinutes,
                    wakeLabel = settings.wakeLabel,
                    leaveLabel = settings.leaveLabel,
                    confirmation = confirmation,
                    onSilence = {
                        AlarmLog.event("ring_screen_silence")
                        AlarmService.silence(this)
                        finishAndRemoveTask()
                    },
                    onRequestStop = {
                        AlarmLog.event("ring_screen_confirm_stop")
                        confirmation = AlarmConfirmation.Stop
                    },
                    onRequestRemindDeparture = {
                        AlarmLog.event("ring_screen_confirm_remind")
                        confirmation = AlarmConfirmation.RemindDeparture
                    },
                    onConfirm = { action ->
                        AlarmLog.event("ring_screen_confirm", "action" to action.name)
                        confirmation = null
                        when (action) {
                            AlarmConfirmation.Stop -> AlarmService.stopAlarm(this)
                            AlarmConfirmation.RemindDeparture -> AlarmService.remindDeparture(this)
                        }
                        finishAndRemoveTask()
                    },
                    onDismissConfirm = { confirmation = null },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_CONFIRMATION, confirmation?.name)
        outState.putInt(EXTRA_REMAINING, remainingMinutes)
        outState.putInt(EXTRA_LATE_MINUTES, lateMinutes)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AlarmLog.event("ring_screen_start", "ringing" to AlarmService.isRinging, "confirmation" to confirmation?.name)
        if (!AlarmService.isRinging && confirmation == null) {
            AlarmLog.event("ring_screen_close_stale")
            finishAndRemoveTask()
            return
        }
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                ringFinishedReceiver,
                IntentFilter(AlarmService.ACTION_RING_FINISHED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(ringFinishedReceiver) }
            receiverRegistered = false
        }
    }

    private fun readIntent(intent: Intent?) {
        if (intent == null) return
        val nextLateMinutes = intent.getIntExtra(EXTRA_LATE_MINUTES, 0)
        val requestedStop = intent.getBooleanExtra(EXTRA_CONFIRM_STOP, false)
        val requestedRemind = intent.getBooleanExtra(EXTRA_CONFIRM_REMIND, false)
        val previous = confirmation
        remainingMinutes = intent.getIntExtra(EXTRA_REMAINING, -1)
        lateMinutes = nextLateMinutes
        confirmation = when {
            requestedStop -> AlarmConfirmation.Stop
            // Кнопка «Напомнить» в шторке тоже проходит через вопрос «Вы уверены?».
            requestedRemind && nextLateMinutes <= 0 -> AlarmConfirmation.RemindDeparture
            // Новый сигнал в окне опоздания не должен сбрасывать вопрос об отключении.
            previous == AlarmConfirmation.Stop && nextLateMinutes > 0 -> previous
            // Вопрос «напомнить о выходе» остаётся, пока не началось опоздание.
            previous == AlarmConfirmation.RemindDeparture && nextLateMinutes <= 0 -> previous
            else -> null
        }
    }

    companion object {
        const val EXTRA_REMAINING = "extra_remaining"
        const val EXTRA_LATE_MINUTES = "extra_late_minutes"
        const val EXTRA_CONFIRM_STOP = "extra_confirm_stop"
        const val EXTRA_CONFIRM_REMIND = "extra_confirm_remind"
        private const val STATE_CONFIRMATION = "state_confirmation"
    }
}
