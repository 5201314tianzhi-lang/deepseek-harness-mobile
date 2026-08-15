package com.dshmobile.shell

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** Probes the local dsh web engine (127.0.0.1:3080) from the shell side. */
object EngineProbe {

  const val ENGINE_URL = "http://127.0.0.1:3080"

  /**
   * One-shot reachability probe. Performs network I/O, so call it from a
   * background thread (on the main thread it throws NetworkOnMainThreadException,
   * which is caught and reported as "not running").
   * @param timeoutMs connect+read budget per attempt.
   * @returns JSON: {running: Boolean, latencyMs: Int, error?: String}
   */
  fun check(timeoutMs: Int = 800): JSONObject {
    var conn: HttpURLConnection? = null
    return try {
      val c = URL(ENGINE_URL).openConnection() as HttpURLConnection
      conn = c
      c.connectTimeout = timeoutMs
      c.readTimeout = timeoutMs
      c.requestMethod = "GET"
      val start = System.currentTimeMillis()
      val code = c.responseCode
      JSONObject()
        .put("running", code == 200)
        .put("latencyMs", System.currentTimeMillis() - start)
    } catch (e: Exception) {
      JSONObject().put("running", false).put("error", e.message ?: "unknown")
    } finally {
      // I-13: release the connection on the error path too (otherwise it leaks).
      conn?.disconnect()
    }
  }
}
