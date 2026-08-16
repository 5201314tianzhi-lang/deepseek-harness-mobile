package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Engine-same-origin gate: exact scheme/host/port matching against the
 * engine URL (http://127.0.0.1:3080). Guards the WebView navigation boundary
 * and the session-export interception.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineSourceTest {
  @Test
  fun `exact engine URL is source`() {
    assertTrue(EngineSource.isEngineSource("http://127.0.0.1:3080/"))
    assertTrue(EngineSource.isEngineSource("http://127.0.0.1:3080/api/session.export"))
    assertTrue(EngineSource.isEngineSource("http://127.0.0.1:3080/some/page?q=1#frag"))
  }

  @Test
  fun `port prefix spoof is rejected`() {
    assertFalse(EngineSource.isEngineSource("http://127.0.0.1:30800/"))
    assertFalse(EngineSource.isEngineSource("http://127.0.0.1:30801/x"))
  }

  @Test
  fun `host spoof is rejected`() {
    assertFalse(EngineSource.isEngineSource("http://127.0.0.1:3080.evil.com/"))
    assertFalse(EngineSource.isEngineSource("http://127.0.0.1evil.com:3080/"))
    assertFalse(EngineSource.isEngineSource("http://evil.com:3080/"))
  }

  @Test
  fun `scheme spoof is rejected`() {
    assertFalse(EngineSource.isEngineSource("https://127.0.0.1:3080/"))
    assertFalse(EngineSource.isEngineSource("file://127.0.0.1:3080/x"))
  }

  @Test
  fun `localhost variant is not the engine host`() {
    // host must be exactly 127.0.0.1 — localhost is a different host string.
    assertFalse(EngineSource.isEngineSource("http://localhost:3080/"))
  }

  @Test
  fun `garbage input never throws`() {
    assertFalse(EngineSource.isEngineSource(""))
    assertFalse(EngineSource.isEngineSource("not a url"))
    assertFalse(EngineSource.isEngineSource(":///"))
  }

  @Test
  fun `session export matches exactly`() {
    assertTrue(EngineSource.isSessionExport("http://127.0.0.1:3080/api/session.export", "GET"))
  }

  @Test
  fun `session export prefix must not match`() {
    assertFalse(EngineSource.isSessionExport("http://127.0.0.1:3080/api/session.export.evil", "GET"))
    assertFalse(EngineSource.isSessionExport("http://127.0.0.1:3080/api/session.exported", "GET"))
    assertFalse(EngineSource.isSessionExport("http://127.0.0.1:3080/api/session.export", "HEAD"))
    assertFalse(EngineSource.isSessionExport("http://127.0.0.1:3080/api/session.export", "POST"))
    assertFalse(EngineSource.isSessionExport("http://127.0.0.1:30800/api/session.export", "GET"))
    assertFalse(EngineSource.isSessionExport("http://evil.com/api/session.export", "GET"))
  }
}
