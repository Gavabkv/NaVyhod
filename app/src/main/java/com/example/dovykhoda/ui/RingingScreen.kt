package com.example.dovykhoda.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dovykhoda.SpeechText
import com.example.dovykhoda.ui.theme.AlarmButtonDark
import com.example.dovykhoda.ui.theme.AlarmButtonMedium
import com.example.dovykhoda.ui.theme.AlarmButtonLight
import kotlinx.coroutines.delay
import java.time.LocalTime

private val ScreenBackground = Color(0xFF0F0E0C)
private val PrimaryText = Color(0xFFFFF3E3)
private val SecondaryText = Color(0xFFB7A695)
private val LateAccent = Color(0xFFFFB74D)

/** Действие, которое ждёт обязательного подтверждения «Вы уверены?». */
enum class AlarmConfirmation {
    /** Полностью выключить будильник: музыка, напоминания и расписание. */
    Stop,

    /** Выключить музыку, но оставить голосовые напоминания о выходе. */
    RemindDeparture,
}

/**
 * Полноэкранное окно сигнала: крупные кнопки разных цветов,
 * чтобы попасть спящему пользователю, и обязательное подтверждение
 * для «Выключить будильник, но напомнить о выходе» и «Отключить будильник».
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
                lateMinutes > 0 -> SpeechText.late(lateMinutes)
                remainingMinutes <= 0 -> "Пора выходить"
                else -> "До выхода $remainingMinutes " +
                    SpeechText.plural(remainingMinutes, "минута", "минуты", "минут")
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
                text = "Пробуждение $wakeLabel · выход $leaveLabel",
                color = SecondaryText,
                style = MaterialTheme.typography.bodyMedium,
            )

            if (lateMinutes > 0) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Отключить будильник?",
                    color = PrimaryText,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.weight(1f))

            BigAlarmButton(
                title = "Тишина",
                hint = if (lateMinutes > 0) "Остановить голос до следующей минуты"
                    else "Остановить музыку, будильник останется",
                container = AlarmButtonLight,
                content = AlarmButtonDark,
                onClick = onSilence,
            )

            Spacer(Modifier.height(18.dp))

            if (lateMinutes <= 0) {
                BigAlarmButton(
                    title = "Выключить будильник, но напомнить о выходе",
                    hint = if (remainingMinutes > 0) "Голос за 20, 15, 10 и 5 минут и в момент выхода"
                        else "«Пора выходить» — затем отключение без опозданий",
                    container = AlarmButtonMedium,
                    content = Color.White,
                    onClick = onRequestRemindDeparture,
                )
                Spacer(Modifier.height(18.dp))
            }

            BigAlarmButton(
                title = "Отключить будильник",
                hint = "Остановить музыку и все напоминания",
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
            title = { Text("Вы уверены?") },
            text = {
                Text(
                    when {
                        stopEverything ->
                            "Музыка, голосовые напоминания и этот будильник будут отключены. " +
                                "Остальные будильники продолжат работать; включить этот снова " +
                                "можно в приложении."
                        remainingMinutes > 0 ->
                            "Музыка остановится, а голосовые напоминания о выходе останутся: " +
                                "они прозвучат за 20, 15, 10 и 5 минут и в момент выхода."
                        else ->
                            "Музыка остановится, но голос «Пора выходить» прозвучит в момент " +
                                "выхода. После этого будильник выключится без напоминаний об опоздании."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(pending) }) {
                    Text(
                        text = if (stopEverything) "Да, отключить" else "Да, напомнить",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissConfirm) {
                    Text("Отмена")
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
