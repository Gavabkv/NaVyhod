package com.example.dovykhoda

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.dovykhoda.ui.AppScreen
import com.example.dovykhoda.ui.theme.NaVykhodTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* состояние обновится при возврате на экран */ }

    /** Все разрешения, без которых будильник может не разбудить. */
    private enum class PermissionKind(val key: String) {
        Notifications("notifications"),
        ExactAlarms("exact_alarms"),
        FullScreenIntent("full_screen_intent"),
        TurnScreenOn("turn_screen_on"),
        RingChannel("ring_channel"),
        DndAccess("dnd_access"),
        BatteryExemption("battery_exemption"),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NaVykhodTheme {
                AppScreen(
                    onRequestExactAlarms = ::openExactAlarmSettings,
                    onRequestNotifications = ::askNotifications,
                    onRequestBatteryExemption = ::askBatteryExemption,
                    onRequestFullScreenIntent = ::openFullScreenIntentSettings,
                    onRequestTurnScreenOn = ::openTurnScreenOnSettings,
                    onRequestRingChannel = ::openRingChannelSettings,
                    onRequestDndAccess = ::openDndAccessSettings,
                    onExportLogs = ::shareLogs,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Первый запуск: по очереди открываем недостающие разрешения. Вернувшись
        // из системного экрана, здесь же спрашиваем следующее. Каждое
        // предлагается один раз, дальше напоминают строки в «Настройках».
        requestMissingPermissions()
    }

    private fun requestMissingPermissions() {
        // Поверх экрана сигнала системные окна открывать нельзя.
        if (AlarmService.isRinging) return
        val store = SettingsStore(this)
        val kind = PermissionKind.entries.firstOrNull { candidate ->
            !store.permissionAsked(candidate.key) && isPermissionMissing(candidate)
        } ?: return
        store.markPermissionAsked(kind.key)
        AlarmLog.event("permission_request", "kind" to kind.key)
        when (kind) {
            PermissionKind.Notifications -> askNotifications()
            PermissionKind.ExactAlarms -> openExactAlarmSettings()
            PermissionKind.FullScreenIntent -> openFullScreenIntentSettings()
            PermissionKind.TurnScreenOn -> openTurnScreenOnSettings()
            PermissionKind.RingChannel -> openRingChannelSettings()
            PermissionKind.DndAccess -> openDndAccessSettings()
            PermissionKind.BatteryExemption -> askBatteryExemption()
        }
    }

    private fun isPermissionMissing(kind: PermissionKind): Boolean {
        val notifications = getSystemService(NotificationManager::class.java)
        return when (kind) {
            PermissionKind.Notifications -> notifications?.areNotificationsEnabled() != true
            PermissionKind.ExactAlarms -> !AlarmScheduler.canScheduleExact(this)
            PermissionKind.FullScreenIntent -> !canUseFullScreenIntent(this)
            PermissionKind.TurnScreenOn -> !canTurnScreenOn(this)
            PermissionKind.RingChannel -> !StatusNotifier.isRingChannelAvailable(this)
            PermissionKind.DndAccess -> notifications?.isNotificationPolicyAccessGranted != true
            PermissionKind.BatteryExemption -> !isIgnoringBatteryOptimizations(this)
        }
    }

    override fun onStart() {
        super.onStart()
        // Если приложение открыли после перезагрузки/обновления — восстановим цепочку
        // и приведём уведомление в соответствие с текущим моментом.
        val settings = SettingsStore(this).load()
        if (settings.enabled) {
            AlarmScheduler.reschedule(this, settings)
        }
        StatusNotifier.refresh(this, settings)
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
    }

    private fun askBatteryExemption() {
        if (isIgnoringBatteryOptimizations(this)) return
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }
    }

    /** Android 14+ разрешает полноэкранные уведомления только с явного согласия. */
    private fun openFullScreenIntentSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure {
                val fallback = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                runCatching { startActivity(fallback) }
            }
    }

    /**
     * Android 14+ не даёт будить экран через wake lock без разрешения
     * «Включение экрана». Без него окно будильника может не разбудить дисплей.
     */
    private fun openTurnScreenOnSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val intent = Intent("android.settings.TURN_SCREEN_ON_SETTINGS")
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }
            }
    }

    private fun openRingChannelSettings() {
        val intent = StatusNotifier.ringChannelSettingsIntent(this)
        runCatching { startActivity(intent) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
    }

    /** Без доступа к политике уведомлений приложение не видит «Не беспокоить». */
    private fun openDndAccessSettings() {
        val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(ACTION_DND_ACCESS_DETAIL, Uri.parse("package:$packageName"))
        } else null
        if (detail != null && runCatching { startActivity(detail) }.isSuccess) return
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            .onFailure {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
    }

    private fun shareLogs() {
        lifecycleScope.launch {
            val zip = withContext(Dispatchers.IO) { AlarmLog.exportZip(this@MainActivity) }
            if (zip == null) {
                Toast.makeText(this@MainActivity, "Логов пока нет", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity, "$packageName.logs", zip,
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Логи приложения «На выход»")
                putExtra(Intent.EXTRA_TEXT, "Диагностические логи будильника. Архив может содержать сведения об устройстве и расписании.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { startActivity(Intent.createChooser(share, "Отправить логи")) }
                .onFailure {
                    AlarmLog.failure("log_share_failed", it)
                    Toast.makeText(this@MainActivity, "Не удалось открыть отправку логов", Toast.LENGTH_LONG).show()
                }
        }
    }

    companion object {
        /** Экран доступа к политике уведомлений для одного приложения. */
        private const val ACTION_DND_ACCESS_DETAIL =
            "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS"

        fun isIgnoringBatteryOptimizations(context: Context): Boolean {
            val manager = context.getSystemService(PowerManager::class.java) ?: return false
            return manager.isIgnoringBatteryOptimizations(context.packageName)
        }

        fun canUseFullScreenIntent(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            return manager.canUseFullScreenIntent()
        }

        /** Android 14+: без этого разрешения wake lock не может включить экран. */
        fun canTurnScreenOn(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
            val manager = context.getSystemService(AppOpsManager::class.java) ?: return false
            return runCatching {
                manager.unsafeCheckOpNoThrow(
                    // AppOpsManager.OPSTR_TURN_SCREEN_ON, строка для API 34
                    "android:turn_screen_on",
                    Process.myUid(),
                    context.packageName,
                ) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }
    }
}
