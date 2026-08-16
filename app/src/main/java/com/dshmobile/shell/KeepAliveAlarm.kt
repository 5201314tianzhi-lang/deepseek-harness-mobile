package com.dshmobile.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Heartbeat recovery net (keep-alive L2): a repeating inexact alarm wakes the
 * app in Doze, re-arms itself, and restarts the engine if the foreground
 * service/engine is gone (OEM battery managers kill the service despite
 * START_STICKY; the alarm is the only scheduled wakeup that survives their
 * policies). setAndAllowWhileIdle needs no exact-alarm permission.
 */
object KeepAliveAlarm {

  /** Heartbeat interval: 30 minutes — frequent enough to recover from OEM
   *  kills, sparse enough to be battery-neutral. */
  const val INTERVAL_MS = 30 * 60 * 1000L

  fun schedule(context: Context) {
    try {
      val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
      val pi = PendingIntent.getBroadcast(
        context, 0, Intent(context, KeepAliveAlarm::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
      am.setAndAllowWhileIdle(
        AlarmManager.ELAPSED_REALTIME_WAKEUP,
        SystemClock.elapsedRealtime() + INTERVAL_MS,
        pi,
      )
    } catch (t: Throwable) {
      AppLog.log("keepalive", "alarm schedule failed", t)
    }
  }
}

class KeepAliveAlarm : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent?) {
    // Re-arm first: an exception in the engine check must not stop the chain.
    KeepAliveAlarm.schedule(context)
    // Engine check is HTTP I/O — off the main thread (NetworkOnMainThread).
    Thread {
      try {
        val running = EngineProbe.check().optBoolean("running", false)
        if (running) return@Thread
        AppLog.log("keepalive", "alarm tick: engine down, restarting")
        val svc = Intent(context, EngineService::class.java)
        try {
          // Exempt from the Android 12+ background FGS limit when triggered
          // from a broadcast the user can still expect (BOOT/POWER/ALARM).
          context.startForegroundService(svc)
        } catch (_: Throwable) {
          // Fallback: plain start — START_STICKY + the next alarm tick cover
          // the case where the system refused a foreground start.
          try {
            context.startService(svc)
          } catch (_: Throwable) {
          }
        }
      } catch (t: Throwable) {
        AppLog.log("keepalive", "alarm tick failed", t)
      }
    }.start()
  }
}
