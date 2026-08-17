package com.dshmobile.shell

/**
 * Tracks whether the engine-source page currently shown in the WebView ended
 * in a network error. WebView fires onPageFinished (with the pending URL)
 * even for error pages, so a finished load must NOT clear the failed flag —
 * only a load that started AND finished without an error can (the pending
 * error page then still counts as failed and gets reloaded once the engine
 * answers).
 */
class EnginePageState(
  private val isEngineSource: (String) -> Boolean,
) {
  private var failed = false
  private var loadErrored = false

  /** True when the current engine page is a failed load and needs a reload. */
  val isFailed: Boolean get() = failed

  /** WebViewClient.onPageStarted: a fresh load begins. */
  fun onLoadStarted(url: String) {
    if (isEngineSource(url)) loadErrored = false
  }

  /** WebViewClient.onReceivedError: the current load failed. */
  fun onLoadError(failingUrl: String) {
    if (isEngineSource(failingUrl)) {
      loadErrored = true
      failed = true
    }
  }

  /** WebViewClient.onPageFinished: the load ended (successfully or with an
   *  error — an error page finishes too). */
  fun onLoadFinished(url: String) {
    if (isEngineSource(url)) failed = loadErrored
  }
}
