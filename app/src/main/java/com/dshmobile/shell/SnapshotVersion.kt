package com.dshmobile.shell

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Version of the @deepseek-ai/dsh package inside the runtime snapshot,
 * read once and cached — the guide rebuilds must never re-read the
 * filesystem (boot speed), and the value is stable for the app's lifetime.
 */
object SnapshotVersion {
  @Volatile
  private var cached: String? = null

  fun read(context: Context): String {
    cached?.let { return it }
    val file =
      File(
        context.filesDir,
        DshPaths.USR_DIR + "/lib/node_modules/@deepseek-ai/dsh/package.json",
      )
    val version =
      try {
        JSONObject(file.readText()).optString("version", "")
      } catch (_: Throwable) {
        ""
      }
    cached = version.ifEmpty { "?" }
    return cached!!
  }

  /** Test hook: drop the cached value so the next read hits the filesystem. */
  fun clearCache() {
    cached = null
  }
}
