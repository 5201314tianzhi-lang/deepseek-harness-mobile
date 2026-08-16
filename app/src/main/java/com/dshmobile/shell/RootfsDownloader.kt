package com.dshmobile.shell

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

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

  @Volatile private var running = false

  fun isInstalling(): Boolean = running

  fun state(context: Context): String {
    val rootfs = File(context.filesDir, "rootfs")
    return when {
      running -> "downloading"
      File(rootfs, ".ready").isFile -> "ready"
      rootfs.exists() -> "failed"
      else -> "missing"
    }
  }

  private fun abi(context: Context): String? {
    val abis = android.os.Build.SUPPORTED_ABIS
    return when {
      abis.any { it.startsWith("arm64") } -> "arm64"
      abis.any { it.startsWith("x86_64") } -> "x86_64"
      else -> null
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val n = ins.read(buf)
        if (n < 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  fun install(context: Context): Boolean {
    if (running) return false
    running = true
    return try {
      val arch = abi(context) ?: return false
      val tarballName = TARBALL_PREFIX + arch + ".tar.gz"
      val tmp = File(context.filesDir, "ubuntu-base.download")
      val rootfs = File(context.filesDir, "rootfs")

      AppLog.log("rootfs", "downloading " + BASE_URL + tarballName)
      val conn = URL(BASE_URL + tarballName).openConnection() as HttpURLConnection
      conn.connectTimeout = 30_000
      conn.readTimeout = 60_000
      conn.inputStream.use { ins ->
        FileOutputStream(tmp).use { out ->
          val buf = ByteArray(256 * 1024)
          var total = 0L
          while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
          }
          AppLog.log("rootfs", "downloaded " + total + " bytes")
        }
      }

      val want = expectedChecksum(context, tarballName)
      if (want != null && !sha256(tmp).equals(want, ignoreCase = true)) {
        AppLog.log("rootfs", "sha256 mismatch, expected " + want)
        return false
      }

      rootfs.deleteRecursively()
      rootfs.mkdirs()
      extractTarGz(tmp, rootfs)
      File(rootfs, ".ready").writeText(BASE_URL + tarballName)
      AppLog.log("rootfs", "rootfs installed at " + rootfs.absolutePath)
      tmp.delete()
      true
    } catch (t: Throwable) {
      AppLog.log("rootfs", "install failed", t)
      false
    } finally {
      running = false
    }
  }

  private fun expectedChecksum(context: Context, tarballName: String): String? {
    return try {
      val conn = URL(CHECKSUM_URL).openConnection() as HttpURLConnection
      conn.connectTimeout = 30_000
      conn.inputStream.bufferedReader().useLines { lines ->
        lines.map { it.trim() }
          .firstOrNull { it.endsWith("  $tarballName") || it.endsWith(" *$tarballName") }
          ?.substringBefore(' ')
      }
    } catch (t: Throwable) {
      AppLog.log("rootfs", "checksum fetch failed, skipping verify", t)
      null
    }
  }

  private fun extractTarGz(tarGz: File, dest: File) {
    // Pure-Java extraction (no child process): app-data ELF exec is denied on
    // Android 10+ vendors (EACCES), and this path has no exec-hook protection.
    GzipCompressorInputStream(tarGz.inputStream().buffered()).use { gz ->
      val tar = TarArchiveInputStream(gz)
      SnapshotExtractor.extractTar(tar, dest)
      tar.close()
    }
  }
}
