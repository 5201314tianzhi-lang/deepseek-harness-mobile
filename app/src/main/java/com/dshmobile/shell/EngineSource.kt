package com.dshmobile.shell

import android.net.Uri

/**
 * Engine-same-origin checks shared by the WebView navigation gate and the
 * export flow. Exact-match on scheme/host/port (guards against prefix
 * spoofing, e.g. 127.0.0.1:30800 or 127.0.0.1:3080.evil.com being mistaken
 * for the engine source).
 */
object EngineSource {
  /** Session-log export endpoint path (matched by the dual WebView interception). */
  const val SESSION_EXPORT_PATH = "/api/session.export"

  fun isEngineSource(url: String): Boolean =
    try {
      val base = Uri.parse(EngineProbe.ENGINE_URL)
      val uri = Uri.parse(url)
      uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port
    } catch (_: Exception) {
      false
    }

  /** Match: engine source + exact session-export path + GET (HEAD is the
   *  front-end preflight and must not trigger a redirect). A contains-prefix
   *  match would also hit /api/session.export.evil; compare the path exactly. */
  fun isSessionExport(
    url: String,
    method: String,
  ): Boolean {
    if (method != "GET" || !isEngineSource(url)) return false
    return try {
      Uri.parse(url).path == SESSION_EXPORT_PATH
    } catch (_: Exception) {
      false
    }
  }
}
