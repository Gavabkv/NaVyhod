package com.example.dovykhoda

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object StatusNotifier {
    const val NOTIFICATION_ID = 1001
    const val RING_NOTIFICATION_ID = 1002
    const val CHANNEL_STATUS = "alarm_status"
    const val CHANNEL_RINGING = "alarm_ringing"
    const val ACTION_PRE_NOTIFY_DISMISSED = "com.example.dovykhoda.action.PRE_NOTIFY_DISMISSED"
    const val EXTRA_CYCLE_WAKE = "extra_cycle_wake"
    private const val PRE_NOTIFY_LEAD_MS = 12 * 60 * 60 * 1000L
    private const val RC_OPEN_APP = 10
    private const val RC_FULL_SCREEN = 11
    private const val RC_STOP_CONFIRM = 12
    private const val RC_SILENCE = 13
    private const val RC_DISMISS = 14
    private const val RC_LATE_SCREEN = 16

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_STATUS) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_STATUS, "Будильник заведён", NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Расписание и напоминания об опоздании"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (manager.getNotificationChannel(CHANNEL_RINGING) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_RINGING, "Сигнал будильника", NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Музыка и голосовые объявления до выхода"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    fun showScheduled(context: Context, settings: AlarmSettings, cycleWakeMillis: Long) {
        if (!settings.enabled || !settings.preNotifyEnabled || AlarmService.isRinging) return
        if (SettingsStore(context).preNotifyDismissedCycle() == cycleWakeMillis) return
        ensureChannels(context)
        val text = "Пробуждение в ${settings.wakeLabel} · выход в ${settings.leaveLabel}"
        post(context, baseBuilder(context)
            .setContentTitle("Будильник заведён")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setDeleteIntent(dismissIntent(context, cycleWakeMillis))
            .build())
    }

    fun buildActive(context: Context, settings: AlarmSettings, remainingMinutes: Int): Notification {
        ensureChannels(context)
        val text = remainingText(remainingMinutes)
        return baseBuilder(context)
            .setContentTitle(if (remainingMinutes <= 0) "Пора выходить" else "Будильник заведён")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$text\nПробуждение в ${settings.wakeLabel} · выход в ${settings.leaveLabel}",
            ))
            .setAutoCancel(false)
            .addAction(0, "Выключить", stopConfirmIntent(context, remainingMinutes))
            .build()
    }

    fun buildRinging(context: Context, remainingMinutes: Int): Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CHANNEL_RINGING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFB45309.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(if (remainingMinutes <= 0) "Пора выходить" else "Пора вставать")
            .setContentText(remainingText(remainingMinutes))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true)
            .setContentIntent(fullScreenIntent(context, false, remainingMinutes))
            .setFullScreenIntent(fullScreenIntent(context, false, remainingMinutes), true)
            .addAction(0, "Отложить", silenceIntent(context))
            .addAction(0, "Напомнить", PendingIntent.getService(
                context, 15, Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_REMIND_ONLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ))
            .addAction(0, "Выключить", stopConfirmIntent(context, remainingMinutes))
            .build()
    }

    /**
     * Заглушка на первые миллисекунды жизни foreground-сервиса:
     * системное уведомление показывается до чтения настроек и построения
     * настоящего уведомления, чтобы не попасть под таймаут startForeground().
     */
    fun buildStarting(context: Context): Notification {
        ensureChannels(context)
        return baseBuilder(context)
            .setContentTitle("На выход")
            .setContentText("Подготовка сигнала…")
            .setAutoCancel(false)
            .setOngoing(true)
            .build()
    }

    fun buildReminders(context: Context, leaveMillis: Long): Notification {
        ensureChannels(context)
        val leave = java.time.Instant.ofEpochMilli(leaveMillis)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        val text = "Без музыки · выход в ${AlarmSettings.formatTime(leave.hour, leave.minute)}. " +
            "Голос за 20, 15, 10 и 5 минут и в момент выхода. После этого — без напоминаний."
        return baseBuilder(context)
            .setContentTitle("Напоминания о выходе")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, "Выключить", stopConfirmIntent(context,
                SchedulePlanner.remainingMinutes(System.currentTimeMillis(), leaveMillis)))
            .build()
    }

    fun buildLate(context: Context, lateMinutes: Int, showWindow: Boolean = false): Notification {
        ensureChannels(context)
        val text = SpeechText.late(lateMinutes)
        val window = fullScreenIntent(context, false, 0, lateMinutes)
        return baseBuilder(context, if (showWindow) CHANNEL_RINGING else CHANNEL_STATUS)
            .setContentTitle("Вы опоздали")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (showWindow) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(!showWindow)
            .setSilent(!showWindow)
            .setContentIntent(window)
            .setFullScreenIntent(if (showWindow) window else null, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(0, "Отложить", silenceIntent(context))
            .addAction(0, "Выключить", fullScreenIntent(context, true, 0, lateMinutes))
            .build()
    }

    fun isRingChannelAvailable(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return true
        val channel = manager.getNotificationChannel(CHANNEL_RINGING) ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    fun ringChannelSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_RINGING)

    fun cancel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(RING_NOTIFICATION_ID)
    }

    fun cancelStatus(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun refresh(context: Context, settings: AlarmSettings) {
        if (AlarmService.isRinging) return
        if (!settings.enabled) {
            cancel(context)
            return
        }
        NotificationManagerCompat.from(context).cancel(RING_NOTIFICATION_ID)
        val now = System.currentTimeMillis()
        val store = SettingsStore(context)
        val active = store.activeCycle()
        if (active != null && (active.remindersOnly || now >= active.leaveMillis)) {
            val minutes = ((now - active.leaveMillis).coerceAtLeast(0L) / 60_000L).toInt()
            post(context, when {
                active.remindersOnly -> buildReminders(context, active.leaveMillis)
                minutes > 0 -> buildLate(context, minutes)
                else -> buildActive(context, settings, 0)
            })
            return
        }
        if (!settings.preNotifyEnabled) {
            cancel(context)
            return
        }
        val next = SchedulePlanner.nextTrigger(now, settings)
        if (now >= next.cycleWakeMillis && now < next.cycleLeaveMillis) {
            post(context, buildActive(context, settings, SchedulePlanner.remainingMinutes(now, next.cycleLeaveMillis)))
            return
        }
        val wakeMillis = SchedulePlanner.nextWakeMillis(now, settings)
        if (wakeMillis - PRE_NOTIFY_LEAD_MS <= now) showScheduled(context, settings, wakeMillis)
        else cancel(context)
    }

    private fun fullScreenIntent(
        context: Context,
        confirmStop: Boolean,
        remainingMinutes: Int,
        lateMinutes: Int = 0,
    ): PendingIntent {
        val intent = Intent(context, AlarmRingingActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AlarmRingingActivity.EXTRA_CONFIRM_STOP, confirmStop)
            .putExtra(AlarmRingingActivity.EXTRA_REMAINING, remainingMinutes)
            .putExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, lateMinutes)
        val requestCode = when {
            confirmStop -> RC_STOP_CONFIRM
            lateMinutes > 0 -> RC_LATE_SCREEN
            else -> RC_FULL_SCREEN
        }
        return PendingIntent.getActivity(context, requestCode,
            intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stopConfirmIntent(context: Context, remainingMinutes: Int) =
        fullScreenIntent(context, true, remainingMinutes)

    private fun silenceIntent(context: Context): PendingIntent = PendingIntent.getService(
        context, RC_SILENCE, Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_SILENCE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun dismissIntent(context: Context, cycleWakeMillis: Long): PendingIntent = PendingIntent.getBroadcast(
        context, RC_DISMISS, Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_PRE_NOTIFY_DISMISSED).putExtra(EXTRA_CYCLE_WAKE, cycleWakeMillis),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun baseBuilder(context: Context, channel: String = CHANNEL_STATUS) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setColor(0xFFB45309.toInt())
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setContentIntent(PendingIntent.getActivity(context, RC_OPEN_APP, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOnlyAlertOnce(true)
        .setSilent(true)

    private fun post(context: Context, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w("StatusNotifier", "notifications permission revoked", error)
        }
    }

    fun remainingText(minutes: Int): String = if (minutes <= 0) "Пора выходить"
        else "До выхода $minutes " + SpeechText.plural(minutes, "минута", "минуты", "минут")
}
