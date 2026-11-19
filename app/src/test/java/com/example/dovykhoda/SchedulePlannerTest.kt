package com.example.dovykhoda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

class SchedulePlannerTest {

    private fun settings(
        wakeHour: Int,
        wakeMinute: Int,
        leaveHour: Int,
        leaveMinute: Int,
        interval: Int = 5,
        lead: Int = 30,
        speak: Boolean = true,
    ) = AlarmSettings(
        wakeHour = wakeHour,
        wakeMinute = wakeMinute,
        leaveHour = leaveHour,
        leaveMinute = leaveMinute,
        intervalMinutes = interval,
        ttsLeadMinutes = lead,
        speakEnabled = speak,
        enabled = true,
    )

    private fun at(hour: Int, minute: Int, day: Int = 17) =
        LocalDateTime.of(2026, 11, day, hour, minute)

    @Test
    fun `first signal of the day is wake time`() {
        val s = settings(7, 0, 8, 0)
        assertEquals(at(7, 0), SchedulePlanner.nextTriggerAfter(at(5, 0), s))
    }

    @Test
    fun `signals repeat every interval and end exactly at leave time`() {
        val s = settings(7, 0, 8, 0, interval = 5)
        val cycle = SchedulePlanner.cycleContaining(at(7, 31), s)
        val triggers = SchedulePlanner.triggers(cycle, s)

        assertEquals(13, triggers.size)
        assertEquals(at(7, 0), triggers.first())
        assertEquals(at(7, 35), triggers[7])
        assertEquals(at(8, 0), triggers.last())
    }

    @Test
    fun `ten minute interval gives fewer signals`() {
        val s = settings(7, 0, 8, 0, interval = 10)
        val triggers = SchedulePlanner.triggers(SchedulePlanner.cycleContaining(at(7, 31), s), s)

        assertEquals(7, triggers.size) // 07:00 07:10 ... 07:50 + 08:00
        assertTrue(triggers.contains(at(7, 50)))
    }

    @Test
    fun `after departure next signal is tomorrow wake time`() {
        val s = settings(7, 0, 8, 0)
        assertEquals(at(7, 0, day = 18), SchedulePlanner.nextTriggerAfter(at(8, 0), s))
        assertEquals(at(7, 0, day = 18), SchedulePlanner.nextTriggerAfter(at(23, 59), s))
    }

    @Test
    fun `moment exactly at a signal is skipped`() {
        val s = settings(7, 0, 8, 0, interval = 5)
        assertEquals(at(7, 5), SchedulePlanner.nextTriggerAfter(at(7, 0), s))
    }

    @Test
    fun `minutes until leave are counted from the signal`() {
        val s = settings(7, 0, 8, 0, interval = 5)
        assertEquals(60, SchedulePlanner.minutesUntilLeave(at(7, 0), s))
        assertEquals(5, SchedulePlanner.minutesUntilLeave(at(7, 55), s))
        assertEquals(0, SchedulePlanner.minutesUntilLeave(at(8, 0), s))
    }

    @Test
    fun `voice starts inside lead window only`() {
        val s = settings(7, 0, 8, 0, interval = 5, lead = 30)
        assertFalse(SchedulePlanner.shouldSpeak(at(7, 0), s))  // 60 min
        assertFalse(SchedulePlanner.shouldSpeak(at(7, 25), s)) // 35 min
        assertTrue(SchedulePlanner.shouldSpeak(at(7, 30), s))  // 30 min
        assertTrue(SchedulePlanner.shouldSpeak(at(8, 0), s))   // 0 min
    }

    @Test
    fun `voice starts exactly ninety minutes before departure`() {
        val s = settings(6, 0, 8, 0, lead = 90)
        assertFalse(SchedulePlanner.shouldSpeak(at(6, 25), s))
        assertTrue(SchedulePlanner.shouldSpeak(at(6, 30), s))
        assertTrue(SchedulePlanner.shouldSpeak(at(6, 35), s))
        assertTrue(SchedulePlanner.shouldSpeak(at(8, 0), s))
        assertFalse(SchedulePlanner.shouldSpeak(at(6, 30), s.copy(speakEnabled = false)))
    }

    @Test
    fun `zero lead speaks only at departure`() {
        val s = settings(6, 30, 8, 0, lead = 0)
        assertFalse(SchedulePlanner.shouldSpeak(at(6, 30), s))
        assertFalse(SchedulePlanner.shouldSpeak(at(7, 55), s))
        assertTrue(SchedulePlanner.shouldSpeak(at(8, 0), s))
    }

    @Test
    fun `voice can be disabled`() {
        val s = settings(7, 0, 8, 0, lead = 30, speak = false)
        assertFalse(SchedulePlanner.shouldSpeak(at(7, 55), s))
    }

    @Test
    fun `overnight window belongs to the previous day`() {
        val s = settings(23, 0, 7, 0, interval = 10)

        val cycle = SchedulePlanner.cycleContaining(at(3, 0), s)
        assertEquals(at(23, 0, day = 16), cycle.wake)
        assertEquals(at(7, 0), cycle.leave)
        assertEquals(240, SchedulePlanner.minutesUntilLeave(at(3, 0), s))

        assertEquals(at(3, 10), SchedulePlanner.nextTriggerAfter(at(3, 0), s))
        assertEquals(at(23, 0), SchedulePlanner.nextTriggerAfter(at(9, 0), s))
    }

    @Test
    fun `equal wake and leave means a full day window`() {
        val s = settings(7, 0, 7, 0)
        val cycle = SchedulePlanner.cycleContaining(at(12, 0), s)
        assertEquals(24 * 60L, Duration.between(cycle.wake, cycle.leave).toMinutes())
        assertTrue(SchedulePlanner.isActive(at(12, 0), cycle))
    }

    @Test
    fun `millis conversion round trips`() {
        val s = settings(7, 0, 8, 0)
        val zone = ZoneId.of("Europe/Moscow")
        val from = LocalDateTime.of(2026, 11, 17, 6, 0)
            .atZone(zone).toInstant().toEpochMilli()

        val next = SchedulePlanner.nextTriggerMillis(from, s, zone)
        assertEquals(
            LocalDateTime.of(2026, 11, 17, 7, 0).atZone(zone).toInstant().toEpochMilli(),
            next,
        )
    }

    @Test
    fun `signal chain never stalls across several days`() {
        val s = settings(7, 0, 8, 0, interval = 5)
        val chain = generateSequence(at(6, 0)) { SchedulePlanner.nextTriggerAfter(it, s) }
            .drop(1)
            .take(40)
            .toList()

        chain.zipWithNext { previous, next ->
            assertTrue("next must be after previous: $next <= $previous", next.isAfter(previous))
        }
        assertEquals(at(8, 0), chain[12])            // последний сигнал первого цикла
        assertEquals(at(7, 0, day = 18), chain[13])  // первый сигнал следующего дня
        assertEquals(at(7, 0, day = 20), chain[39])
    }

    @Test
    fun `next wake is tomorrow once today wake has passed`() {
        val s = settings(7, 0, 8, 0)
        assertEquals(at(7, 0), SchedulePlanner.nextWakeAfter(at(6, 59), s))
        assertEquals(at(7, 0, day = 18), SchedulePlanner.nextWakeAfter(at(7, 0), s))
        assertEquals(at(7, 0, day = 18), SchedulePlanner.nextWakeAfter(at(23, 30), s))
    }

    @Test
    fun `alarm delay of a few seconds does not shift the minute`() {
        val s = settings(7, 0, 8, 0)
        assertEquals(0, SchedulePlanner.remainingMinutesNow(at(8, 0).plusSeconds(4), s))
        assertEquals(5, SchedulePlanner.remainingMinutesNow(at(7, 55).plusSeconds(4), s))
        assertEquals(60, SchedulePlanner.remainingMinutesNow(at(7, 0).plusSeconds(4), s))
    }

    @Test
    fun `late phase no longer ends after three minutes`() {
        val s = settings(7, 0, 8, 0)
        assertEquals(at(8, 0), SchedulePlanner.lateLeave(at(8, 0), s))
        assertEquals(at(8, 0), SchedulePlanner.lateLeave(at(8, 2), s))
        assertNull(SchedulePlanner.lateLeave(at(7, 59), s))
        assertEquals(at(8, 0), SchedulePlanner.lateLeave(at(8, 3).plusSeconds(1), s))
        assertEquals(at(8, 0), SchedulePlanner.lateLeave(at(9, 0), s))
    }

    @Test
    fun `delayed departure remains in its original cycle`() {
        val s = settings(7, 0, 8, 0)
        val zone = ZoneId.of("Europe/Moscow")
        val planned = SchedulePlanner.nextTrigger(SchedulePlanner.toMillis(at(7, 59), zone), s, zone)
        val delivered = SchedulePlanner.toMillis(at(8, 1).plusSeconds(1), zone)
        assertEquals(0, SchedulePlanner.remainingMinutes(delivered, planned.cycleLeaveMillis))
        assertEquals(0, SchedulePlanner.remainingMinutesNow(at(8, 1).plusSeconds(1), s))
    }

    @Test
    fun `remaining minutes round up without changing the cycle`() {
        val leave = 10_000_000L
        assertEquals(5, SchedulePlanner.remainingMinutes(leave - 296_000L, leave))
        assertEquals(0, SchedulePlanner.remainingMinutes(leave + 61_000L, leave))
        assertEquals(0, SchedulePlanner.remainingMinutes(leave, leave))
    }

    @Test
    fun `fall back produces a future trigger during the repeated hour`() {
        val s = settings(1, 0, 4, 0)
        val zone = ZoneId.of("Europe/Berlin")
        val after = java.time.ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 10, 25, 2, 30), zone, java.time.ZoneOffset.ofHours(1),
        ).toInstant().toEpochMilli()
        assertEquals(after + 5 * 60_000L, SchedulePlanner.nextTriggerMillis(after, s, zone))
    }

    @Test
    fun `trigger chain stays monotonic across both clock changes`() {
        val zone = ZoneId.of("Europe/Berlin")
        val s = settings(1, 0, 4, 0)
        for (day in listOf(LocalDateTime.of(2026, 3, 29, 0, 0), LocalDateTime.of(2026, 10, 25, 0, 0))) {
            var now = SchedulePlanner.toMillis(day, zone)
            repeat(65) {
                val next = SchedulePlanner.nextTrigger(now, s, zone)
                assertTrue(next.atMillis > now)
                assertTrue(next.atMillis >= next.cycleWakeMillis)
                assertTrue(next.atMillis <= next.cycleLeaveMillis)
                now = next.atMillis
            }
        }
    }

    @Test
    fun `next wake never points into the past during fall back`() {
        val zone = ZoneId.of("Europe/Berlin")
        val s = settings(2, 45, 4, 0)
        val after = java.time.ZonedDateTime.ofLocal(
            LocalDateTime.of(2026, 10, 25, 2, 30), zone, java.time.ZoneOffset.ofHours(1),
        ).toInstant().toEpochMilli()
        val expected = SchedulePlanner.toMillis(LocalDateTime.of(2026, 10, 26, 2, 45), zone)
        assertEquals(expected, SchedulePlanner.nextWakeMillis(after, s, zone))
    }

    @Test
    fun `late recovery preserves cycle timestamps`() {
        val zone = ZoneId.of("Europe/Moscow")
        val s = settings(7, 0, 8, 0)
        val now = SchedulePlanner.toMillis(at(8, 2), zone)
        val late = SchedulePlanner.lateTrigger(now, s, zone)!!
        assertEquals(SchedulePlanner.toMillis(at(7, 0), zone), late.cycleWakeMillis)
        assertEquals(SchedulePlanner.toMillis(at(8, 0), zone), late.cycleLeaveMillis)
        assertEquals(late, SchedulePlanner.lateTrigger(SchedulePlanner.toMillis(at(8, 30), zone), s, zone))
        assertNull(SchedulePlanner.lateTrigger(SchedulePlanner.toMillis(at(7, 0, day = 18), zone), s, zone))
    }

    @Test
    fun `departure reminders include the departure moment after four advance reminders`() {
        val zone = ZoneId.of("Europe/Moscow")
        val leave = SchedulePlanner.toMillis(at(0, 10), zone)
        assertEquals(listOf(20L, 15L, 10L, 5L, 0L),
            SchedulePlanner.departureReminders(leave).map { (leave - it) / 60_000L })
        assertEquals(SchedulePlanner.toMillis(at(23, 50, day = 16), zone),
            SchedulePlanner.departureReminders(leave).first())
    }

    @Test
    fun `late minutes continue past three minutes and past one day`() {
        val leave = 10_000_000L
        for (minutes in listOf(1, 2, 3, 4, 10, 60, 1440, 2000)) {
            assertEquals(minutes, SchedulePlanner.nextLateMinute(leave + minutes * 60_000L, leave))
        }
        assertEquals(1, SchedulePlanner.nextLateMinute(leave, leave))
        assertEquals(4, SchedulePlanner.nextLateMinute(leave + 185_001L, leave))
        assertEquals(5, SchedulePlanner.nextLateMinute(leave + 246_000L, leave))
    }

    @Test
    fun `departure phrase grammar`() {
        assertEquals("Через 20 минут надо выйти", SpeechText.departure(20))
        assertEquals("Через 15 минут надо выйти", SpeechText.departure(15))
        assertEquals("Через 10 минут надо выйти", SpeechText.departure(10))
        assertEquals("Через 5 минут надо выйти", SpeechText.departure(5))
        assertEquals("Пора выходить", SpeechText.departure(0))
    }

    @Test
    fun `late phrase grammar`() {
        assertEquals("Вы опоздали с выходом на 1 минуту", SpeechText.late(1))
        assertEquals("Вы опоздали с выходом на 2 минуты", SpeechText.late(2))
        assertEquals("Вы опоздали с выходом на 3 минуты", SpeechText.late(3))
        assertEquals("Вы опоздали с выходом на 11 минут", SpeechText.late(11))
    }

    @Test
    fun `custom preparation duration calculates departure from wake through midnight`() {
        for (minutes in listOf(40, 90, 150)) {
            val s = AlarmSettings().withPreparationMinutes(minutes).withWakeTime(23, 30)
            val expectedLeave = java.time.LocalTime.of(23, 30).plusMinutes(minutes.toLong())
            assertEquals(expectedLeave.hour, s.leaveHour)
            assertEquals(expectedLeave.minute, s.leaveMinute)
            val wake = at(23, 30)
            assertEquals(wake.plusMinutes(minutes.toLong()), SchedulePlanner.leaveFor(wake, s))
        }
    }

    @Test
    fun `departure mode calculates wake through midnight and keeps departure when duration changes`() {
        val initial = AlarmSettings().withDepartureTimeOnly(true).withLeaveTime(0, 30)
        assertEquals("23:00", initial.wakeLabel)
        for (minutes in listOf(40, 90, 150)) {
            val s = initial.withPreparationMinutes(minutes)
            val expected = java.time.LocalTime.of(0, 30).minusMinutes(minutes.toLong())
            assertEquals(expected.hour, s.wakeHour)
            assertEquals(expected.minute, s.wakeMinute)
            assertEquals("00:30", s.leaveLabel)
            val wake = at(s.wakeHour, s.wakeMinute, day = 16)
            assertEquals(at(0, 30), SchedulePlanner.leaveFor(wake, s))
        }
    }

    @Test
    fun `mode switching keeps the chosen anchor and ordinary manual departure remains possible`() {
        val initial = AlarmSettings(wakeHour = 7, leaveHour = 12, leaveMinute = 10)
        val departure = initial.withDepartureTimeOnly(true)
        assertEquals("12:10", departure.leaveLabel)
        assertEquals("10:40", departure.wakeLabel)
        val ordinary = departure.withDepartureTimeOnly(false)
        assertEquals(departure.copy(departureTimeOnly = false), ordinary)
        assertEquals("10:40", ordinary.withLeaveTime(13, 0).wakeLabel)
        assertEquals("13:00", ordinary.withLeaveTime(13, 0).leaveLabel)
        assertEquals("09:40", ordinary.withPreparationMinutes(40).withWakeTime(9, 0).leaveLabel)
    }

    @Test
    fun `preparation duration bounds are forty to one hundred fifty minutes`() {
        val s = AlarmSettings().withDepartureTimeOnly(true).withLeaveTime(8, 0)
        assertEquals("07:20", s.withPreparationMinutes(0).wakeLabel)
        assertEquals(40, s.withPreparationMinutes(0).preparationMinutes)
        assertEquals("05:30", s.withPreparationMinutes(200).wakeLabel)
        assertEquals(150, s.withPreparationMinutes(200).preparationMinutes)
    }

    @Test
    fun `current time phrase uses correct hour and minute endings`() {
        for ((hour, minute, expected) in listOf(
            Triple(0, 0, "Сейчас 0 часов ровно"),
            Triple(1, 1, "Сейчас 1 час 1 минута"),
            Triple(2, 2, "Сейчас 2 часа 2 минуты"),
            Triple(5, 5, "Сейчас 5 часов 5 минут"),
            Triple(7, 30, "Сейчас 7 часов 30 минут"),
            Triple(11, 11, "Сейчас 11 часов 11 минут"),
            Triple(12, 12, "Сейчас 12 часов 12 минут"),
            Triple(21, 21, "Сейчас 21 час 21 минута"),
            Triple(23, 55, "Сейчас 23 часа 55 минут"),
        )) {
            assertEquals(expected, SpeechText.currentTime(java.time.LocalTime.of(hour, minute)))
        }
    }

    @Test
    fun `phrase grammar`() {
        assertEquals("До выхода осталась 1 минута", SpeechText.remaining(1))
        assertEquals("До выхода осталось 2 минуты", SpeechText.remaining(2))
        assertEquals("До выхода осталось 5 минут", SpeechText.remaining(5))
        assertEquals("До выхода осталось 11 минут", SpeechText.remaining(11))
        assertEquals("До выхода осталась 21 минута", SpeechText.remaining(21))
        assertEquals("Пора выходить", SpeechText.remaining(0))
    }
}
