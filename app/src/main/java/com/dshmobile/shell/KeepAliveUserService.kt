package com.dshmobile.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.ShizukuUserService

/**
 * Shizuku user service (keep-alive L4): runs as the shell/root identity in a
 * separate process and applies the appops exemptions that make OEM battery
 * managers stop killing the app — `RUN_IN_BACKGROUND` and
 * `RUN_ANY_IN_BACKGROUND` allow. Without these, aggressive vendors kill the
 * process regardless of the foreground service; with them the process becomes
 * effectively un-killable by the background policy.
 *
 * Protocol: plain binder transact (code [CMD_APPLY_APPOPS]) with the package
 * name as the input string; the reply carries a human-readable result.
 */
class KeepAliveUserService : ShizukuUserService() {

  private val binder = object : android.os.Binder() {
    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
      if (code == CMD_APPLY_APPOPS) {
        val pkg = data.readString() ?: return false
        val result = applyAppOps(pkg)
        if (reply != null) {
          reply.writeString(result)
          reply.writeNoException()
        }
        return true
      }
      return super.onTransact(code, data, reply, flags)
    }
  }

  override fun onBind(intent: Intent?): IBinder? = binder

  /** Run the appops commands as the remote (shell) identity. */
  private fun applyAppOps(pkg: String): String {
    val results = StringBuilder()
    for (op in arrayOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")) {
      val out = runShell("cmd", "appops", "set", pkg, op, "allow")
      results.append(op).append(": ").append(out).append("\n")
    }
    AppLog.log("keepalive", "appops applied for " + pkg + ":\n" + results)
    return results.toString().trim()
  }

  private fun runShell(vararg args: String): String {
    return try {
      val p = Runtime.getRuntime().exec(args)
      val out = p.inputStream.bufferedReader().readText()
      val err = p.errorStream.bufferedReader().readText()
      val code = p.waitFor()
      "exit=$code out=$out err=$err".trim()
    } catch (t: Throwable) {
      t.message ?: t.javaClass.simpleName
    }
  }

  companion object {
    /** Binder transaction code: apply the keep-alive appops exemptions. */
    const val CMD_APPLY_APPOPS = 1
  }
}
