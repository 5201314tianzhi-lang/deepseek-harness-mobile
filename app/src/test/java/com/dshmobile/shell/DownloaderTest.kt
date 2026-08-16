package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

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

  /** Minimal single-shot HTTP server (the JDK httpserver module is not on the
   *  Android test bootclasspath). Serves one request then closes. */
  private fun withServer(
    statusCode: Int,
    body: ByteArray,
    block: (port: Int) -> Unit,
  ) {
    val server = java.net.ServerSocket(0)
    server.reuseAddress = true
    val thread =
      Thread {
        try {
          server.accept().use { socket ->
            socket.soTimeout = 5000
            val input = socket.getInputStream()
            val buf = ByteArray(4096)
            var seen = ""
            while (!seen.contains("\r\n\r\n")) {
              val n = input.read(buf)
              if (n < 0) break
              seen += String(buf, 0, n)
            }
            val out = socket.getOutputStream()
            val head = "HTTP/1.1 $statusCode T\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
          }
        } catch (_: Exception) {
        } finally {
          try {
            server.close()
          } catch (_: Exception) {
          }
        }
      }
    thread.isDaemon = true
    thread.start()
    try {
      block(server.localPort)
    } finally {
      thread.join(5000)
      try {
        server.close()
      } catch (_: Exception) {
      }
    }
  }

  @Test
  fun `download rejects non-200`() {
    withServer(404, ByteArray(0)) { port ->
      val url = "http://127.0.0.1:$port/missing"
      val target = tmpFile(ByteArray(0))
      val e =
        assertThrows(java.io.IOException::class.java) {
          Downloader.downloadToFile(url, target, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
        }
      assert(e.message!!.contains("404"))
    }
  }

  @Test
  fun `download streams content to file`() {
    val payload = "mirror-content-" + "x".repeat(300 * 1024)
    withServer(200, payload.toByteArray()) { port ->
      val url = "http://127.0.0.1:$port/ok"
      val target = File.createTempFile("dsh-dl", ".bin")
      target.deleteOnExit()
      Downloader.downloadToFile(url, target, connectTimeoutMs = 5_000, readTimeoutMs = 5_000)
      assertEquals(payload, target.readText())
    }
  }
}
