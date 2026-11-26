package com.gavrovis.navyhod.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gavrovis.navyhod.AlarmItem
import com.gavrovis.navyhod.AlarmScheduler
import com.gavrovis.navyhod.R
import com.gavrovis.navyhod.AlarmService
import com.gavrovis.navyhod.AlarmSettings
import com.gavrovis.navyhod.SchedulePlanner
import com.gavrovis.navyhod.SettingsStore
import com.gavrovis.navyhod.StatusNotifier
import kotlinx.coroutines.delay
import java.time.DayOfWeek

@Composable
fun AppScreen(
    onRequestExactAlarms: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestFullScreenIntent: () -> Unit,
    onRequestTurnScreenOn: () -> Unit,
    onRequestRingChannel: () -> Unit,
    onRequestDndAccess: () -> Unit,
    onExportLogs: () -> Unit,
    darkTheme: Boolean = isSystemInDarkTheme(),
    onDarkThemeChange: (Boolean) -> Unit = {},
    onLanguageChange: (String?) -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val languageTag = remember { store.languageTag() }
    var settings by remember { mutableStateOf(store.load()) }
    var alarms by remember { mutableStateOf(store.alarms()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun reload() {
        settings = store.load()
        alarms = store.alarms()
    }

    fun rescheduleAndNotify() {
        AlarmScheduler.reschedule(context, settings)
        StatusNotifier.refresh(context, settings)
    }

    fun applySettings(updated: AlarmSettings) {
        store.save(updated)
        reload()
        rescheduleAndNotify()
    }

    fun applyAlarm(updated: AlarmItem) {
        store.updateAlarm(updated)
        reload()
        rescheduleAndNotify()
    }

    fun toggleAlarm(id: Long, enabled: Boolean) {
        store.setAlarmEnabled(id, enabled)
        reload()
        rescheduleAndNotify()
    }

    fun addAlarm() {
        val base = alarms.firstOrNull()
        val alarm = AlarmItem(
            id = store.nextAlarmId(),
            wakeHour = base?.wakeHour ?: 7,
            wakeMinute = base?.wakeMinute ?: 0,
            leaveHour = base?.leaveHour ?: 8,
            leaveMinute = base?.leaveMinute ?: 0,
            preparationMinutes = base?.preparationMinutes ?: 90,
            departureTimeOnly = base?.departureTimeOnly ?: false,
            days = AlarmItem.ALL_DAYS,
            enabled = true,
        )
        store.addAlarm(alarm)
        reload()
        rescheduleAndNotify()
        editingId = alarm.id
    }

    fun deleteAlarm(id: Long) {
        store.deleteAlarm(id)
        AlarmScheduler.cancelAlarm(context, id)
        val cycle = store.activeCycle()
        if (cycle != null && (cycle.alarmId == id || store.alarm(cycle.alarmId) == null)) {
            store.clearActiveCycle()
        }
        reload()
        rescheduleAndNotify()
        editingId = null
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                reload()
                nowMillis = System.currentTimeMillis()
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

    val editing = editingId?.let { id -> alarms.firstOrNull { it.id == id } }
    if (editingId != null && editing == null) editingId = null

    Scaffold(
        bottomBar = {
            val tabColors = NavigationBarItemDefaults.colors(
                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                indicatorColor = MaterialTheme.colorScheme.primary,
            )
            NavigationBar(
                containerColor = if (darkTheme) {
                    MaterialTheme.colorScheme.surfaceContainer
                } else {
                    Color.White
                },
            ) {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0; editingId = null },
                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_alarms)) },
                    colors = tabColors,
                    modifier = Modifier.testTag("tab_alarms"),
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1; editingId = null },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                    colors = tabColors,
                    modifier = Modifier.testTag("tab_settings"),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                editing != null -> AlarmEditor(
                    alarm = editing,
                    onBack = { editingId = null },
                    onChange = ::applyAlarm,
                    onDelete = { deleteAlarm(editing.id) },
                )
                tab == 0 -> AlarmsTab(
                    alarms = alarms,
                    settings = settings,
                    activeCycle = store.activeCycle(),
                    stoppedCycle = store.stoppedCycle(),
                    nowMillis = nowMillis,
                    onToggle = ::toggleAlarm,
                    onOpen = { editingId = it },
                    onAdd = ::addAlarm,
                )
                else -> SettingsTab(
                    settings = settings,
                    alarms = alarms,
                    activeCycle = store.activeCycle(),
                    stoppedCycle = store.stoppedCycle(),
                    nowMillis = nowMillis,
                    darkTheme = darkTheme,
                    languageTag = languageTag,
                    onChange = ::applySettings,
                    onDarkThemeChange = onDarkThemeChange,
                    onLanguageChange = onLanguageChange,
                    onRequestExactAlarms = onRequestExactAlarms,
                    onRequestNotifications = onRequestNotifications,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    onRequestFullScreenIntent = onRequestFullScreenIntent,
                    onRequestTurnScreenOn = onRequestTurnScreenOn,
                    onRequestRingChannel = onRequestRingChannel,
                    onRequestDndAccess = onRequestDndAccess,
                    onExportLogs = onExportLogs,
                )
            }
        }
    }
}

@Composable
private fun AlarmsTab(
    alarms: List<AlarmItem>,
    settings: AlarmSettings,
    activeCycle: SettingsStore.ActiveCycle?,
    stoppedCycle: SettingsStore.StoppedCycle?,
    nowMillis: Long,
    onToggle: (Long, Boolean) -> Unit,
    onOpen: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    var confirmStop by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.alarms_title),
                Modifier.weight(1f).testTag("alarms_title"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onAdd, modifier = Modifier.testTag("add_alarm")) {
                Text(stringResource(R.string.alarms_add))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (activeCycle != null) {
            CycleBanner(activeCycle, onStop = { confirmStop = true })
            Spacer(Modifier.height(12.dp))
        }
        if (alarms.isEmpty()) {
            Text(
                stringResource(R.string.alarms_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        alarms.forEach { alarm ->
            AlarmCard(
                alarm = alarm,
                settings = settings,
                stoppedCycle = stoppedCycle,
                nowMillis = nowMillis,
                onToggle = { onToggle(alarm.id, it) },
                onOpen = { onOpen(alarm.id) },
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.stop_signals_title)) },
            text = { Text(stringResource(R.string.stop_signals_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    AlarmService.stopAlarm(context)
                }) { Text(stringResource(R.string.yes_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun CycleBanner(cycle: SettingsStore.ActiveCycle, onStop: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (cycle.remindersOnly) R.string.banner_reminders else R.string.banner_cycle,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.banner_leave, formatClock(cycle.leaveMillis)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onStop, modifier = Modifier.testTag("stop_cycle")) {
                Text(stringResource(R.string.banner_stop))
            }
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: AlarmItem,
    settings: AlarmSettings,
    stoppedCycle: SettingsStore.StoppedCycle?,
    nowMillis: Long,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth().testTag("alarm_card_${alarm.id}")) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    alarm.wakeLabel,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Light,
                    color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
                Text(
                    stringResource(R.string.alarm_leave_days, alarm.leaveLabel, daysLabel(alarm.days)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alarm.scheduled) {
                    val from = stoppedCycle?.scheduleFrom(alarm.id, nowMillis) ?: nowMillis
                    val next = SchedulePlanner.nextTrigger(from, alarm.toSettings(settings))
                    Text(
                        stringResource(R.string.alarm_next_signal, formatWhen(next.atMillis, nowMillis)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Switch(
                checked = alarm.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.testTag("alarm_switch_${alarm.id}"),
            )
        }
    }
}

@Composable
private fun AlarmEditor(
    alarm: AlarmItem,
    onBack: () -> Unit,
    onChange: (AlarmItem) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("editor_back")) {
                Text(stringResource(R.string.back))
            }
            Text(
                stringResource(R.string.editor_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(8.dp))
        ScreenCard {
            ToggleRow(
                title = stringResource(R.string.editor_enabled),
                subtitle = stringResource(R.string.editor_enabled_hint),
                checked = alarm.enabled,
                tag = "editor_enabled",
                onCheckedChange = { onChange(alarm.copy(enabled = it)) },
            )
        }
        Spacer(Modifier.height(12.dp))
        ScreenCard(stringResource(R.string.editor_time)) {
            TimeTile(
                label = stringResource(R.string.editor_wake),
                value = alarm.wakeLabel,
                enabled = alarm.enabled && !alarm.departureTimeOnly,
                tag = "editor_wake_time",
                onPick = { hour, minute -> onChange(alarm.withWakeTime(hour, minute)) },
            )
            Spacer(Modifier.height(10.dp))
            TimeTile(
                label = stringResource(R.string.editor_leave),
                value = alarm.leaveLabel,
                enabled = alarm.enabled,
                tag = "editor_leave_time",
                onPick = { hour, minute -> onChange(alarm.withLeaveTime(hour, minute)) },
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = stringResource(R.string.editor_departure_only),
                subtitle = stringResource(R.string.editor_departure_only_hint),
                checked = alarm.departureTimeOnly,
                enabled = alarm.enabled,
                tag = "editor_departure_only",
                onCheckedChange = { onChange(alarm.withDepartureTimeOnly(it)) },
            )
        }
        Spacer(Modifier.height(12.dp))
        ScreenCard(stringResource(R.string.editor_preparation)) {
            ValueSlider(
                title = stringResource(R.string.editor_preparation_range),
                valueLabel = formatDuration(alarm.preparationMinutes),
                value = alarm.preparationMinutes,
                range = 40..150,
                step = 5,
                enabled = alarm.enabled,
                tag = "editor_preparation",
                onChange = { onChange(alarm.withPreparationMinutes(it)) },
            )
        }
        Spacer(Modifier.height(12.dp))
        ScreenCard(stringResource(R.string.editor_days)) {
            DaysEditor(alarm, onChange)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth().testTag("delete_alarm"),
        ) {
            Text(stringResource(R.string.editor_delete), color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_alarm_title)) },
            text = {
                Text(stringResource(R.string.delete_alarm_text, alarm.wakeLabel, alarm.leaveLabel))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DaysEditor(alarm: AlarmItem, onChange: (AlarmItem) -> Unit) {
    fun toggleDay(day: DayOfWeek) {
        val next = if (day in alarm.days) alarm.days - day else alarm.days + day
        if (next.isEmpty()) return
        onChange(alarm.withDays(next))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        SelectChip(
            text = stringResource(R.string.days_all),
            selected = alarm.days.size == DayOfWeek.entries.size,
            enabled = alarm.enabled,
            tag = "day_preset_all",
        ) { onChange(alarm.withDays(AlarmItem.ALL_DAYS)) }
        SelectChip(
            text = stringResource(R.string.days_weekdays),
            selected = alarm.days == AlarmItem.WEEKDAYS,
            enabled = alarm.enabled,
            tag = "day_preset_weekdays",
        ) { onChange(alarm.withDays(AlarmItem.WEEKDAYS)) }
        SelectChip(
            text = stringResource(R.string.days_weekend),
            selected = alarm.days == AlarmItem.WEEKEND,
            enabled = alarm.enabled,
            tag = "day_preset_weekend",
        ) { onChange(alarm.withDays(AlarmItem.WEEKEND)) }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        DayOfWeek.entries.forEach { day ->
            SelectChip(
                text = dayShort(day),
                selected = day in alarm.days,
                enabled = alarm.enabled,
                tag = "day_chip_${day.name}",
            ) { toggleDay(day) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.days_need_one),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
