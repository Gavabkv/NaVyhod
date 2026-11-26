package com.example.dovykhoda

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek

class SettingsStore(context: Context) {
    data class ActiveCycle(
        val wakeMillis: Long,
        val leaveMillis: Long,
        val remindersOnly: Boolean = false,
        val alarmId: Long = 0L,
    )

    private val prefs: SharedPreferences

    init {
        val app = context.applicationContext
        val storage = app.createDeviceProtectedStorageContext()
        if (app.getSystemService(UserManager::class.java).isUserUnlocked &&
            !storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_STORAGE_MIGRATED, false)
        ) {
            storage.moveSharedPreferencesFrom(app, PREFS_NAME)
            storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_STORAGE_MIGRATED, true).apply()
        }
        prefs = storage.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Голос больше не выключается настройкой: для старых установок включаем его один раз.
        if (!prefs.getBoolean(KEY_VOICE_ON_MIGRATED, false)) {
            prefs.edit()
                .putBoolean(KEY_SPEAK, true)
                .putBoolean(KEY_VOICE_ON_MIGRATED, true)
                .apply()
        }
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun load(): AlarmSettings {
        val legacyTime = prefs.getBoolean(KEY_SPEAK_TIME, true)
        val alarms = alarms()
        val active = activeCycle()
        val focus = alarms.firstOrNull { it.id == active?.alarmId }
            ?: alarms.firstOrNull { it.enabled }
            ?: alarms.firstOrNull()
        return AlarmSettings(
            wakeHour = focus?.wakeHour ?: 7,
            wakeMinute = focus?.wakeMinute ?: 0,
            leaveHour = focus?.leaveHour ?: 8,
            leaveMinute = focus?.leaveMinute ?: 0,
            intervalMinutes = prefs.getInt(KEY_INTERVAL, 5),
            speakEnabled = prefs.getBoolean(KEY_SPEAK, true),
            announceTimeOnSignals = prefs.getBoolean(KEY_ANNOUNCE_SIGNALS, legacyTime),
            announceTimeOnDeparture = prefs.getBoolean(KEY_ANNOUNCE_DEPARTURE, legacyTime),
            announceTimeOnLate = prefs.getBoolean(KEY_ANNOUNCE_LATE, legacyTime),
            ttsLeadMinutes = prefs.getInt(KEY_TTS_LEAD, 30),
            ringSeconds = prefs.getInt(KEY_RING_SECONDS, 40).coerceIn(20, 60),
            preNotifyEnabled = prefs.getBoolean(KEY_PRE_NOTIFY, true),
            enabled = alarms.any { it.enabled },
            preparationMinutes = focus?.preparationMinutes ?: 90,
            departureTimeOnly = focus?.departureTimeOnly ?: false,
            days = focus?.days ?: AlarmItem.ALL_DAYS,
        )
    }

    fun save(settings: AlarmSettings) {
        val previous = load()
        val alarms = alarms()
        if (previous.enabled != settings.enabled) {
            clearActiveCycle()
            // Старый «общий выключатель»: смена флага адресуется всем будильникам.
            if (alarms.isNotEmpty()) {
                persistAlarms(alarms.map { it.copy(enabled = settings.enabled) })
            }
        }
        // Настройки времени продолжают жить в единственном будильнике,
        // пока в списке не появится второй.
        if (alarms.size <= 1) {
            alarms.firstOrNull()?.let { alarm ->
                val updated = alarm.copy(
                    wakeHour = settings.wakeHour,
                    wakeMinute = settings.wakeMinute,
                    leaveHour = settings.leaveHour,
                    leaveMinute = settings.leaveMinute,
                    preparationMinutes = settings.preparationMinutes.coerceIn(40, 150),
                    departureTimeOnly = settings.departureTimeOnly,
                    days = settings.days,
                    enabled = settings.enabled,
                )
                if (updated != alarm) persistAlarms(listOf(updated))
            }
        }
        if (previous.wakeHour != settings.wakeHour || previous.wakeMinute != settings.wakeMinute ||
            previous.leaveHour != settings.leaveHour || previous.leaveMinute != settings.leaveMinute
        ) {
            activeCycle()?.takeIf { it.leaveMillis > System.currentTimeMillis() }?.let { cycle ->
                val date = java.time.Instant.ofEpochMilli(
                    if (settings.departureTimeOnly) cycle.leaveMillis else cycle.wakeMillis,
                ).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                val wake = if (settings.departureTimeOnly) {
                    date.atTime(SchedulePlanner.leaveTime(settings))
                        .minusMinutes(settings.preparationMinutes.toLong())
                } else date.atTime(SchedulePlanner.wakeTime(settings))
                setActiveCycle(cycle.copy(
                    wakeMillis = SchedulePlanner.toMillis(wake),
                    leaveMillis = SchedulePlanner.toMillis(SchedulePlanner.leaveFor(wake, settings)),
                ))
            }
        }
        prefs.edit()
            .putInt(KEY_WAKE_HOUR, settings.wakeHour)
            .putInt(KEY_WAKE_MINUTE, settings.wakeMinute)
            .putInt(KEY_LEAVE_HOUR, settings.leaveHour)
            .putInt(KEY_LEAVE_MINUTE, settings.leaveMinute)
            .putInt(KEY_INTERVAL, settings.intervalMinutes)
            .putBoolean(KEY_SPEAK, settings.speakEnabled)
            .putBoolean(KEY_ANNOUNCE_SIGNALS, settings.announceTimeOnSignals)
            .putBoolean(KEY_ANNOUNCE_DEPARTURE, settings.announceTimeOnDeparture)
            .putBoolean(KEY_ANNOUNCE_LATE, settings.announceTimeOnLate)
            .putInt(KEY_TTS_LEAD, settings.ttsLeadMinutes)
            .putInt(KEY_RING_SECONDS, settings.ringSeconds.coerceIn(20, 60))
            .putBoolean(KEY_PRE_NOTIFY, settings.preNotifyEnabled)
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putInt(KEY_PREPARATION, settings.preparationMinutes.coerceIn(40, 150))
            .putBoolean(KEY_DEPARTURE_TIME_ONLY, settings.departureTimeOnly)
            .apply()

        val changed = buildList {
            if (previous.enabled != settings.enabled) add("enabled")
            if (previous.wakeHour != settings.wakeHour || previous.wakeMinute != settings.wakeMinute) add("wake")
            if (previous.leaveHour != settings.leaveHour || previous.leaveMinute != settings.leaveMinute) add("leave")
            if (previous.intervalMinutes != settings.intervalMinutes) add("interval")
            if (previous.ttsLeadMinutes != settings.ttsLeadMinutes) add("ttsLead")
            if (previous.ringSeconds != settings.ringSeconds) add("ringSeconds")
            if (previous.preNotifyEnabled != settings.preNotifyEnabled) add("preNotify")
            if (previous.preparationMinutes != settings.preparationMinutes) add("preparation")
            if (previous.departureTimeOnly != settings.departureTimeOnly) add("departureOnly")
            if (previous.announceTimeOnSignals != settings.announceTimeOnSignals) add("timeSignals")
            if (previous.announceTimeOnDeparture != settings.announceTimeOnDeparture) add("timeDeparture")
            if (previous.announceTimeOnLate != settings.announceTimeOnLate) add("timeLate")
            if (previous.speakEnabled != settings.speakEnabled) add("signalsVoice")
        }
        AlarmLog.event(
            "settings_saved",
            "changed" to changed.joinToString(","),
            "enabled" to settings.enabled,
            "wake" to settings.wakeLabel,
            "leave" to settings.leaveLabel,
            "interval" to settings.intervalMinutes,
            "ttsLead" to settings.ttsLeadMinutes,
            "ringSeconds" to settings.ringSeconds,
            "preNotify" to settings.preNotifyEnabled,
            "preparation" to settings.preparationMinutes,
            "departureOnly" to settings.departureTimeOnly,
            "timeSignals" to settings.announceTimeOnSignals,
            "timeDeparture" to settings.announceTimeOnDeparture,
            "timeLate" to settings.announceTimeOnLate,
        )
    }

    /** Все будильники. При первом обращении старые настройки превращаются в один будильник. */
    fun alarms(): List<AlarmItem> {
        val raw = prefs.getString(KEY_ALARMS, null)
        if (raw == null) {
            val legacy = AlarmItem(
                id = 1L,
                wakeHour = prefs.getInt(KEY_WAKE_HOUR, 7),
                wakeMinute = prefs.getInt(KEY_WAKE_MINUTE, 0),
                leaveHour = prefs.getInt(KEY_LEAVE_HOUR, 8),
                leaveMinute = prefs.getInt(KEY_LEAVE_MINUTE, 0),
                preparationMinutes = prefs.getInt(KEY_PREPARATION, 90).coerceIn(40, 150),
                departureTimeOnly = prefs.getBoolean(KEY_DEPARTURE_TIME_ONLY, false),
                enabled = prefs.getBoolean(KEY_ENABLED, false),
            )
            persistAlarms(listOf(legacy))
            AlarmLog.event("alarms_migrated", "wake" to legacy.wakeLabel,
                "leave" to legacy.leaveLabel, "enabled" to legacy.enabled)
            return listOf(legacy)
        }
        return runCatching { parseAlarms(raw) }.getOrElse {
            AlarmLog.failure("alarms_parse_failed", it)
            emptyList()
        }
    }

    fun alarm(id: Long): AlarmItem? = alarms().firstOrNull { it.id == id }

    fun nextAlarmId(): Long = AlarmItem.newId(alarms())

    fun saveAlarms(items: List<AlarmItem>) {
        persistAlarms(items)
        AlarmLog.event("alarms_saved", "count" to items.size,
            "enabled" to items.count { it.enabled })
    }

    fun addAlarm(alarm: AlarmItem) = saveAlarms(alarms() + alarm)

    fun updateAlarm(alarm: AlarmItem) {
        val updated = alarms().map { if (it.id == alarm.id) alarm else it }
        saveAlarms(updated)
    }

    fun deleteAlarm(id: Long) {
        saveAlarms(alarms().filterNot { it.id == id })
    }

    /** Отключение одного будильника; id=0 — старый одиночный режим без списка. */
    fun setAlarmEnabled(id: Long, enabled: Boolean) {
        if (id == 0L) {
            save(load().copy(enabled = enabled))
            return
        }
        val alarm = alarm(id) ?: return
        updateAlarm(alarm.copy(enabled = enabled && alarm.days.isNotEmpty()))
    }

    /** Настройки одного будильника поверх глобальных; null — если будильника нет. */
    fun settingsFor(alarmId: Long, global: AlarmSettings = load()): AlarmSettings? =
        alarm(alarmId)?.toSettings(global)

    private fun parseAlarms(raw: String): List<AlarmItem> {
        val array = JSONArray(raw)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(AlarmItem(
                    id = item.getLong("id"),
                    wakeHour = item.getInt("wakeHour"),
                    wakeMinute = item.getInt("wakeMinute"),
                    leaveHour = item.getInt("leaveHour"),
                    leaveMinute = item.getInt("leaveMinute"),
                    preparationMinutes = item.optInt("preparation", 90).coerceIn(40, 150),
                    departureTimeOnly = item.optBoolean("departureOnly", false),
                    days = AlarmItem.fromMask(item.optInt("days", 0x7F)),
                    enabled = item.optBoolean("enabled", false),
                ))
            }
        }
    }

    private fun persistAlarms(items: List<AlarmItem>) {
        val array = JSONArray()
        items.forEach { alarm ->
            array.put(JSONObject().apply {
                put("id", alarm.id)
                put("wakeHour", alarm.wakeHour)
                put("wakeMinute", alarm.wakeMinute)
                put("leaveHour", alarm.leaveHour)
                put("leaveMinute", alarm.leaveMinute)
                put("preparation", alarm.preparationMinutes)
                put("departureOnly", alarm.departureTimeOnly)
                put("days", alarm.daysMask)
                put("enabled", alarm.enabled)
            })
        }
        prefs.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    fun activeCycle(): ActiveCycle? {
        val leave = prefs.getLong(KEY_ACTIVE_LEAVE, 0L)
        if (leave == 0L) return null
        return ActiveCycle(
            prefs.getLong(KEY_ACTIVE_WAKE, 0L), leave,
            prefs.getBoolean(KEY_REMINDERS_ONLY, false),
            prefs.getLong(KEY_ACTIVE_ALARM_ID, 0L),
        )
    }

    fun setActiveCycle(cycle: ActiveCycle) {
        val previous = activeCycle()
        val editor = prefs.edit()
            .putLong(KEY_ACTIVE_WAKE, cycle.wakeMillis)
            .putLong(KEY_ACTIVE_LEAVE, cycle.leaveMillis)
            .putBoolean(KEY_REMINDERS_ONLY, cycle.remindersOnly)
            .putLong(KEY_ACTIVE_ALARM_ID, cycle.alarmId)
        if (previous != null && previous.leaveMillis != cycle.leaveMillis) {
            editor.remove(spokenKey(previous.leaveMillis))
        }
        editor.apply()
        AlarmLog.event(
            "active_cycle_set",
            "wake" to AlarmLog.formatTime(cycle.wakeMillis),
            "leave" to AlarmLog.formatTime(cycle.leaveMillis),
            "remindersOnly" to cycle.remindersOnly,
            "alarmId" to cycle.alarmId,
        )
    }

    fun clearActiveCycle() {
        val cycle = activeCycle()
        if (cycle != null) AlarmLog.event("active_cycle_cleared")
        val editor = prefs.edit().remove(KEY_ACTIVE_WAKE).remove(KEY_ACTIVE_LEAVE)
            .remove(KEY_REMINDERS_ONLY).remove(KEY_SIGNAL_DELIVERED).remove(KEY_ACTIVE_ALARM_ID)
        if (cycle != null) editor.remove(spokenKey(cycle.leaveMillis))
        editor.apply()
    }

    /** Обычный сигнал уже доставлен сервису: страховочный повтор не должен звонить снова. */
    fun signalDelivered(wakeMillis: Long): Boolean =
        wakeMillis > 0L && prefs.getLong(KEY_SIGNAL_DELIVERED, 0L) == wakeMillis

    fun markSignalDelivered(wakeMillis: Long) {
        if (wakeMillis <= 0L) return
        prefs.edit().putLong(KEY_SIGNAL_DELIVERED, wakeMillis).apply()
    }

    /**
     * Какие напоминания о выходе уже озвучены в этом цикле.
     * Защищает от повторов, когда сигнал приходит и от Exact-будильника,
     * и от страховочного повтора.
     */
    fun spokenDepartureReminders(leaveMillis: Long): Set<Int> =
        prefs.getStringSet(spokenKey(leaveMillis), emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            .orEmpty()

    fun markDepartureReminderSpoken(leaveMillis: Long, minutes: Int) {
        val key = spokenKey(leaveMillis)
        val spoken = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (!spoken.add(minutes.toString())) return
        prefs.edit().putStringSet(key, spoken).apply()
    }

    private fun spokenKey(leaveMillis: Long) = KEY_DEPARTURE_SPOKEN + leaveMillis

    /** Для какого цикла (момент пробуждения) пользователь смахнул уведомление. */
    fun preNotifyDismissedCycle(): Long = prefs.getLong(KEY_PRE_DISMISSED_CYCLE, 0L)

    fun dismissPreNotify(cycleWakeMillis: Long) {
        prefs.edit().putLong(KEY_PRE_DISMISSED_CYCLE, cycleWakeMillis).apply()
    }

    private companion object {
        const val PREFS_NAME = "alarm_settings"
        const val KEY_STORAGE_MIGRATED = "storage_migrated"

        const val KEY_WAKE_HOUR = "wake_hour"
        const val KEY_WAKE_MINUTE = "wake_minute"
        const val KEY_LEAVE_HOUR = "leave_hour"
        const val KEY_LEAVE_MINUTE = "leave_minute"
        const val KEY_INTERVAL = "interval_minutes"
        const val KEY_SPEAK = "speak_enabled"
        const val KEY_ANNOUNCE_SIGNALS = "announce_time_signals"
        const val KEY_ANNOUNCE_DEPARTURE = "announce_time_departure"
        const val KEY_ANNOUNCE_LATE = "announce_time_late"
        const val KEY_SPEAK_TIME = "speak_time_enabled"
        const val KEY_VOICE_ON_MIGRATED = "voice_on_migrated"
        const val KEY_TTS_LEAD = "tts_lead_minutes"
        const val KEY_RING_SECONDS = "ring_seconds"
        const val KEY_PRE_NOTIFY = "pre_notify_enabled"
        const val KEY_PRE_DISMISSED_CYCLE = "pre_notify_dismissed_cycle"
        const val KEY_ENABLED = "enabled"
        const val KEY_PREPARATION = "preparation_minutes"
        const val KEY_DEPARTURE_TIME_ONLY = "departure_time_only"
        const val KEY_ALARMS = "alarms_json"
        const val KEY_ACTIVE_WAKE = "active_cycle_wake"
        const val KEY_ACTIVE_LEAVE = "active_cycle_leave"
        const val KEY_REMINDERS_ONLY = "reminders_only"
        const val KEY_ACTIVE_ALARM_ID = "active_cycle_alarm_id"
        const val KEY_DEPARTURE_SPOKEN = "departure_spoken_"
        const val KEY_SIGNAL_DELIVERED = "signal_delivered_wake"
    }
}
