package com.dshmobile.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lifecycle events that restart the keep-alive chain (keep-alive L1):
 * device boot, APK upgrade (MY_PACKAGE_REPLACED), power re-connected and
 * screen unlock all re-raise the foreground service. On Android 12+ a
 * foreground-service start from these broadcasts is exempt from the
 * background-start restriction.
 */
class BootReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent?,
  ) {
    val action = intent?.action ?: return
    AppLog.log("keepalive", "event: " + action)
    KeepAliveAlarm.schedule(context)
    val svc = Intent(context, EngineService::class.java)
    try {
      context.startForegroundService(svc)
    } catch (t: Throwable) {
      AppLog.log("keepalive", "foreground start refused (" + action + ")", t)
      try {
        context.startService(svc)
      } catch (_: Throwable) {
      }
    }
  }
}
