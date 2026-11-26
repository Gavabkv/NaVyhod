package com.example.dovykhoda

import java.time.LocalTime
import java.util.Locale

/** Язык голосовых объявлений; без явного выбора — английский, как и базовые ресурсы. */
enum class SpeechLang(val tag: String) {
    RU("ru"), EN("en"), ES("es"), DE("de"), JA("ja"), FR("fr"), PT("pt"), IT("it");

    companion object {
        fun fromTag(tag: String?): SpeechLang =
            entries.firstOrNull { it.tag == tag?.lowercase() } ?: EN

        fun fromLocale(locale: Locale): SpeechLang = fromTag(locale.language)
    }
}

/** Фразы для TTS. Тексты подчиняются грамматике выбранного языка. */
object SpeechText {

    fun currentTime(time: LocalTime, lang: SpeechLang = SpeechLang.RU): String {
        val hour = time.hour
        val minute = time.minute
        return when (lang) {
            SpeechLang.RU -> {
                val hours = "$hour ${plural(hour, "час", "часа", "часов")}"
                val minutes = if (minute == 0) "ровно"
                    else "$minute ${plural(minute, "минута", "минуты", "минут")}"
                "Сейчас $hours $minutes"
            }
            SpeechLang.EN -> "It's $hour:${clock(minute)}"
            SpeechLang.ES -> "Son las $hour:${clock(minute)}"
            SpeechLang.DE -> "Es ist $hour:${clock(minute)} Uhr"
            SpeechLang.JA -> "${hour}時${clock(minute)}分です"
            SpeechLang.FR -> "Il est $hour:${clock(minute)}"
            SpeechLang.PT -> "São $hour:${clock(minute)}"
            SpeechLang.IT -> "Sono le $hour:${clock(minute)}"
        }
    }

    fun remaining(minutes: Int, lang: SpeechLang = SpeechLang.RU): String {
        if (minutes <= 0) return timeToLeave(lang)
        return when (lang) {
            SpeechLang.RU -> {
                val one = endsWithOne(minutes)
                val verb = if (one) "осталась" else "осталось"
                "До выхода $verb $minutes ${plural(minutes, "минута", "минуты", "минут")}"
            }
            SpeechLang.EN -> if (minutes == 1) "1 minute left until departure"
                else "$minutes minutes left until departure"
            SpeechLang.ES -> if (minutes == 1) "Queda 1 minuto para salir"
                else "Quedan $minutes minutos para salir"
            SpeechLang.DE -> if (minutes == 1) "Noch 1 Minute bis zum Aufbruch"
                else "Noch $minutes Minuten bis zum Aufbruch"
            SpeechLang.JA -> "出発まであと${minutes}分"
            SpeechLang.FR -> if (minutes == 1) "Il reste 1 minute avant le départ"
                else "Il reste $minutes minutes avant le départ"
            SpeechLang.PT -> if (minutes == 1) "Falta 1 minuto para sair"
                else "Faltam $minutes minutos para sair"
            SpeechLang.IT -> if (minutes == 1) "Manca 1 minuto all'uscita"
                else "Mancano $minutes minuti all'uscita"
        }
    }

    fun departure(minutes: Int, lang: SpeechLang = SpeechLang.RU): String {
        if (minutes <= 0) return timeToLeave(lang)
        return when (lang) {
            SpeechLang.RU -> "Через $minutes ${plural(minutes, "минуту", "минуты", "минут")} надо выйти"
            SpeechLang.EN -> if (minutes == 1) "You need to leave in 1 minute"
                else "You need to leave in $minutes minutes"
            SpeechLang.ES -> if (minutes == 1) "Tienes que salir en 1 minuto"
                else "Tienes que salir en $minutes minutos"
            SpeechLang.DE -> if (minutes == 1) "Du musst in 1 Minute los"
                else "Du musst in $minutes Minuten los"
            SpeechLang.JA -> "${minutes}分後に出発してください"
            SpeechLang.FR -> if (minutes == 1) "Vous devez partir dans 1 minute"
                else "Vous devez partir dans $minutes minutes"
            SpeechLang.PT -> if (minutes == 1) "Você precisa sair em 1 minuto"
                else "Você precisa sair em $minutes minutos"
            SpeechLang.IT -> if (minutes == 1) "Devi uscire tra 1 minuto"
                else "Devi uscire tra $minutes minuti"
        }
    }

    fun late(minutes: Int, lang: SpeechLang = SpeechLang.RU): String = when (lang) {
        SpeechLang.RU -> "Вы опоздали с выходом на $minutes ${plural(minutes, "минуту", "минуты", "минут")}"
        SpeechLang.EN -> if (minutes == 1) "You are 1 minute late leaving"
            else "You are $minutes minutes late leaving"
        SpeechLang.ES -> if (minutes == 1) "Has salido 1 minuto tarde"
            else "Has salido $minutes minutos tarde"
        SpeechLang.DE -> if (minutes == 1) "Du bist 1 Minute zu spät losgegangen"
            else "Du bist $minutes Minuten zu spät losgegangen"
        SpeechLang.JA -> "出発が${minutes}分遅れています"
        SpeechLang.FR -> if (minutes == 1) "Vous êtes parti 1 minute en retard"
            else "Vous êtes parti $minutes minutes en retard"
        SpeechLang.PT -> if (minutes == 1) "Você saiu 1 minuto atrasado"
            else "Você saiu $minutes minutos atrasado"
        SpeechLang.IT -> if (minutes == 1) "Sei uscito 1 minuto in ritardo"
            else "Sei uscito $minutes minuti in ritardo"
    }

    fun timeToLeave(lang: SpeechLang = SpeechLang.RU): String = when (lang) {
        SpeechLang.RU -> "Пора выходить"
        SpeechLang.EN -> "Time to leave"
        SpeechLang.ES -> "Es hora de salir"
        SpeechLang.DE -> "Zeit zu gehen"
        SpeechLang.JA -> "出発の時間です"
        SpeechLang.FR -> "Il est temps de partir"
        SpeechLang.PT -> "Hora de sair"
        SpeechLang.IT -> "È ora di uscire"
    }

    /** Например: 21 -> «минута», 22 -> «минуты», 25 -> «минут». */
    private fun plural(number: Int, one: String, few: String, many: String): String {
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

    private fun clock(value: Int): String = value.toString().padStart(2, '0')
}
