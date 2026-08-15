package com.dshmobile.shell

import android.content.Context
import rikka.shizuku.Shizuku

/**
 * Optional Shizuku integration (M2 keep-alive boost, stage 1): detect the
 * Shizuku server and report status. The appops-application step needs the
 * shell-exec API (Shizuku.newProcess is not public in api 13.1.5; upgrade the
 * dependency or route via a user service) — deferred.
 * Everything degrades gracefully when Shizuku is absent.
 */
object ShizukuSupport {

  /** True when the Shizuku server binder is reachable. */
  fun isAvailable(): Boolean {
    return try {
      Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }
  }

  /** Status text for the UI; never throws. */
  fun status(context: Context): String {
    return if (isAvailable()) {
      context.getString(R.string.shizuku_granted, Shizuku.getVersion())
    } else {
      context.getString(R.string.shizuku_absent)
    }
  }
}
