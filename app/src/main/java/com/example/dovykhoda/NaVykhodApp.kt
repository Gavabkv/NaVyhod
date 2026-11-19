package com.example.dovykhoda

import android.app.Application

/**
 * Инициализирует файловые логи до запуска сервисов и приёмников.
 */
class NaVykhodApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AlarmLog.init(this)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { AlarmLog.failure("uncaught_exception", error, "thread" to thread.name) }
            previous?.uncaughtException(thread, error)
        }
        AlarmLog.event("process_start", "userUnlocked" to runCatching {
            getSystemService(android.os.UserManager::class.java)?.isUserUnlocked
        }.getOrNull())
    }
}
