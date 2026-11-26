package com.gavrovis.navyhod.ui

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.app.NotificationManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.gavrovis.navyhod.AlarmItem
import com.gavrovis.navyhod.AlarmScheduler
import com.gavrovis.navyhod.AlarmSettings
import com.gavrovis.navyhod.LocaleHelper
import com.gavrovis.navyhod.MainActivity
import com.gavrovis.navyhod.R
import com.gavrovis.navyhod.SchedulePlanner
import com.gavrovis.navyhod.SettingsStore
import com.gavrovis.navyhod.StatusNotifier

@Composable
fun SettingsTab(
    settings: AlarmSettings,
    alarms: List<AlarmItem>,
    activeCycle: SettingsStore.ActiveCycle?,
    stoppedCycle: SettingsStore.StoppedCycle?,
    nowMillis: Long,
    darkTheme: Boolean,
    languageTag: String?,
    onChange: (AlarmSettings) -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    onLanguageChange: (String?) -> Unit,
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
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("settings_title"),
        )
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_next_signal)) {
            NextSignalInfo(alarms, settings, activeCycle, stoppedCycle, nowMillis)
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_signals)) {
            Text(
                stringResource(R.string.settings_signals_hint),
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
                stringResource(R.string.settings_interval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_voice)) {
            ToggleRow(
                title = stringResource(R.string.settings_announce_signals),
                checked = settings.announceTimeOnSignals,
                tag = "time_signals",
                onCheckedChange = { onChange(settings.copy(announceTimeOnSignals = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.settings_announce_departure),
                checked = settings.announceTimeOnDeparture,
                tag = "time_departure",
                onCheckedChange = { onChange(settings.copy(announceTimeOnDeparture = it)) },
            )
            ToggleRow(
                title = stringResource(R.string.settings_announce_late),
                checked = settings.announceTimeOnLate,
                tag = "time_late",
                onCheckedChange = { onChange(settings.copy(announceTimeOnLate = it)) },
            )
            Spacer(Modifier.height(8.dp))
            ValueSlider(
                title = stringResource(R.string.settings_tts_lead),
                valueLabel = formatDuration(settings.ttsLeadMinutes),
                value = settings.ttsLeadMinutes,
                range = 5..120,
                step = 5,
                tag = "tts_lead",
                onChange = { onChange(settings.copy(ttsLeadMinutes = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_ring_duration)) {
            ValueSlider(
                title = stringResource(R.string.settings_ring_plays),
                valueLabel = stringResource(R.string.seconds, settings.ringSeconds),
                value = settings.ringSeconds,
                range = 20..60,
                step = 5,
                tag = "ring_seconds",
                onChange = { onChange(settings.copy(ringSeconds = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_notifications)) {
            ToggleRow(
                title = stringResource(R.string.settings_pre_notify),
                subtitle = stringResource(R.string.settings_pre_notify_hint),
                checked = settings.preNotifyEnabled,
                tag = "pre_notify",
                onCheckedChange = { onChange(settings.copy(preNotifyEnabled = it)) },
            )
        }

        val missingHint = stringResource(R.string.permission_missing_hint)
        val configure = stringResource(R.string.action_configure)
        val allow = stringResource(R.string.action_allow)
        val permissionIssues = buildList {
            if (!exactAllowed) add(
                PermissionIssue(stringResource(R.string.perm_exact), "perm_exact", configure, onRequestExactAlarms),
            )
            if (!notificationsAllowed) add(
                PermissionIssue(
                    stringResource(R.string.perm_notifications),
                    "perm_notifications",
                    allow,
                    onRequestNotifications,
                ),
            )
            if (!batteryExempt) add(
                PermissionIssue(
                    stringResource(R.string.perm_battery),
                    "perm_battery",
                    configure,
                    onRequestBatteryExemption,
                ),
            )
            if (!fullScreenAllowed) add(
                PermissionIssue(
                    stringResource(R.string.perm_fullscreen),
                    "perm_fullscreen",
                    allow,
                    onRequestFullScreenIntent,
                ),
            )
            if (!turnScreenAllowed) add(
                PermissionIssue(
                    stringResource(R.string.perm_turn_screen),
                    "perm_turn_screen",
                    allow,
                    onRequestTurnScreenOn,
                ),
            )
            if (!ringChannelAllowed) add(
                PermissionIssue(
                    stringResource(R.string.perm_ring_channel),
                    "perm_ring_channel",
                    configure,
                    onRequestRingChannel,
                ),
            )
            if (volumeMuted) add(
                PermissionIssue(
                    stringResource(R.string.perm_volume),
                    "alarm_volume_muted",
                    stringResource(R.string.action_fix),
                    { openSettings(context, Settings.ACTION_SOUND_SETTINGS) },
                ),
            )
            if (!dndAccess) add(
                PermissionIssue(
                    stringResource(R.string.perm_dnd_access),
                    "perm_dnd_access",
                    allow,
                    onRequestDndAccess,
                    stringResource(R.string.perm_dnd_access_hint),
                ),
            )
            if (dndBlocks) add(
                PermissionIssue(
                    stringResource(R.string.perm_dnd_blocks),
                    "dnd_blocks_alarms",
                    configure,
                    { openSettings(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                ),
            )
        }
        if (permissionIssues.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            ScreenCard(stringResource(R.string.permissions_title)) {
                permissionIssues.forEach { issue ->
                    PermissionRow(
                        text = issue.text,
                        tag = issue.tag,
                        action = issue.action,
                        subtitle = issue.subtitle ?: missingHint,
                        onAction = issue.onAction,
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_appearance)) {
            ToggleRow(
                title = stringResource(R.string.settings_dark_theme),
                subtitle = stringResource(R.string.settings_dark_theme_hint),
                checked = darkTheme,
                tag = "theme_dark",
                onCheckedChange = onDarkThemeChange,
            )
        }
        Spacer(Modifier.height(12.dp))

        LanguageCard(languageTag, onLanguageChange)
        Spacer(Modifier.height(12.dp))

        ScreenCard(stringResource(R.string.settings_diagnostics)) {
            Text(
                stringResource(R.string.settings_diagnostics_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onExportLogs, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_send_logs))
            }
        }
        Spacer(Modifier.height(12.dp))

        GitHubCard()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GitHubCard() {
    val uriHandler = LocalUriHandler.current
    ScreenCard("GitHub") {
        Surface(
            onClick = { uriHandler.openUri(GITHUB_URL) },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("github_link"),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    stringResource(R.string.settings_github),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    GITHUB_URL.removePrefix("https://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun LanguageCard(currentTag: String?, onLanguageChange: (String?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val currentLabel = currentTag?.let { LocaleHelper.label(it) }
        ?: stringResource(R.string.language_system)
    ScreenCard(stringResource(R.string.settings_language)) {
        Surface(
            onClick = { showDialog = true },
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().testTag("language_picker"),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_language),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    currentLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LanguageOption(
                        label = stringResource(R.string.language_system),
                        selected = currentTag == null,
                        tag = "language_option_system",
                    ) {
                        showDialog = false
                        onLanguageChange(null)
                    }
                    LocaleHelper.LANGUAGES.filter { it.tag != null }.forEach { language ->
                        LanguageOption(
                            label = language.label,
                            selected = language.tag == currentTag,
                            tag = "language_option_${language.tag}",
                        ) {
                            showDialog = false
                            onLanguageChange(language.tag)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LanguageOption(
    label: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect)
            .padding(vertical = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
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
            stringResource(
                if (activeCycle.remindersOnly) R.string.banner_reminders else R.string.banner_cycle,
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.banner_leave, formatClock(activeCycle.leaveMillis)),
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
            stringResource(R.string.settings_no_alarms),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val (alarm, trigger) = next
    val minutesUntil = (trigger.atMillis - nowMillis + 59_999L) / 60_000L
    Text(
        if (minutesUntil <= 0L) stringResource(R.string.settings_next_soon)
        else stringResource(R.string.settings_next_in, formatDuration(minutesUntil.toInt())),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(R.string.settings_when_days, formatWhen(trigger.atMillis, nowMillis), daysLabel(alarm.days)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PermissionRow(
    text: String,
    tag: String,
    action: String,
    subtitle: String,
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
    val subtitle: String? = null,
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

private const val GITHUB_URL = "https://github.com/Gavabkv/NaVyhod"
