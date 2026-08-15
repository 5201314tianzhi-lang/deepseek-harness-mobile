package com.dshmobile.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Client-visible diagnostic log. Records the engine boot flow, extraction,
 * probing and errors into a bounded in-memory ring buffer plus a log file.
 * With All Files Access granted the file lands in
 * Documents/dshdata/logs/dsh.log (visible in any file manager); otherwise it
 * falls back to filesDir/dsh.log. The guide UI can copy the full log to the
 * clipboard with [copyToClipboard] so the user can paste it straight into a
 * bug report.
 *
 * All entries are English and timestamped; logging never throws.
 */
object AppLog {

  private const val TAG = "dsh-applog"
  private const val MAX_BUFFER_LINES = 500
  private const val MAX_FILE_BYTES = 256 * 1024

  @Volatile
  private var context: Context? = null

  private val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
  private val lock = Any()

  fun init(ctx: Context) {
    context = ctx.applicationContext
  }

  fun log(tag: String, message: String) {
    val line = timestamp() + " " + tag + ": " + message
    synchronized(lock) {
      lines.add(line)
      while (lines.size > MAX_BUFFER_LINES) lines.removeAt(0)
      appendToFile(line)
    }
    Log.i(TAG, line)
  }

  fun log(tag: String, message: String, t: Throwable) {
    log(tag, message + " — " + t.javaClass.simpleName + ": " + (t.message ?: ""))
    t.stackTrace.take(8).forEach { frame -> log(tag, "  at " + frame) }
  }

  /** Current log text, newest entry last. */
  fun dump(): String {
    synchronized(lock) { return lines.joinToString("\n") }
  }

  /** Copy the full log to the clipboard; returns the copied text. */
  fun copyToClipboard(activity: android.app.Activity): String {
    val text = dump()
    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("dsh log", text))
    return text
  }

  private fun timestamp(): String =
    SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

  private fun appendToFile(line: String) {
    val ctx = context ?: return
    try {
      val file = resolveLogFile(ctx) ?: return
      if (file.exists() && file.length() > MAX_FILE_BYTES) {
        // Keep only the tail so the file cannot grow unbounded.
        val tail = file.readLines().takeLast(200)
        file.writeText("")
        tail.forEach { file.appendText(it + "\n") }
      }
      file.appendText(line + "\n")
    } catch (_: Throwable) {
      // Logging must never break the app.
    }
  }

  /** Public log file when All Files Access is held, else the private fallback. */
  private fun resolveLogFile(ctx: Context): File? {
    return try {
      if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
        val base = File(
          android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS),
          "dshdata/logs",
        )
        base.mkdirs()
        if (base.isDirectory) return File(base, "dsh.log")
      }
      File(ctx.filesDir, "dsh.log")
    } catch (_: Throwable) {
      File(ctx.filesDir, "dsh.log")
    }
  }
}
