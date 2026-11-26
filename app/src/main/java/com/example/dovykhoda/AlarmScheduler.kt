package com.example.dovykhoda

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object AlarmScheduler {
    const val ACTION_TRIGGER = "com.example.dovykhoda.action.TRIGGER"
    const val ACTION_PRE_NOTIFY = "com.example.dovykhoda.action.PRE_NOTIFY"
    const val ACTION_LATE = "com.example.dovykhoda.action.LATE"
    const val ACTION_DEPARTURE_REMINDER = "com.example.dovykhoda.action.DEPARTURE_REMINDER"
    const val EXTRA_REMINDER_MINUTES = "extra_reminder_minutes"
    const val EXTRA_REMINDER_RETRY = "extra_reminder_retry"
    const val EXTRA_TRIGGER_RETRY = "extra_trigger_retry"
    const val EXTRA_TRIGGER_AT = "extra_trigger_at"
    const val EXTRA_CYCLE_WAKE = "extra_cycle_wake"
    const val EXTRA_CYCLE_LEAVE = "extra_cycle_leave"
    const val EXTRA_ALARM_ID = "extra_alarm_id"

    private const val RC_TRIGGER_BASE = 1_000
    private const val RC_SHOW_APP = 43
    private const val RC_PRE_NOTIFY = 44
    private const val RC_TRIGGER_RETRY = 45
    private const val RC_LATE = 100
    private const val RC_DEPARTURE_REMINDER_BASE = 110
    private const val RC_DEPARTURE_RETRY_BASE = 120
    private val DEPARTURE_REMINDER_MINUTES = listOf(20, 15, 10, 5, 0)
    /** Страховочный повтор: если процесс убит ровно в момент сигнала. */
    private const val DEPARTURE_RETRY_DELAY_MS = 30_000L
    private const val TRIGGER_RETRY_DELAY_MS = 30_000L
    private const val PRE_NOTIFY_LEAD_MS = 12 * 60 * 60 * 1000L
    private const val TAG = "AlarmScheduler"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    }

    fun reschedule(
        context: Context,
        settings: AlarmSettings,
        fromMillis: Long = System.currentTimeMillis(),
        recoverLate: Boolean = true,
    ) {
        cancelAll(context)
        if (!settings.enabled) {
            AlarmLog.event("reschedule_skipped", "reason" to "disabled")
            StatusNotifier.cancel(context)
            return
        }

        val store = SettingsStore(context)
        val alarms = store.alarms().filter { it.scheduled }
        var active = store.activeCycle()
        if (active != null && active.alarmId > 0L && store.alarm(active.alarmId)?.scheduled != true) {
            // Цикл принадлежал будильнику, который выключили или удалили.
            AlarmLog.event("active_cycle_dropped", "reason" to "alarm_off",
                "alarmId" to active.alarmId)
            store.clearActiveCycle()
            AlarmService.disable(context)
            StatusNotifier.cancel(context)
            active = null
        }
        AlarmLog.event(
            "reschedule",
            "from" to AlarmLog.formatTime(fromMillis),
            "wake" to settings.wakeLabel,
            "leave" to settings.leaveLabel,
            "enabled" to settings.enabled,
            "alarms" to alarms.joinToString(";") { "${it.id}:${it.wakeLabel}-${it.leaveLabel}" },
            "exactAllowed" to canScheduleExact(context),
            "activeWake" to active?.let { AlarmLog.formatTime(it.wakeMillis) },
            "activeLeave" to active?.let { AlarmLog.formatTime(it.leaveMillis) },
            "remindersOnly" to active?.remindersOnly,
            "recoverLate" to recoverLate,
            "ringing" to AlarmService.isRinging,
        )
        if (active?.remindersOnly == true &&
            fromMillis > active.leaveMillis + 5_000L
        ) {
            AlarmLog.event("reminders_expired", "leave" to AlarmLog.formatTime(active.leaveMillis))
            store.setAlarmEnabled(active.alarmId, false)
            store.clearActiveCycle()
            AlarmService.disable(context)
            StatusNotifier.cancel(context)
            return
        }
        if (active?.remindersOnly == true) {
            val windowStart = active.leaveMillis - 20 * 60_000L
            if (fromMillis < windowStart) {
                val at = maxOf(fromMillis + 1_000L, windowStart)
                AlarmLog.event("schedule_departure_window_recovery", "at" to AlarmLog.formatTime(at))
                scheduleAt(context,
                    SchedulePlanner.ScheduledTrigger(at, active.wakeMillis, active.leaveMillis), ACTION_LATE)
            } else {
                scheduleDepartureReminders(context, active)
            }
            return
        }
        if (active != null && fromMillis >= active.leaveMillis) {
            if (AlarmService.isRinging) {
                AlarmLog.event("refresh_reminders", "leave" to AlarmLog.formatTime(active.leaveMillis))
                AlarmService.refreshReminders(context)
            } else if (recoverLate) {
                val at = maxOf(fromMillis + 1_000L, active.leaveMillis)
                AlarmLog.event("schedule_late_recovery", "at" to AlarmLog.formatTime(at))
                scheduleAt(context,
                    SchedulePlanner.ScheduledTrigger(at, active.wakeMillis, active.leaveMillis), ACTION_LATE)
            }
            // Страховочный будильник опоздания: cancelAll выше снял старый,
            // а процесс может погибнуть в любой момент между репликами.
            val backstopAt = active.leaveMillis +
                SchedulePlanner.nextLateMinute(fromMillis, active.leaveMillis) * 60_000L
            if (backstopAt - fromMillis >= 5_000L) {
                AlarmLog.event("late_backstop_armed", "at" to AlarmLog.formatTime(backstopAt))
                armLateBackstop(context, active, backstopAt)
            }
            // Опоздание живёт в текущем цикле: следующий день всё равно должен
            // быть заведён, иначе после долгого опоздания будильник пропадёт.
            scheduleTriggers(context, alarms, settings, fromMillis)
            schedulePreNotifyForNext(context, alarms, settings, fromMillis)
            return
        }
        if (active != null && !active.remindersOnly &&
            fromMillis < active.leaveMillis && fromMillis - active.wakeMillis in 0L..30 * 60_000L &&
            !store.signalDelivered(active.wakeMillis)
        ) {
            // Страховка на случай убийства процесса сразу после сигнала:
            // повторный будильник через 30 секунд. Уже доставленный сигнал
            // отсекает журнал в приёмнике.
            val at = fromMillis + TRIGGER_RETRY_DELAY_MS
            AlarmLog.event("alarm_set_retry", "at" to AlarmLog.formatTime(at),
                "wake" to AlarmLog.formatTime(active.wakeMillis))
            scheduleAt(context,
                SchedulePlanner.ScheduledTrigger(at, active.wakeMillis, active.leaveMillis, active.alarmId),
                ACTION_TRIGGER, retry = true)
        }
        scheduleTriggers(context, alarms, settings, fromMillis)
        schedulePreNotifyForNext(context, alarms, settings, fromMillis)
    }

    private fun scheduleTriggers(
        context: Context,
        alarms: List<AlarmItem>,
        settings: AlarmSettings,
        fromMillis: Long,
    ) {
        alarms.forEach { alarm ->
            val next = SchedulePlanner.nextTrigger(fromMillis, alarm.toSettings(settings))
            scheduleAt(context, next.copy(alarmId = alarm.id))
        }
    }

    private fun schedulePreNotifyForNext(
        context: Context,
        alarms: List<AlarmItem>,
        settings: AlarmSettings,
        fromMillis: Long,
    ) {
        val next = SchedulePlanner.nextAlarmWake(fromMillis, alarms, settings) ?: return
        schedulePreNotifyAt(context, next.first.toSettings(settings), next.second, next.first.id)
    }

    /** Schedule each departure prompt as an OS wake-up alarm, not a coroutine timer. */
    fun scheduleDepartureReminders(context: Context, cycle: SettingsStore.ActiveCycle) {
        SchedulePlanner.departureReminders(cycle.leaveMillis).forEachIndexed { index, atMillis ->
            val minutes = DEPARTURE_REMINDER_MINUTES[index]
            // Keep the firing window tight: alarms already past are missed prompts;
            // replaying them minutes later risks stale announcements out of order.
            if (atMillis < System.currentTimeMillis() - 5_000L) return@forEachIndexed
            scheduleDepartureAlarm(context, cycle.leaveMillis, minutes, atMillis, retry = false)
            // Второй сигнал через 30 секунд страхует случай, когда процесс приложения
            // убит прямо в момент первого. Повтор уже озвученного отсекается журналом.
            scheduleDepartureAlarm(context, cycle.leaveMillis, minutes,
                atMillis + DEPARTURE_RETRY_DELAY_MS, retry = true)
        }
    }

    private fun scheduleDepartureAlarm(
        context: Context,
        leaveMillis: Long,
        minutes: Int,
        atMillis: Long,
        retry: Boolean,
    ) {
        val operation = departureReminderIntent(context, leaveMillis, minutes, atMillis, retry)
        val manager = context.getSystemService(AlarmManager::class.java)
        try {
            if (canScheduleExact(context)) {
                // Keep the five prompts distinct on Android versions that coalesce
                // ordinary exact alarms while the device is idle.
                val showIntent = PendingIntent.getActivity(
                    context, RC_SHOW_APP, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(atMillis, showIntent), operation)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
            }
            AlarmLog.event("departure_alarm_set", "minutes" to minutes,
                "at" to AlarmLog.formatTime(atMillis), "exact" to canScheduleExact(context),
                "retry" to retry)
        } catch (error: SecurityException) {
            AlarmLog.failure("departure_alarm_exact_denied", error,
                "minutes" to minutes, "at" to AlarmLog.formatTime(atMillis), "retry" to retry)
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    /**
     * Системный будильник на ближайшую минуту опоздания: если процесс убьют,
     * сервис перезапустится и продолжит голосовые напоминания.
     */
    fun armLateBackstop(context: Context, cycle: SettingsStore.ActiveCycle, atMillis: Long) {
        scheduleAt(context,
            SchedulePlanner.ScheduledTrigger(atMillis, cycle.wakeMillis, cycle.leaveMillis), ACTION_LATE)
    }

    fun cancelDepartureReminders(context: Context) {
        DEPARTURE_REMINDER_MINUTES.forEach { minutes ->
            context.getSystemService(AlarmManager::class.java).cancel(
                departureReminderIntent(context, 0L, minutes, 0L, retry = false))
            context.getSystemService(AlarmManager::class.java).cancel(
                departureReminderIntent(context, 0L, minutes, 0L, retry = true))
        }
    }

    fun schedulePreNotifyAt(
        context: Context,
        settings: AlarmSettings,
        wakeMillis: Long,
        alarmId: Long = 0L,
    ) {
        if (!settings.preNotifyEnabled) return
        val atMillis = wakeMillis - PRE_NOTIFY_LEAD_MS
        if (atMillis <= System.currentTimeMillis()) {
            AlarmLog.event("pre_notify_now", "wake" to AlarmLog.formatTime(wakeMillis))
            StatusNotifier.showScheduled(context, settings, wakeMillis)
            return
        }
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = preNotifyIntent(context, wakeMillis, alarmId)
        try {
            if (canScheduleExact(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                AlarmLog.event("pre_notify_set", "at" to AlarmLog.formatTime(atMillis), "exact" to true)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
                AlarmLog.event("pre_notify_set", "at" to AlarmLog.formatTime(atMillis), "exact" to false)
            }
        } catch (error: SecurityException) {
            Log.w(TAG, "cannot schedule exact pre-notify", error)
            AlarmLog.failure("pre_notify_exact_denied", error, "at" to AlarmLog.formatTime(atMillis))
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, operation)
        }
    }

    private fun scheduleAt(
        context: Context,
        trigger: SchedulePlanner.ScheduledTrigger,
        action: String = ACTION_TRIGGER,
        retry: Boolean = false,
    ) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val operation = triggerIntent(context, action, trigger, retry)
        try {
            if (canScheduleExact(context)) {
                val showIntent = PendingIntent.getActivity(
                    context,
                    RC_SHOW_APP,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(trigger.atMillis, showIntent), operation)
                AlarmLog.event(
                    "alarm_set",
                    "action" to action,
                    "at" to AlarmLog.formatTime(trigger.atMillis),
                    "wake" to AlarmLog.formatTime(trigger.cycleWakeMillis),
                    "leave" to AlarmLog.formatTime(trigger.cycleLeaveMillis),
                    "exact" to true,
                    "mode" to "setAlarmClock",
                    "inMs" to (trigger.atMillis - System.currentTimeMillis()),
                )
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.atMillis, operation)
                AlarmLog.event(
                    "alarm_set",
                    "action" to action,
                    "at" to AlarmLog.formatTime(trigger.atMillis),
                    "wake" to AlarmLog.formatTime(trigger.cycleWakeMillis),
                    "leave" to AlarmLog.formatTime(trigger.cycleLeaveMillis),
                    "exact" to false,
                    "mode" to "setAndAllowWhileIdle",
                    "inMs" to (trigger.atMillis - System.currentTimeMillis()),
                )
            }
            Log.d(TAG, "$action scheduled at ${trigger.atMillis}")
        } catch (error: SecurityException) {
            Log.w(TAG, "exact alarm not allowed", error)
            AlarmLog.failure(
                "alarm_exact_denied", error,
                "action" to action,
                "at" to AlarmLog.formatTime(trigger.atMillis),
            )
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.atMillis, operation)
        }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java)
        manager.cancel(triggerIntent(context, ACTION_TRIGGER))
        SettingsStore(context).alarms().forEach { alarm ->
            manager.cancel(triggerIntent(context, ACTION_TRIGGER, alarmId = alarm.id))
        }
        manager.cancel(triggerIntent(context, ACTION_TRIGGER, retry = true))
        manager.cancel(triggerIntent(context, ACTION_LATE))
        manager.cancel(preNotifyIntent(context, 0L))
        cancelDepartureReminders(context)
    }

    /** Снять запланированный сигнал удалённого будильника. */
    fun cancelAlarm(context: Context, alarmId: Long) {
        context.getSystemService(AlarmManager::class.java)
            .cancel(triggerIntent(context, ACTION_TRIGGER, alarmId = alarmId))
    }

    private fun triggerIntent(
        context: Context,
        action: String,
        trigger: SchedulePlanner.ScheduledTrigger? = null,
        retry: Boolean = false,
        alarmId: Long = 0L,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
        if (trigger != null) {
            intent.putExtra(EXTRA_TRIGGER_AT, trigger.atMillis)
                .putExtra(EXTRA_CYCLE_WAKE, trigger.cycleWakeMillis)
                .putExtra(EXTRA_CYCLE_LEAVE, trigger.cycleLeaveMillis)
                .putExtra(EXTRA_ALARM_ID, trigger.alarmId)
        }
        if (retry) intent.putExtra(EXTRA_TRIGGER_RETRY, true)
        return PendingIntent.getBroadcast(
            context,
            when {
                action == ACTION_LATE -> RC_LATE
                retry -> RC_TRIGGER_RETRY
                else -> RC_TRIGGER_BASE + (alarmId % 900_000L).toInt()
            },
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun departureReminderIntent(
        context: Context,
        leaveMillis: Long,
        minutes: Int,
        triggerMillis: Long,
        retry: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_DEPARTURE_REMINDER)
            .putExtra(EXTRA_CYCLE_LEAVE, leaveMillis)
            .putExtra(EXTRA_REMINDER_MINUTES, minutes)
            .putExtra(EXTRA_TRIGGER_AT, triggerMillis)
            .putExtra(EXTRA_REMINDER_RETRY, retry)
        val base = if (retry) RC_DEPARTURE_RETRY_BASE else RC_DEPARTURE_REMINDER_BASE
        return PendingIntent.getBroadcast(
            context, base + DEPARTURE_REMINDER_MINUTES.indexOf(minutes), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun preNotifyIntent(context: Context, wakeMillis: Long, alarmId: Long = 0L): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_PRE_NOTIFY)
            .putExtra(StatusNotifier.EXTRA_CYCLE_WAKE, wakeMillis)
            .putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            RC_PRE_NOTIFY,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
