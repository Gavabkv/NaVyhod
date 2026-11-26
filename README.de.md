# Zeit zum Gehen

[Русский](README.md) · [English](README.en.md) · [Español](README.es.md) ·
[**Deutsch**](README.de.md) · [日本語](README.ja.md) · [Français](README.fr.md) ·
[Português](README.pt.md) · [Italiano](README.it.md)

Wecker für Android mit zwei Zeiten pro Wecker: **Aufstehen** und **Verlassen
des Hauses**. Es können mehrere Wecker angelegt werden — jeder mit eigenen
Wochentagen, Zeiten und Einstellungen.

* Das erste Signal ertönt genau zur Aufwachzeit.
* Danach spielt Musik alle **5 oder 10 Minuten** (einstellbar) — vom Aufstehen
  bis zum Gehen.
* N Minuten vor Beginn der Sprachansagen (einstellbar, 5–120) wird die Musik
  von System-TTS-Sätzen ergänzt: „Noch 25 Minuten bis zum Gehen“.
* Erinnerungen an das Gehen ertönen **20, 15, 10 und 5 Minuten** vorher und
  genau zur Gehzeit: „Zeit zu gehen“.
* Liegt die Gehzeit vor der Aufwachzeit, zählt das Gehen am nächsten Tag
  (z. B. Aufstehen 23:00, Gehen 07:00).
* Modus **„nur Erinnerungen“** — ohne Musik und Vibration, nur
  Spracherinnerungen an das Gehen.
* In den Einstellungen lässt sich die aktuelle Uhrzeit vor dem Satz ein- oder
  ausschalten — getrennt für Weckersignale, Geh- und Verspätungserinnerungen:
  „Es ist jetzt 7 Uhr 5 Minuten“.
* Wochentage: jeden Tag, werktags, Wochenende oder eine beliebige Auswahl.
* Im Wecker-Editor wird die Vorbereitungszeit vom Aufstehen bis zum Gehen
  festgelegt (40–150 Minuten); man kann auch nur die Gehzeit angeben — das
  Aufstehen wird berechnet.
* Dunkles Design und **8 Oberflächensprachen**: Englisch (Standard),
  Russisch, Spanisch, Deutsch, Japanisch, Französisch, Portugiesisch und
  Italienisch. Die Sprache folgt dem System und kann in den Einstellungen
  manuell gewählt werden — das Menü liegt zwischen „Darstellung“ und
  „Diagnose“.

## Screenshots

| Wecker | Wecker-Editor | Signalfenster |
|:---:|:---:|:---:|
| ![Wecker](docs/screenshots/en-alarms.png) | ![Editor](docs/screenshots/en-editor.png) | ![Signal](docs/screenshots/en-ringing.png) |

| Einstellungen | Sprache und GitHub | Dunkles Design |
|:---:|:---:|:---:|
| ![Einstellungen](docs/screenshots/en-settings.png) | ![Ende der Einstellungen](docs/screenshots/en-settings-bottom.png) | ![Dunkles Design](docs/screenshots/en-alarms-dark.png) |

## Benachrichtigungen und Signalfenster

* **12 Stunden vor dem Aufstehen** erscheint die Benachrichtigung „Wecker
  gestellt“ und bleibt in der Leiste. Sie kann weggewischt werden — dann
  kommt sie bis zum nächsten Zyklus nicht wieder. Die Benachrichtigung lässt
  sich in den Einstellungen komplett abschalten.
* **Während der Musik** bekommt die Benachrichtigung die Schaltflächen
  „Schlummern“, „Erinnern“ und „Stoppen“, und auf dem Bildschirm erscheint ein
  **Vollbildfenster** mit drei großen, verschiedenfarbigen Schaltflächen (hell,
  bernsteinfarben und dunkel), damit auch ein schlafender Mensch sie trifft.
* „Stille“ stoppt Musik und Vibration, der Wecker läuft aber weiter: die
  nächsten Signale ertönen planmäßig.
* „Musik stoppen, aber ans Gehen erinnern“ fragt immer **„Sind Sie sicher?“**:
  nach der Bestätigung verstummt die Musik, die Spracherinnerungen an das
  Gehen bleiben.
* „Signale stoppen“ fragt immer **„Sind Sie sicher?“** und schaltet nach der
  Bestätigung Musik, Erinnerungen und den Zeitplan ab, ohne morgen
  automatisch wieder einzuschalten. Der Wecker kann manuell neu gestellt
  werden, auch am selben Tag.
* Die Benachrichtigung bleibt bis zum Ende des Zyklus oder bis zum bestätigten
  Ausschalten.
* Wird der Wecker vor der Gehzeit nicht manuell ausgeschaltet, meldet die
  Stimme nach dem Gehen jede Minute dreimal die Verspätung mit **3 Sekunden
  Pause zwischen den Sätzen** (Kategorie „Verspätung“ in den
  Spracheinstellungen). Gleichzeitig erscheint ein Fenster, das anbietet, die
  Signale zu stoppen; die Erinnerungen laufen bis zum manuellen Ausschalten.

## So funktioniert es

```
AlarmManager (setAlarmClock, eigener PendingIntent pro Wecker)
        │  feuert genau zur Signalzeit, Wiederholung +30 Sekunden
        ▼
AlarmReceiver ── plant sofort das nächste Signal und startet den Dienst
        │
        ├─ „gestellt“: separater Alarm 12 Stunden vor dem Aufstehen
        ├─ Geh-Erinnerungen: separate Alarme zu 20/15/10/5/0 Minuten
        └─ Verspätung nach Neustart: ein Wiederherstellungsalarm
        ▼
AlarmService (Foreground, mediaPlayback)
        ├─ MediaPlayer: System-Weckerton (Loop)
        ├─ VoiceSpeaker: TTS in der gewählten Sprache
        ├─ Vibration
        ├─ Full-Screen-Intent → AlarmRingingActivity (Fenster mit Schaltflächen)
        └─ Benachrichtigung: „Schlummern“ / „Erinnern“ / „Stoppen“
```

* Die Statusbenachrichtigung (ID 1001, Kanal `alarm_status`) ist von der
  Signalbenachrichtigung (ID 1002, Kanal `alarm_ringing`) getrennt, damit das
  Vollbildfenster erneut geöffnet wird.
* Jedes Signal hat eine Wiederholung nach 30 Sekunden, die die ursprüngliche
  Signalzeit mitführt — ein bereits gelieferte Signal feuert nie doppelt.
* Nach dem Geh-Signal bleibt der Foreground-Dienst aktiv, bis er ausgeschaltet
  wird: minutliche Erinnerungen laufen darin, ohne separate, von Doze
  begrenzte Alarme.
* Das nächste Signal berechnet die reine `SchedulePlanner`-Logik (ohne
  Android-Abhängigkeiten, durch Unit-Tests abgedeckt).
* Statt `setExact*` wird `setAlarmClock` verwendet: das System behandelt einen
  solchen Alarm als echten Wecker — er verlässt Doze und unterliegt nicht den
  Hintergrundstart-Einschränkungen für Foreground-Dienste.
* Aktiver und gestoppter Zyklus liegen in device-protected
  SharedPreferences; nach Neustart, App-Update oder Zeitzonenwechsel stellt
  `BootReceiver` die Signale neu. Die Einstellungen sind im Direct Boot
  verfügbar: der Wecker wird vor der ersten PIN-Eingabe wiederhergestellt.
* TTS spricht über `USAGE_ALARM` in Weckerlautstärke; während des Satzes wird
  die Musik abgesenkt. Die TTS-Sprache folgt der App-Sprache.
* Der Audiofokus `AUDIOFOCUS_GAIN_TRANSIENT` pausiert Musik/Video anderer Apps,
  solange das Signal läuft.
* Das Signalfenster erscheint über dem Sperrbildschirm (`showWhenLocked`,
  `turnScreenOn`), die Zurück-Taste ist gesperrt: die Wahl muss bewusst
  erfolgen.
* Das lokale `AlarmLog` schreibt Signalereignisse; in den Einstellungen lässt
  es sich über „Diagnose“ versenden (die Datei wird über FileProvider
  geteilt).

## Berechtigungen

Beim ersten Start prüft die App **einmal** die fehlenden Berechtigungen;
bereits erteilte werden nicht erneut abgefragt.

* **Exakte Wecker** — ohne sie kann das Signal zu spät kommen.
* **Benachrichtigungen** — die „Wecker gestellt“-Benachrichtigung und die
  Signal-Schaltflächen.
* **Vollbild-Signalfenster** (Android 14+) — damit das Fenster mit
  Schaltflächen über dem Sperrbildschirm erscheint.
* **Bildschirm aktivieren** (Android 14+) — ohne dieses kann das Signalfenster
  das Display nicht wecken: Musik läuft, der Bildschirm bleibt schwarz.
  Zusätzlich wird der Bildschirm per `WakeLock` geweckt.
* **Zugriff auf „Nicht stören“** — um zu warnen, wenn eine Tonrichtlinie das
  Signal stummschalten könnte.
* **Kanal „Weckersignal“** — hat der Nutzer ihn stummgeschaltet, schlägt die
  App die Korrektur vor.
* **Akku-Optimierung ignorieren** — auf Xiaomi-, realme/ColorOS-, Huawei- und
  ähnlicher Firmware können Signale sonst verzögert werden oder der Prozess
  wird beendet.

Der Ton stammt aus den Systemeinstellungen: „Einstellungen → Ton → Wecker“.
Ist kein Weckerton gesetzt, wird der Klingel-/Benachrichtigungston verwendet,
im äußersten Fall ein eingebauter Ton.

## Bauen

Benötigt JDK 17+ und Android SDK (compileSdk 36, build-tools 36).
Projekt: AGP 8.9.1, Gradle 8.11.1, Kotlin 2.0.20, minSdk 26.

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Installation:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests (Unit-Tests des Zeitplans und Robolectric-Tests für UI/Dienst):

```bash
gradlew.bat testDebugUnitTest
```

## Release-Build und Google Play

Anwendungspaket: `com.gavrovis.navyhod`.

Die Signatur wird aus der lokalen `keystore.properties` im Projektstamm
gelesen (Datei und `*.jks`-Schlüssel sind in `.gitignore` und gelangen nicht
ins Repository):

```properties
storeFile=navyhod-upload.jks
storePassword=…
keyAlias=navyhod
keyPassword=…
```

Build des signierten App Bundles für Play:

```bash
gradlew.bat bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Vor der Veröffentlichung in der Play Console braucht es Erklärungen für
eingeschränkte Berechtigungen (exakte Wecker, Full-Screen-Intent,
Akku-Optimierung, Foreground-Diensttyp `mediaPlayback`), eine
Datenschutzerklärung und ein ausgefülltes Data-safety-Formular. Netz braucht
die App nicht: es werden keine Daten gesammelt oder gesendet, Logs teilt der
Nutzer manuell.

## Struktur

```
app/src/main/java/com/gavrovis/navyhod/
├── AlarmItem.kt            Wecker: Zeiten, Tage, Vorbereitungsmodus
├── AlarmSettings.kt        gemeinsame Einstellungen (data class)
├── SettingsStore.kt        SharedPreferences im device-protected Speicher
├── SchedulePlanner.kt      Zyklus-, Signal- und Verspätungslogik (rein)
├── SpeechText.kt           TTS-Sätze in 8 Sprachen
├── LocaleHelper.kt         automatische Sprachwahl und Context-Wrapper
├── StatusNotifier.kt       Benachrichtigungskanäle und -zustände (ID 1001/1002)
├── AlarmScheduler.kt       exakte Alarme, +30-s-Wiederholungen, Erinnerungen
├── AlarmReceiver.kt        Alarmannahme → nächstes Signal / Dienst
├── AlarmService.kt         Musik, TTS, Vibration, Signalfenster, Verspätung
├── VoiceSpeaker.kt         TextToSpeech-Wrapper
├── AlarmRingingActivity.kt Vollbildfenster über dem Sperrbildschirm
├── BootReceiver.kt         Wiederherstellung nach Neustart/Zeitwechsel
├── AlarmLog.kt             lokales Journal für die Diagnose
├── MainActivity.kt         Einstiegspunkt, einmaliger Berechtigungsdurchlauf
├── NaVykhodApp.kt          Application
└── ui/
    ├── AppScreen.kt        Tabs, Weckerliste, Editor
    ├── SettingsTab.kt      Signale, Stimme, Benachrichtigungen, Design, Sprache, Logs
    ├── ScreenParts.kt      gemeinsame Elemente und Formatierung
    ├── RingingScreen.kt    große Schaltflächen des Signalfensters
    └── theme/Theme.kt      warmes helles/dunkles Design
```

Ressourcensätze: `values` (Englisch), `values-ru`, `values-es`, `values-de`,
`values-ja`, `values-fr`, `values-pt`, `values-it`; die Sprachliste liegt in
`res/xml/locales_config.xml`.

## Einschränkungen

* Die Melodie ist der System-Weckerton; einen eigenen Player/Playlist gibt es
  nicht.
* Die Signallautstärke entspricht der System-Weckerlautstärke (bei Null ist es
  leise, wie bei jedem Wecker).
* Auf Geräten mit aggressiver Energiesparfunktion (manche OEMs) hängt die
  Zuverlässigkeit von der Berechtigung für Hintergrundarbeit ab.
* Spielt morgens Musik, aber das Display geht nicht an, prüfe die Karte
  „Berechtigungen und Warnungen“ in den Einstellungen: die Berechtigungen
  „Vollbild-Signalfenster“ und „Bildschirm aktivieren“ sowie dass der Kanal
  „Weckersignal“ nicht stumm ist. Unter Android 14+ werden diese
  Berechtigungen separat erteilt und können vom System oder Nutzer entzogen
  werden.
* Die aktuelle Uhrzeit vor dem Satz wird getrennt für Signale, Geh- und
  Verspätungserinnerungen eingestellt. Die Spracherinnerungen selbst lassen
  sich nicht abschalten.

## Lizenz

Veröffentlicht unter der MIT-Lizenz — siehe [LICENSE](LICENSE).
