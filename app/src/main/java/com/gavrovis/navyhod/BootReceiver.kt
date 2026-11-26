package com.gavrovis.navyhod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * После перезагрузки, обновления приложения или смены часового пояса
 * системные будильники теряются — ставим их заново.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                val app = context.applicationContext
                val settings = SettingsStore(app).load()
                AlarmLog.event(
                    "boot_event",
                    "action" to intent.action,
                    "enabled" to settings.enabled,
                    "wake" to settings.wakeLabel,
                    "leave" to settings.leaveLabel,
                    "active" to SettingsStore(app).activeCycle()?.let {
                        "wake=${AlarmLog.formatTime(it.wakeMillis)} leave=${AlarmLog.formatTime(it.leaveMillis)} remindersOnly=${it.remindersOnly}"
                    },
                )
                if (settings.enabled) {
                    AlarmScheduler.reschedule(app, settings)
                }
                StatusNotifier.refresh(app, settings)
            }
        }
    }
}
