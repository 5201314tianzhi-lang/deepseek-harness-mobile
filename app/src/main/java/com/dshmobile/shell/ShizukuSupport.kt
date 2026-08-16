package com.dshmobile.shell

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku

/**
 * Shizuku integration (keep-alive L4): detect the server, request permission,
 * and apply the appops background exemptions through a user service running
 * with the shell identity (`cmd appops set <pkg> RUN_IN_BACKGROUND allow`,
 * `RUN_ANY_IN_BACKGROUND allow`). Everything degrades gracefully when Shizuku
 * is absent — the exemptions are a boost, not a requirement.
 */
object ShizukuSupport {
  private const val PERMISSION_REQUEST_CODE = 9001

  /** True when the Shizuku server binder is reachable. */
  fun isAvailable(): Boolean =
    try {
      Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
      false
    }

  /** Status text for the UI; never throws. */
  fun status(context: Context): String =
    if (isAvailable()) {
      context.getString(R.string.shizuku_granted, Shizuku.getVersion())
    } else {
      context.getString(R.string.shizuku_absent)
    }

  /** True when the battery-optimization exemption is already granted. */
  fun isBatteryOptimizationIgnored(context: Context): Boolean =
    try {
      val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
      pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (_: Throwable) {
      false
    }

  /**
   * Apply the keep-alive appops exemptions. Handles the whole flow:
   * availability check → permission request → user-service bind → transact.
   * @param onResult called on the main thread with a status line.
   */
  fun applyAppOpsBoost(
    context: Context,
    onResult: (String) -> Unit,
  ) {
    val report: (String) -> Unit = { text -> android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) } }
    try {
      if (!Shizuku.pingBinder()) {
        report(context.getString(R.string.shizuku_absent))
        return
      }
      if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
        val listener =
          object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(
              requestCode: Int,
              grantResult: Int,
            ) {
              Shizuku.removeRequestPermissionResultListener(this)
              if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindAndApply(context, report)
              } else {
                report(context.getString(R.string.shizuku_permission_denied))
              }
            }
          }
        Shizuku.addRequestPermissionResultListener(listener)
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        return
      }
      bindAndApply(context, report)
    } catch (t: Throwable) {
      report(t.message ?: t.javaClass.simpleName)
    }
  }

  private fun bindAndApply(
    context: Context,
    report: (String) -> Unit,
  ) {
    val args =
      Shizuku
        .UserServiceArgs(
          ComponentName(context.packageName, "com.dshmobile.shell.KeepAliveUserService"),
        ).apply {
          processNameSuffix("keepalive")
          daemon(false)
          version(1)
        }
    val conn =
      object : ServiceConnection {
        override fun onServiceConnected(
          name: ComponentName,
          service: IBinder,
        ) {
          try {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            data.writeInterfaceToken("com.dshmobile.shell.KeepAliveUserService")
            data.writeString(context.packageName)
            service.transact(KeepAliveUserService.CMD_APPLY_APPOPS, data, reply, 0)
            reply.readException()
            report(reply.readString() ?: context.getString(R.string.err_unknown))
            data.recycle()
            reply.recycle()
          } catch (t: Throwable) {
            report(t.message ?: t.javaClass.simpleName)
          } finally {
            try {
              Shizuku.unbindUserService(args, this, true)
            } catch (_: Throwable) {
            }
          }
        }

        override fun onServiceDisconnected(name: ComponentName) {
          report(context.getString(R.string.shizuku_service_disconnected))
        }
      }
    try {
      Shizuku.bindUserService(args, conn)
    } catch (t: Throwable) {
      report(t.message ?: t.javaClass.simpleName)
    }
  }
}
