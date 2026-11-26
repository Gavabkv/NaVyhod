# L'heure de partir

[Русский](README.md) · [English](README.en.md) · [Español](README.es.md) ·
[Deutsch](README.de.md) · [日本語](README.ja.md) · [**Français**](README.fr.md) ·
[Português](README.pt.md) · [Italiano](README.it.md)

Réveil pour Android avec deux heures par alarme : **réveil** et **sortie de
la maison**. Plusieurs alarmes sont possibles, chacune avec ses propres jours
de la semaine, heures et réglages.

* Le premier signal sonne exactement à l'heure du réveil.
* Ensuite, la musique joue toutes les **5 ou 10 minutes** (réglable), du
  réveil jusqu'à la sortie.
* N minutes avant le début des annonces vocales (réglable, 5–120), la musique
  est complétée par des phrases TTS du système : « Il reste 25 minutes avant
  la sortie ».
* Les rappels de sortie sonnent à **20, 15, 10 et 5 minutes** et juste à
  l'heure de sortie : « C'est l'heure de partir ».
* Si l'heure de sortie est antérieure à celle du réveil, la sortie compte le
  lendemain (par exemple, réveil 23:00, sortie 07:00).
* Mode **« rappels uniquement »** — sans musique ni vibration, seulement les
  rappels vocaux de sortie.
* Dans les réglages, on peut activer ou désactiver l'heure actuelle avant la
  phrase — séparément pour les signaux d'alarme, les rappels de sortie et de
  retard : « Il est maintenant 7 heures 5 minutes ».
* Jours de la semaine : chaque jour, jours ouvrés, week-end ou toute
  combinaison libre.
* L'éditeur d'alarme définit le temps de préparation du réveil à la sortie
  (40–150 minutes) ; on peut aussi ne définir que l'heure de sortie, le réveil
  est alors calculé.
* Thème sombre et **8 langues d'interface** : anglais (par défaut), russe,
  espagnol, allemand, japonais, français, portugais et italien. La langue
  suit le système, elle peut être choisie manuellement dans les réglages ;
  le menu se trouve entre « Apparence » et « Diagnostics ».

## Captures d'écran

| Alarmes | Éditeur d'alarme | Écran du signal |
|:---:|:---:|:---:|
| ![Alarmes](docs/screenshots/en-alarms.png) | ![Éditeur](docs/screenshots/en-editor.png) | ![Signal](docs/screenshots/en-ringing.png) |

| Réglages | Langue et GitHub | Thème sombre |
|:---:|:---:|:---:|
| ![Réglages](docs/screenshots/en-settings.png) | ![Bas des réglages](docs/screenshots/en-settings-bottom.png) | ![Thème sombre](docs/screenshots/en-alarms-dark.png) |

## Notifications et fenêtre du signal

* **12 heures avant le réveil**, la notification « Alarme programmée »
  apparaît et reste dans le panneau. On peut la balayer pour la retirer ;
  elle ne reviendra qu'au cycle suivant. La notification peut être
  complètement désactivée dans les réglages.
* **Pendant la musique**, la notification gagne les boutons « Répéter plus
  tard », « Rappeler » et « Arrêter », et une **fenêtre plein écran** avec
  trois grands boutons de couleurs différentes (claire, ambre et sombre)
  s'affiche, pour qu'une personne endormie puisse les atteindre.
* « Silence » arrête la musique et les vibrations, mais l'alarme continue :
  les signaux suivants sonnent selon le planning.
* « Arrêter la musique, mais rappeler la sortie » demande toujours
  **« Êtes-vous sûr ? »** : après confirmation, la musique s'arrête et les
  rappels vocaux de sortie restent.
* « Arrêter les signaux » demande toujours **« Êtes-vous sûr ? »** et, après
  confirmation, coupe la musique, les rappels et le planning sans réactiver
  automatiquement demain. L'alarme peut être reprogrammée à la main, même le
  jour même.
* La notification vit jusqu'à la fin du cycle ou jusqu'à un arrêt confirmé.
* Si l'alarme n'est pas arrêtée à la main avant l'heure de sortie, après la
  sortie la voix annonce le retard trois fois chaque minute avec une **pause
  de 3 secondes entre les phrases** (catégorie « Retard » dans les réglages
  vocaux). En même temps, une fenêtre propose d'arrêter les signaux ; les
  rappels continuent jusqu'à l'arrêt manuel.

## Fonctionnement

```
AlarmManager (setAlarmClock, un PendingIntent distinct par alarme)
        │  déclenche exactement à l'heure du signal, reprise +30 secondes
        ▼
AlarmReceiver ── programme aussitôt le signal suivant et démarre le service
        │
        ├─ « programmée » : alarme séparée 12 heures avant le réveil
        ├─ rappels de sortie : alarmes séparées à 20/15/10/5/0 minutes
        └─ retard après redémarrage : une alarme de récupération
        ▼
AlarmService (foreground, mediaPlayback)
        ├─ MediaPlayer : sonnerie d'alarme système (boucle)
        ├─ VoiceSpeaker : TTS dans la langue choisie
        ├─ vibration
        ├─ full-screen intent → AlarmRingingActivity (fenêtre avec boutons)
        └─ notification : « Répéter » / « Rappeler » / « Arrêter »
```

* La notification d'état (ID 1001, canal `alarm_status`) est séparée de la
  notification du signal (ID 1002, canal `alarm_ringing`) pour que la fenêtre
  plein écran s'ouvre de nouveau.
* Chaque signal a une reprise après 30 secondes qui porte l'heure d'origine
  du signal, ainsi un signal déjà délivré ne sonne jamais deux fois.
* Après le signal de sortie, le service foreground reste actif jusqu'à
  l'arrêt : les rappels à la minute tournent dedans, sans alarmes séparées
  limitées par Doze.
* Le signal suivant est calculé par la logique pure `SchedulePlanner` (sans
  dépendances Android, couverte par des tests unitaires).
* `setAlarmClock` est utilisé au lieu de `setExact*` : le système traite cette
  alarme comme un vrai réveil — elle sort de Doze et n'est pas soumise aux
  restrictions de démarrage en arrière-plan des services foreground.
* Le cycle actif et le cycle arrêté sont stockés dans des SharedPreferences
  protégées par l'appareil ; après redémarrage, mise à jour ou changement de
  fuseau horaire, `BootReceiver` reprogramme les signaux. Les réglages sont
  disponibles en Direct Boot : l'alarme est restaurée avant le premier PIN.
* Le TTS parle via `USAGE_ALARM` au volume de l'alarme ; la musique est
  atténuée pendant la phrase. La langue du TTS suit celle de l'app.
* Le focus audio `AUDIOFOCUS_GAIN_TRANSIENT` met en pause la musique/vidéo des
  autres applis pendant le signal.
* La fenêtre du signal s'affiche par-dessus le verrouillage (`showWhenLocked`,
  `turnScreenOn`), et le bouton retour est bloqué : le choix doit être
  conscient.
* Le journal local `AlarmLog` enregistre les événements des signaux ; dans les
  réglages, il peut être envoyé via « Diagnostics » (le fichier est partagé
  par FileProvider).

## Autorisations

Au premier lancement, l'app parcourt **une fois** les autorisations
manquantes ; celles déjà accordées ne sont pas redemandées.

* **Alarmes exactes** — sans elles, le signal peut être en retard.
* **Notifications** — la notification « alarme programmée » et les boutons du
  signal.
* **Fenêtre contextuelle du signal** (Android 14+) — pour que la fenêtre avec
  boutons apparaisse par-dessus le verrouillage.
* **Allumage de l'écran** (Android 14+) — sans lui, la fenêtre du signal peut
  ne pas réveiller l'écran : la musique joue et l'écran reste noir. L'écran
  est aussi réveillé via `WakeLock`.
* **Accès à « Ne pas déranger »** — pour prévenir si la politique sonore peut
  couper le signal.
* **Canal « Signal d'alarme »** — si l'utilisateur l'a coupé, l'app propose de
  corriger.
* **Ignorer l'optimisation de la batterie** — sur les firmwares Xiaomi,
  realme/ColorOS, Huawei, etc., sans cela les signaux peuvent être retardés ou
  le processus tué.

Le son vient des réglages système : « Réglages → Son → Alarme ».
Si aucune sonnerie d'alarme n'est définie, la sonnerie de
notification/appel est utilisée, et en dernier recours un ton intégré.

## Compilation

Nécessite JDK 17+ et le SDK Android (compileSdk 36, build-tools 36).
Projet : AGP 8.9.1, Gradle 8.11.1, Kotlin 2.0.20, minSdk 26.

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`

Installation :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Tests (unitaires du planning et Robolectric pour l'UI/le service) :

```bash
gradlew.bat testDebugUnitTest
```

## Build de release et Google Play

Paquet de l'application : `com.gavrovis.navyhod`.

La signature est lue depuis le `keystore.properties` local à la racine du
projet (le fichier et la clé `*.jks` sont dans `.gitignore` et n'entrent pas
dans le dépôt) :

```properties
storeFile=navyhod-upload.jks
storePassword=…
keyAlias=navyhod
keyPassword=…
```

Build de l'App Bundle signé pour Play :

```bash
gradlew.bat bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Avant de publier dans Play Console, il faut des déclarations pour les
autorisations restreintes (alarmes exactes, full-screen intent, exemption
d'optimisation de batterie, type de service foreground `mediaPlayback`), une
politique de confidentialité et le formulaire Data safety rempli. L'app n'a
pas besoin de réseau : aucune donnée n'est collectée ni envoyée, les journaux
sont partagés manuellement par l'utilisateur.

## Structure

```
app/src/main/java/com/gavrovis/navyhod/
├── AlarmItem.kt            alarme : heures, jours, mode de préparation
├── AlarmSettings.kt        réglages communs (data class)
├── SettingsStore.kt        SharedPreferences en stockage protégé par l'appareil
├── SchedulePlanner.kt      calcul du cycle, des signaux, des retards (logique pure)
├── SpeechText.kt           phrases TTS en 8 langues
├── LocaleHelper.kt         sélection automatique de la langue et wrapper de contexte
├── StatusNotifier.kt       canaux et états de notification (ID 1001/1002)
├── AlarmScheduler.kt       alarmes exactes, reprises +30 s, rappels
├── AlarmReceiver.kt        réception des alarmes → signal suivant / service
├── AlarmService.kt         musique, TTS, vibration, fenêtre du signal, retard
├── VoiceSpeaker.kt         wrapper TextToSpeech
├── AlarmRingingActivity.kt fenêtre plein écran par-dessus le verrouillage
├── BootReceiver.kt         récupération après redémarrage/changement d'heure
├── AlarmLog.kt             journal local pour les diagnostics
├── MainActivity.kt         point d'entrée, parcours unique des autorisations
├── NaVykhodApp.kt          Application
└── ui/
    ├── AppScreen.kt        onglets, liste des alarmes, éditeur
    ├── SettingsTab.kt      signaux, voix, notifications, thème, langue, logs
    ├── ScreenParts.kt      éléments communs et formatage
    ├── RingingScreen.kt    grands boutons de la fenêtre du signal
    └── theme/Theme.kt      thème clair/sombre chaleureux
```

Jeux de ressources : `values` (anglais), `values-ru`, `values-es`,
`values-de`, `values-ja`, `values-fr`, `values-pt`, `values-it` ; la liste des
langues se trouve dans `res/xml/locales_config.xml`.

## Limites

* La mélodie est la sonnerie d'alarme système ; pas de lecteur ni de
  playlist intégrés.
* Le volume du signal est celui de l'alarme système (à zéro, ce sera
  silencieux, comme pour tout réveil).
* Sur les appareils à économie d'énergie agressive (certains OEM), la
  fiabilité dépend de l'autorisation de travail en arrière-plan.
* Si la musique joue le matin mais que l'écran ne s'allume pas, vérifiez la
  carte « Autorisations et avertissements » dans les réglages : les
  autorisations « Fenêtre contextuelle du signal » et « Allumage de l'écran »,
  et que le canal « Signal d'alarme » n'est pas coupé. Sur Android 14+, ces
  autorisations sont accordées séparément et peuvent être révoquées par le
  système ou l'utilisateur.
* L'heure actuelle avant la phrase se configure séparément pour les signaux,
  les rappels de sortie et de retard. Les rappels vocaux eux-mêmes ne peuvent
  pas être désactivés.

## Licence

Distribué sous licence MIT — voir [LICENSE](LICENSE).
