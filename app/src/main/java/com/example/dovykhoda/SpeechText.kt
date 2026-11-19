package com.example.dovykhoda

import java.time.LocalTime

/** Фразы для TTS с правильными русскими окончаниями. */
object SpeechText {

    fun currentTime(time: LocalTime): String {
        val hours = "${time.hour} ${plural(time.hour, "час", "часа", "часов")}"
        val minutes = if (time.minute == 0) "ровно"
            else "${time.minute} ${plural(time.minute, "минута", "минуты", "минут")}"
        return "Сейчас $hours $minutes"
    }

    fun remaining(minutes: Int): String {
        if (minutes <= 0) return "Пора выходить"

        val one = endsWithOne(minutes)
        val verb = if (one) "осталась" else "осталось"
        val word = plural(minutes, "минута", "минуты", "минут")
        return "До выхода $verb $minutes $word"
    }

    fun departure(minutes: Int): String = if (minutes <= 0) "Пора выходить"
        else "Через $minutes ${plural(minutes, "минуту", "минуты", "минут")} надо выйти"

    /** Напоминание об опоздании: «Вы опоздали с выходом на 2 минуты». */
    fun late(minutes: Int): String {
        val word = plural(minutes, "минуту", "минуты", "минут")
        return "Вы опоздали с выходом на $minutes $word"
    }

    /** Например: 21 -> «минута», 22 -> «минуты», 25 -> «минут». */
    fun plural(number: Int, one: String, few: String, many: String): String {
        val mod100 = number % 100
        if (mod100 in 11..14) return many
        return when (number % 10) {
            1 -> one
            2, 3, 4 -> few
            else -> many
        }
    }

    private fun endsWithOne(number: Int): Boolean =
        number % 10 == 1 && number % 100 != 11
}
