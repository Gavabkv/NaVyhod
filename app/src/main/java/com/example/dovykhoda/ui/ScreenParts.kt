package com.example.dovykhoda.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
        colors = FilterChipDefaults.filterChipColors(
            // Коричневый акцент темы вместо синеватых цветов по умолчанию.
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
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
    fun clock(value: Int) = value.toString().padStart(2, '0')

    var hourDigits by remember { mutableStateOf(clock(initialHour)) }
    var minuteDigits by remember { mutableStateOf(clock(initialMinute)) }
    var active by remember { mutableStateOf(TimeField.Hour) }
    // Выбранные цифры: следующая нажатая цифра заменяет их целиком.
    var hourSelected by remember { mutableStateOf(true) }
    var minuteSelected by remember { mutableStateOf(false) }

    fun pressDigit(digit: Char) {
        when (active) {
            TimeField.Hour -> {
                hourDigits = if (hourSelected || hourDigits.length >= 2) "$digit"
                else hourDigits + digit
                hourSelected = false
                if (hourDigits.length == 2) {
                    active = TimeField.Minute
                    minuteSelected = true
                }
            }
            TimeField.Minute -> {
                minuteDigits = if (minuteSelected || minuteDigits.length >= 2) "$digit"
                else minuteDigits + digit
                minuteSelected = false
            }
        }
    }

    fun backspace() {
        when (active) {
            TimeField.Hour -> hourDigits = if (hourSelected) "" else hourDigits.dropLast(1)
            TimeField.Minute -> minuteDigits = if (minuteSelected) "" else minuteDigits.dropLast(1)
        }
        hourSelected = false
        minuteSelected = false
    }

    val hour = hourDigits.toIntOrNull()
    val minute = minuteDigits.toIntOrNull()
    val valid = hour != null && hour in 0..23 && minute != null && minute in 0..59
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время: $title") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TimeDigitsBox(
                        text = hourDigits,
                        selected = hourSelected,
                        tag = "time_hour_input",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            active = TimeField.Hour
                            hourSelected = true
                            minuteSelected = false
                        },
                    )
                    Text(":", style = MaterialTheme.typography.headlineSmall)
                    TimeDigitsBox(
                        text = minuteDigits,
                        selected = minuteSelected,
                        tag = "time_minute_input",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            active = TimeField.Minute
                            minuteSelected = true
                            hourSelected = false
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeypadRow(listOf('1', '2', '3'), ::pressDigit)
                    KeypadRow(listOf('4', '5', '6'), ::pressDigit)
                    KeypadRow(listOf('7', '8', '9'), ::pressDigit)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KeypadButton("0", "time_digit_0", Modifier.weight(2f)) { pressDigit('0') }
                        KeypadButton("\u232B", "time_backspace", Modifier.weight(1f)) { backspace() }
                    }
                }
                if (!valid && (hourDigits.isNotEmpty() || minuteDigits.isNotEmpty())) {
                    Spacer(Modifier.height(8.dp))
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

private enum class TimeField { Hour, Minute }

@Composable
private fun TimeDigitsBox(
    text: String,
    selected: Boolean,
    tag: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface,
        modifier = modifier.testTag(tag),
    ) {
        Text(
            text = text.ifEmpty { "–" },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun KeypadRow(digits: List<Char>, onDigit: (Char) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        digits.forEach { digit ->
            KeypadButton(digit.toString(), "time_digit_$digit", Modifier.weight(1f)) { onDigit(digit) }
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp).testTag(tag),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
