package com.dshmobile.shell

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the official Ubuntu base tarball (cdimage.ubuntu.com), verifies it
 * against the release SHA256SUMS, and extracts it to filesDir/rootfs.
 * Single-flight: only one install at a time. Progress goes to AppLog.
 */
object RootfsDownloader {
  private const val BASE_URL =
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
  private const val TARBALL_PREFIX = "ubuntu-base-24.04.3-base-"
  private const val CHECKSUM_URL = BASE_URL + "SHA256SUMS"

  /** Atomic single-flight guard: check-then-set on a volatile bool races. */
  private val RUNNING =
    java.util.concurrent.atomic
      .AtomicBoolean(false)

  fun isInstalling(): Boolean = RUNNING.get()

  fun state(context: Context): String {
    val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)
    return when {
      RUNNING.get() -> "downloading"
      File(rootfs, ".ready").isFile -> "ready"
      rootfs.exists() -> "failed"
      else -> "missing"
    }
  }

  /** Ubuntu base release arch name: the device ABI is `x86_64`, but Ubuntu
   *  names that architecture `amd64` in its tarball/checksum files. */
  private fun abi(context: Context): String? {
    val abis = android.os.Build.SUPPORTED_ABIS
    return when {
      abis.any { it.startsWith("arm64") } -> "arm64"
      abis.any { it.startsWith("x86_64") } -> "amd64"
      else -> null
    }
  }

  fun install(context: Context): Boolean {
    if (!RUNNING.compareAndSet(false, true)) return false
    val tmp = File(context.filesDir, "ubuntu-base.download")
    return try {
      val arch = abi(context) ?: return false
      val tarballName = TARBALL_PREFIX + arch + ".tar.gz"
      val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)

      AppLog.log("rootfs", "downloading " + BASE_URL + tarballName)
      Downloader.downloadToFile(BASE_URL + tarballName, tmp)

      // Checksum is mandatory, not advisory: the catch-all in expectedChecksum
      // previously swallowed disk/parse errors too and skipped verification
      // whenever the checksum fetch hiccupped — unverified rootfs would be
      // installed from the wire. No checksum → no install.
      val want = expectedChecksum(context, tarballName)
      if (want == null) {
        AppLog.log("rootfs", "checksum unavailable — refusing unverified rootfs install")
        return false
      }
      if (!Downloader.sha256(tmp).equals(want, ignoreCase = true)) {
        AppLog.log("rootfs", "sha256 mismatch, expected " + want)
        return false
      }

      // Stage outside the live root, then swap with rollback: an interrupted
      // extraction must not destroy the previously working rootfs (the old
      // code deleted rootfs before extracting, so a mid-way failure left a
      // half-tree and forced a full re-download).
      val stage = File(context.filesDir, "rootfs-staging")
      stage.deleteRecursively()
      stage.mkdirs()
      extractTarGz(tmp, stage)
      File(stage, ".ready").writeText(BASE_URL + tarballName)
      val old = File(context.filesDir, "rootfs-old")
      old.deleteRecursively()
      if (rootfs.exists() && !rootfs.renameTo(old)) {
        throw java.io.IOException("cannot move old rootfs aside")
      }
      if (!stage.renameTo(rootfs)) {
        if (old.exists() && !old.renameTo(rootfs)) {
          throw java.io.IOException("swap failed and rollback failed")
        }
        throw java.io.IOException("cannot move staged rootfs into place")
      }
      old.deleteRecursively()
      AppLog.log("rootfs", "rootfs installed at " + rootfs.absolutePath)
      true
    } catch (t: Throwable) {
      AppLog.log("rootfs", "install failed", t)
      false
    } finally {
      tmp.delete()
      RUNNING.set(false)
    }
  }

  private fun expectedChecksum(
    context: Context,
    tarballName: String,
  ): String? =
    try {
      val conn = URL(CHECKSUM_URL).openConnection() as HttpURLConnection
      conn.connectTimeout = 30_000
      conn.inputStream.bufferedReader().useLines { lines ->
        lines
          .map { it.trim() }
          .firstOrNull { it.endsWith("  $tarballName") || it.endsWith(" *$tarballName") }
          ?.substringBefore(' ')
      }
    } catch (t: Throwable) {
      AppLog.log("rootfs", "checksum fetch failed", t)
      null
    }

  private fun extractTarGz(
    tarGz: File,
    dest: File,
  ) {
    // Pure-Java extraction (no child process): app-data ELF exec is denied on
    // Android 10+ vendors (EACCES), and this path has no exec-hook protection.
    GzipCompressorInputStream(tarGz.inputStream().buffered()).use { gz ->
      val tar = TarArchiveInputStream(gz)
      try {
        SnapshotExtractor.extractTar(tar, dest)
      } finally {
        tar.close()
      }
    }
  }
}
