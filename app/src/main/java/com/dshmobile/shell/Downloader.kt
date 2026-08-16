package com.dshmobile.shell

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Shared network plumbing: streamed downloads and SHA-256 verification. */
object Downloader {

  /** Stream a GET to a file (256KB buffer); throws IOException on HTTP failure. */
  fun downloadToFile(
    url: String,
    target: File,
    connectTimeoutMs: Int = 30_000,
    readTimeoutMs: Int = 60_000,
  ) {
    val conn = URL(url).openConnection() as HttpURLConnection
    try {
      conn.connectTimeout = connectTimeoutMs
      conn.readTimeout = readTimeoutMs
      if (conn.responseCode != HttpURLConnection.HTTP_OK) {
        throw java.io.IOException("HTTP " + conn.responseCode)
      }
      conn.inputStream.use { ins ->
        target.outputStream().use { out ->
          val buf = ByteArray(256 * 1024)
          while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
          }
        }
      }
    } finally {
      conn.disconnect()
    }
  }

  /** Hex SHA-256 of a file. */
  fun sha256(file: File): String {
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
}
