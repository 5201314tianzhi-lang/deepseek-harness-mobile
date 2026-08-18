package com.dshmobile.shell

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Single-runtime rootfs online update: fetch a manifest {url, sha256, size},
 * download the rootfs archive, verify its SHA-256, extract to a staging
 * directory outside the live tree, then swap rootfs with the staged copy
 * (rootfs → rootfs-old → new rootfs) with rollback on failure. The engine
 * restart is handled by the EngineService watchdog on the next poll.
 */
class UpdateManager(
  private val context: Context,
) {
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
   * Manifest fetcher, injectable for tests (Robolectric cannot reach the
   * network deterministically). Production uses the real HTTP client.
   */
  internal var fetcher: (String) -> String = { url -> fetch(url) }

  /**
   * Run the update flow on a background thread.
   * @param onStatus progress text callback (any thread).
   */
  fun checkAndApply(onStatus: (String) -> Unit) {
    // Single-flight: two concurrent runs (guide button + debug ACTION_UPDATE)
    // used to share fixed tmp/stage paths and could tear each other's staging
    // directory apart mid-extract, swapping a hollow tree into the live usr.
    if (!UPDATE_IN_FLIGHT.compareAndSet(false, true)) {
      onStatus(context.getString(R.string.update_in_progress))
      return
    }
    Thread {
      val uuid =
        java.util.UUID
          .randomUUID()
          .toString()
      val tmp = File(context.filesDir, "update-" + uuid + ".tar.xz")
      val stage = File(context.filesDir, "update-stage-" + uuid)
      try {
        onStatus(context.getString(R.string.update_checking))
        val manifestUrl = this.manifestUrl
        if (!manifestUrl.startsWith("https://")) throw IllegalStateException(context.getString(R.string.err_manifest_https))
        val manifest = JSONObject(fetcher(manifestUrl))
        val url = manifest.getString("url")
        if (!url.startsWith("https://")) throw IllegalStateException(context.getString(R.string.err_snapshot_url_https))
        // sha256 is mandatory: refuse the update when missing, otherwise the
        // snapshot has no integrity protection (I-01b).
        val expectedSha = manifest.optString("sha256", "").lowercase()
        if (expectedSha.isEmpty()) throw IllegalStateException(context.getString(R.string.err_manifest_no_sha))

        onStatus(context.getString(R.string.update_downloading, manifest.optLong("size", 0) / 1024 / 1024))
        download(url, tmp)

        onStatus(context.getString(R.string.update_verifying))
        val actual = sha256(tmp)
        if (!actual.equals(expectedSha, ignoreCase = true)) {
          throw IllegalStateException(context.getString(R.string.err_sha_mismatch, actual.take(12) + "…"))
        }

        onStatus(context.getString(R.string.update_extracting))
        // The archive IS the rootfs (no usr/ prefix); stage it OUTSIDE the
        // live tree and swap rootfs → rootfs-old → new rootfs.
        SnapshotExtractor.extract(
          tmp.inputStream(),
          manifest.optLong("size", 0),
          stage,
          { _, _ -> },
        )
        if (!File(stage, DshPaths.ROOTFS_NODE).exists()) {
          throw IllegalStateException(context.getString(R.string.err_new_snapshot_no_node))
        }

        onStatus(context.getString(R.string.update_switching))
        val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)
        val old = File(context.filesDir, "rootfs-old")
        deleteRecursively(old)
        if (rootfs.exists() && !rootfs.renameTo(old)) {
          // Cannot move the old runtime aside: keep it in place, do not switch (I-08).
          throw IllegalStateException(context.getString(R.string.err_old_runtime_switch))
        }
        if (!stage.renameTo(rootfs)) {
          // Cannot move the new runtime in: roll the old one back so the
          // engine stays usable (I-08).
          if (old.exists() && !old.renameTo(rootfs)) {
            // Rollback also failed (rare, e.g. storage fault): keep rootfs-old
            // for manual recovery.
            throw IllegalStateException(context.getString(R.string.err_switch_failed_rollback))
          }
          throw IllegalStateException(context.getString(R.string.err_switch_failed_rolled_back))
        }

        // Kill the old engine process: the EngineService watchdog restarts
        // it from the NEW usr within seconds. The process keeps running on
        // the OLD tree's inodes after the swap, so a missed kill means the
        // running engine silently stays on the previous snapshot — surface
        // it instead of swallowing it.
        val killed =
          try {
            val p = Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-f", "bin.js"))
            val rc = p.waitFor()
            if (rc == 0) {
              AppLog.log("update", "old engine killed")
              true
            } else {
              AppLog.log("update", "pkill exit=" + rc + " (no engine matched, nothing to kill)")
              true
            }
          } catch (t: Throwable) {
            AppLog.log("update", "pkill unavailable", t)
            false
          }
        onStatus(
          context.getString(R.string.update_done) +
            if (killed) "" else " " + context.getString(R.string.update_restart_hint),
        )
      } catch (t: Throwable) {
        AppLog.log("update", "update FAILED", t)
        onStatus(context.getString(R.string.update_failed, t.message ?: t.javaClass.simpleName))
      } finally {
        // Unique tmp/stage are always cleaned: a failed run must not leave a
        // ~500MB tarball or a half-extracted stage behind (and reuse of a
        // stale fixed name would corrupt the next run).
        try {
          tmp.delete()
        } catch (_: Throwable) {
        }
        try {
          deleteRecursively(stage)
        } catch (_: Throwable) {
        }
        UPDATE_IN_FLIGHT.set(false)
      }
    }.start()
  }

  private fun fetch(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = 10_000
      conn.readTimeout = 30_000
      val code = conn.responseCode
      if (code != 200) throw IllegalStateException(context.getString(R.string.err_manifest_http, code))
      return conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
      conn.disconnect()
    }
  }

  private fun download(
    url: String,
    dest: File,
  ) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
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
    } finally {
      conn.disconnect()
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
    /** Single-runtime rootfs updates come from dsh-io/dsh-arm64 releases. */
    const val DEFAULT_MANIFEST_URL =
      "https://github.com/dsh-io/dsh-arm64/releases/latest/download/manifest.json"

    /** Total-size cap for rootfs downloads: the artifact is ~80MB; 500MB
     *  leaves ample headroom while preventing storage exhaustion (I-09). */
    const val MAX_SNAPSHOT_BYTES = 500L * 1024 * 1024

    /** Process-level single-flight guard (both update entry points share it). */
    private val UPDATE_IN_FLIGHT =
      java.util.concurrent.atomic
        .AtomicBoolean(false)
  }
}
