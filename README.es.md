# Time to Leave

[Русский](README.md) · [English](README.en.md) · [**Español**](README.es.md) ·
[Deutsch](README.de.md) · [日本語](README.ja.md) · [Français](README.fr.md) ·
[Português](README.pt.md) · [Italiano](README.it.md)

Despertador para Android con dos horas por alarma: **despertar** y **salir de
casa**. Puedes crear varias alarmas; cada una tiene sus propios días de la
semana, horas y ajustes.

* La primera señal suena exactamente a la hora de despertar.
* Después la música suena cada **5 o 10 minutos** (configurable), desde el
  despertar hasta la salida.
* N minutos antes de que empiecen los anuncios de voz (configurable, 5–120),
  la música se acompaña de frases TTS del sistema: «Quedan 25 minutos para
  salir».
* Los recordatorios de salida suenan a **20, 15, 10 y 5 minutos** y justo a la
  hora de salida: «Es hora de salir».
* Si la hora de salida es anterior a la de despertar, la salida se cuenta al
  día siguiente (por ejemplo, despertar 23:00, salir 07:00).
* Modo **«solo recordatorios»**: sin música ni vibración, solo recordatorios
  de voz sobre la salida.
* En los ajustes se puede activar o desactivar la hora actual antes de la
  frase, por separado para señales de alarma, recordatorios de salida y de
  retraso: «Son las 7 horas 5 minutos».
* Días de la semana: todos los días, laborables, fines de semana o cualquier
  conjunto.
* En el editor de la alarma se define el tiempo de preparación desde el
  despertar hasta la salida (40–150 minutos); también se puede indicar solo la
  hora de salida, y el despertar se calcula.
* Tema oscuro y **8 idiomas de interfaz**: inglés (por defecto), ruso,
  español, alemán, japonés, francés, portugués e italiano. El idioma se toma
  del sistema y se puede elegir manualmente en los ajustes; el menú está entre
  «Apariencia» y «Diagnóstico».

## Capturas de pantalla

| Alarmas | Editor de alarma | Pantalla de señal |
|:---:|:---:|:---:|
| ![Alarmas](docs/screenshots/en-alarms.png) | ![Editor](docs/screenshots/en-editor.png) | ![Señal](docs/screenshots/en-ringing.png) |

| Ajustes | Idioma y GitHub | Tema oscuro |
|:---:|:---:|:---:|
| ![Ajustes](docs/screenshots/en-settings.png) | ![Final de ajustes](docs/screenshots/en-settings-bottom.png) | ![Tema oscuro](docs/screenshots/en-alarms-dark.png) |

## Notificaciones y ventana de señal

* **12 horas antes de despertar** aparece la notificación «Alarma activada» y
  se queda en el panel. Se puede deslizar para descartarla; entonces no
  volverá hasta el siguiente ciclo. La notificación se puede desactivar por
  completo en los ajustes.
* **Mientras suena la música**, la notificación añade los botones «Posponer»,
  «Recordar» y «Detener», y en pantalla aparece una **ventana a pantalla
  completa** con tres botones grandes de distintos colores (claro, ámbar y
  oscuro) para que una persona dormida pueda pulsarlos.
* «Silencio» detiene la música y la vibración, pero la alarma sigue
  funcionando: las siguientes señales sonarán según el horario.
* «Detener la música, pero recordarme salir» siempre pregunta **«¿Estás
  seguro?»**: tras confirmar, la música se apaga y los recordatorios de voz
  sobre la salida continúan.
* «Detener las señales» siempre pregunta **«¿Estás seguro?»** y, tras
  confirmar, apaga la música, los recordatorios y la programación sin activar
  automáticamente mañana. La alarma se puede volver a programar a mano, incluso
  el mismo día.
* La notificación permanece hasta el final del ciclo o hasta una parada
  confirmada.
* Si la alarma no se detiene a mano antes de la hora de salida, después de la
  salida la voz anuncia el retraso tres veces cada minuto con una **pausa de
  3 segundos entre frases** (categoría «Retraso» en los ajustes de voz). Al
  mismo tiempo aparece una ventana que ofrece detener las señales; los
  recordatorios continúan hasta desactivarlos a mano.

## Cómo funciona

```
AlarmManager (setAlarmClock, un PendingIntent distinto por alarma)
        │  se dispara exactamente a la hora de la señal, reintento +30 segundos
        ▼
AlarmReceiver ── de inmediato programa la siguiente señal y arranca el servicio
        │
        ├─ «activada»: alarma separada 12 horas antes de despertar
        ├─ recordatorios de salida: alarmas separadas a 20/15/10/5/0 minutos
        └─ retraso tras reinicio: una alarma de recuperación
        ▼
AlarmService (foreground, mediaPlayback)
        ├─ MediaPlayer: sonido de alarma del sistema (loop)
        ├─ VoiceSpeaker: TTS en el idioma seleccionado
        ├─ vibración
        ├─ full-screen intent → AlarmRingingActivity (ventana con botones)
        └─ notificación: «Posponer» / «Recordar» / «Detener»
```

* La notificación de estado (ID 1001, canal `alarm_status`) está separada de
  la notificación de la señal (ID 1002, canal `alarm_ringing`) para que la
  ventana a pantalla completa se abra de nuevo.
* Cada señal tiene un reintento a los 30 segundos que lleva la hora original
  de la señal, así que una señal ya entregada no suena dos veces.
* Tras la señal de salida el servicio foreground sigue activo hasta
  desactivarlo: los recordatorios por minuto van dentro de él, sin alarmas
  separadas limitadas por Doze.
* La siguiente señal la calcula la lógica pura `SchedulePlanner` (sin
  dependencias de Android, cubierta por pruebas unitarias).
* Se usa `setAlarmClock` en lugar de `setExact*`: el sistema trata esa alarma
  como un despertador real; sale de Doze y no cae bajo las restricciones de
  inicio en segundo plano de servicios foreground.
* El ciclo activo y el ciclo detenido se guardan en SharedPreferences con
  protección del dispositivo; tras reiniciar, actualizar la app o cambiar de
  zona horaria, `BootReceiver` vuelve a programar las señales. Los ajustes
  están disponibles en Direct Boot: la alarma se restaura antes del primer PIN.
* El TTS habla con `USAGE_ALARM`, al mismo volumen que la alarma; durante la
  frase la música baja. El idioma del TTS sigue al idioma de la app.
* El enfoque de audio `AUDIOFOCUS_GAIN_TRANSIENT` pausa la música/vídeo de
  otras apps mientras suena la señal.
* La ventana de señal se muestra sobre el bloqueo (`showWhenLocked`,
  `turnScreenOn`) y el botón «atrás» está bloqueado: la elección debe ser
  consciente.
* El registro local `AlarmLog` apunta los eventos de las señales; en los
  ajustes se puede enviar desde «Diagnóstico» (el archivo se comparte con
  FileProvider).

## Permisos

En el primer arranque la app repasa **una vez** los permisos que faltan; los ya
concedidos no se vuelven a pedir.

* **Alarmas exactas** — sin ellas la señal puede llegar tarde.
* **Notificaciones** — la notificación «alarma activada» y los botones de la
  señal.
* **Ventana emergente de la señal** (Android 14+) — para que la ventana con
  botones aparezca sobre el bloqueo.
* **Encendido de pantalla** (Android 14+) — sin él la ventana de señal puede
  no despertar la pantalla: la música suena y la pantalla queda negra. Además
  la pantalla se enciende con `WakeLock`.
* **Acceso a No molestar** — para avisar si la política de sonido puede
  silenciar la señal.
* **Canal «Señal de alarma»** — si el usuario lo silenció, la app ofrecerá
  corregirlo.
* **Ignorar la optimización de batería** — en firmware Xiaomi,
  realme/ColorOS, Huawei y similares, sin esto las señales pueden retrasarse
  o el proceso puede morir.

El sonido se toma de los ajustes del sistema: «Ajustes → Sonido → Alarma».
Si no hay melodía de alarma, se usa el tono de llamada/notificación y, en
último caso, un tono integrado.

## Compilación

Hace falta JDK 17+ y Android SDK (compileSdk 36, build-tools 36).
Proyecto: AGP 8.9.1, Gradle 8.11.1, Kotlin 2.0.20, minSdk 26.

```bash
# Windows
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Instalación:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Pruebas (unitarias del horario y Robolectric de UI/servicio):

```bash
gradlew.bat testDebugUnitTest
```

## Compilación de release y Google Play

Paquete de la aplicación: `com.gavrovis.navyhod`.

La firma se toma del `keystore.properties` local en la raíz del proyecto
(el archivo y la clave `*.jks` están en `.gitignore` y no llegan al
repositorio):

```properties
storeFile=navyhod-upload.jks
storePassword=…
keyAlias=navyhod
keyPassword=…
```

Compilación del App Bundle firmado para Play:

```bash
gradlew.bat bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Antes de publicar en Play Console hacen falta declaraciones para permisos
restringidos (alarmas exactas, full-screen intent, exención de optimización de
batería, tipo de servicio foreground `mediaPlayback`), política de privacidad
y el formulario Data safety rellenado. La app no necesita red: no recoge datos
ni los envía a ninguna parte, los registros los comparte el usuario a mano.

## Estructura

```
app/src/main/java/com/gavrovis/navyhod/
├── AlarmItem.kt            alarma: horas, días, modo de preparación
├── AlarmSettings.kt        ajustes comunes (data class)
├── SettingsStore.kt        SharedPreferences con protección del dispositivo
├── SchedulePlanner.kt      cálculo de ciclo, señales y retrasos (lógica pura)
├── SpeechText.kt           frases TTS en 8 idiomas
├── LocaleHelper.kt         selección automática de idioma y envoltorio
├── StatusNotifier.kt       canales y estados de notificaciones (ID 1001/1002)
├── AlarmScheduler.kt       alarmas exactas, reintentos +30 s, recordatorios
├── AlarmReceiver.kt        recepción de alarmas → siguiente señal / servicio
├── AlarmService.kt         música, TTS, vibración, ventana de señal, retraso
├── VoiceSpeaker.kt         envoltorio de TextToSpeech
├── AlarmRingingActivity.kt ventana a pantalla completa sobre el bloqueo
├── BootReceiver.kt         recuperación tras reinicio/cambio de hora
├── AlarmLog.kt             registro local para diagnóstico
├── MainActivity.kt         punto de entrada, repaso único de permisos
├── NaVykhodApp.kt          Application
└── ui/
    ├── AppScreen.kt        pestañas, lista de alarmas, editor
    ├── SettingsTab.kt      señales, voz, notificaciones, tema, idioma, logs
    ├── ScreenParts.kt      widgets y formato comunes
    ├── RingingScreen.kt    botones grandes de la ventana de señal
    └── theme/Theme.kt      tema claro/oscuro cálido
```

Conjuntos de recursos: `values` (inglés), `values-ru`, `values-es`,
`values-de`, `values-ja`, `values-fr`, `values-pt`, `values-it`; la lista de
idiomas está en `res/xml/locales_config.xml`.

## Limitaciones

* La melodía es el sonido de alarma del sistema; no hay reproductor ni lista
  de reproducción propia.
* El volumen de la señal es el volumen de alarma del sistema (si está a cero,
  sonará bajo, como cualquier despertador).
* En dispositivos con ahorro de energía agresivo (algunos OEM) la fiabilidad
  depende del permiso para trabajar en segundo plano.
* Si por la mañana suena la música pero la pantalla no se enciende, revisa la
  tarjeta «Permisos y avisos» en los ajustes: los permisos «Ventana emergente
  de la señal» y «Encendido de pantalla», y que el canal «Señal de alarma» no
  esté silenciado. En Android 14+ estos permisos se conceden por separado y
  pueden ser revocados por el sistema o el usuario.
* La hora actual antes de la frase se configura por separado para señales,
  recordatorios de salida y recordatorios de retraso. Los propios recordatorios
  de voz no se pueden desactivar.
