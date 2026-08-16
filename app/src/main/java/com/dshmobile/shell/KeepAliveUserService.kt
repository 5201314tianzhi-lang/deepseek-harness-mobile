package com.dshmobile.shell

import android.os.Binder
import android.os.Parcel

/**
 * Shizuku user service (keep-alive L4): instantiated by the Shizuku server
 * (v13 protocol — the server reflects a (Context) or no-arg constructor and
 * runs the instance with the shell identity, so this is a plain Binder, NOT
 * an android Service and NOT declared in the manifest).
 *
 * It applies the appops exemptions that make OEM battery managers stop
 * killing the app: `cmd appops set <pkg> RUN_IN_BACKGROUND allow` and
 * `RUN_ANY_IN_BACKGROUND allow`. Without these, aggressive vendors kill the
 * process regardless of the foreground service; with them the process becomes
 * effectively un-killable by the background policy.
 *
 * Protocol: app binds via [rikka.shizuku.Shizuku.bindUserService], receives
 * this binder through the ServiceConnection, and sends
 * [CMD_APPLY_APPOPS] with the package name as the input string; the reply
 * carries a human-readable result.
 */
class KeepAliveUserService : Binder() {
  override fun onTransact(
    code: Int,
    data: Parcel,
    reply: Parcel?,
    flags: Int,
  ): Boolean {
    when (code) {
      USER_SERVICE_DESTROY -> {
        // Reserved destroy transaction: the server asks us to exit.
        android.os.Process.killProcess(android.os.Process.myPid())
        return true
      }

      CMD_APPLY_APPOPS -> {
        val pkg = data.readString() ?: return false
        val result = applyAppOps(pkg)
        if (reply != null) {
          reply.writeString(result)
          reply.writeNoException()
        }
        return true
      }
    }
    return super.onTransact(code, data, reply, flags)
  }

  /** Run the appops commands as the remote (shell) identity. */
  private fun applyAppOps(pkg: String): String {
    val results = StringBuilder()
    for (op in arrayOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND")) {
      val out = runShell("cmd", "appops", "set", pkg, op, "allow")
      results
        .append(op)
        .append(": ")
        .append(out)
        .append("\n")
    }
    AppLog.log("keepalive", "appops applied for " + pkg + ":\n" + results)
    return results.toString().trim()
  }

  private fun runShell(vararg args: String): String =
    try {
      val p = Runtime.getRuntime().exec(args)
      val out = p.inputStream.bufferedReader().readText()
      val err = p.errorStream.bufferedReader().readText()
      val code = p.waitFor()
      "exit=$code out=$out err=$err".trim()
    } catch (t: Throwable) {
      t.message ?: t.javaClass.simpleName
    }

  companion object {
    /** Binder transaction code: apply the keep-alive appops exemptions. */
    const val CMD_APPLY_APPOPS = 1

    /** Shizuku reserved "destroy" transaction (16777115). */
    private const val USER_SERVICE_DESTROY = 16777115
  }
}
