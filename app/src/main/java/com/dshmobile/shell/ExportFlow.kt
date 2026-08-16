package com.dshmobile.shell

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Session-log export: download an engine URL into the system Downloads
 * directory via MediaStore (permission-free on Android 10+).
 *
 * Only engine-same-origin URLs are accepted (guards against local SSRF /
 * malicious file drops); written streaming with a size cap. The in-app
 * HttpURLConnection carries no browser markers (Origin/sec-fetch-site), so
 * it passes dsh's /api browser-trust fence (the fix for the 403 on browser
 * navigation).
 */
class ExportFlow(
  private val context: Context,
  private val notify: (title: String, text: String) -> Unit,
  private val pushResult: (ok: Boolean, detail: String) -> Unit,
) {
  /** In-flight download guard: dedupes the shouldOverrideUrlLoading and
   *  downloadListener entry points. */
  private val exportDownloading = AtomicBoolean(false)

  fun downloadToDownloads(
    url: String,
    contentDisposition: String?,
  ) {
    if (!EngineSource.isEngineSource(url)) {
      val reason = context.getString(R.string.notif_engine_only_export)
      notify(context.getString(R.string.notif_download_rejected), reason)
      pushResult(false, reason)
      return
    }
    if (!exportDownloading.compareAndSet(false, true)) return
    if (Build.VERSION.SDK_INT < 29) {
      val reason = context.getString(R.string.notif_export_failed_old_os)
      notify(context.getString(R.string.notif_export_failed), reason)
      pushResult(false, reason)
      exportDownloading.set(false)
      return
    }
    val filename = sanitizeFilename(parseDownloadFilename(url, contentDisposition))
    Thread {
      var conn: HttpURLConnection? = null
      try {
        val c = URL(url).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.requestMethod = "GET"
        // Do not follow redirects: the engine is trusted, but a redirect
        // target is not — it could smuggle arbitrary remote content into the
        // user's Downloads folder.
        c.instanceFollowRedirects = false
        if (c.responseCode != HttpURLConnection.HTTP_OK) {
          throw java.io.IOException("HTTP " + c.responseCode)
        }
        var saved: String? = null
        c.inputStream.use { input ->
          saved = saveToDownloadsStreamed(filename, input)
        }
        val finalName = saved
        // notify and pushResult are injected main-thread-safe (MainActivity
        // routes them through runOnUiThread / webView.post).
        val detail = context.getString(R.string.notif_export_saved_to, finalName)
        notify(context.getString(R.string.notif_export_saved), detail)
        pushResult(true, detail)
      } catch (t: Throwable) {
        val message = t.message ?: context.getString(R.string.err_unknown)
        notify(context.getString(R.string.notif_export_failed), message)
        pushResult(false, message)
      } finally {
        conn?.disconnect()
        exportDownloading.set(false)
      }
    }.start()
  }

  /** Write to MediaStore.Downloads (permission-free on Android 10+), streaming
   *  with a 200MB cap. Callers must guard for SDK_INT < 29 (downloadToDownloads
   *  rejects before reaching here). */
  @androidx.annotation.RequiresApi(29)
  private fun saveToDownloadsStreamed(
    filename: String,
    input: java.io.InputStream,
  ): String {
    val values =
      ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        put(MediaStore.Downloads.IS_PENDING, 1)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
      }
    val uri =
      context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw java.io.IOException(context.getString(R.string.err_create_download))
    try {
      context.contentResolver.openOutputStream(uri)?.use { out ->
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          total += n
          if (total > MAX_DOWNLOAD_BYTES) throw java.io.IOException(context.getString(R.string.err_export_too_large))
          out.write(buf, 0, n)
        }
      } ?: throw java.io.IOException(context.getString(R.string.err_write_download))
      values.clear()
      values.put(MediaStore.Downloads.IS_PENDING, 0)
      context.contentResolver.update(uri, values, null, null)
    } catch (t: Throwable) {
      context.contentResolver.delete(uri, null, null)
      throw t
    }
    return filename
  }

  /** Sanitize a filename: replace path separators/control characters, cap length. */
  private fun sanitizeFilename(name: String): String {
    val cleaned = name.replace(Regex("[/\\\u0000-\u001f]"), "_").take(200)
    return if (cleaned.isBlank()) "dsh-session-export.zip" else cleaned
  }

  /** Filename: Content-Disposition wins, then the sessionId from the URL, then
   *  a fixed fallback name. */
  private fun parseDownloadFilename(
    url: String,
    contentDisposition: String?,
  ): String {
    contentDisposition?.let { cd ->
      Regex("filename=\"?([^\";]+)\"?")
        .find(cd)
        ?.groupValues
        ?.get(1)
        ?.let { return it }
    }
    return try {
      val q = URL(url).query ?: ""
      val sid =
        q
          .split("&")
          .mapNotNull { seg ->
            val kv = seg.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == "sessionId") kv[1] else null
          }.firstOrNull()
      if (sid != null) "dsh-session-$sid.zip" else "dsh-session-export.zip"
    } catch (_: Exception) {
      "dsh-session-export.zip"
    }
  }

  companion object {
    /** Export file-size cap (guards against OOM from a malicious/oversized file). */
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
  }
}
