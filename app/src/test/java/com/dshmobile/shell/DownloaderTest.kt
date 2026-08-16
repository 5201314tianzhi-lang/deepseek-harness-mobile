package com.dshmobile.shell

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** SHA-256 correctness for the download/rootfs verification path. */
class DownloaderTest {

  private fun tmpFile(content: ByteArray): File {
    val f = File.createTempFile("dsh-test", ".bin")
    f.writeBytes(content)
    f.deleteOnExit()
    return f
  }

  @Test
  fun `sha256 of empty file`() {
    val f = tmpFile(ByteArray(0))
    assertEquals(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      Downloader.sha256(f),
    )
  }

  @Test
  fun `sha256 of abc`() {
    val f = tmpFile("abc".toByteArray())
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      Downloader.sha256(f),
    )
  }

  @Test
  fun `sha256 of large streamed content matches`() {
    val big = ByteArray(300 * 1024) // crosses the 64KB buffer boundary
    for (i in big.indices) big[i] = (i % 251).toByte()
    val f = tmpFile(big)
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    digest.update(big)
    val expected = digest.digest().joinToString("") { "%02x".format(it) }
    assertEquals(expected, Downloader.sha256(f))
  }

  @Test
  fun `sha256 differs between files`() {
    val a = tmpFile("hello".toByteArray())
    val b = tmpFile("world".toByteArray())
    val ha = Downloader.sha256(a)
    val hb = Downloader.sha256(b)
    assert(ha != hb) { "distinct files must produce distinct digests" }
    assertEquals(64, ha.length)
  }

  @Test
  fun `download rejects non-200`() {
    // Local server returning 404 must surface as IOException, not a silent success.
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
    server.createContext("/missing") { ex ->
      ex.sendResponseHeaders(404, -1)
      ex.close()
    }
    server.start()
    try {
      val url = "http://127.0.0.1:" + server.address.port + "/missing"
      val target = tmpFile(ByteArray(0))
      val e = assertThrows(java.io.IOException::class.java) {
        Downloader.downloadToFile(url, target, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
      }
      assert(e.message!!.contains("404"))
    } finally {
      server.stop(0)
    }
  }

  @Test
  fun `download streams content to file`() {
    val payload = "mirror-content-" + "x".repeat(300 * 1024)
    val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(0), 0)
    server.createContext("/ok") { ex ->
      val bytes = payload.toByteArray()
      ex.sendResponseHeaders(200, bytes.size.toLong())
      ex.responseBody.use { it.write(bytes) }
    }
    server.start()
    try {
      val url = "http://127.0.0.1:" + server.address.port + "/ok"
      val target = File.createTempFile("dsh-dl", ".bin")
      target.deleteOnExit()
      Downloader.downloadToFile(url, target, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
      assertEquals(payload, target.readText())
    } finally {
      server.stop(0)
    }
  }
}
