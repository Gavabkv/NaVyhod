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
        val active = store.activeCycle()
        val lateMs = if (atMillis > 0L) now - atMillis else null
        val reminders = active != null && (active.remindersOnly ||
            (now >= active.leaveMillis && active.leaveMillis != leaveMillis))
        AlarmLog.deviceInfo("alarm_trigger_device_state")
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
        )
        if (!settings.enabled) {
            AlarmLog.event("alarm_ignored", "reason" to "disabled")
            return
        }
        if (atMillis == 0L || wakeMillis == 0L || leaveMillis == 0L) {
            AlarmLog.event("alarm_ignored", "reason" to "bad_extras")
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
            store.setActiveCycle(SettingsStore.ActiveCycle(wakeMillis, leaveMillis))
        }
        AlarmScheduler.reschedule(app, settings, now, recoverLate = false)
        val trigger = SchedulePlanner.ScheduledTrigger(atMillis, wakeMillis, leaveMillis)
        runCatching {
            if (reminders || intent.action == AlarmScheduler.ACTION_LATE) {
                AlarmLog.event("service_start_late", "leave" to AlarmLog.formatTime(active!!.leaveMillis))
                AlarmService.startLate(app, active.leaveMillis)
            } else {
                AlarmLog.event("service_start_alarm", "at" to AlarmLog.formatTime(atMillis))
                AlarmService.start(app, trigger = trigger)
            }
        }.onFailure {
            Log.e(TAG, "cannot start alarm service", it)
            AlarmLog.failure("service_start_failed", it, "action" to intent.action)
        }
    }

    private fun handleDepartureReminder(app: Context, intent: Intent) {
        val store = SettingsStore(app)
        val cycle = store.activeCycle()
        val leave = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L)
        val minutes = intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1)
        val at = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)
        val now = System.currentTimeMillis()
        val valid = store.load().enabled && cycle?.remindersOnly == true &&
            cycle.leaveMillis == leave && minutes in listOf(20, 15, 10, 5, 0) &&
            at > 0L && kotlin.math.abs(now - at) <= 5 * 60_000L
        AlarmLog.event("departure_alarm_fired", "minutes" to minutes,
            "at" to AlarmLog.formatTime(at), "lateMs" to (now - at), "valid" to valid)
        if (!valid) return
        // Don't reschedule an already-consumed alarm. Service validates the active cycle
        // again before announcing, so stale broadcasts after Stop are harmless.
        runCatching { AlarmService.startDepartureReminder(app, leave, minutes, at) }
            .onFailure { AlarmLog.failure("departure_service_start_failed", it,
                "minutes" to minutes, "leave" to AlarmLog.formatTime(leave)) }
    }

    private fun handlePreNotify(app: Context, intent: Intent) {
        val settings = SettingsStore(app).load()
        val wakeMillis = intent.getLongExtra(StatusNotifier.EXTRA_CYCLE_WAKE, 0L)
        if (!settings.enabled || !settings.preNotifyEnabled || wakeMillis <= System.currentTimeMillis()) {
            AlarmLog.event(
                "pre_notify_ignored",
                "enabled" to settings.enabled,
                "preNotify" to settings.preNotifyEnabled,
                "wake" to if (wakeMillis > 0L) AlarmLog.formatTime(wakeMillis) else null,
            )
            return
        }
        AlarmLog.event("pre_notify_show", "wake" to AlarmLog.formatTime(wakeMillis))
        StatusNotifier.showScheduled(app, settings, wakeMillis)
    }

    private companion object {
        const val TAG = "AlarmReceiver"
    }
}
