# Time to Leave

[Русский](README.md) · [**English**](README.en.md) · [Español](README.es.md) ·
[Deutsch](README.de.md) · [日本語](README.ja.md) · [Français](README.fr.md) ·
[Português](README.pt.md) · [Italiano](README.it.md)

An Android alarm clock with two times per alarm: **wake-up** and **leaving
home**. You can create several alarms — each with its own weekdays, times and
settings.

* The first signal plays exactly at wake-up time.
* After that, music plays every **5 or 10 minutes** (configurable) — from
  wake-up until departure.
* N minutes before voice announcements begin (configurable, 5–120), music is
  mixed with system TTS phrases: "25 minutes until departure".
* Departure reminders play at **20, 15, 10 and 5 minutes** before and right at
  departure time: "Time to leave".
* If the departure time is earlier than wake-up, departure is counted on the
  next day (e.g., wake-up 23:00, departure 07:00).
* **"Reminders only"** mode — no music or vibration, only voice departure
  reminders.
* Settings can enable or disable the current time before a phrase — separately
  for alarm signals, departure reminders and late reminders: "It is now
  7 hours 5 minutes".
* Weekdays: every day, weekdays, weekends or any custom set.
* The alarm editor defines preparation time from wake-up to departure
  (40–150 minutes); you can set only the departure time — wake-up is
  calculated automatically.
* Dark theme and **8 interface languages**: English (default), Russian,
  Spanish, German, Japanese, French, Portuguese and Italian. The language
  follows the system and can be picked manually in settings — the menu sits
  between "Appearance" and "Diagnostics".

## Screenshots

| Alarms | Alarm editor | Signal screen |
|:---:|:---:|:---:|
| ![Alarms](docs/screenshots/en-alarms.png) | ![Editor](docs/screenshots/en-editor.png) | ![Signal](docs/screenshots/en-ringing.png) |

| Settings | Language and GitHub | Dark theme |
|:---:|:---:|:---:|
| ![Settings](docs/screenshots/en-settings.png) | ![Settings bottom](docs/screenshots/en-settings-bottom.png) | ![Dark theme](docs/screenshots/en-alarms-dark.png) |

## Notifications and the signal screen

* **12 hours before wake-up** a "Alarm is set" notification appears and stays
  in the shade. It can be swiped away — then it will not return until the next
  cycle. The notification can be disabled completely in settings.
* **While music plays** the notification gains "Snooze", "Remind" and "Stop"
  buttons, and a **full-screen window** with three large buttons in different
  colors (light, amber and dark) is shown so a sleeping person can hit it.
* "Silence" stops the music and vibration, but the alarm keeps working: the
  next signals play on schedule.
* "Stop music, but remind me to leave" always asks **"Are you sure?"**: after
  confirmation music stops and voice departure reminders continue.
* "Stop the signals" always asks **"Are you sure?"** and after confirmation
  turns off music, reminders and the schedule without automatically enabling
  tomorrow. The alarm can be set again manually, even the same day.
* The notification lives until the end of the cycle or a confirmed stop.
* If the alarm is not stopped manually before departure time, after departure
  the voice announces the delay three times every minute with a **3-second
  pause between phrases** ("Late" category in voice settings). At the same
  time a window offers to stop the signals; reminders continue until manually
  disabled.

## How it works

```
AlarmManager (setAlarmClock, a separate PendingIntent per alarm)
        │  fires exactly at signal time, retry +30 seconds
        ▼
AlarmReceiver ── immediately schedules the next signal and starts the service
        │
        ├─ "is set": a separate alarm 12 hours before wake-up
        ├─ departure reminders: separate alarms at 20/15/10/5/0 minutes
        └─ late after restart: one recovery alarm
        ▼
AlarmService (foreground, mediaPlayback)
        ├─ MediaPlayer: system alarm sound (loop)
        ├─ VoiceSpeaker: TTS in the selected language
        ├─ vibration
        ├─ full-screen intent → AlarmRingingActivity (window with buttons)
        └─ notification: "Snooze" / "Remind" / "Stop"
```

* The status notification (ID 1001, channel `alarm_status`) is separate from
  the ringing notification (ID 1002, channel `alarm_ringing`) so the
  full-screen window opens again.
* Every signal has a retry after 30 seconds carrying the original signal time,
  so an already delivered signal never fires twice.
* After the departure signal the foreground service stays active until it is
  stopped: minute reminders run inside it, without separate alarms limited by
  Doze.
* The next signal is computed by the pure `SchedulePlanner` logic (no Android
  dependencies, covered by unit tests).
* `setAlarmClock` is used instead of plain `setExact*`: the system treats such
  an alarm as a real clock alarm — it exits Doze and is not subject to
  foreground service background start restrictions.
* The active cycle and stopped cycle are kept in device-protected
  SharedPreferences; after a reboot, app update or time zone change
  `BootReceiver` re-schedules signals. Settings are available in Direct Boot:
  the alarm is restored before the first PIN entry.
* TTS speaks through `USAGE_ALARM` at the alarm volume; music is ducked while
  a phrase plays. The TTS language follows the app language.
* Audio focus `AUDIOFOCUS_GAIN_TRANSIENT` pauses music/video in other apps
  while the signal plays.
* The signal window is shown over the lock screen (`showWhenLocked`,
  `turnScreenOn`), and the back button is blocked: the choice must be
  deliberate.
* The local `AlarmLog` journal records signal events; in settings it can be
  sent via "Diagnostics" (the file is shared through FileProvider).

## Permissions

On first launch the app walks through missing permissions **once**; already
granted permissions are not asked again.

* **Exact alarms** — without them the signal may be late.
* **Notifications** — the "alarm is set" notification and signal buttons.
* **Full-screen signal window** (Android 14+) — so the window with buttons
  appears over the lock screen.
* **Screen turn-on** (Android 14+) — without it the signal window may not wake
  the display: music plays while the screen stays black. The screen is also
  woken through `WakeLock`.
* **Do Not Disturb access** — to warn when the sound policy may mute the
  signal.
* **"Alarm signal" channel** — if the user muted it, the app offers to fix it.
* **Ignoring battery optimization** — on Xiaomi, realme/ColorOS, Huawei and
  similar firmware without this, signals may be delayed or the process killed.

The sound comes from system settings: "Settings → Sound → Alarm".
If no alarm sound is set, the ringtone/notification sound is used, and as a
last resort a built-in tone.

## Build

Requires JDK 17+ and Android SDK (compileSdk 36, build-tools 36).
Project: AGP 8.9.1, Gradle 8.11.1, Kotlin 2.0.20, minSdk 26.

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Install:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests (schedule unit tests and Robolectric UI/service tests):

```bash
gradlew.bat testDebugUnitTest
```

## Release build and Google Play

Application package: `com.gavrovis.navyhod`.

Signing is read from a local `keystore.properties` in the project root
(the file and the `*.jks` key are gitignored and never reach the repository):

```properties
storeFile=navyhod-upload.jks
storePassword=…
keyAlias=navyhod
keyPassword=…
```

Building the signed App Bundle for Play:

```bash
gradlew.bat bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Before publishing in Play Console you need declarations for restricted
permissions (exact alarms, full-screen intent, battery optimization exemption,
`mediaPlayback` foreground service type), a privacy policy and a filled
Data safety form. The app needs no network: no data is collected or sent
anywhere, logs are shared by the user manually.

## Structure

```
app/src/main/java/com/gavrovis/navyhod/
├── AlarmItem.kt            alarm: times, days, preparation mode
├── AlarmSettings.kt        shared settings (data class)
├── SettingsStore.kt        SharedPreferences in device-protected storage
├── SchedulePlanner.kt      cycle, signal and late-time math (pure logic)
├── SpeechText.kt           TTS phrases in 8 languages
├── LocaleHelper.kt         automatic language selection and context wrapper
├── StatusNotifier.kt       notification channels and states (ID 1001/1002)
├── AlarmScheduler.kt       exact alarms, +30 s retries, reminders
├── AlarmReceiver.kt        alarm intake → next signal / service
├── AlarmService.kt         music, TTS, vibration, signal window, late alerts
├── VoiceSpeaker.kt         TextToSpeech wrapper
├── AlarmRingingActivity.kt full-screen window over the lock screen
├── BootReceiver.kt         recovery after reboot/time change
├── AlarmLog.kt             local journal for diagnostics
├── MainActivity.kt         entry point, one-time permission walk
├── NaVykhodApp.kt          Application
└── ui/
    ├── AppScreen.kt        tabs, alarm list, editor
    ├── SettingsTab.kt      signals, voice, notifications, theme, language, logs
    ├── ScreenParts.kt      shared widgets and formatting
    ├── RingingScreen.kt    large buttons of the signal window
    └── theme/Theme.kt      warm light/dark theme
```

Resource sets: `values` (English), `values-ru`, `values-es`, `values-de`,
`values-ja`, `values-fr`, `values-pt`, `values-it`; the language list lives
in `res/xml/locales_config.xml`.

## Limitations

* The melody is the system alarm sound; there is no built-in player/playlist.
* Signal volume equals the system alarm volume (if it is zero, it will be
  quiet, just like any alarm clock).
* On devices with aggressive power saving (some OEMs) reliability depends on
  the background work permission.
* If music plays in the morning but the display does not turn on, check the
  "Permissions and warnings" card in settings: the "Full-screen signal window"
  and "Screen turn-on" permissions, and that the "Alarm signal" channel is not
  muted. On Android 14+ these permissions are granted separately and can be
  revoked by the system or the user.
* The current time before a phrase is configured separately for signals,
  departure reminders and late reminders. The voice reminders themselves
  cannot be turned off.
