package com.example.dovykhoda

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        AlarmLog.event(
            "broadcast",
            "action" to intent.action,
            "now" to AlarmLog.formatTime(System.currentTimeMillis()),
        )
        when (intent.action) {
            AlarmScheduler.ACTION_TRIGGER, AlarmScheduler.ACTION_LATE -> handleTrigger(app, intent)
            AlarmScheduler.ACTION_DEPARTURE_REMINDER -> handleDepartureReminder(app, intent)
            AlarmScheduler.ACTION_PRE_NOTIFY -> handlePreNotify(app, intent)
            StatusNotifier.ACTION_PRE_NOTIFY_DISMISSED -> {
                val cycle = intent.getLongExtra(StatusNotifier.EXTRA_CYCLE_WAKE, 0L)
                if (cycle > 0L) SettingsStore(app).dismissPreNotify(cycle)
            }
        }
    }

    private fun handleTrigger(app: Context, intent: Intent) {
        val store = SettingsStore(app)
        val settings = store.load()
        val now = System.currentTimeMillis()
        val atMillis = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)
        val wakeMillis = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, 0L)
        val leaveMillis = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L)
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0L)
        val alarm = if (alarmId > 0L) store.alarm(alarmId) else null
        val active = store.activeCycle()
        val lateMs = if (atMillis > 0L) now - atMillis else null
        // Новый цикл (следующий день) главнее вчерашнего: иначе застрявший
        // «опоздание»-цикл глушил бы свежий сигнал.
        val newerCycle = active != null &&
            wakeMillis > active.wakeMillis && leaveMillis > active.leaveMillis
        // Режим «напомнить» распространяется только на сигналы своего цикла.
        // Без сверки будильника напоминания одного будильника съедали сигнал
        // другого, и тот выходил как «Вы опоздали».
        val sameCycle = active != null && !newerCycle &&
            active.matches(wakeMillis, leaveMillis, alarmId)
        val reminders = sameCycle && active!!.remindersOnly
        AlarmLog.event(
            "alarm_trigger",
            "action" to intent.action,
            "at" to if (atMillis > 0L) AlarmLog.formatTime(atMillis) else null,
            "lateMs" to lateMs,
            "wake" to if (wakeMillis > 0L) AlarmLog.formatTime(wakeMillis) else null,
            "leave" to if (leaveMillis > 0L) AlarmLog.formatTime(leaveMillis) else null,
            "now" to AlarmLog.formatTime(now),
            "enabled" to settings.enabled,
            "reminders" to reminders,
            "activeWake" to active?.let { AlarmLog.formatTime(it.wakeMillis) },
            "activeLeave" to active?.let { AlarmLog.formatTime(it.leaveMillis) },
            "remindersOnly" to active?.remindersOnly,
            "alarmId" to alarmId,
        )
        if (!settings.enabled) {
            AlarmLog.event("alarm_ignored", "reason" to "disabled")
            return
        }
        if (alarmId > 0L && (alarm == null || !alarm.scheduled)) {
            // Сигнал уже удалённого или выключенного будильника.
            AlarmLog.event("alarm_ignored", "reason" to "alarm_off", "alarmId" to alarmId)
            return
        }
        if (atMillis == 0L || wakeMillis == 0L || leaveMillis == 0L) {
            AlarmLog.event("alarm_ignored", "reason" to "bad_extras")
            return
        }
        if (intent.getBooleanExtra(AlarmScheduler.EXTRA_TRIGGER_RETRY, false) &&
            store.signalDelivered(atMillis)
        ) {
            // Страховочный повтор после уже прозвучавшего сигнала.
            AlarmLog.event("alarm_retry_deduped", "at" to AlarmLog.formatTime(atMillis))
            return
        }

        if (intent.action == AlarmScheduler.ACTION_LATE &&
            (active == null || active.leaveMillis != leaveMillis)
        ) {
            AlarmLog.event(
                "alarm_ignored",
                "reason" to "stale_late",
                "activeLeave" to active?.let { AlarmLog.formatTime(it.leaveMillis) },
                "intentLeave" to AlarmLog.formatTime(leaveMillis),
            )
            return
        }
        if (!reminders && intent.action == AlarmScheduler.ACTION_TRIGGER) {
            store.setActiveCycle(SettingsStore.ActiveCycle(wakeMillis, leaveMillis, alarmId = alarmId))
        }
        AlarmScheduler.reschedule(app, settings, now, recoverLate = false)
        val trigger = SchedulePlanner.ScheduledTrigger(atMillis, wakeMillis, leaveMillis, alarmId)
        runCatching {
            if (reminders || intent.action == AlarmScheduler.ACTION_LATE) {
                AlarmLog.event("service_start_late", "leave" to AlarmLog.formatTime(active!!.leaveMillis))
                AlarmService.startLate(app, active.leaveMillis)
            } else {
                AlarmLog.event("service_start_alarm", "at" to AlarmLog.formatTime(atMillis))
                AlarmService.start(app, trigger = trigger)
            }
            // Отметка ставится сразу после успешного запуска: повтор через
            // 30 секунд не должен звонить, даже если сервис ещё не отметил сигнал.
            store.markSignalDelivered(atMillis)
        }.onFailure {
            Log.e(TAG, "cannot start alarm service", it)
            AlarmLog.failure("service_start_failed", it, "action" to intent.action)
        }
        // Снимок состояния устройства стоит дорого; будильник важнее, поэтому
        // пишем его только после запуска сервиса.
        AlarmLog.deviceInfo("alarm_trigger_device_state")
    }

    private fun handleDepartureReminder(app: Context, intent: Intent) {
        val store = SettingsStore(app)
        val cycle = store.activeCycle()
        val leave = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L)
        val minutes = intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1)
        val at = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)
        val now = System.currentTimeMillis()
        val cycleAlarm = if (cycle != null && cycle.alarmId > 0L) store.alarm(cycle.alarmId) else null
        val alarmAlive = cycleAlarm == null || cycleAlarm.scheduled
        val valid = store.load().enabled && alarmAlive && cycle?.remindersOnly == true &&
            cycle.leaveMillis == leave && minutes in listOf(20, 15, 10, 5, 0) &&
            at > 0L && kotlin.math.abs(now - at) <= 5 * 60_000L
        AlarmLog.event("departure_alarm_fired", "minutes" to minutes,
            "at" to AlarmLog.formatTime(at), "lateMs" to (now - at), "valid" to valid,
            "retry" to intent.getBooleanExtra(AlarmScheduler.EXTRA_REMINDER_RETRY, false))
        if (!valid) return
        // Don't reschedule an already-consumed alarm. Service validates the active cycle
        // again before announcing, so stale broadcasts after Stop are harmless.
        runCatching { AlarmService.startDepartureReminder(app, leave, minutes, at) }
            .onFailure { AlarmLog.failure("departure_service_start_failed", it,
                "minutes" to minutes, "leave" to AlarmLog.formatTime(leave)) }
    }

    private fun handlePreNotify(app: Context, intent: Intent) {
        val store = SettingsStore(app)
        val settings = store.load()
        val wakeMillis = intent.getLongExtra(StatusNotifier.EXTRA_CYCLE_WAKE, 0L)
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0L)
        if (!settings.enabled || !settings.preNotifyEnabled || wakeMillis <= System.currentTimeMillis()) {
            AlarmLog.event(
                "pre_notify_ignored",
                "enabled" to settings.enabled,
                "preNotify" to settings.preNotifyEnabled,
                "wake" to if (wakeMillis > 0L) AlarmLog.formatTime(wakeMillis) else null,
            )
            return
        }
        AlarmLog.event("pre_notify_show", "wake" to AlarmLog.formatTime(wakeMillis),
            "alarmId" to alarmId)
        StatusNotifier.showScheduled(app, store.settingsFor(alarmId) ?: settings, wakeMillis)
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
