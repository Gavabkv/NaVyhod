package com.gavrovis.navyhod

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var toneGenerator: ToneGenerator? = null
    private var toneJob: Job? = null
    private var ringJob: Job? = null
    private var lateJob: Job? = null
    private var speechJob: Job? = null
    private var speaker: VoiceSpeaker? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var foregroundStarted = false
    private var ringScreenVisible = false
    private var musicDucked = false
    private var testMode = false
    private var lastLateMinutes = 0
    private var sessionCycle: SettingsStore.ActiveCycle? = null
    private val announcingReminders = mutableSetOf<Int>()

    private val speechLang: SpeechLang get() = LocaleHelper.speechLang(this)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        AlarmLog.event("service_created")
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NaVykhod:alarm")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AlarmLog.event(
            "service_command",
            "action" to intent?.action,
            "flags" to flags,
            "startId" to startId,
            "startedRinging" to isRinging,
            "test" to intent?.getBooleanExtra(EXTRA_TEST, false),
        )
        ensureForeground(intent)
        when (intent?.action) {
            ACTION_TRIGGER -> startAlarm(intent)
            ACTION_SILENCE -> finishRing()
            ACTION_STOP_ALL -> {
                // Выключить расписание можно только после подтверждения на экране сигнала.
                if (intent.getBooleanExtra(EXTRA_CONFIRMED_STOP, false)) {
                    handleStopAlarm()
                } else {
                    AlarmLog.event("stop_alarm_ignored", "reason" to "no_confirmation")
                }
            }
            ACTION_REMIND_ONLY -> handleRemindersOnly()
            ACTION_DISABLE -> {
                SettingsStore(this).clearActiveCycle()
                finishSession(refresh = false)
            }
            ACTION_LATE -> handleLate(intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L))
            ACTION_DEPARTURE_REMINDER -> handleDepartureReminder(
                intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L),
                intent.getIntExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, -1),
                intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L),
            )
            ACTION_REFRESH, null -> resumeReminders()
            else -> stopSelf()
        }
        return if (!testMode && (lateJob?.isActive == true || reminderStandbyActive())) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    /**
     * Android даёт около десяти секунд на startForeground() после
     * startForegroundService(). Уведомление показывается раньше чтения
     * настроек: если дальше систему заклинит, сервис всё равно успеет
     * стать foreground и не будет убит по таймауту.
     */
    private fun ensureForeground(intent: Intent?) {
        if (foregroundStarted) return
        val notification = when (intent?.action) {
            ACTION_DEPARTURE_REMINDER -> {
                val leave = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L)
                if (leave > 0L) StatusNotifier.buildReminders(this, leave)
                else StatusNotifier.buildStarting(this)
            }
            ACTION_TRIGGER, ACTION_LATE, ACTION_REMIND_ONLY, null -> StatusNotifier.buildStarting(this)
            else -> return
        }
        startForegroundNow(notification, ringing = false)
    }

    /** Напоминания идут прямо сейчас: сервис держит foreground до момента выхода. */
    private fun reminderStandbyActive(): Boolean {
        val cycle = sessionCycle ?: return false
        return cycle.remindersOnly && System.currentTimeMillis() <= cycle.leaveMillis + 5_000L
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        AlarmLog.event("service_destroyed", "ringing" to isRinging)
        scope.cancel()
        releaseAll()
        shutdownVoice()
        closeRingScreen()
        isRinging = false
        super.onDestroy()
    }

    private fun startAlarm(intent: Intent) {
        val store = SettingsStore(this)
        val settings = store.load()
        val test = intent.getBooleanExtra(EXTRA_TEST, false)
        if (test && isRinging) return
        val now = System.currentTimeMillis()
        val atMillis = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, 0L)
        val leaveMillis = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, 0L)
        AlarmLog.event(
            "signal_start",
            "test" to test,
            "now" to AlarmLog.formatTime(now),
            "at" to if (atMillis > 0L) AlarmLog.formatTime(atMillis) else null,
            "lateMs" to if (atMillis > 0L) now - atMillis else null,
            "wake" to if (leaveMillis > 0L) AlarmLog.formatTime(
                intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, 0L)
            ) else null,
            "leave" to if (leaveMillis > 0L) AlarmLog.formatTime(leaveMillis) else null,
            "enabled" to settings.enabled,
            "ringSeconds" to settings.ringSeconds,
            "ttsLead" to settings.ttsLeadMinutes,
            "timeSignals" to settings.announceTimeOnSignals,
            "timeDeparture" to settings.announceTimeOnDeparture,
            "timeLate" to settings.announceTimeOnLate,
        )
        if (!test && !settings.enabled) {
            AlarmLog.event("signal_ignored", "reason" to "disabled")
            stopSelf()
            return
        }
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, 0L)
        if (!test && alarmId > 0L && store.alarm(alarmId)?.scheduled != true) {
            AlarmLog.event("signal_ignored", "reason" to "alarm_off", "alarmId" to alarmId)
            stopSelf()
            return
        }

        val active = store.activeCycle()
        val wakeMillis = intent.getLongExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, 0L)
        // Режим «напомнить» относится только к своему циклу. Сигнал другого
        // будильника обязан звонить, даже если рядом идут напоминания.
        val sameCycle = active != null && active.matches(wakeMillis, leaveMillis, alarmId)
        if (!test && sameCycle && (active!!.remindersOnly ||
            (now >= active.leaveMillis && lateJob?.isActive == true))
        ) {
            AlarmLog.event(
                "signal_to_reminders",
                "remindersOnly" to active.remindersOnly,
                "activeLeave" to AlarmLog.formatTime(active.leaveMillis),
            )
            resumeReminders()
            return
        }
        if (!test) {
            store.setActiveCycle(SettingsStore.ActiveCycle(wakeMillis, leaveMillis, alarmId = alarmId))
            // Повтор через 30 секунд после доставленного сигнала звонить не должен.
            store.markSignalDelivered(atMillis)
        }
        releaseAll()
        stopForegroundNow()
        sessionCycle = if (test) null else store.activeCycle()
        testMode = test
        lastLateMinutes = 0
        val remaining = if (test) minOf(settings.ttsLeadMinutes, 10).coerceAtLeast(1)
            else SchedulePlanner.remainingMinutes(now, leaveMillis)
        val speak = settings.speakEnabled && (test || remaining <= settings.ttsLeadMinutes)
        isRinging = true
        if (settings.enabled && !test) store.dismissPreNotify(0L)
        AlarmLog.event(
            "signal_ringing",
            "remaining" to remaining,
            "speak" to speak,
            "leaveInMs" to (leaveMillis - now),
        )
        startForegroundNow(StatusNotifier.buildRinging(this, remaining), ringing = true)
        acquireWakeLock()
        requestAudioFocus()
        // Движок инициализируется, пока звучит музыка: к реплике он уже готов,
        // иначе первый запуск TTS может сорваться в запасной сигнал.
        if (speak) voice()
        playMusic()
        startVibration()
        showRingScreen(remaining)

        ringJob = scope.launch {
            if (speak) {
                delay(SPEECH_DELAY_MS)
                speechJob = scope.launch {
                    val phrase = withCurrentTime(
                        SpeechText.remaining(remaining, speechLang),
                        settings.announceTimeOnSignals,
                    )
                    val spoken = voice().speak(phrase)
                    AlarmLog.event("signal_voice", "spoken" to spoken, "text" to phrase)
                }
            }
            delay((settings.ringSeconds.coerceIn(20, 60) * 1_000L -
                if (speak) SPEECH_DELAY_MS else 0L).coerceAtLeast(0L))
            ringJob = null
            finishRing()
        }
        if (!test && (atMillis == leaveMillis || now >= leaveMillis)) {
            AlarmLog.event("late_sequence_start", "leave" to AlarmLog.formatTime(leaveMillis))
            startLateSequence(leaveMillis)
        }
    }

    private fun finishRing() {
        AlarmLog.event("finish_ring", "lateActive" to (lateJob?.isActive == true))
        stopCurrentSignal()
        if (lateJob?.isActive == true) {
            showWaitingNotification()
        } else {
            finishSession()
        }
    }

    private fun stopCurrentSignal(closeScreen: Boolean = true) {
        ringJob?.cancel()
        ringJob = null
        releaseAudio()
        if (closeScreen) closeRingScreen()
    }

    private fun finishSession(refresh: Boolean = true) {
        AlarmLog.event("finish_session", "refresh" to refresh, "lateActive" to (lateJob?.isActive == true))
        releaseAll()
        shutdownVoice()
        isRinging = false
        stopForegroundNow()
        closeRingScreen()
        StatusNotifier.cancel(this)
        if (refresh) StatusNotifier.refresh(this, SettingsStore(this).load())
        stopSelf()
    }

    private fun handleStopAlarm() {
        val store = SettingsStore(this)
        AlarmLog.event(
            "stop_alarm",
            "testMode" to testMode,
            "enabled" to store.load().enabled,
            "active" to store.activeCycle()?.let {
                "leave=${AlarmLog.formatTime(it.leaveMillis)} remindersOnly=${it.remindersOnly}"
            },
        )
        if (!testMode) {
            val cycle = store.activeCycle()
            if (cycle != null) {
                // Останавливаются только сигналы текущего цикла. Будильник
                // остаётся включённым: завтра он должен зазвонить снова.
                store.markCycleStopped(cycle)
                store.clearActiveCycle()
            }
        }
        finishSession(refresh = false)
        val settings = store.load()
        AlarmScheduler.reschedule(this, settings)
        StatusNotifier.refresh(this, settings)
    }

    private fun handleRemindersOnly() {
        val store = SettingsStore(this)
        val cycle = store.activeCycle()
        if (testMode || !store.load().enabled || cycle == null || isCycleAlarmOff(cycle)) {
            AlarmLog.event(
                "reminders_only_ignored",
                "testMode" to testMode,
                "enabled" to store.load().enabled,
                "hasCycle" to (cycle != null),
                "alarmOff" to (cycle?.let { isCycleAlarmOff(it) }),
            )
            return
        }
        AlarmLog.event(
            "reminders_only_on",
            "leave" to AlarmLog.formatTime(cycle.leaveMillis),
            "wake" to AlarmLog.formatTime(cycle.wakeMillis),
        )
        store.setActiveCycle(cycle.copy(remindersOnly = true))
        resumeReminders()
        AlarmScheduler.reschedule(this, store.load())
    }

    private fun handleDepartureReminder(leaveMillis: Long, minutes: Int, atMillis: Long) {
        val store = SettingsStore(this)
        val cycle = store.activeCycle()
        val now = System.currentTimeMillis()
        if (!store.load().enabled || cycle?.remindersOnly != true || cycle.leaveMillis != leaveMillis ||
            minutes !in listOf(20, 15, 10, 5, 0) || atMillis <= 0L || now - atMillis > 5 * 60_000L ||
            cycle?.let { isCycleAlarmOff(it) } == true
        ) {
            AlarmLog.event("departure_reminder_ignored", "minutes" to minutes,
                "leave" to AlarmLog.formatTime(leaveMillis), "activeLeave" to cycle?.leaveMillis)
            if (cycle?.remindersOnly == true && now > cycle.leaveMillis + 5_000L) handleStopAlarm()
            else if (!isRinging && !reminderStandbyActive()) {
                stopForegroundNow()
                stopSelf()
            }
            return
        }
        val announcing = minutes in announcingReminders
        if (announcing || store.spokenDepartureReminders(leaveMillis).contains(minutes)) {
            // Страховочный повтор или повторная доставка после перезапуска:
            // этот сигнал уже звучит или уже прозвучал.
            AlarmLog.event("departure_reminder_duplicate", "minutes" to minutes,
                "announcing" to announcing)
            return
        }
        sessionCycle = cycle
        testMode = false
        isRinging = true
        announcingReminders += minutes
        startForegroundNow(StatusNotifier.buildReminders(this, leaveMillis), ringing = false)
        acquireWakeLock()
        AlarmLog.event("departure_reminder", "minutes" to minutes,
            "due" to AlarmLog.formatTime(atMillis), "lateMs" to (now - atMillis))
        scope.launch {
            try {
                val announce = store.load().announceTimeOnDeparture
                speakReminder(SpeechText.departure(minutes, speechLang), announceTime = announce)
                store.markDepartureReminderSpoken(leaveMillis, minutes)
                if (minutes == 0) {
                    AlarmScheduler.cancelDepartureReminders(this@AlarmService)
                    handleStopAlarm()
                } else {
                    releaseAll()
                    isRinging = false
                    // Не останавливаем сервис: следующий сигнал придёт в уже живой
                    // foreground-процесс, без старта сервиса из фона.
                    AlarmLog.event("reminder_standby", "minutes" to minutes,
                        "leave" to AlarmLog.formatTime(leaveMillis))
                }
            } finally {
                announcingReminders -= minutes
            }
        }
    }

    private fun handleLate(leaveMillis: Long) {
        val active = SettingsStore(this).activeCycle()
        AlarmLog.event(
            "handle_late",
            "leave" to AlarmLog.formatTime(leaveMillis),
            "activeLeave" to active?.let { AlarmLog.formatTime(it.leaveMillis) },
            "ringing" to isRinging,
        )
        if (active?.leaveMillis != leaveMillis) {
            if (!isRinging) stopSelf()
            return
        }
        resumeReminders()
    }

    private fun resumeReminders() {
        val store = SettingsStore(this)
        val cycle = store.activeCycle()
        val now = System.currentTimeMillis()
        AlarmLog.event(
            "resume_reminders",
            "now" to AlarmLog.formatTime(now),
            "enabled" to store.load().enabled,
            "active" to cycle?.let {
                "wake=${AlarmLog.formatTime(it.wakeMillis)} leave=${AlarmLog.formatTime(it.leaveMillis)} remindersOnly=${it.remindersOnly}"
            },
            "lateActive" to (lateJob?.isActive == true),
            "sameSession" to (sessionCycle == cycle),
        )
        if (!store.load().enabled || cycle == null || isCycleAlarmOff(cycle)) {
            finishSession(refresh = false)
            return
        }
        if (!cycle.remindersOnly && now < cycle.leaveMillis) return
        if (lateJob?.isActive == true && sessionCycle == cycle) return
        if (cycle.remindersOnly && now > cycle.leaveMillis + 5_000L) {
            handleStopAlarm()
            return
        }
        stopCurrentSignal()
        releaseAll()
        sessionCycle = cycle
        testMode = false
        lastLateMinutes = 0
        if (cycle.remindersOnly && now < cycle.leaveMillis - 20 * 60_000L) {
            AlarmLog.event("reminders_wait_for_window", "leave" to AlarmLog.formatTime(cycle.leaveMillis))
            finishSession(refresh = false)
            AlarmScheduler.reschedule(this, store.load())
            StatusNotifier.refresh(this, store.load())
            return
        }
        isRinging = true
        showWaitingNotification()
        if (cycle.remindersOnly) {
            AlarmScheduler.scheduleDepartureReminders(this, cycle)
            lateJob?.cancel()
            lateJob = null
            releaseAll()
            // Держим foreground-сеанс до последнего напоминания: система с меньшей
            // вероятностью убьёт живой процесс, а сигналы придут уже в него.
            // Заодно заранее поднимаем движок речи, чтобы первое напоминание
            // не сорвалось в писк.
            isRinging = false
            if (store.load().speakEnabled) voice()
            AlarmLog.event("departure_alarms_armed", "leave" to AlarmLog.formatTime(cycle.leaveMillis))
            return
        }
        acquireWakeLock()
        startLateSequence(cycle.leaveMillis)
    }

    private fun startLateSequence(leaveMillis: Long) {
        lateJob?.cancel()
        val cycle = sessionCycle ?: return
        AlarmLog.event(
            "late_sequence",
            "leave" to AlarmLog.formatTime(leaveMillis),
            "remindersOnly" to cycle.remindersOnly,
        )
        lateJob = scope.launch(start = CoroutineStart.LAZY) {
            val startedAt = System.currentTimeMillis()
            val elapsedOrigin = SystemClock.elapsedRealtime()
            fun nowMillis() = startedAt + SystemClock.elapsedRealtime() - elapsedOrigin
            var minutes = SchedulePlanner.nextLateMinute(nowMillis(), leaveMillis)
            while (isActive && canContinueReminders(cycle)) {
                val due = leaveMillis + minutes * 60_000L
                // Будильник на ближайшую минуту в системе: если процесс убьют
                // между репликами, сервис перезапустится и продолжит опоздание.
                if (due - nowMillis() >= 5_000L) {
                    AlarmScheduler.armLateBackstop(this@AlarmService, cycle, due)
                }
                delay((due - nowMillis()).coerceAtLeast(0L))
                if (!canContinueReminders(cycle)) break
                acquireWakeLock()
                stopCurrentSignal(closeScreen = false)
                lastLateMinutes = minutes
                startForegroundNow(
                    StatusNotifier.buildLate(this@AlarmService, minutes, showWindow = true),
                    ringing = true,
                )
                showRingScreen(remainingMinutes = 0, lateMinutes = minutes)
                val announce = SettingsStore(this@AlarmService).load().announceTimeOnLate
                AlarmLog.event(
                    "late_reminder",
                    "minutes" to minutes,
                    "due" to AlarmLog.formatTime(due),
                    "lateMs" to (System.currentTimeMillis() - due),
                    "announceTime" to announce,
                )
                speakReminder(SpeechText.late(minutes, speechLang), repeats = 3, announceTime = announce)
                minutes = maxOf(minutes + 1,
                    SchedulePlanner.nextLateMinute(nowMillis(), leaveMillis))
            }
            lateJob = null
            AlarmLog.event("late_sequence_done", "lastLateMinutes" to lastLateMinutes)
            finishSession()
        }
        lateJob?.start()
    }

    private fun canContinueReminders(cycle: SettingsStore.ActiveCycle): Boolean {
        val store = SettingsStore(this)
        return store.load().enabled && !isCycleAlarmOff(cycle) && store.activeCycle() == cycle
    }

    /** Сработавший цикл относится к будильнику, который уже выключили или удалили. */
    private fun isCycleAlarmOff(cycle: SettingsStore.ActiveCycle): Boolean =
        cycle.alarmId > 0L && SettingsStore(this).alarm(cycle.alarmId)?.scheduled != true

    private fun sessionSettings(settings: AlarmSettings): AlarmSettings =
        sessionCycle?.let { SettingsStore(this).settingsFor(it.alarmId) } ?: settings

    private fun withCurrentTime(text: String, announceTime: Boolean): String =
        if (announceTime) "${SpeechText.currentTime(LocalTime.now(), speechLang)}. $text" else text

    private suspend fun speakReminder(text: String, repeats: Int = 1, announceTime: Boolean = false) {
        AlarmLog.event(
            "speak_start",
            "length" to text.length,
            "repeats" to repeats,
            "announceTime" to announceTime,
            "text" to text,
        )
        requestAudioFocus()
        var spoken = true
        val speech = scope.launch(start = CoroutineStart.LAZY) {
            repeat(repeats) { index ->
                if (index > 0) delay(3_000L)
                val phrase = if (announceTime && index == 0) withCurrentTime(text, true) else text
                if (!voice().speak(phrase)) spoken = false
            }
        }
        speechJob = speech
        speech.start()
        speech.join()
        speechJob = null
        if (!spoken && !speech.isCancelled) {
            Log.w(TAG, "reminder speech unavailable, playing fallback tone")
            AlarmLog.event("speak_unavailable", "text" to text)
            startFallbackTone()
            delay(1_500L)
        }
        AlarmLog.event("speak_done", "spoken" to spoken, "cancelled" to speech.isCancelled)
        releaseAudio()
    }

    private fun showWaitingNotification() {
        val cycle = sessionCycle
        val notification = when {
            lastLateMinutes > 0 -> StatusNotifier.buildLate(this, lastLateMinutes)
            cycle?.remindersOnly == true -> StatusNotifier.buildReminders(this, cycle.leaveMillis)
            else -> StatusNotifier.buildActive(this, sessionSettings(SettingsStore(this).load()), 0)
        }
        startForegroundNow(notification)
    }

    private fun playMusic() {
        AlarmLog.event("music_start")
        releasePlayer()
        val mediaPlayer = MediaPlayer()
        try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION)
            if (uri == null) {
                mediaPlayer.release()
                AlarmLog.event("music_no_uri")
                startFallbackTone()
                return
            }
            AlarmLog.event("music_uri", "uri" to uri.toString())
            mediaPlayer.setAudioAttributes(alarmAttributes())
            mediaPlayer.setDataSource(this, uri)
            mediaPlayer.isLooping = true
            mediaPlayer.setOnPreparedListener {
                if (player === it) {
                    runCatching { it.start() }.onFailure { error ->
                        Log.w(TAG, "cannot start system ringtone", error)
                        AlarmLog.failure("music_start_failed", error)
                        releasePlayer()
                        startFallbackTone()
                    }.onSuccess { AlarmLog.event("music_playing") }
                }
            }
            mediaPlayer.setOnErrorListener { failed, what, extra ->
                if (player === failed) {
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    AlarmLog.event("music_error", "what" to what, "extra" to extra)
                    releasePlayer()
                    startFallbackTone()
                }
                true
            }
            player = mediaPlayer
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "cannot play system ringtone", error)
            AlarmLog.failure("music_failed", error)
            player = null
            runCatching { mediaPlayer.release() }
            startFallbackTone()
        }
    }

    private fun startFallbackTone() {
        if (toneGenerator != null) return
        val generator = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }
            .onFailure {
                Log.w(TAG, "cannot create fallback tone", it)
                AlarmLog.failure("tone_failed", it)
            }.getOrNull() ?: return
        AlarmLog.event("tone_start")
        toneGenerator = generator
        toneJob = scope.launch {
            while (isActive) {
                if (!musicDucked) generator.startTone(ToneGenerator.TONE_CDMA_HIGH_L, 1_200)
                delay(1_700L)
            }
        }
    }

    private fun releasePlayer() {
        toneJob?.cancel()
        toneJob = null
        toneGenerator?.release()
        toneGenerator = null
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.release() }
        }
        player = null
    }

    private fun alarmAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun requestAudioFocus() {
        abandonAudioFocus()
        val manager = getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(alarmAttributes())
            .setWillPauseWhenDucked(false)
            .build()
        runCatching { manager.requestAudioFocus(request) }
        audioManager = manager
        focusRequest = request
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { request -> runCatching { audioManager?.abandonAudioFocusRequest(request) } }
        audioManager = null
        focusRequest = null
    }

    private fun voice(): VoiceSpeaker = speaker ?: VoiceSpeaker(
        context = this,
        onSpeechStart = { scope.launch { duckMusic(true) } },
        onSpeechEnd = { scope.launch { duckMusic(false) } },
    ).also { speaker = it }

    private fun duckMusic(duck: Boolean) {
        musicDucked = duck
        val volume = if (duck) 0.15f else 1f
        runCatching { player?.setVolume(volume, volume) }
        if (duck) runCatching { toneGenerator?.stopTone() }
    }

    private fun startVibration() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        runCatching {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 700, 600, 1_200), 0))
        }
    }

    private fun acquireWakeLock(timeoutMillis: Long? = DEFAULT_WAKE_LOCK_MS) {
        runCatching {
            val lock = wakeLock ?: return@runCatching
            if (timeoutMillis == null) lock.acquire() else lock.acquire(timeoutMillis)
        }.onFailure {
            Log.w(TAG, "cannot acquire alarm wake lock", it)
            AlarmLog.failure("wake_lock_failed", it, "timeoutMs" to timeoutMillis)
        }
    }

    /**
     * Будит экран до того, как система покажет full-screen intent.
     * На части прошивок (Android 14+) всплывающее окно само экран не включает,
     * поэтому будильник звучит, а дисплей остаётся чёрным.
     * На Android 14+ для ACQUIRE_CAUSES_WAKEUP нужно разрешение «Включение экрана».
     */
    private fun wakeScreen() {
        runCatching {
            val manager = getSystemService(PowerManager::class.java)
            val lock = screenWakeLock ?: run {
                @Suppress("DEPRECATION")
                manager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                    "NaVykhod:screen",
                ).apply { setReferenceCounted(false) }
                    .also { screenWakeLock = it }
            }
            lock.acquire(60_000L)
            AlarmLog.event("screen_wake", "held" to lock.isHeld, "interactive" to {
                getSystemService(PowerManager::class.java)?.isInteractive
            }())
        }.onFailure {
            Log.w(TAG, "cannot turn the screen on", it)
            AlarmLog.failure("screen_wake_failed", it)
        }
    }

    private fun releaseScreenWakeLock() {
        runCatching { if (screenWakeLock?.isHeld == true) screenWakeLock?.release() }
    }

    private fun releaseAudio() {
        speechJob?.cancel()
        speechJob = null
        releasePlayer()
        abandonAudioFocus()
        runCatching { vibrator?.cancel() }
        vibrator = null
        musicDucked = false
    }

    /** Движок TTS закрываем только вместе с сеансом: повторный запуск занимает секунды. */
    private fun shutdownVoice() {
        speechJob?.cancel()
        speechJob = null
        speaker?.shutdown()
        speaker = null
    }

    private fun releaseAll() {
        ringJob?.cancel()
        ringJob = null
        lateJob?.cancel()
        lateJob = null
        releaseAudio()
        releaseScreenWakeLock()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun showRingScreen(remainingMinutes: Int, lateMinutes: Int = 0) {
        wakeScreen()
        ringScreenVisible = true
        val intent = Intent(this, AlarmRingingActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlarmRingingActivity.EXTRA_REMAINING, remainingMinutes)
            putExtra(AlarmRingingActivity.EXTRA_LATE_MINUTES, lateMinutes)
        }
        runCatching { startActivity(intent) }
            .onFailure {
                Log.w(TAG, "cannot open ring screen directly", it)
                AlarmLog.failure("ring_screen_failed", it, "remaining" to remainingMinutes, "late" to lateMinutes)
            }
            .onSuccess {
                AlarmLog.event("ring_screen_opened", "remaining" to remainingMinutes, "late" to lateMinutes)
            }
    }

    private fun closeRingScreen() {
        if (!ringScreenVisible) return
        ringScreenVisible = false
        sendBroadcast(Intent(ACTION_RING_FINISHED).setPackage(packageName))
    }

    private fun startForegroundNow(notification: Notification, ringing: Boolean = false) {
        stopForegroundNow()
        if (ringing) StatusNotifier.cancelStatus(this)
        val id = if (ringing) StatusNotifier.RING_NOTIFICATION_ID else StatusNotifier.NOTIFICATION_ID
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(id, notification)
            }
            foregroundStarted = true
        } catch (error: Exception) {
            Log.e(TAG, "cannot start foreground", error)
            AlarmLog.failure("foreground_start_failed", error, "ringing" to ringing)
        }
    }

    private fun stopForegroundNow() {
        if (!foregroundStarted) return
        foregroundStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val TAG = "AlarmService"
        private const val DEFAULT_WAKE_LOCK_MS = 10 * 60 * 1_000L
        const val ACTION_TRIGGER = "com.gavrovis.navyhod.action.SERVICE_TRIGGER"
        const val ACTION_SILENCE = "com.gavrovis.navyhod.action.SILENCE"
        const val ACTION_STOP_ALL = "com.gavrovis.navyhod.action.STOP_ALL"
        const val ACTION_DISABLE = "com.gavrovis.navyhod.action.DISABLE"
        const val ACTION_LATE = "com.gavrovis.navyhod.action.SERVICE_LATE"
        const val ACTION_DEPARTURE_REMINDER = "com.gavrovis.navyhod.action.SERVICE_DEPARTURE_REMINDER"
        const val ACTION_REMIND_ONLY = "com.gavrovis.navyhod.action.REMIND_ONLY"
        private const val ACTION_REFRESH = "com.gavrovis.navyhod.action.REFRESH_REMINDERS"
        const val ACTION_RING_FINISHED = "com.gavrovis.navyhod.action.RING_FINISHED"
        const val EXTRA_CONFIRMED_STOP = "extra_confirmed_stop"
        private const val EXTRA_TEST = "extra_test"
        private const val SPEECH_DELAY_MS = 1_200L

        @Volatile
        var isRinging: Boolean = false
            private set

        fun start(context: Context, test: Boolean = false, trigger: SchedulePlanner.ScheduledTrigger? = null) {
            val intent = Intent(context, AlarmService::class.java).setAction(ACTION_TRIGGER)
                .putExtra(EXTRA_TEST, test)
            trigger?.let {
                intent.putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, it.atMillis)
                    .putExtra(AlarmScheduler.EXTRA_CYCLE_WAKE, it.cycleWakeMillis)
                    .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, it.cycleLeaveMillis)
                    .putExtra(AlarmScheduler.EXTRA_ALARM_ID, it.alarmId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startDepartureReminder(context: Context, leaveMillis: Long, minutes: Int, atMillis: Long) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
                .setAction(ACTION_DEPARTURE_REMINDER)
                .putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leaveMillis)
                .putExtra(AlarmScheduler.EXTRA_REMINDER_MINUTES, minutes)
                .putExtra(AlarmScheduler.EXTRA_TRIGGER_AT, atMillis))
        }

        fun startLate(context: Context, leaveMillis: Long) {
            ContextCompat.startForegroundService(context, Intent(context, AlarmService::class.java)
                .setAction(ACTION_LATE).putExtra(AlarmScheduler.EXTRA_CYCLE_LEAVE, leaveMillis))
        }

        fun refreshReminders(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_REFRESH))
        }

        fun remindDeparture(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_REMIND_ONLY))
        }

        fun silence(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_SILENCE))
        }

        fun stopAlarm(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP_ALL)
                .putExtra(EXTRA_CONFIRMED_STOP, true))
        }

        fun disable(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_DISABLE))
        }
    }
}
