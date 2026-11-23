package com.example.dovykhoda.ui

import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.dovykhoda.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.dovykhoda.AlarmRingingActivity
import com.example.dovykhoda.AlarmScheduler
import com.example.dovykhoda.AlarmSettings
import com.example.dovykhoda.AlarmService
import com.example.dovykhoda.AlarmSettings.Companion.formatTime
import com.example.dovykhoda.MainActivity
import com.example.dovykhoda.SchedulePlanner
import com.example.dovykhoda.SettingsStore
import com.example.dovykhoda.SpeechText
import com.example.dovykhoda.StatusNotifier
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

private enum class TimeTarget { WAKE, LEAVE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmScreen(
    onRequestExactAlarms: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
    onRequestTurnScreenOn: () -> Unit,
    onRequestRingChannel: () -> Unit,
    onExportLogs: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }

    var settings by remember { mutableStateOf(store.load()) }
    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    var pendingDisable by remember { mutableStateOf(false) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var exactAlarmsAllowed by remember { mutableStateOf(AlarmScheduler.canScheduleExact(context)) }
    var notificationsAllowed by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    var batteryExempt by remember {
        mutableStateOf(MainActivity.isIgnoringBatteryOptimizations(context))
    }
    var fullScreenIntentAllowed by remember {
        mutableStateOf(MainActivity.canUseFullScreenIntent(context))
    }
    var turnScreenOnAllowed by remember {
        mutableStateOf(MainActivity.canTurnScreenOn(context))
    }
    var ringChannelAllowed by remember {
        mutableStateOf(StatusNotifier.isRingChannelAvailable(context))
    }

    DisposableEffect(store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            settings = store.load()
            nowMillis = System.currentTimeMillis()
        }
        store.registerListener(listener)
        onDispose { store.unregisterListener(listener) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = store.load()
                nowMillis = System.currentTimeMillis()
                exactAlarmsAllowed = AlarmScheduler.canScheduleExact(context)
                notificationsAllowed =
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                batteryExempt = MainActivity.isIgnoringBatteryOptimizations(context)
                fullScreenIntentAllowed = MainActivity.canUseFullScreenIntent(context)
                turnScreenOnAllowed = MainActivity.canTurnScreenOn(context)
                ringChannelAllowed = StatusNotifier.isRingChannelAvailable(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            nowMillis = System.currentTimeMillis()
        }
    }

    fun update(newSettings: AlarmSettings) {
        settings = newSettings
        store.save(newSettings)
        if (!newSettings.enabled) AlarmService.disable(context)
        AlarmScheduler.reschedule(context, newSettings)
        StatusNotifier.refresh(context, newSettings)
        nowMillis = System.currentTimeMillis()
    }

    val now = remember(nowMillis) {
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    val activeCycle = remember(settings, nowMillis) { store.activeCycle() }
    val remindersActive = settings.enabled && activeCycle != null &&
        (activeCycle.remindersOnly || nowMillis >= activeCycle.leaveMillis)
    val scheduledTrigger = remember(settings, nowMillis, activeCycle) {
        when {
            !settings.enabled -> null
            remindersActive -> {
                val cycle = activeCycle!!
                val next = if (cycle.remindersOnly) {
                    SchedulePlanner.departureReminders(cycle.leaveMillis).firstOrNull { it > nowMillis }
                } else null
                val at = if (cycle.remindersOnly) next ?: cycle.leaveMillis
                    else cycle.leaveMillis +
                        SchedulePlanner.nextLateMinute(nowMillis, cycle.leaveMillis) * 60_000L
                SchedulePlanner.ScheduledTrigger(at, cycle.wakeMillis, cycle.leaveMillis)
            }
            else -> SchedulePlanner.nextTrigger(nowMillis, settings)
        }
    }
    val nextTrigger = scheduledTrigger?.let {
        Instant.ofEpochMilli(it.atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
    }
    val minutesToLeave = scheduledTrigger?.let {
        SchedulePlanner.remainingMinutes(it.atMillis, it.cycleLeaveMillis)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EnabledCard(
                settings = settings,
                remindersActive = remindersActive,
                onToggle = { enabled ->
                    if (enabled) update(settings.copy(enabled = true)) else pendingDisable = true
                },
                onRequestStop = {
                    context.startActivity(Intent(context, AlarmRingingActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(AlarmRingingActivity.EXTRA_CONFIRM_STOP, true)
                        .putExtra(AlarmRingingActivity.EXTRA_REMAINING,
                            SchedulePlanner.remainingMinutes(nowMillis, activeCycle!!.leaveMillis)))
                },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TimeTile(
                    label = "Пробуждение",
                    value = settings.wakeLabel,
                    enabled = settings.enabled && !settings.departureTimeOnly,
                    modifier = Modifier.weight(1f).testTag("wake_time"),
                    onClick = { timeTarget = TimeTarget.WAKE },
                )
                TimeTile(
                    label = "Выход",
                    value = settings.leaveLabel,
                    enabled = settings.enabled,
                    modifier = Modifier.weight(1f).testTag("leave_time"),
                    onClick = { timeTarget = TimeTarget.LEAVE },
                )
            }

            SettingsCard(title = "Настройка времени", enabled = settings.enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Задавать только время выхода",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.departureTimeOnly,
                        onCheckedChange = { update(settings.withDepartureTimeOnly(it)) },
                        enabled = settings.enabled,
                        modifier = Modifier.testTag("departure_time_only"),
                    )
                }
                PreparationTimeSlider(
                    minutes = settings.preparationMinutes,
                    departureTimeOnly = settings.departureTimeOnly,
                    enabled = settings.enabled,
                    onChange = { update(settings.withPreparationMinutes(it)) },
                )
            }

            SettingsCard(
                title = "Интервал музыки",
                subtitle = "Первый сигнал — в момент пробуждения",
                enabled = settings.enabled,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10).forEach { minutes ->
                        FilterChip(
                            selected = settings.intervalMinutes == minutes,
                            onClick = { update(settings.copy(intervalMinutes = minutes)) },
                            enabled = settings.enabled,
                            label = { Text("Каждые $minutes мин") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }

            SettingsCard(
                title = "Голос",
                subtitle = "Системный синтез речи (TTS), по-русски",
                enabled = settings.enabled,
            ) {
                Text(
                    text = "Говорить текущее время",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Например: «Сейчас 7 часов 5 минут. До выхода осталось 25 минут».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TimeToggleRow(
                    label = "В сигналах будильника",
                    checked = settings.announceTimeOnSignals,
                    tag = "time_signals",
                    enabled = settings.enabled,
                    onCheckedChange = { update(settings.copy(announceTimeOnSignals = it)) },
                )
                TimeToggleRow(
                    label = "В напоминаниях о выходе",
                    checked = settings.announceTimeOnDeparture,
                    tag = "time_departure",
                    enabled = settings.enabled,
                    onCheckedChange = { update(settings.copy(announceTimeOnDeparture = it)) },
                )
                TimeToggleRow(
                    label = "В напоминаниях об опоздании",
                    checked = settings.announceTimeOnLate,
                    tag = "time_late",
                    enabled = settings.enabled,
                    onCheckedChange = { update(settings.copy(announceTimeOnLate = it)) },
                )

                HorizontalDivider()
                LeadTimeSlider(
                    leadMinutes = settings.ttsLeadMinutes,
                    enabled = settings.enabled,
                    onChange = { update(settings.copy(ttsLeadMinutes = it)) },
                )
                Text(
                    text = "Голос на сигналах звучит с этого момента и до самого выхода. " +
                        "Напоминания о выходе и об опоздании звучат всегда.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(title = "Длительность сигнала", enabled = settings.enabled) {
                RingDurationSlider(
                    seconds = settings.ringSeconds,
                    enabled = settings.enabled,
                    onChange = { update(settings.copy(ringSeconds = it)) },
                )
            }

            NotificationsCard(
                settings = settings,
                enabled = settings.enabled,
                onToggle = { update(settings.copy(preNotifyEnabled = it)) },
            )

            StatusCard(
                enabled = settings.enabled,
                nextTrigger = nextTrigger,
                now = now,
                minutesToLeave = minutesToLeave,
                settings = settings,
                nowMillis = nowMillis,
                nextTriggerMillis = scheduledTrigger?.atMillis,
                remindersActive = remindersActive,
                remindersOnly = activeCycle?.remindersOnly == true,
            )

            PermissionsCard(
                exactAlarmsAllowed = exactAlarmsAllowed,
                notificationsAllowed = notificationsAllowed,
                batteryExempt = batteryExempt,
                fullScreenIntentAllowed = fullScreenIntentAllowed,
                turnScreenOnAllowed = turnScreenOnAllowed,
                ringChannelAllowed = ringChannelAllowed,
                onRequestExactAlarms = onRequestExactAlarms,
                onRequestNotifications = onRequestNotifications,
                onRequestBatteryExemption = onRequestBatteryExemption,
                onRequestFullScreenIntent = onRequestFullScreenIntent,
                onRequestTurnScreenOn = onRequestTurnScreenOn,
                onRequestRingChannel = onRequestRingChannel,
            )

            SettingsCard(title = "Диагностика будильника") {
                Text(
                    "Логи сохраняются по дням в папке приложения. Архив включает сведения о расписании, разрешениях и состоянии устройства для поиска причин задержек.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onExportLogs, modifier = Modifier.fillMaxWidth()) {
                    Text("Поделиться логами")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    timeTarget?.let { target ->
        val isWake = target == TimeTarget.WAKE
        TimePickerDialog(
            title = if (isWake) "Время пробуждения" else "Время выхода",
            initialHour = if (isWake) settings.wakeHour else settings.leaveHour,
            initialMinute = if (isWake) settings.wakeMinute else settings.leaveMinute,
            onDismiss = { timeTarget = null },
            onConfirm = { hour, minute ->
                timeTarget = null
                update(
                    if (isWake) {
                        settings.withWakeTime(hour, minute)
                    } else {
                        settings.withLeaveTime(hour, minute)
                    }
                )
            },
        )
    }

    if (pendingDisable) {
        AlertDialog(
            onDismissRequest = { pendingDisable = false },
            title = { Text("Выключить будильник?") },
            text = {
                Text(
                    "Сигналы и напоминания не прозвучат, пока расписание выключено. " +
                        "Время и настройки сохранятся — включить можно этим же переключателем."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDisable = false
                        update(settings.copy(enabled = false))
                    },
                ) { Text("Выключить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDisable = false }) { Text("Отмена") }
            },
        )
    }
}

// ------------------------------------------------------------------ карточки

@Composable
private fun EnabledCard(
    settings: AlarmSettings,
    remindersActive: Boolean,
    onToggle: (Boolean) -> Unit,
    onRequestStop: () -> Unit,
) {
    val container = if (settings.enabled) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (settings.enabled) "Будильник включен" else "Будильник выключен",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (!settings.enabled) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Настройки ниже заблокированы. Включите переключатель, " +
                                "чтобы менять время и сигналы.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = settings.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.testTag("alarm_enabled"),
                )
            }
            if (remindersActive) {
                Button(
                    onClick = onRequestStop,
                    modifier = Modifier
                        .padding(start = 18.dp, end = 18.dp, bottom = 16.dp)
                        .fillMaxWidth(),
                ) {
                    Text("Отключить будильник")
                }
            }
        }
    }
}

@Composable
private fun TimeTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.6f)
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else Color.Gray,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.displaySmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.45f)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
private fun TimeToggleRow(
    label: String,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun LeadTimeSlider(leadMinutes: Int, enabled: Boolean = true, onChange: (Int) -> Unit) {
    var value by remember(leadMinutes) { mutableFloatStateOf(leadMinutes.toFloat()) }
    val shown = snapToStep(value, step = 5, min = 0, max = 90)
    Text(
        text = "Начинать за $shown " +
            SpeechText.plural(shown, "минуту", "минуты", "минут") + " до выхода",
        style = MaterialTheme.typography.bodyLarge,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = 0f..90f,
        steps = 17,
        enabled = enabled,
        onValueChangeFinished = { onChange(snapToStep(value, step = 5, min = 0, max = 90)) },
    )
}

@Composable
private fun PreparationTimeSlider(
    minutes: Int,
    departureTimeOnly: Boolean,
    enabled: Boolean = true,
    onChange: (Int) -> Unit,
) {
    var value by remember(minutes) { mutableFloatStateOf(minutes.toFloat()) }
    val shown = snapToStep(value, step = 5, min = 40, max = 150)
    val duration = if (shown < 60) {
        "$shown ${SpeechText.plural(shown, "минута", "минуты", "минут")}"
    } else {
        val hours = shown / 60
        val minutesLeft = shown % 60
        "$hours ${SpeechText.plural(hours, "час", "часа", "часов")}" +
            if (minutesLeft == 0) "" else
                " $minutesLeft ${SpeechText.plural(minutesLeft, "минута", "минуты", "минут")}"
    }
    Text(
        text = (if (departureTimeOnly) "Время на сборы: " else "До выхода: ") + duration,
        style = MaterialTheme.typography.bodyLarge,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = 40f..150f,
        steps = 21,
        enabled = enabled,
        onValueChangeFinished = { onChange(snapToStep(value, step = 5, min = 40, max = 150)) },
        modifier = Modifier.testTag("preparation_minutes"),
    )
    Text("От 40 минут до 2,5 часов", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RingDurationSlider(seconds: Int, enabled: Boolean = true, onChange: (Int) -> Unit) {
    var value by remember(seconds) { mutableFloatStateOf(seconds.toFloat()) }
    val shown = snapToStep(value, step = 10, min = 20, max = 60)
    Text(
        text = "$shown секунд музыки на каждый сигнал",
        style = MaterialTheme.typography.bodyLarge,
    )
    Slider(
        value = value,
        onValueChange = { value = it },
        valueRange = 20f..60f,
        steps = 3,
        enabled = enabled,
        onValueChangeFinished = { onChange(snapToStep(value, step = 10, min = 20, max = 60)) },
        modifier = Modifier.testTag("ring_seconds"),
    )
}

@Composable
private fun StatusCard(
    enabled: Boolean,
    nextTrigger: LocalDateTime?,
    now: LocalDateTime,
    minutesToLeave: Int?,
    settings: AlarmSettings,
    nowMillis: Long,
    nextTriggerMillis: Long?,
    remindersActive: Boolean,
    remindersOnly: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Ближайший сигнал",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (enabled && nextTrigger != null && nextTriggerMillis != null) {
                Text(
                    text = formatWhen(nextTrigger, now),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                val minutes = minutesToLeave ?: 0
                val withVoice = remindersActive || minutes <= settings.ttsLeadMinutes
                val leaveText = if (minutes <= 0) {
                    "Пора выходить"
                } else {
                    "До выхода $minutes " +
                        SpeechText.plural(minutes, "минута", "минуты", "минут")
                }
                Text(
                    text = leaveText + if (withVoice) " · с голосом" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )

                val delta = ((nextTriggerMillis - nowMillis) / 60_000L)
                    .coerceAtLeast(0L)
                Text(
                    text = if (remindersOnly) {
                        "через ${formatDuration(delta)} · без музыки; " +
                            "за 20, 15, 10 и 5 минут и в момент выхода, затем отключение"
                    } else if (remindersActive) {
                        "через ${formatDuration(delta)} · без музыки; " +
                            "опоздания каждую минуту до отключения"
                    } else {
                        "через ${formatDuration(delta)} · далее каждые " +
                            "${settings.intervalMinutes} мин до ${settings.leaveLabel}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Расписание выключено",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Включите переключатель выше — первый сигнал прозвучит в " +
                        "${settings.wakeLabel}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NotificationsCard(
    settings: AlarmSettings,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    SettingsCard(
        title = "Уведомление",
        subtitle = "«Будильник заведён» появляется за 12 часов и висит до конца цикла",
        enabled = enabled,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Показывать уведомление",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Предварительное уведомление можно смахнуть до следующего цикла. " +
                        "Во время сигнала и напоминаний служебное уведомление показывается всегда.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.preNotifyEnabled,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun PermissionsCard(
    exactAlarmsAllowed: Boolean,
    notificationsAllowed: Boolean,
    batteryExempt: Boolean,
    fullScreenIntentAllowed: Boolean,
    turnScreenOnAllowed: Boolean,
    ringChannelAllowed: Boolean,
    onRequestExactAlarms: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
    onRequestTurnScreenOn: () -> Unit,
    onRequestRingChannel: () -> Unit,
) {
    val needNotifications = !notificationsAllowed
    if (exactAlarmsAllowed && !needNotifications && batteryExempt &&
        fullScreenIntentAllowed && turnScreenOnAllowed && ringChannelAllowed
    ) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Чтобы сигнал сработал вовремя",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!exactAlarmsAllowed) {
                PermissionRow(
                    text = "Нет разрешения на точные будильники",
                    action = "Разрешить",
                    onAction = onRequestExactAlarms,
                )
            }
            if (needNotifications) {
                PermissionRow(
                    text = "Уведомления отключены",
                    action = "Разрешить",
                    onAction = onRequestNotifications,
                )
            }
            if (!batteryExempt) {
                PermissionRow(
                    text = "Разрешите «${stringResource(R.string.app_name)}» работать без экономии батареи",
                    action = "Настроить",
                    onAction = onRequestBatteryExemption,
                )
            }
            if (!fullScreenIntentAllowed) {
                PermissionRow(
                    text = "Нет разрешения на всплывающее окно сигнала",
                    action = "Разрешить",
                    onAction = onRequestFullScreenIntent,
                )
            }
            if (!turnScreenOnAllowed) {
                PermissionRow(
                    text = "Разрешите будильнику включать экран",
                    action = "Разрешить",
                    onAction = onRequestTurnScreenOn,
                )
            }
            if (!ringChannelAllowed) {
                PermissionRow(
                    text = "Уведомление «Сигнал будильника» заглушено — экран не разбудить",
                    action = "Настроить",
                    onAction = onRequestRingChannel,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(text: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onAction) { Text(action) }
    }
}

// ------------------------------------------------------------------ диалог времени

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    var hour by remember(initialHour) {
        mutableStateOf(TextFieldValue("%02d".format(initialHour), TextRange(0, 2)))
    }
    var minute by remember(initialMinute) {
        mutableStateOf(TextFieldValue("%02d".format(initialMinute), TextRange(0, 2)))
    }
    val hourFocus = remember { FocusRequester() }
    val minuteFocus = remember { FocusRequester() }
    val parsedHour = hour.text.toIntOrNull()
    val parsedMinute = minute.text.toIntOrNull()
    val valid = parsedHour != null && parsedHour in 0..23 &&
        parsedMinute != null && parsedMinute in 0..59

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    hourFocus.requestFocus()
                    keyboard?.show()
                }
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = hour,
                        onValueChange = {
                            if (it.text.length <= 2 && it.text.all { digit -> digit in '0'..'9' }) hour = it
                        },
                        modifier = Modifier.weight(1f).focusRequester(hourFocus),
                        label = { Text("Часы") },
                        singleLine = true,
                        isError = parsedHour == null || parsedHour !in 0..23,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                        ),
                        keyboardActions = KeyboardActions(onNext = { minuteFocus.requestFocus() }),
                    )
                    Text(":", style = MaterialTheme.typography.headlineMedium)
                    OutlinedTextField(
                        value = minute,
                        onValueChange = {
                            if (it.text.length <= 2 && it.text.all { digit -> digit in '0'..'9' }) minute = it
                        },
                        modifier = Modifier.weight(1f).focusRequester(minuteFocus),
                        label = { Text("Минуты") },
                        singleLine = true,
                        isError = parsedMinute == null || parsedMinute !in 0..59,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (valid) onConfirm(parsedHour!!, parsedMinute!!)
                        }),
                    )
                }
                Text("24-часовой формат · часы 0–23, минуты 0–59",
                    style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Отмена") }
                    TextButton(
                        enabled = valid,
                        onClick = { onConfirm(parsedHour!!, parsedMinute!!) },
                    ) { Text("Готово") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ форматирование

private fun snapToStep(value: Float, step: Int, min: Int, max: Int): Int =
    ((value / step).roundToInt() * step).coerceIn(min, max)

private fun formatWhen(trigger: LocalDateTime, now: LocalDateTime): String {
    val today = now.toLocalDate()
    val dayText = when (trigger.toLocalDate()) {
        today -> "сегодня"
        today.plusDays(1) -> "завтра"
        else -> "${trigger.dayOfMonth} ${monthGenitive(trigger.monthValue)}"
    }
    return "$dayText в ${formatTime(trigger.hour, trigger.minute)}"
}

private fun formatDuration(minutes: Long): String = when {
    minutes <= 0L -> "меньше минуты"
    minutes < 60L -> "$minutes мин"
    else -> {
        val hours = minutes / 60
        val rest = minutes % 60
        if (rest == 0L) "$hours ч" else "$hours ч $rest мин"
    }
}

private fun monthGenitive(month: Int): String = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)[(month - 1).coerceIn(0, 11)]
