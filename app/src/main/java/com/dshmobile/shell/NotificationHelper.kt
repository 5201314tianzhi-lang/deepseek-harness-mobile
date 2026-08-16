package com.dshmobile.shell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat

/**
 * Test/status notifications with the POST_NOTIFICATIONS permission flow.
 * I-07: the permission result re-sends the notification that was queued
 * while the dialog was up (otherwise the first tap never notifies).
 */
class NotificationHelper(private val activity: ComponentActivity) {

  private var pendingNotification: Pair<String, String>? = null

  private val notificationPermission =
    activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        val pending = pendingNotification
        pendingNotification = null
        if (pending != null) postNotification(pending.first, pending.second)
      }
    }

  fun showTestNotification(title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      pendingNotification = title to text
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
    postNotification(title, text)
  }

  /** Actually send the notification (called directly when the permission is
   *  held; the permission-callback re-send also lands here). */
  private fun postNotification(title: String, text: String) {
    val manager = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(NotificationChannel(DshPaths.NOTIFICATION_CHANNEL, DshPaths.NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_DEFAULT))
    }
    val pending = android.app.PendingIntent.getActivity(
      activity, 0, Intent(activity, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(
      1,
      NotificationCompat.Builder(activity, DshPaths.NOTIFICATION_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build(),
    )
  }
}
