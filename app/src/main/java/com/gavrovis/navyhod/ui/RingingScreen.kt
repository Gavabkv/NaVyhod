package com.gavrovis.navyhod.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gavrovis.navyhod.LocaleHelper
import com.gavrovis.navyhod.R
import com.gavrovis.navyhod.SpeechText
import com.gavrovis.navyhod.ui.theme.AlarmButtonDark
import com.gavrovis.navyhod.ui.theme.AlarmButtonMedium
import com.gavrovis.navyhod.ui.theme.AlarmButtonLight
import kotlinx.coroutines.delay
import java.time.LocalTime

private val ScreenBackground = Color(0xFF0F0E0C)
private val PrimaryText = Color(0xFFFFF3E3)
private val SecondaryText = Color(0xFFB7A695)
private val LateAccent = Color(0xFFFFB74D)

/** Действие, которое ждёт обязательного подтверждения «Вы уверены?». */
enum class AlarmConfirmation {
    /** Остановить сигналы и напоминания этого цикла; будильник остаётся включённым. */
    Stop,

    /** Выключить музыку, но оставить голосовые напоминания о выходе. */
    RemindDeparture,
}

/**
 * Полноэкранное окно сигнала: крупные кнопки разных цветов,
 * чтобы попасть спящему пользователю, и обязательное подтверждение
 * для «Остановить музыку, но напомнить о выходе» и «Остановить сигналы».
 */
@Composable
fun RingingScreen(
    remainingMinutes: Int,
    wakeLabel: String,
    leaveLabel: String,
    confirmation: AlarmConfirmation?,
    onSilence: () -> Unit,
    onRequestStop: () -> Unit,
    onRequestRemindDeparture: () -> Unit,
    onConfirm: (AlarmConfirmation) -> Unit,
    onDismissConfirm: () -> Unit,
    lateMinutes: Int = 0,
) {
    // Кнопка «назад» не должна закрывать окно: выбор нужно сделать осознанно.
    BackHandler(enabled = true) { }

    val context = LocalContext.current
    val speechLang = remember(context) { LocaleHelper.speechLang(context) }
    var clock by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            clock = LocalTime.now()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = ScreenBackground) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        val minimumHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = minimumHeight)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.6f))

            Text(
                text = "%02d:%02d".format(clock.hour, clock.minute),
                color = PrimaryText,
                fontSize = 72.sp,
                fontWeight = FontWeight.Light,
            )

            Spacer(Modifier.height(18.dp))

            val status = when {
                lateMinutes > 0 -> SpeechText.late(lateMinutes, speechLang)
                remainingMinutes <= 0 -> stringResource(R.string.time_to_leave)
                else -> pluralStringResource(
                    R.plurals.until_departure, remainingMinutes, remainingMinutes,
                )
            }
            Text(
                text = status,
                color = if (remainingMinutes <= 0) LateAccent else PrimaryText,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.ring_wake_leave, wakeLabel, leaveLabel),
                color = SecondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (lateMinutes > 0) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.ring_stop_title),
                    color = PrimaryText,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            BigAlarmButton(
                title = stringResource(R.string.ring_silence),
                hint = stringResource(
                    if (lateMinutes > 0) R.string.ring_silence_hint_late else R.string.ring_silence_hint,
                ),
                container = AlarmButtonLight,
                content = AlarmButtonDark,
                onClick = onSilence,
            )

            Spacer(Modifier.height(18.dp))

            if (lateMinutes <= 0) {
                BigAlarmButton(
                    title = stringResource(R.string.ring_remind),
                    hint = stringResource(
                        if (remainingMinutes > 0) R.string.ring_remind_hint
                        else R.string.ring_remind_hint_late,
                    ),
                    container = AlarmButtonMedium,
                    content = Color.White,
                    onClick = onRequestRemindDeparture,
                )
                Spacer(Modifier.height(18.dp))
            }

            BigAlarmButton(
                title = stringResource(R.string.ring_stop),
                hint = stringResource(R.string.ring_stop_hint),
                container = AlarmButtonDark,
                content = AlarmButtonLight,
                onClick = onRequestStop,
            )

            Spacer(Modifier.weight(0.5f))
        }
        }
    }

    confirmation?.let { pending ->
        val stopEverything = pending == AlarmConfirmation.Stop
        AlertDialog(
            onDismissRequest = onDismissConfirm,
            title = { Text(stringResource(R.string.confirm_title)) },
            text = {
                Text(
                    stringResource(
                        when {
                            stopEverything -> R.string.confirm_stop_text
                            remainingMinutes > 0 -> R.string.confirm_remind_text
                            else -> R.string.confirm_remind_text_late
                        },
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(pending) }) {
                    Text(
                        text = stringResource(
                            if (stopEverything) R.string.yes_stop else R.string.yes_remind,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirm) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun BigAlarmButton(
    title: String,
    hint: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontSize = 27.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = content,
                textAlign = TextAlign.Center,
            )
        }
    }
}
