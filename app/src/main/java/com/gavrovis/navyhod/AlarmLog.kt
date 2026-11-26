package com.gavrovis.navyhod

import android.Manifest
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Файловые логи приложения: один файл на день, хранение 30 дней.
 *
 * Пишем в `Android/data/com.gavrovis.navyhod/files/logs/na-vyhod-ГГГГ-ММ-ДД.log`.
 * Если внешний каталог недоступен (например, до первой разблокировки),
 * используется внутренний/device-protected каталог. Сбой записи лога
 * никогда не должен ломать будильник, поэтому все операции обёрнуты в runCatching.
 */
object AlarmLog {
    private const val TAG = "AlarmLog"
    private const val DIR_NAME = "logs"
    private const val PREFIX = "na-vyhod-"
    private const val RETENTION_DAYS = 30L
    private const val MAX_PART_BYTES = 4L * 1024 * 1024
    private const val MAX_EXPORT_BYTES = 25L * 1024 * 1024

    private val lock = Any()
    private var appContext: Context? = null
    private var candidates: List<File> = emptyList()
    private var targetIndex = 0
    private var currentDir: File? = null
    private var currentDay: String = ""
    private var currentFile: File? = null
    private var part = 1
    private var retentionDay: String = ""

    /** Вызывается из Application один раз за процесс. */
    fun init(context: Context) {
        synchronized(lock) {
            if (appContext != null) return
            val app = context.applicationContext
            appContext = app
            candidates = buildCandidates(app)
            val unlocked = runCatching {
                app.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked
            }.getOrNull()
            writeLocked("EVENT", "app_start", deviceFields(app).apply {
                put("logsDir", candidates.getOrNull(targetIndex)?.absolutePath)
                put("pid", Process.myPid())
                put("userUnlocked", unlocked)
            })
        }
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        synchronized(lock) {
            if (appContext == null) return
            writeLocked("EVENT", name, fields.toMap())
        }
    }

    fun failure(name: String, error: Throwable?, vararg fields: Pair<String, Any?>) {
        synchronized(lock) {
            if (appContext == null) return
            val map = LinkedHashMap<String, Any?>(fields.toMap())
            if (error != null) map["error"] = "${error.javaClass.name}: ${error.message}"
            writeLocked("ERROR", name, map)
            if (error != null) {
                error.stackTraceToString().lineSequence()
                    .filter { it.isNotBlank() }
                    .take(50)
                    .forEach { line -> writeLocked("ERROR", "stack", mapOf("at" to line.trim())) }
            }
        }
    }

    /** Снимок состояния системы и разрешений — полезен на старте и при сигнале. */
    fun deviceInfo(name: String = "device_info") {
        synchronized(lock) {
            val context = appContext ?: return
            writeLocked("EVENT", name, deviceFields(context))
        }
    }

    /** Каталог, в который сейчас пишутся логи. */
    fun directory(context: Context): File {
        init(context)
        synchronized(lock) {
            return candidates.getOrElse(targetIndex) { File(context.filesDir, DIR_NAME) }
        }
    }

    /** Собирает все дневные файлы в zip во временном каталоге для отправки. */
    fun exportZip(context: Context): File? {
        init(context)
        synchronized(lock) {
            val files = candidates
                .filter { it.isDirectory }
                .flatMap { dir ->
                    dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
                        ?.toList() ?: emptyList()
                }
                .distinctBy { it.name }
                .sortedByDescending { it.name }
            if (files.isEmpty()) return null
            val outDir = File(context.cacheDir, "logs-export").apply { mkdirs() }
            val zip = File(outDir, "na-vyhod-logs.zip")
            var total = 0L
            ZipOutputStream(zip.outputStream().buffered()).use { stream ->
                for (file in files) {
                    if (total + file.length() > MAX_EXPORT_BYTES) continue
                    runCatching {
                        stream.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(stream) }
                        stream.closeEntry()
                        total += file.length()
                    }.onFailure { Log.w(TAG, "cannot add ${file.name} to export", it) }
                }
            }
            return zip
        }
    }

    fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(millis))

    // ------------------------------------------------------------------ запись

    private fun writeLocked(level: String, name: String, fields: Map<String, Any?>) {
        val line = formatLine(level, name, fields)
        writeBytes((line + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(payload: ByteArray): Boolean {
        val dirs = candidates
        if (dirs.isEmpty()) return false
        var index = targetIndex
        repeat(dirs.size) {
            val dir = dirs[index]
            val ok = runCatching {
                if (!dir.exists() && !dir.mkdirs()) error("cannot create ${dir.absolutePath}")
                val day = LocalDate.now().toString()
                if (currentDir != dir || currentDay != day || currentFile == null) {
                    setCurrentFile(dir, day)
                }
                val file = currentFile ?: error("no log file")
                if (file.length() + payload.size > MAX_PART_BYTES) {
                    part += 1
                    currentFile = fileFor(dir, day, part)
                }
                FileOutputStream(currentFile, true).use { it.write(payload) }
                targetIndex = index
                true
            }.getOrElse { error ->
                Log.w(TAG, "cannot write log to ${dir.absolutePath}", error)
                false
            }
            if (ok) return true
            currentFile = null
            currentDir = null
            index = (index + 1) % dirs.size
        }
        return false
    }

    private fun setCurrentFile(dir: File, day: String) {
        currentDir = dir
        currentDay = day
        part = 1
        val file = fileFor(dir, day, part)
        currentFile = file
        cleanupOldFiles(dir, day)
        if (!file.exists() || file.length() == 0L) {
            val context = appContext
            val header = buildString {
                append("===== Na Vyhod log, created ").append(formatTime(System.currentTimeMillis())).append(" =====\n")
                if (context != null) {
                    append("# app=").append(safeString { versionName(context) })
                    append(" device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" android=").append(Build.VERSION.RELEASE).append(" (sdk ").append(Build.VERSION.SDK_INT).append(")\n")
                }
            }
            runCatching {
                FileOutputStream(file, true).use { it.write(header.toByteArray(Charsets.UTF_8)) }
            }.onFailure { Log.w(TAG, "cannot write log header", it) }
        }
    }

    private fun cleanupOldFiles(dir: File, today: String) {
        if (retentionDay == today) return
        retentionDay = today
        val cutoff = runCatching { LocalDate.parse(today).minusDays(RETENTION_DAYS) }.getOrNull() ?: return
        runCatching {
            dir.listFiles { file -> file.isFile && file.name.startsWith(PREFIX) }?.forEach { file ->
                val date = runCatching { LocalDate.parse(file.name.removePrefix(PREFIX).take(10)) }.getOrNull()
                if (date != null && date.isBefore(cutoff)) file.delete()
            }
        }.onFailure { Log.w(TAG, "cannot clean old logs", it) }
    }

    private fun fileFor(dir: File, day: String, part: Int): File =
        File(dir, "$PREFIX$day${if (part > 1) "-part$part" else ""}.log")

    private fun formatLine(level: String, name: String, fields: Map<String, Any?>): String {
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }
            .format(Date(now))
        return buildString(192) {
            append(time).append(" | ").append(level).append(" | ").append(name)
            append(" | t=").append(now)
            append(" uptime=").append(SystemClock.elapsedRealtime())
            append(" pid=").append(Process.myPid())
            append(" thread=").append(sanitize(Thread.currentThread().name))
            fields.forEach { (key, value) ->
                if (value != null) append(' ').append(key).append('=').append(sanitize(value))
            }
        }
    }

    private fun sanitize(value: Any): String {
        val text = when (value) {
            is Throwable -> "${value.javaClass.simpleName}: ${value.message}"
            else -> value.toString()
        }
        return text.replace('\n', ' ').replace('\r', ' ').replace('|', '/').take(600)
    }

    // ------------------------------------------------------------------ окружение

    private fun buildCandidates(context: Context): List<File> {
        val list = ArrayList<File>(3)
        runCatching { context.getExternalFilesDir(DIR_NAME) }.getOrNull()?.let { list.add(it) }
        runCatching { File(context.filesDir, DIR_NAME) }.getOrNull()?.let { list.add(it) }
        runCatching {
            File(context.createDeviceProtectedStorageContext().filesDir, DIR_NAME)
        }.getOrNull()?.let { list.add(it) }
        return list.distinctBy { it.absolutePath }
    }

    private fun deviceFields(context: Context): LinkedHashMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["appVersion"] = safe("?") { versionName(context) }
        map["versionCode"] = safe(-1L) {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        }
        map["manufacturer"] = Build.MANUFACTURER
        map["model"] = Build.MODEL
        map["device"] = Build.DEVICE
        map["android"] = "${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})"
        map["locale"] = Locale.getDefault().toLanguageTag()
        map["timezone"] = TimeZone.getDefault().id
        map["exactAlarms"] = safe(false) { AlarmScheduler.canScheduleExact(context) }
        map["batteryOptimized"] = safe(true) {
            val power = context.getSystemService(PowerManager::class.java)
            !power.isIgnoringBatteryOptimizations(context.packageName)
        }
        map["notificationsEnabled"] = safe(false) {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        map["postNotifications"] = safe(false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            } else true
        }
        map["fullScreenIntent"] = safe(false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.getSystemService(NotificationManager::class.java)
                    ?.canUseFullScreenIntent() == true
            } else true
        }
        map["turnScreenOn"] = safe(false) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val ops = context.getSystemService(AppOpsManager::class.java)
                ops?.unsafeCheckOpNoThrow(
                    "android:turn_screen_on", Process.myUid(), context.packageName,
                ) == AppOpsManager.MODE_ALLOWED
            } else true
        }
        map["ringChannelImportance"] = safe(-1) {
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(StatusNotifier.CHANNEL_RINGING)?.importance ?: -1
        }
        map["keyguardLocked"] = safe(false) {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false
        }
        map["deviceLocked"] = safe(false) {
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked ?: false
        }
        map["screenInteractive"] = safe(false) {
            context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        }
        map["alarmVolume"] = safe(-1) {
            context.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
        }
        map["alarmVolumeMax"] = safe(-1) {
            context.getSystemService(AudioManager::class.java)?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: -1
        }
        map["ringerMode"] = safe(-1) {
            context.getSystemService(AudioManager::class.java)?.ringerMode ?: -1
        }
        map["interruptionFilter"] = safe(-1) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: -1
            } else -1
        }
        map["batteryLevel"] = safe(-1) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        }
        map["batteryStatus"] = safe(-1) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        }
        return map
    }

    private fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    private fun <T> safe(fallback: T, block: () -> T): T =
        runCatching(block).getOrDefault(fallback)

    private fun safeString(block: () -> String): String =
        runCatching(block).getOrDefault("?")
}
