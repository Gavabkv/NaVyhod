package com.example.dovykhoda

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Язык интерфейса. null — как в системе, иначе явный выбор пользователя.
 * Автовыбор работает штатными ресурсами Android: values-ru, values-en и т. д.
 */
object LocaleHelper {

    data class Language(val tag: String?, val label: String)

    /** Порядок пунктов в меню: системный, затем языки по алфавиту. */
    val LANGUAGES = listOf(
        Language(null, ""),
        Language("de", "Deutsch"),
        Language("en", "English"),
        Language("es", "Español"),
        Language("fr", "Français"),
        Language("it", "Italiano"),
        Language("ja", "日本語"),
        Language("pt", "Português"),
        Language("ru", "Русский"),
    )

    /** Контекст с ресурсами выбранного языка; без выбора — системный. */
    fun wrap(context: Context): Context {
        val tag = SettingsStore(context).languageTag() ?: return context
        val locale = Locale.forLanguageTag(tag)
        if (context.resources.configuration.locales[0] == locale) return context
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }

    /** Текущая локаль: выбор пользователя или системная. */
    fun appLocale(context: Context): Locale =
        context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun speechLang(context: Context): SpeechLang = SpeechLang.fromLocale(appLocale(context))

    fun label(tag: String?): String =
        LANGUAGES.firstOrNull { it.tag == tag }?.label.orEmpty()
}
