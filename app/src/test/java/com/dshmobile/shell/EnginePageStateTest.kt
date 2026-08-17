package com.dshmobile.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: WebView fires onPageFinished (with the pending URL) even for
 * error pages — the old inline logic cleared the failed flag there, so a
 * page that failed before the engine came up was never reloaded even after
 * the engine became reachable ("never connected" symptom).
 */
class EnginePageStateTest {
  private val isEngineSource: (String) -> Boolean = { url -> url.startsWith("http://127.0.0.1:3080") }

  private fun state(): EnginePageState = EnginePageState(isEngineSource)

  @Test
  fun cleanLoadStartsAndFinishesNotFailed() {
    val s = state()
    s.onLoadStarted("http://127.0.0.1:3080/")
    s.onLoadFinished("http://127.0.0.1:3080/")
    assertFalse(s.isFailed)
  }

  @Test
  fun errorPageFinishedKeepsFailure() {
    val s = state()
    s.onLoadStarted("http://127.0.0.1:3080/")
    s.onLoadError("http://127.0.0.1:3080/")
    s.onLoadFinished("http://127.0.0.1:3080/")
    assertTrue("onPageFinished on an error page must not clear the failed flag", s.isFailed)
  }

  @Test
  fun failedLoadThenCleanLoadRecovers() {
    val s = state()
    s.onLoadStarted("http://127.0.0.1:3080/")
    s.onLoadError("http://127.0.0.1:3080/")
    s.onLoadFinished("http://127.0.0.1:3080/")
    assertTrue(s.isFailed)
    s.onLoadStarted("http://127.0.0.1:3080/")
    s.onLoadFinished("http://127.0.0.1:3080/")
    assertFalse(s.isFailed)
  }

  @Test
  fun consecutiveFailuresStayFailed() {
    val s = state()
    repeat(2) {
      s.onLoadStarted("http://127.0.0.1:3080/")
      s.onLoadError("http://127.0.0.1:3080/")
      s.onLoadFinished("http://127.0.0.1:3080/")
    }
    assertTrue(s.isFailed)
  }

  @Test
  fun nonEngineEventsIgnored() {
    val s = state()
    s.onLoadStarted("http://evil.com/")
    s.onLoadError("http://evil.com/")
    s.onLoadFinished("http://evil.com/")
    assertFalse(s.isFailed)
  }
}
