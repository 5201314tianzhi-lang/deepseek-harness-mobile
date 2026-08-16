package com.dshmobile.shell

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service owning the embedded engine lifecycle: keeps the app
 * process alive while backgrounded (user-visible notification) and restarts
 * the engine process when it dies (watchdog). M2 keep-alive, no root needed.
 *
 * Ownership contract: the watchdog ALWAYS arms once the service runs — even
 * when the engine is currently up, so a later crash gets caught. The engine
 * process is only stopped when this service itself is stopped (the Activity
 * never kills it).
 */
class EngineService : Service() {

  private lateinit var engineManager: EngineManager
  private var watchdog: ScheduledExecutorService? = null
  private var ensureRunner: java.util.concurrent.ExecutorService? = null

  override fun onCreate() {
    super.onCreate()
    engineManager = EngineManager(this)
    startForeground(NOTIFICATION_ID, buildNotification())
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    AppLog.log("watchdog", "service start")
    ensureEngine()
    return START_STICKY
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    watchdog?.shutdownNow()
    watchdog = null
    ensureRunner?.shutdownNow()
    ensureRunner = null
    // I-12: nothing takes over the engine process after the service dies, so
    // stop it to avoid an orphaned process.
    engineManager.stopEngine()
    super.onDestroy()
  }

  /** Ensure the engine is up, then arm the watchdog. All I/O runs off the
   *  main thread — EngineProbe.check() does HTTP I/O and would throw
   *  NetworkOnMainThreadException on the service's main thread (silently
   *  swallowed → the guard always said "not running" → double starts). */
  private fun ensureEngine() {
    if (ensureRunner == null) {
      ensureRunner = Executors.newSingleThreadExecutor()
    }
    ensureRunner?.execute {
      try {
        val running = EngineProbe.check().optBoolean("running", false)
        if (!running && engineManager.engineReady) {
          AppLog.log("watchdog", "engine not running, starting")
          engineManager.startEngine()
        }
        armWatchdog()
      } catch (t: Throwable) {
        AppLog.log("watchdog", "ensureEngine failed", t)
        // Never give up: try again on the next onStartCommand / restart.
      }
    }
  }

  /** Single-flight watchdog: poll every 5s; if the engine process dies (or
   *  the port stops answering), restart it. Task body is fully guarded — a
   *  scheduleWithFixedDelay task that throws is silently suppressed forever. */
  private fun armWatchdog() {
    if (watchdog != null) return
    watchdog = Executors.newSingleThreadScheduledExecutor().also { exec ->
      exec.scheduleWithFixedDelay({
        try {
          val running = EngineProbe.check().optBoolean("running", false)
          if (!running && engineManager.engineReady) {
            AppLog.log("watchdog", "engine down, restarting")
            AppLog.includeFile(java.io.File(this.filesDir, DshPaths.ENGINE_LOG), DshPaths.ENGINE_LOG)
            engineManager.startEngine()
          }
        } catch (t: Throwable) {
          AppLog.log("watchdog", "watchdog tick failed", t)
        }
      }, 5, 5, TimeUnit.SECONDS)
    }
  }

  private fun buildNotification(): android.app.Notification {
    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(
        NotificationChannel(
          DshPaths.NOTIFICATION_CHANNEL, "dsh engine", NotificationManager.IMPORTANCE_LOW,
        ),
      )
    }
    val content = android.content.Intent(this, MainActivity::class.java)
    val pending = PendingIntent.getActivity(
      this, 0, content, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(this, DshPaths.NOTIFICATION_CHANNEL)
      .setSmallIcon(com.dshmobile.shell.R.mipmap.ic_launcher)
      .setContentTitle(getString(R.string.engine_notification_title))
      .setContentText(getString(R.string.engine_notification_text))
      .setContentIntent(pending)
      .setOngoing(true)
      .build()
  }

  companion object {
    const val NOTIFICATION_ID = 1
  }
}
