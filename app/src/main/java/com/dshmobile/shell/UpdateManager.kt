package com.dshmobile.shell

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Runtime snapshot online update (M2): fetch a manifest {url, sha256, size},
 * download the snapshot, verify its SHA-256, extract to a staging directory
 * outside the live tree, then swap usr with the staged copy (usr → usr-old →
 * new usr) with rollback on failure. The engine restart is handled by the
 * EngineService watchdog on the next poll.
 */
class UpdateManager(private val context: Context) {

  /**
   * Manifest URL override for testing (the emulator reaches the host via
   * 10.0.2.2). Production builds point at a real release server.
   * Must be HTTPS: a plaintext manifest + snapshot can be tampered with on
   * the wire and yields remote code execution (I-01).
   */
  var manifestUrl: String = DEFAULT_MANIFEST_URL
    set(value) {
      if (!value.startsWith("https://")) {
        throw IllegalArgumentException("manifest URL must be HTTPS: $value")
      }
      field = value
    }

  /**
   * Run the update flow on a background thread.
   * @param onStatus progress text callback (any thread).
   */
  fun checkAndApply(onStatus: (String) -> Unit) {
    Thread {
      try {
        onStatus(context.getString(R.string.update_checking))
        val manifestUrl = this.manifestUrl
        if (!manifestUrl.startsWith("https://")) throw IllegalStateException(context.getString(R.string.err_manifest_https))
        val manifest = JSONObject(fetch(manifestUrl))
        val url = manifest.getString("url")
        if (!url.startsWith("https://")) throw IllegalStateException(context.getString(R.string.err_snapshot_url_https))
        // sha256 is mandatory: refuse the update when missing, otherwise the
        // snapshot has no integrity protection (I-01b).
        val expectedSha = manifest.optString("sha256", "").lowercase()
        if (expectedSha.isEmpty()) throw IllegalStateException(context.getString(R.string.err_manifest_no_sha))

        onStatus(context.getString(R.string.update_downloading, manifest.optLong("size", 0) / 1024 / 1024))
        val tmp = File(context.filesDir, "update.tar.xz")
        download(url, tmp)

        onStatus(context.getString(R.string.update_verifying))
        val actual = sha256(tmp)
        if (!actual.equals(expectedSha, ignoreCase = true)) {
          tmp.delete()
          throw IllegalStateException(context.getString(R.string.err_sha_mismatch, actual.take(12) + "…"))
        }

        onStatus(context.getString(R.string.update_extracting))
        // The archive holds a usr/ prefix; stage it OUTSIDE the live tree.
        val stage = File(context.filesDir, "update-stage")
        deleteRecursively(stage)
        SnapshotExtractor.extract(
          tmp.inputStream(), manifest.optLong("size", 0), stage, { _, _ -> },
        )
        tmp.delete()
        val newUsr = File(stage, "usr")
        if (!File(newUsr, "bin/node").exists()) throw IllegalStateException(context.getString(R.string.err_new_snapshot_no_node))

        onStatus(context.getString(R.string.update_switching))
        val usr = File(context.filesDir, "usr")
        val old = File(context.filesDir, "usr-old")
        deleteRecursively(old)
        if (usr.exists() && !usr.renameTo(old)) {
          // Cannot move the old runtime aside: keep it in place, do not switch (I-08).
          throw IllegalStateException(context.getString(R.string.err_old_runtime_switch))
        }
        if (!newUsr.renameTo(usr)) {
          // Cannot move the new runtime in: roll the old one back so the
          // engine stays usable (I-08).
          if (old.exists() && !old.renameTo(usr)) {
            // Rollback also failed (rare, e.g. storage fault): keep usr-old
            // for manual recovery.
            throw IllegalStateException(context.getString(R.string.err_switch_failed_rollback))
          }
          throw IllegalStateException(context.getString(R.string.err_switch_failed_rolled_back))
        }
        deleteRecursively(stage)
        deleteRecursively(old)

        // Kill the old engine process: the EngineService watchdog restarts
        // it from the NEW usr within seconds.
        try {
          Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-f", "bin.js")).waitFor()
        } catch (_: Throwable) {
        }
        onStatus(context.getString(R.string.update_done))
      } catch (t: Throwable) {
        AppLog.log("update", "update FAILED", t)
        onStatus(context.getString(R.string.update_failed, t.message ?: t.javaClass.simpleName))
      }
    }.start()
  }

  private fun fetch(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 30_000
    val code = conn.responseCode
    if (code != 200) throw IllegalStateException(context.getString(R.string.err_manifest_http, code))
    return conn.inputStream.bufferedReader().use { it.readText() }
  }

  private fun download(url: String, dest: File) {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 10_000
    conn.readTimeout = 60_000
    val code = conn.responseCode
    if (code != 200) throw IllegalStateException(context.getString(R.string.err_download_http, code))
    conn.inputStream.use { input ->
      dest.outputStream().use { out ->
        // Stream with a total-size cap (I-09): a manifest can point at a huge
        // file, so without a limit the download could fill the storage.
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          total += n
          if (total > MAX_SNAPSHOT_BYTES) throw IllegalStateException(context.getString(R.string.err_snapshot_too_large))
          out.write(buf, 0, n)
        }
      }
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      var n = input.read(buf)
      while (n >= 0) {
        digest.update(buf, 0, n)
        n = input.read(buf)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun deleteRecursively(file: File) {
    if (!file.exists()) return
    file.walkBottomUp().forEach { it.delete() }
  }

  companion object {
    /** Emulator reaches the host loopback alias; production overrides via manifestUrl. */
    const val DEFAULT_MANIFEST_URL = "https://10.0.2.2:8899/manifest.json"

    /** Total-size cap for snapshot downloads: the bundled snapshot is ~70MB,
     *  500MB leaves ample headroom while preventing storage exhaustion (I-09). */
    const val MAX_SNAPSHOT_BYTES = 500L * 1024 * 1024
  }
}
