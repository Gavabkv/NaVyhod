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
    stoppedCycle: SettingsStore.StoppedCycle?,
    nowMillis: Long,
    darkTheme: Boolean,
    onChange: (AlarmSettings) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    onRequestExactAlarms: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
    onRequestTurnScreenOn: () -> Unit,
    onRequestRingChannel: () -> Unit,
    onRequestDndAccess: () -> Unit,
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
    val dndAccess = remember(nowMillis) {
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true
    }
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
            NextSignalInfo(alarms, settings, activeCycle, stoppedCycle, nowMillis)
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard("Оформление") {
            ToggleRow(
                title = "Тёмная тема",
                subtitle = "Светлый или тёмный вид приложения",
                checked = darkTheme,
                tag = "theme_dark",
                onCheckedChange = onDarkThemeChange,
            )
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
                listOf(5, 10).forEach { minutes ->
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
                range = 5..120,
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

        val permissionIssues = buildList {
            if (!exactAllowed) add(
                PermissionIssue("Точные будильники", "perm_exact", "Настроить", onRequestExactAlarms),
            )
            if (!notificationsAllowed) add(
                PermissionIssue("Уведомления", "perm_notifications", "Разрешить", onRequestNotifications),
            )
            if (!batteryExempt) add(
                PermissionIssue(
                    "Исключение из экономии батареи",
                    "perm_battery",
                    "Настроить",
                    onRequestBatteryExemption,
                ),
            )
            if (!fullScreenAllowed) add(
                PermissionIssue(
                    "Полноэкранные уведомления",
                    "perm_fullscreen",
                    "Разрешить",
                    onRequestFullScreenIntent,
                ),
            )
            if (!turnScreenAllowed) add(
                PermissionIssue("Включение экрана", "perm_turn_screen", "Разрешить", onRequestTurnScreenOn),
            )
            if (!ringChannelAllowed) add(
                PermissionIssue(
                    "Громкий канал «Сигнал будильника»",
                    "perm_ring_channel",
                    "Настроить",
                    onRequestRingChannel,
                ),
            )
            if (volumeMuted) add(
                PermissionIssue(
                    "Звук будильника выключен — сигнала не будет слышно",
                    "alarm_volume_muted",
                    "Исправить",
                    { openSettings(context, Settings.ACTION_SOUND_SETTINGS) },
                ),
            )
            if (!dndAccess) add(
                PermissionIssue(
                    "Доступ к режиму «Не беспокоить»",
                    "perm_dnd_access",
                    "Разрешить",
                    onRequestDndAccess,
                    "Без доступа приложение не узнает, глушит ли режим будильник",
                ),
            )
            if (dndBlocks) add(
                PermissionIssue(
                    "Режим «Не беспокоить» может заглушать будильник",
                    "dnd_blocks_alarms",
                    "Настроить",
                    { openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                ),
            )
        }
        if (permissionIssues.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ScreenCard("Разрешения и предупреждения") {
                permissionIssues.forEach { issue ->
                    PermissionRow(
                        text = issue.text,
                        tag = issue.tag,
                        action = issue.action,
                        subtitle = issue.subtitle,
                        onAction = issue.onAction,
                    )
                }
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
    stoppedCycle: SettingsStore.StoppedCycle?,
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
    val next = alarms.filter { it.scheduled }.mapNotNull { alarm ->
        val from = stoppedCycle?.scheduleFrom(alarm.id, nowMillis) ?: nowMillis
        alarm to SchedulePlanner.nextTrigger(from, alarm.toSettings(settings))
    }.minByOrNull { it.second.atMillis }
    if (next == null) {
        Text(
            "Нет включённых будильников.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val (alarm, trigger) = next
    val minutesUntil = (trigger.atMillis - nowMillis + 59_999L) / 60_000L
    val whenText = if (minutesUntil <= 0L) "меньше чем через минуту"
    else "через ${formatDuration(minutesUntil.toInt())}"
    Text(
        "Ближайший будильник включится $whenText",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        "${formatWhen(trigger.atMillis, nowMillis)} · ${alarm.daysLabel}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionRow(
    text: String,
    tag: String,
    action: String,
    subtitle: String = "Не разрешено — будильник может не сработать",
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
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onAction, modifier = Modifier.testTag(tag)) {
            Text(action)
        }
    }
}

private data class PermissionIssue(
    val text: String,
    val tag: String,
    val action: String,
    val onAction: () -> Unit,
    val subtitle: String = "Не разрешено — будильник может не сработать",
)

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
