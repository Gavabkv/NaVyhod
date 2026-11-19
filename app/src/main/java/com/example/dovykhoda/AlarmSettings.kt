package com.example.dovykhoda

import java.time.LocalTime

/**
 * Все пользовательские настройки будильника.
 *
 * wake*    — время пробуждения (первый сигнал),
 * leave*   — время выхода из дома (последний сигнал),
 * interval — с какой периодичностью играет музыка,
 * ttsLead  — за сколько минут до выхода начинать голосовые объявления.
 */
data class AlarmSettings(
    val wakeHour: Int = 7,
    val wakeMinute: Int = 0,
    val leaveHour: Int = 8,
    val leaveMinute: Int = 0,
    val intervalMinutes: Int = 5,
    /** Голос на сигналах с музыкой; в интерфейсе не выключается, поле нужно для совместимости. */
    val speakEnabled: Boolean = true,
    /** Говорить текущее время перед фразой на сигналах будильника. */
    val announceTimeOnSignals: Boolean = true,
    /** Говорить текущее время перед напоминанием о выходе. */
    val announceTimeOnDeparture: Boolean = true,
    /** Говорить текущее время перед напоминанием об опоздании. */
    val announceTimeOnLate: Boolean = true,
    val ttsLeadMinutes: Int = 30,
    val ringSeconds: Int = 40,
    val preNotifyEnabled: Boolean = true,
    val enabled: Boolean = false,
    val preparationMinutes: Int = 90,
    val departureTimeOnly: Boolean = false,
) {
    val wakeLabel: String get() = formatTime(wakeHour, wakeMinute)
    val leaveLabel: String get() = formatTime(leaveHour, leaveMinute)

    fun withWakeTime(hour: Int, minute: Int): AlarmSettings {
        if (hour == wakeHour && minute == wakeMinute) return this
        val leave = LocalTime.of(hour, minute).plusMinutes(preparationMinutes.toLong())
        return copy(
            wakeHour = hour,
            wakeMinute = minute,
            leaveHour = leave.hour,
            leaveMinute = leave.minute,
        )
    }

    fun withLeaveTime(hour: Int, minute: Int): AlarmSettings {
        val changed = copy(leaveHour = hour, leaveMinute = minute)
        return if (departureTimeOnly) changed.withCalculatedWake() else changed
    }

    fun withPreparationMinutes(minutes: Int): AlarmSettings {
        val changed = copy(preparationMinutes = minutes.coerceIn(40, 150))
        return if (departureTimeOnly) changed.withCalculatedWake() else changed.withCalculatedLeave()
    }

    fun withDepartureTimeOnly(onlyDeparture: Boolean): AlarmSettings {
        val changed = copy(departureTimeOnly = onlyDeparture)
        return if (onlyDeparture) changed.withCalculatedWake() else changed.withCalculatedLeave()
    }

    private fun withCalculatedWake(): AlarmSettings {
        val wake = LocalTime.of(leaveHour, leaveMinute).minusMinutes(preparationMinutes.toLong())
        return copy(wakeHour = wake.hour, wakeMinute = wake.minute)
    }

    private fun withCalculatedLeave(): AlarmSettings {
        val leave = LocalTime.of(wakeHour, wakeMinute).plusMinutes(preparationMinutes.toLong())
        return copy(leaveHour = leave.hour, leaveMinute = leave.minute)
    }

    companion object {
        fun formatTime(hour: Int, minute: Int): String =
            hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
    }
}
