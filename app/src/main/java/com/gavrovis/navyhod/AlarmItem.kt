package com.gavrovis.navyhod

import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Один будильник из списка. Времена, сборы и дни недели задаются здесь;
 * интервал сигналов, голос и длительность звонка остаются общими настройками.
 */
data class AlarmItem(
    val id: Long,
    val wakeHour: Int,
    val wakeMinute: Int,
    val leaveHour: Int,
    val leaveMinute: Int,
    val preparationMinutes: Int = 90,
    val departureTimeOnly: Boolean = false,
    val days: Set<DayOfWeek> = ALL_DAYS,
    val enabled: Boolean = false,
) {
    val wakeLabel: String get() = AlarmSettings.formatTime(wakeHour, wakeMinute)
    val leaveLabel: String get() = AlarmSettings.formatTime(leaveHour, leaveMinute)

    /** Будильник реально ставится на ближайший день: включён и есть хотя бы один день. */
    val scheduled: Boolean get() = enabled && days.isNotEmpty()

    val daysMask: Int get() = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

    fun withWakeTime(hour: Int, minute: Int): AlarmItem {
        if (hour == wakeHour && minute == wakeMinute) return this
        val leave = LocalTime.of(hour, minute).plusMinutes(preparationMinutes.toLong())
        return copy(
            wakeHour = hour,
            wakeMinute = minute,
            leaveHour = leave.hour,
            leaveMinute = leave.minute,
        )
    }

    fun withLeaveTime(hour: Int, minute: Int): AlarmItem {
        val changed = copy(leaveHour = hour, leaveMinute = minute)
        return if (departureTimeOnly) changed.withCalculatedWake() else changed
    }

    fun withPreparationMinutes(minutes: Int): AlarmItem {
        val changed = copy(preparationMinutes = minutes.coerceIn(40, 150))
        return if (departureTimeOnly) changed.withCalculatedWake() else changed.withCalculatedLeave()
    }

    fun withDepartureTimeOnly(onlyDeparture: Boolean): AlarmItem {
        val changed = copy(departureTimeOnly = onlyDeparture)
        return if (onlyDeparture) changed.withCalculatedWake() else changed.withCalculatedLeave()
    }

    fun withDays(days: Set<DayOfWeek>): AlarmItem = copy(days = days)

    private fun withCalculatedWake(): AlarmItem {
        val wake = LocalTime.of(leaveHour, leaveMinute).minusMinutes(preparationMinutes.toLong())
        return copy(wakeHour = wake.hour, wakeMinute = wake.minute)
    }

    private fun withCalculatedLeave(): AlarmItem {
        val leave = LocalTime.of(wakeHour, wakeMinute).plusMinutes(preparationMinutes.toLong())
        return copy(leaveHour = leave.hour, leaveMinute = leave.minute)
    }

    /** Снимок настроек этого будильника для планировщика и уведомлений. */
    fun toSettings(global: AlarmSettings): AlarmSettings = global.copy(
        wakeHour = wakeHour,
        wakeMinute = wakeMinute,
        leaveHour = leaveHour,
        leaveMinute = leaveMinute,
        preparationMinutes = preparationMinutes,
        departureTimeOnly = departureTimeOnly,
        days = days,
        enabled = enabled,
    )

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
        )
        val WEEKEND: Set<DayOfWeek> = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val ALL_DAYS: Set<DayOfWeek> = DayOfWeek.entries.toSet()

        fun fromMask(mask: Int): Set<DayOfWeek> {
            val days = DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
            return if (days.isEmpty()) ALL_DAYS else days
        }

        fun newId(existing: List<AlarmItem>): Long =
            (existing.maxOfOrNull { it.id } ?: 0L) + 1L
    }
}
