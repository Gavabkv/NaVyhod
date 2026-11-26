package com.gavrovis.navyhod

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Чистая (без Android) логика расписания — её же проверяют юнит-тесты.
 *
 * Цикл: сигналы в момент пробуждения, затем каждые N минут до выхода,
 * последний сигнал — ровно во время выхода.
 * Если время выхода раньше времени пробуждения, выход считается на следующий день.
 */
object SchedulePlanner {

    data class Cycle(val wake: LocalDateTime, val leave: LocalDateTime)

    data class ScheduledTrigger(
        val atMillis: Long,
        val cycleWakeMillis: Long,
        val cycleLeaveMillis: Long,
        val alarmId: Long = 0L,
    )

    fun nextTrigger(
        afterMillis: Long,
        settings: AlarmSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScheduledTrigger {
        val date = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        val stepMillis = settings.intervalMinutes.coerceAtLeast(1) * 60_000L
        return (-1L..8L).mapNotNull { offset ->
            val wake = date.plusDays(offset).atTime(wakeTime(settings))
            if (wake.dayOfWeek !in settings.days) return@mapNotNull null
            val wakeMillis = toMillis(wake, zone)
            val leaveMillis = toMillis(leaveFor(wake, settings), zone).coerceAtLeast(wakeMillis)
            val atMillis = when {
                afterMillis < wakeMillis -> wakeMillis
                afterMillis < leaveMillis ->
                    (wakeMillis + ((afterMillis - wakeMillis) / stepMillis + 1) * stepMillis)
                        .coerceAtMost(leaveMillis)
                else -> return@mapNotNull null
            }
            ScheduledTrigger(atMillis, wakeMillis, leaveMillis)
        }.minBy { it.atMillis }
    }

    /** Ближайший сигнал среди включённых будильников. */
    fun nextAlarmTrigger(
        afterMillis: Long,
        alarms: List<AlarmItem>,
        settings: AlarmSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<AlarmItem, ScheduledTrigger>? =
        alarms.filter { it.scheduled }
            .map { it to nextTrigger(afterMillis, it.toSettings(settings), zone) }
            .minByOrNull { it.second.atMillis }

    /** Ближайшее пробуждение среди включённых будильников. */
    fun nextAlarmWake(
        afterMillis: Long,
        alarms: List<AlarmItem>,
        settings: AlarmSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<AlarmItem, Long>? =
        alarms.filter { it.scheduled }
            .map { it to nextWakeMillis(afterMillis, it.toSettings(settings), zone) }
            .minByOrNull { it.second }

    fun departureReminders(leaveMillis: Long): List<Long> =
        listOf(20, 15, 10, 5, 0).map { leaveMillis - it * 60_000L }

    fun nextLateMinute(nowMillis: Long, leaveMillis: Long): Int {
        val minutes = ((nowMillis - leaveMillis).coerceAtLeast(0L) / 60_000L).toInt().coerceAtLeast(1)
        return if (leaveMillis + minutes * 60_000L < nowMillis - 5_000L) minutes + 1 else minutes
    }

    fun remainingMinutes(nowMillis: Long, leaveMillis: Long): Int {
        val remainingMillis = (leaveMillis - nowMillis).coerceAtLeast(0L)
        return ((remainingMillis + 59_999L) / 60_000L).toInt()
    }

    fun lateTrigger(
        nowMillis: Long,
        settings: AlarmSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ScheduledTrigger? {
        val date = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return (-1L..0L).map { offset ->
            val wake = date.plusDays(offset).atTime(wakeTime(settings))
            val wakeMillis = toMillis(wake, zone)
            val leaveMillis = toMillis(leaveFor(wake, settings), zone).coerceAtLeast(wakeMillis)
            ScheduledTrigger(leaveMillis, wakeMillis, leaveMillis)
        }.firstOrNull {
            nowMillis >= it.cycleLeaveMillis &&
                nowMillis < nextWakeMillis(it.cycleWakeMillis, settings, zone)
        }
    }

    fun wakeTime(settings: AlarmSettings): LocalTime =
        LocalTime.of(settings.wakeHour, settings.wakeMinute)

    fun leaveTime(settings: AlarmSettings): LocalTime =
        LocalTime.of(settings.leaveHour, settings.leaveMinute)

    /** Время выхода для цикла, который начинается в [wake]. */
    fun leaveFor(wake: LocalDateTime, settings: AlarmSettings): LocalDateTime {
        var leave = wake.toLocalDate().atTime(leaveTime(settings))
        if (!leave.isAfter(wake)) leave = leave.plusDays(1)
        return leave
    }

    /**
     * Цикл, в который попадает [now], либо ближайший будущий, если сейчас «щель»
     * между циклами. Учитывает окно через полночь.
     */
    fun cycleContaining(now: LocalDateTime, settings: AlarmSettings): Cycle {
        val candidates = (-1..1).map { offset ->
            val wake = now.toLocalDate().plusDays(offset.toLong()).atTime(wakeTime(settings))
            Cycle(wake, leaveFor(wake, settings))
        }
        candidates.firstOrNull { !now.isBefore(it.wake) && now.isBefore(it.leave) }
            ?.let { return it }
        return candidates.first { it.wake.isAfter(now) }
    }

    fun isActive(now: LocalDateTime, cycle: Cycle): Boolean =
        !now.isBefore(cycle.wake) && now.isBefore(cycle.leave)

    /** Все сигналы цикла: пробуждение, +N минут, ..., время выхода. */
    fun triggers(cycle: Cycle, settings: AlarmSettings): List<LocalDateTime> {
        val step = settings.intervalMinutes.coerceAtLeast(1).toLong()
        val result = ArrayList<LocalDateTime>()
        var moment = cycle.wake
        while (moment.isBefore(cycle.leave)) {
            result.add(moment)
            moment = moment.plusMinutes(step)
        }
        result.add(cycle.leave)
        return result
    }

    /** Ближайший сигнал строго после [after]. */
    fun nextTriggerAfter(after: LocalDateTime, settings: AlarmSettings): LocalDateTime {
        val cycle = cycleContaining(after, settings)
        return triggers(cycle, settings).firstOrNull { it.isAfter(after) }
            ?: cycle.leave.plusMinutes(1)
    }

    fun nextTriggerMillis(
        afterMillis: Long,
        settings: AlarmSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long = nextTrigger(afterMillis, settings, zone).atMillis

    fun toMillis(moment: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        moment.atZone(zone).toInstant().toEpochMilli()

    /**
     * Ближайшее пробуждение строго после [after].
     * Если сегодняшнее пробуждение уже прошло (или идёт прямо сейчас) — берём завтрашнее.
     */
    fun nextWakeAfter(after: LocalDateTime, settings: AlarmSettings): LocalDateTime {
        var wake = after.toLocalDate().atTime(wakeTime(settings))
        if (!wake.isAfter(after)) wake = wake.plusDays(1)
        return wake
    }

    fun nextWakeMillis(
        afterMillis: Long,
        settings: AlarmSettings,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val date = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        return (0L..8L).map { date.plusDays(it) }
            .filter { it.dayOfWeek in settings.days }
            .map { toMillis(it.atTime(wakeTime(settings)), zone) }
            .first { it > afterMillis }
    }

    /**
     * Остаток до выхода «прямо сейчас».
     *
     * Время округляется вниз до минуты: точный аларм срабатывает на несколько секунд
     * позже назначенного момента, и без этого сигнал ровно в момент выхода
     * посчитался бы как относящийся к следующему дню.
     */
    fun remainingMinutesNow(
        now: LocalDateTime,
        settings: AlarmSettings,
    ): Int = if (lateLeave(now, settings) != null) 0
        else minutesUntilLeave(now.truncatedTo(ChronoUnit.MINUTES), settings)

    fun lateLeave(now: LocalDateTime, settings: AlarmSettings): LocalDateTime? =
        lateTrigger(toMillis(now), settings)?.let {
            Instant.ofEpochMilli(it.cycleLeaveMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }

    /**
     * Цикл, к которому относится уже сработавший сигнал.
     * Минус секунда нужен, чтобы сигнал ровно во время выхода попал в свой цикл.
     */
    fun cycleForTrigger(trigger: LocalDateTime, settings: AlarmSettings): Cycle =
        cycleContaining(trigger.minusSeconds(1), settings)

    /** Сколько минут останется до выхода в момент сигнала [trigger]. */
    fun minutesUntilLeave(trigger: LocalDateTime, settings: AlarmSettings): Int {
        val cycle = cycleForTrigger(trigger, settings)
        val seconds = Duration.between(trigger, cycle.leave).seconds
        return (seconds / 60.0).roundToInt().coerceAtLeast(0)
    }

    /** Попадает ли момент в интервал, когда нужно озвучивать остаток. */
    fun shouldSpeak(trigger: LocalDateTime, settings: AlarmSettings): Boolean =
        settings.speakEnabled &&
            minutesUntilLeave(trigger, settings) <= settings.ttsLeadMinutes
}
