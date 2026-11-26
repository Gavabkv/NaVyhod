package com.example.dovykhoda.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.example.dovykhoda.AlarmItem
import com.example.dovykhoda.AlarmScheduler
import com.example.dovykhoda.AlarmSettings
import com.example.dovykhoda.MainActivity
import com.example.dovykhoda.SchedulePlanner
import com.example.dovykhoda.SettingsStore
import com.example.dovykhoda.StatusNotifier

@Composable
fun SettingsTab(
    settings: AlarmSettings,
    alarms: List<AlarmItem>,
    activeCycle: SettingsStore.ActiveCycle?,
    nowMillis: Long,
    onChange: (AlarmSettings) -> Unit,
    onRequestExactAlarms: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
    onRequestTurnScreenOn: () -> Unit,
    onRequestRingChannel: () -> Unit,
    onExportLogs: () -> Unit,
) {
    val context = LocalContext.current
    val exactAllowed = remember(nowMillis) { AlarmScheduler.canScheduleExact(context) }
    val notificationsAllowed = remember(nowMillis) {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    val batteryExempt = remember(nowMillis) { MainActivity.isIgnoringBatteryOptimizations(context) }
    val fullScreenAllowed = remember(nowMillis) { MainActivity.canUseFullScreenIntent(context) }
    val turnScreenAllowed = remember(nowMillis) { MainActivity.canTurnScreenOn(context) }
    val ringChannelAllowed = remember(nowMillis) { StatusNotifier.isRingChannelAvailable(context) }
    val volumeMuted = remember(nowMillis) { alarmVolumeMuted(context) }
    val dndBlocks = remember(nowMillis) { dndBlocksAlarms(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            "Настройки",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("settings_title"),
        )
        Spacer(Modifier.height(12.dp))

        ScreenCard("Ближайший сигнал") {
            NextSignalInfo(alarms, settings, activeCycle, nowMillis)
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Сигналы") {
            Text(
                "Как часто играет музыка от пробуждения до выхода.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 3, 5, 10, 15, 30).forEach { minutes ->
                    SelectChip(
                        text = "$minutes",
                        selected = settings.intervalMinutes == minutes,
                        tag = "interval_chip_$minutes",
                    ) { onChange(settings.copy(intervalMinutes = minutes)) }
                }
            }
            Text(
                "Минут между сигналами",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Голос") {
            ToggleRow(
                title = "Объявлять время на сигналах",
                checked = settings.announceTimeOnSignals,
                tag = "time_signals",
                onCheckedChange = { onChange(settings.copy(announceTimeOnSignals = it)) },
            )
            ToggleRow(
                title = "Объявлять время на напоминаниях о выходе",
                checked = settings.announceTimeOnDeparture,
                tag = "time_departure",
                onCheckedChange = { onChange(settings.copy(announceTimeOnDeparture = it)) },
            )
            ToggleRow(
                title = "Объявлять время при опоздании",
                checked = settings.announceTimeOnLate,
                tag = "time_late",
                onCheckedChange = { onChange(settings.copy(announceTimeOnLate = it)) },
            )
            Spacer(Modifier.height(8.dp))
            ValueSlider(
                title = "Начинать голос за",
                valueLabel = formatDuration(settings.ttsLeadMinutes),
                value = settings.ttsLeadMinutes,
                range = 5..60,
                step = 5,
                tag = "tts_lead",
                onChange = { onChange(settings.copy(ttsLeadMinutes = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Длительность сигнала") {
            ValueSlider(
                title = "Музыка играет",
                valueLabel = "${settings.ringSeconds} с",
                value = settings.ringSeconds,
                range = 20..60,
                step = 5,
                tag = "ring_seconds",
                onChange = { onChange(settings.copy(ringSeconds = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Уведомления") {
            ToggleRow(
                title = "Напоминать за 12 часов",
                subtitle = "В шторке будет видно время пробуждения и выхода",
                checked = settings.preNotifyEnabled,
                tag = "pre_notify",
                onCheckedChange = { onChange(settings.copy(preNotifyEnabled = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Разрешения и предупреждения") {
            PermissionRow(
                text = "Точные будильники",
                granted = exactAllowed,
                tag = "perm_exact",
                action = "Настроить",
                onAction = onRequestExactAlarms,
            )
            PermissionRow(
                text = "Уведомления",
                granted = notificationsAllowed,
                tag = "perm_notifications",
                action = "Разрешить",
                onAction = onRequestNotifications,
            )
            PermissionRow(
                text = "Исключение из экономии батареи",
                granted = batteryExempt,
                tag = "perm_battery",
                action = "Настроить",
                onAction = onRequestBatteryExemption,
            )
            PermissionRow(
                text = "Полноэкранные уведомления",
                granted = fullScreenAllowed,
                tag = "perm_fullscreen",
                action = "Разрешить",
                onAction = onRequestFullScreenIntent,
            )
            PermissionRow(
                text = "Включение экрана",
                granted = turnScreenAllowed,
                tag = "perm_turn_screen",
                action = "Разрешить",
                onAction = onRequestTurnScreenOn,
            )
            PermissionRow(
                text = "Громкий канал «Сигнал будильника»",
                granted = ringChannelAllowed,
                tag = "perm_ring_channel",
                action = "Настроить",
                onAction = onRequestRingChannel,
            )
            if (volumeMuted) {
                PermissionRow(
                    text = "Звук будильника выключен — сигнала не будет слышно",
                    granted = false,
                    tag = "alarm_volume_muted",
                    action = "Исправить",
                    onAction = { openSettings(context, Settings.ACTION_SOUND_SETTINGS) },
                )
            }
            if (dndBlocks) {
                PermissionRow(
                    text = "Режим «Не беспокоить» может заглушать будильник",
                    granted = false,
                    tag = "dnd_blocks_alarms",
                    action = "Настроить",
                    onAction = { openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Диагностика") {
            Text(
                "Если будильник сработал не так, как ожидалось, отправьте журнал — " +
                    "в нём видно, что происходило на устройстве.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExportLogs, modifier = Modifier.fillMaxWidth()) {
                Text("Отправить логи")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun NextSignalInfo(
    alarms: List<AlarmItem>,
    settings: AlarmSettings,
    activeCycle: SettingsStore.ActiveCycle?,
    nowMillis: Long,
) {
    if (activeCycle != null) {
        Text(
            if (activeCycle.remindersOnly) "Идут напоминания о выходе" else "Цикл будильника идёт",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Выход в ${formatClock(activeCycle.leaveMillis)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
    }
    val next = SchedulePlanner.nextAlarmTrigger(nowMillis, alarms, settings)
    if (next == null) {
        Text(
            "Нет включённых будильников.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val (alarm, trigger) = next
    Text(
        "${alarm.wakeLabel}–${alarm.leaveLabel} · ${formatWhen(trigger.atMillis, nowMillis)}",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        alarm.daysLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionRow(
    text: String,
    granted: Boolean,
    tag: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.error,
            )
            Text(
                if (granted) "Разрешено" else "Не разрешено — будильник может не сработать",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
        if (!granted) {
            TextButton(onClick = onAction, modifier = Modifier.testTag(tag)) {
                Text(action)
            }
        }
    }
}

private fun openSettings(context: Context, action: String) {
    runCatching { context.startActivity(Intent(action)) }
}

private fun alarmVolumeMuted(context: Context): Boolean {
    val audio = context.getSystemService(AudioManager::class.java) ?: return false
    return audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0
}

private fun dndBlocksAlarms(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!manager.isNotificationPolicyAccessGranted) return false
    if (manager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL) return false
    val policy = manager.notificationPolicy ?: return false
    return policy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0
}
