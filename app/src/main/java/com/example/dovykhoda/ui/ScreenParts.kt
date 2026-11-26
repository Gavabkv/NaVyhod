package com.example.dovykhoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun ScreenCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

@Composable
fun TimeTile(
    label: String,
    value: String,
    enabled: Boolean,
    tag: String,
    onPick: (hour: Int, minute: Int) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Surface(
        onClick = { if (enabled) showPicker = true },
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor(enabled),
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium,
                color = labelColor(enabled),
            )
        }
    }
    if (showPicker) {
        TimeInputDialog(
            title = label,
            initialHour = value.substringBefore(':').toIntOrNull() ?: 0,
            initialMinute = value.substringAfter(':').toIntOrNull() ?: 0,
            onConfirm = { hour, minute ->
                showPicker = false
                onPick(hour, minute)
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    subtitle: String? = null,
    tag: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = labelColor(enabled))
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}

@Composable
fun ValueSlider(
    title: String,
    valueLabel: String,
    value: Int,
    range: IntRange,
    step: Int,
    enabled: Boolean = true,
    tag: String = "",
    onChange: (Int) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = labelColor(enabled),
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = labelColor(enabled),
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(snapToStep(it.roundToInt(), range, step)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (step > 1) (range.last - range.first) / step - 1 else 0,
            enabled = enabled,
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
fun SelectChip(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    tag: String = "",
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        enabled = enabled,
        label = { Text(text, textAlign = TextAlign.Center) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun labelColor(enabled: Boolean) =
    if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

fun snapToStep(value: Int, range: IntRange, step: Int): Int =
    ((value - range.first + step / 2) / step * step + range.first).coerceIn(range.first, range.last)

private val DAY_NAMES = mapOf(
    DayOfWeek.MONDAY to "Пн",
    DayOfWeek.TUESDAY to "Вт",
    DayOfWeek.WEDNESDAY to "Ср",
    DayOfWeek.THURSDAY to "Чт",
    DayOfWeek.FRIDAY to "Пт",
    DayOfWeek.SATURDAY to "Сб",
    DayOfWeek.SUNDAY to "Вс",
)

fun dayShort(day: DayOfWeek): String = DAY_NAMES.getValue(day)

fun formatClock(millis: Long): String {
    val local = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(local.hour, local.minute)
}

fun formatWhen(atMillis: Long, nowMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val time = formatClock(atMillis)
    return when (date) {
        today -> "сегодня, $time"
        today.plusDays(1) -> "завтра, $time"
        else -> "${DAY_NAMES.getValue(date.dayOfWeek)}, $time"
    }
}

fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours <= 0 -> "$rest мин"
        rest == 0 -> "$hours ч"
        else -> "$hours ч $rest мин"
    }
}

@Composable
private fun TimeInputDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    fun startValue(minutes: Int) =
        TextFieldValue(minutes.toString().padStart(2, '0'), selection = TextRange(0, 2))

    var hourField by remember { mutableStateOf(startValue(initialHour)) }
    var minuteField by remember { mutableStateOf(startValue(initialMinute)) }
    val hourFocus = remember { FocusRequester() }
    val minuteFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    // Фокус сразу на часах, чтобы клавиатура появилась без лишнего нажатия.
    // Ждём кадр: до первого layout FocusRequester ещё не привязан к полю.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { hourFocus.requestFocus() }
        keyboard?.show()
    }

    fun selectAll(value: TextFieldValue) =
        value.copy(selection = TextRange(0, value.text.length))

    val hour = hourField.text.toIntOrNull()
    val minute = minuteField.text.toIntOrNull()
    val valid = hour != null && hour in 0..23 && minute != null && minute in 0..59
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время: $title") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = hourField,
                        onValueChange = { value ->
                            val text = value.text.filter { it.isDigit() }.take(2)
                            hourField = TextFieldValue(text, selection = TextRange(text.length))
                            // После двух цифр часов переходим к минутам с выделением,
                            // чтобы новые цифры сразу заменяли старые. Фокус переводим
                            // следующим кадром: иначе поле часов теряет фокус раньше,
                            // чем успевает применить новое значение, и откатывается назад.
                            if (text.length == 2) {
                                minuteField = selectAll(minuteField)
                                scope.launch {
                                    withFrameNanos { }
                                    minuteFocus.requestFocus()
                                }
                            }
                        },
                        label = { Text("Часы") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("time_hour_input")
                            .focusRequester(hourFocus)
                            .onFocusChanged { if (it.isFocused) hourField = selectAll(hourField) },
                    )
                    Text(":", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = minuteField,
                        onValueChange = { value ->
                            val text = value.text.filter { it.isDigit() }.take(2)
                            minuteField = TextFieldValue(text, selection = TextRange(text.length))
                        },
                        label = { Text("Минуты") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("time_minute_input")
                            .focusRequester(minuteFocus)
                            .onFocusChanged { if (it.isFocused) minuteField = selectAll(minuteField) },
                    )
                }
                if (!valid && (hourField.text.isNotEmpty() || minuteField.text.isNotEmpty())) {
                    Text(
                        "Введите часы 0–23 и минуты 0–59",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(hour!!, minute!!) },
                enabled = valid,
                modifier = Modifier.testTag("time_confirm"),
            ) { Text("Готово") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
