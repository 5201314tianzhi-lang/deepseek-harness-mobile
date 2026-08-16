package com.dshmobile.shell

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Harness WebView: engine-source navigation gate, session-export
 * interception, file chooser routing, the old-WebView compatibility layer and
 * the JS bridge injection. Engine-source pages stay inside; everything else
 * opens in the system browser (untrusted pages can never reach the bridge).
 */
class HarnessWebView(
  private val activity: ComponentActivity,
  private val picker: PickerBridge,
  private val export: ExportFlow,
  private val notify: (title: String, text: String) -> Unit,
  private val onEngineError: () -> Unit,
  private val onKeepScreen: (enable: Boolean) -> Unit,
  private val pickToken: String,
) {

  private val isDebuggable: Boolean
    get() = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  private val exportLaunching = AtomicBoolean(false)
  private var polyfillsJs: String? = null

  val view: WebView = WebView(activity).apply { id = android.view.View.generateViewId() }

  fun configure() {
    // WebView remote debugging (debug builds only): CDP automation on devices
    // and emulators for UI verification.
    if (isDebuggable) WebView.setWebContentsDebuggingEnabled(true)
    view.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      // Make prefers-color-scheme follow the system dark mode (some OEM
      // WebViews do not by default; FORCE_DARK_AUTO lets the media query
      // reflect system darkness, which dsh's "follow system" theme depends on).
      if (Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        forceDark = WebSettings.FORCE_DARK_AUTO
      }
    }
    view.webViewClient = object : WebViewClient() {
      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        injectCompatPolyfills(view)
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // Session-log export (issue apk#6 + the 403 fix): browser navigations
        // carry Origin:null / sec-fetch-site markers and are rejected by dsh's
        // /api browser-trust fence (403, anti DNS-rebinding/cross-site). Route
        // it through an in-app download instead: HttpURLConnection has no
        // browser markers → the fence lets it through (verified on MuMu).
        if (EngineSource.isSessionExport(url, request.method)) {
          export.downloadToDownloads(url, null)
          return true
        }
        // Keep only engine-same-origin pages inside the WebView (the privileged
        // bridge and download capability are trusted only for the engine);
        // external links go to the system browser so untrusted pages can never
        // reach the bridge (social engineering / notification spam / arbitrary
        // downloads).
        if (EngineSource.isEngineSource(url)) {
          view.loadUrl(url)
          return true
        }
        openInExternalBrowser(request.url)
        return true
      }

      override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        if (EngineSource.isEngineSource(failingUrl)) onEngineError()
      }

      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        pushSystemDark()
      }
    }
    // WebView downloads — session-log export (/api/session.export) and other
    // engine-source downloads — all go through the in-app MediaStore path:
    // browser navigations carry Origin:null and are rejected by dsh's /api
    // browser-trust fence (403), while the in-app HttpURLConnection carries no
    // browser markers → the fence lets it through (403 fix, see ExportFlow).
    view.setDownloadListener { url, _userAgent, contentDisposition, _mimeType, _contentLength ->
      export.downloadToDownloads(url, contentDisposition)
    }
    view.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams,
      ): Boolean {
        // File uploads go through the system file picker (OpenDocument,
        // multi-select); PickerBridge's directory picker handles workspaces
        // and the two must stay separate.
        return picker.handleFileChooser(filePathCallback)
      }

      override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.confirm()
        return true
      }
    }
    view.addJavascriptInterface(
      AndroidBridge(
        onPickRequest = { callbackId -> picker.pickDirectoryWithPermissionCheck(callbackId) },
        onKeepScreen = onKeepScreen,
        onNotify = notify,
        onAllFilesAccessRequest = { picker.openAllFilesAccessSettings() },
        pickToken = pickToken,
      ),
      "androidBridge",
    )
    view.loadUrl(EngineProbe.ENGINE_URL)
  }

  /** Push the system dark-mode state: some OEM WebViews do not make
   *  prefers-color-scheme follow uiMode (observed on vivo/Android 16); the UI
   *  plugin consumes this bridge value via a matchMedia hook
   *  (window.__dshThemeBridge.setDark) to drive the upstream system theme. */
  fun pushSystemDark() {
    val dark = (activity.resources.configuration.uiMode and
      android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
      android.content.res.Configuration.UI_MODE_NIGHT_YES
    try {
      view.evaluateJavascript(
        "window.__dshThemeBridge && window.__dshThemeBridge.setDark(" + dark + ")", null,
      )
    } catch (_: Exception) {
      // Page not ready: onPageFinished pushes it again.
    }
  }

  fun canGoBack(): Boolean = view.canGoBack()

  fun goBack() = view.goBack()

  fun reload() = view.reload()

  /** Evaluate a bridge-delivery script on the main thread (post). */
  fun postScript(script: String) {
    view.post { view.evaluateJavascript(script, null) }
  }

  /**
   * Inject the old-WebView compatibility layer (assets/js/compat-polyfills.js)
   * before the page's own scripts run. Android 10 devices often carry
   * 2019-era Chromium; the Harness front-end relies on newer runtime APIs
   * (e.g. AbortSignal.any — missing it broke the directory picker with
   * "AbortSignal.any is not a function"). All polyfills are guarded, so
   * modern WebViews are unaffected.
   */
  private fun injectCompatPolyfills(view: WebView) {
    try {
      val js = polyfillsJs ?: activity.assets.open("js/compat-polyfills.js").bufferedReader().use { it.readText() }
        .also { polyfillsJs = it }
      view.evaluateJavascript(js, null)
    } catch (t: Throwable) {
      AppLog.log("web", "polyfill inject failed", t)
    }
  }

  /**
   * Atomic, replay-guarded external-browser open (for non-export external
   * links). Best effort: a failed launch is silent (callers do not read the
   * return value); there is no MediaStore fallback contract here — the only
   * fallback path is the export route (inside ExportFlow).
   */
  private fun openInExternalBrowser(uri: Uri): Boolean {
    if (!exportLaunching.compareAndSet(false, true)) return true // in flight: swallow the duplicate trigger
    return try {
      activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
      true
    } catch (_: Exception) {
      // No browser can handle it; callers ignore the result.
      false
    } finally {
      exportLaunching.set(false)
    }
  }
}
