package com.dshmobile.shell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

/** Shell activity: WebView over the local dsh engine + engine guide fallback. */
class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private lateinit var guideView: LinearLayout
  /** Thin status bar overlaying the Harness during cold start (quick path). */
  private lateinit var topStatusBar: LinearLayout
  private lateinit var topStatusLabel: TextView
  private var topPulseDot: View? = null
  private var topPulseAnimator: android.animation.ValueAnimator? = null
  private var guideFromTopBar = false
  /** One-time auth token for the directory-pick bridge (random per process
   *  start; held in the engine env and the JS bridge). */
  private val pickToken: String = java.util.UUID.randomUUID().toString()
  private var engineStatus: TextView? = null
  private var statusDetail: TextView? = null
  private var progressBar: android.widget.ProgressBar? = null
  /** Primary action button: "Launch engine" (ready) or "Retry" (error). */
  private var primaryButton: Button? = null
  private var backButton: Button? = null
  private var errorBlock: LinearLayout? = null
  private var errorText: TextView? = null
  /** Wizard step indicators (runtime → container → launch). */
  private var stepDots: Array<View> = emptyArray()
  private var stepLabels: Array<TextView> = emptyArray()
  /** True while the setup wizard asked the user to press Launch manually. */
  private var manualLaunchRequired = false
  /** Engine launch in flight (guards the Launch button against double taps). */
  private val launchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
  private val engineManager by lazy { EngineManager(this, pickToken) }
  private val engineFlowRunning = java.util.concurrent.atomic.AtomicBoolean(false)
  private var pendingPickCallback: String? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null
  /** Screen-on wake lock: reuse a single instance (I-04 — otherwise the lock
   *  could never be released and multiple locks would leak). */
  private var wakeLock: android.os.PowerManager.WakeLock? = null
  /** Notification queued while the permission dialog is up (I-07: re-send it
   *  after the grant callback, otherwise the first tap only shows the dialog). */
  private var pendingNotification: Pair<String, String>? = null

  /** UI surface driving the engine-flow states: full-screen guide (first
   *  launch / errors) or the thin cold-start bar over the Harness. */
  private enum class UiMode { HIDDEN, GUIDE, TOPBAR }

  /** AGP 8 does not generate BuildConfig by default; use the debuggable flag. */
  private val isDebuggable: Boolean
    get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

  /** Record device/env facts once, so bug reports carry the context needed to
   *  diagnose ABI/runtime issues (e.g. x86_64 snapshot on an arm64 device). */
  private fun logDeviceInfo() {
    val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",")
    AppLog.log("device", "model=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT +
      " abis=[" + abis + "] debuggable=" + isDebuggable)
  }

  private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    val callback = pendingPickCallback
    pendingPickCallback = null
    if (callback != null) {
      if (uri != null) {
        val path = AndroidBridge.resolvePickedPath(uri)
        webView.evaluateJavascript(
          "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callback) + ", " + jsString(path) + ")", null,
        )
      } else {
        // User cancelled: report null so the engine-side pick() settles as a
        // cancellation (otherwise the page polling the same request would keep
        // re-opening the picker — observed picker stacking on device).
        webView.evaluateJavascript(
          "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callback) + ", null)", null,
        )
      }
    }
  }

  companion object {
    const val ACTION_UPDATE = "com.dshmobile.shell.action.UPDATE"

    /** Export file-size cap (guards against OOM from a malicious/oversized file). */
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024

    /** Session-log export endpoint path (matched by the dual WebView interception). */
    const val SESSION_EXPORT_PATH = "/api/session.export"
  }

  // File upload (<input type=file> → WebView onShowFileChooser → system file
  // picker). Kept separate from directory picking (directoryPicker, used for
  // workspaces): multi-select, any type.
  private val filePicker =
    registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      val callback = filePathCallback
      filePathCallback = null
      if (callback != null) {
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
      }
    }

  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      // I-07: after the grant, re-send the notification that was queued while
      // the permission dialog was up (otherwise the first tap never notifies).
      if (granted) {
        val pending = pendingNotification
        pendingNotification = null
        if (pending != null) postNotification(pending.first, pending.second)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppLog.init(this)
    logDeviceInfo()
    val root = FrameLayout(this).apply {
      setBackgroundColor(0xFFFFFFFF.toInt())
    }
    webView = WebView(this).apply { id = View.generateViewId() }
    root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    topStatusBar = buildTopStatusBar()
    root.addView(topStatusBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
    guideView = buildGuideView()
    root.addView(guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
    configureWebView()
    // Quick path: snapshot AND mandatory container already provisioned →
    // go straight to the Harness; the cold start is covered by the thin
    // status bar, not the full-screen guide.
    val provisioned = File(filesDir, "usr/bin/node").isFile &&
      File(filesDir, "rootfs/bin/bash").isFile
    if (provisioned) {
      webView.visibility = View.VISIBLE
    } else {
      guideView.visibility = View.VISIBLE
    }
    // Testable update trigger: adb am start -n .../.MainActivity -a com.dshmobile.shell.action.UPDATE
    if (intent?.action == ACTION_UPDATE) {
      // I-03: the activity is exported (LAUNCHER), so any app can fire this
      // intent and trigger the download+execute chain — accept it only in
      // debug builds and ignore it in release.
      if (isDebuggable) runUpdate()
    } else {
      startEngineFlow()
    }
  }

  override fun onResume() {
    super.onResume()
    // Back from the directory picker / Termux: re-route if the engine came up.
    // I-05: the probe performs network I/O; calling it on the main thread
    // always throws NetworkOnMainThreadException (swallowed) → it would always
    // report "not running" and force a reload losing page state on every
    // return to foreground. Move it to a background thread.
    Thread {
      val running = EngineProbe.check().optBoolean("running", false)
      if (!running) startEngineFlow()
    }.start()
  }

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    pushSystemDark(webView)
  }

  override fun onBackPressed() {
    if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
  }

  private fun configureWebView() {
    // WebView remote debugging (debug builds only): CDP automation on devices
    // and emulators for UI verification.
    if (isDebuggable) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
    webView.settings.apply {
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
    webView.webViewClient = object : WebViewClient() {
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
        if (isSessionExport(url, request.method)) {
          downloadToDownloads(url, null)
          return true
        }
        // Keep only engine-same-origin pages inside the WebView (the privileged
        // bridge and download capability are trusted only for the engine);
        // external links go to the system browser so untrusted pages can never
        // reach the bridge (social engineering / notification spam / arbitrary
        // downloads).
        if (isEngineSource(url)) {
          view.loadUrl(url)
          return true
        }
        openInExternalBrowser(request.url)
        return true
      }

      override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        if (isEngineSource(failingUrl)) showGuide()
      }

      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        pushSystemDark(view)
      }
    }
    // WebView downloads — session-log export (/api/session.export) and other
    // engine-source downloads — all go through the in-app MediaStore path:
    // browser navigations carry Origin:null and are rejected by dsh's /api
    // browser-trust fence (403), while the in-app HttpURLConnection carries no
    // browser markers → the fence lets it through (403 fix, see downloadToDownloads).
    webView.setDownloadListener { url, _userAgent, contentDisposition, _mimeType, _contentLength ->
      downloadToDownloads(url, contentDisposition)
    }
    webView.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams,
      ): Boolean {
        // File uploads go through the system file picker (OpenDocument,
        // multi-select); directoryPicker handles directory picking (workspaces)
        // and the two must stay separate.
        this@MainActivity.filePathCallback?.onReceiveValue(null)
        this@MainActivity.filePathCallback = filePathCallback
        filePicker.launch(emptyArray())
        return true
      }

      override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.confirm()
        return true
      }
    }
    webView.addJavascriptInterface(
      AndroidBridge(
        onPickRequest = { callbackId -> pickDirectoryWithPermissionCheck(callbackId) },
        onKeepScreen = { enable -> keepScreenOn(enable) },
        onNotify = { title, text -> showTestNotification(title, text) },
        onAllFilesAccessRequest = { openAllFilesAccessSettings() },
        pickToken = pickToken,
      ),
      "androidBridge",
    )
    webView.loadUrl(EngineProbe.ENGINE_URL)
  }

  /**
   * SAF directory picking (with an All Files Access walkthrough): the external
   * workspace requires the bash process to reach the picked real path directly;
   * when the permission is missing, jump to the system grant screen and let
   * the page prompt the user to retry.
   */
  private fun pickDirectoryWithPermissionCheck(callbackId: String) {
    // Concurrency guard: reject a new request while one is in flight (the
    // single-slot pendingPickCallback would be overwritten and the earlier
    // engine pick would never settle).
    if (pendingPickCallback != null) {
      webView.evaluateJavascript(
        "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callbackId) + ", null)", null,
      )
      return
    }
    if (android.os.Build.VERSION.SDK_INT < 30) {
      // Android 10 and below have no All Files Access model: the external
      // workspace is unavailable. Report null so the engine-side pick settles
      // as a cancellation — no crash, no silent hang.
      webView.evaluateJavascript(
        "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callbackId) + ", null)", null,
      )
      showTestNotification(
        getString(R.string.notif_workspace_unavailable),
        getString(R.string.notif_workspace_unavailable_detail),
      )
      return
    }
    if (android.os.Environment.isExternalStorageManager()) {
      pendingPickCallback = callbackId
      directoryPicker.launch(null)
      return
    }
    openAllFilesAccessSettings()
    webView.evaluateJavascript(
      "window.__dshBridge?.onPermissionRequired?.()", null,
    )
  }

  /** Open the system All Files Access screen for this app. */
  private fun openAllFilesAccessSettings() {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    try {
      startActivity(
        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
          .setData(Uri.parse("package:$packageName")),
      )
    } catch (_: Exception) {
      // Some OEMs lack the per-app screen; fall back to the global one.
      try {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      } catch (_: Exception) {
        // No entry point at all: ignore silently (the engine side settles as
        // a cancellation).
      }
    }
  }

  /**
   * Download an engine-side URL to the system Downloads directory (session-log
   * ZIP export). API 29+ uses MediaStore.Downloads (no permission needed);
   * older systems are unsupported (real devices are all newer).
   * Only engine-same-origin URLs are accepted (guards against local SSRF /
   * malicious file drops); written streaming with a size cap. The in-app
   * HttpURLConnection carries no browser markers (Origin/sec-fetch-site), so
   * it passes dsh's /api browser-trust fence (the fix for the 403 on browser
   * navigation).
   */
  /** In-flight download guard: dedupes the shouldOverrideUrlLoading and
   *  downloadListener entry points. */
  private val exportDownloading = java.util.concurrent.atomic.AtomicBoolean(false)

  private fun downloadToDownloads(url: String, contentDisposition: String?) {
    if (!isEngineSource(url)) {
      val reason = getString(R.string.notif_engine_only_export)
      showTestNotification(getString(R.string.notif_download_rejected), reason)
      pushExportResult(false, reason)
      return
    }
    if (!exportDownloading.compareAndSet(false, true)) return
    if (Build.VERSION.SDK_INT < 29) {
      val reason = getString(R.string.notif_export_failed_old_os)
      showTestNotification(getString(R.string.notif_export_failed), reason)
      pushExportResult(false, reason)
      exportDownloading.set(false)
      return
    }
    val filename = sanitizeFilename(parseDownloadFilename(url, contentDisposition))
    Thread {
      var conn: HttpURLConnection? = null
      try {
        val c = URL(url).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.requestMethod = "GET"
        if (c.responseCode != HttpURLConnection.HTTP_OK) {
          throw java.io.IOException("HTTP " + c.responseCode)
        }
        var saved: String? = null
        c.inputStream.use { input ->
          saved = saveToDownloadsStreamed(filename, input)
        }
        val finalName = saved
        runOnUiThread {
          val detail = getString(R.string.notif_export_saved_to, finalName)
          showTestNotification(getString(R.string.notif_export_saved), detail)
          pushExportResult(true, detail)
        }
      } catch (t: Throwable) {
        val message = t.message ?: getString(R.string.err_unknown)
        runOnUiThread {
          showTestNotification(getString(R.string.notif_export_failed), message)
          pushExportResult(false, message)
        }
      } finally {
        conn?.disconnect()
        exportDownloading.set(false)
      }
    }.start()
  }

  /** Report the export result to the WebView: the UI plugin shows an in-app
   *  result dialog via window.__dshExportResult. */
  private fun pushExportResult(ok: Boolean, detail: String) {
    val title = if (ok) getString(R.string.export_success) else getString(R.string.export_failed)
    val payload = "{\"ok\":" + ok + ",\"title\":" + jsString(title) + ",\"detail\":" + jsString(detail) + "}"
    webView.post {
      webView.evaluateJavascript(
        "window.__dshExportResult && window.__dshExportResult(" + payload + ")", null,
      )
    }
  }

  /** Write to MediaStore.Downloads (permission-free on Android 10+), streaming
   *  with a 200MB cap. */
  private fun saveToDownloadsStreamed(filename: String, input: java.io.InputStream): String {
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, filename)
      put(MediaStore.Downloads.MIME_TYPE, "application/zip")
      put(MediaStore.Downloads.IS_PENDING, 1)
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw java.io.IOException(getString(R.string.err_create_download))
    try {
      contentResolver.openOutputStream(uri)?.use { out ->
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
          val n = input.read(buf)
          if (n < 0) break
          total += n
          if (total > MAX_DOWNLOAD_BYTES) throw java.io.IOException(getString(R.string.err_export_too_large))
          out.write(buf, 0, n)
        }
      } ?: throw java.io.IOException(getString(R.string.err_write_download))
      values.clear()
      values.put(MediaStore.Downloads.IS_PENDING, 0)
      contentResolver.update(uri, values, null, null)
    } catch (t: Throwable) {
      contentResolver.delete(uri, null, null)
      throw t
    }
    return filename
  }

  /** Sanitize a filename: replace path separators/control characters, cap length. */
  private fun sanitizeFilename(name: String): String {
    val cleaned = name.replace(Regex("[/\\\u0000-\u001f]"), "_").take(200)
    return if (cleaned.isBlank()) "dsh-session-export.zip" else cleaned
  }

  /** Filename: Content-Disposition wins, then the sessionId from the URL, then
   *  a fixed fallback name. */
  private fun parseDownloadFilename(url: String, contentDisposition: String?): String {
    contentDisposition?.let { cd ->
      Regex("filename=\"?([^\";]+)\"?").find(cd)?.groupValues?.get(1)?.let { return it }
    }
    return try {
      val q = URL(url).query ?: ""
      val sid = q.split("&").mapNotNull { seg ->
        val kv = seg.split("=", limit = 2)
        if (kv.size == 2 && kv[0] == "sessionId") kv[1] else null
      }.firstOrNull()
      if (sid != null) "dsh-session-$sid.zip" else "dsh-session-export.zip"
    } catch (_: Exception) {
      "dsh-session-export.zip"
    }
  }

  /** Push the system dark-mode state: some OEM WebViews do not make
   *  prefers-color-scheme follow uiMode (observed on vivo/Android 16); the UI
   *  plugin consumes this bridge value via a matchMedia hook
   *  (window.__dshThemeBridge.setDark) to drive the upstream system theme. */
  private fun pushSystemDark(view: android.webkit.WebView) {
    val dark = (resources.configuration.uiMode and
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
      val js = polyfillsJs ?: assets.open("js/compat-polyfills.js").bufferedReader().use { it.readText() }
        .also { polyfillsJs = it }
      view.evaluateJavascript(js, null)
    } catch (t: Throwable) {
      AppLog.log("web", "polyfill inject failed", t)
    }
  }

  private var polyfillsJs: String? = null

  /**
   * Engine-source check: exact match of the local engine's scheme/host/port
   * (guards against prefix spoofing, e.g. 127.0.0.1:30800 or
   * 127.0.0.1:3080.evil.com being mistaken for the engine source).
   */
  private fun isEngineSource(url: String): Boolean {
    return try {
      val base = Uri.parse(EngineProbe.ENGINE_URL)
      val uri = Uri.parse(url)
      uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port
    } catch (_: Exception) {
      false
    }
  }

  /** Match: engine source + exact session-export path + GET (HEAD is the
   *  front-end preflight and must not trigger a redirect). I-06: the previous
   *  contains-prefix match would also hit /api/session.export.evil; compare
   *  the path exactly instead. */
  private fun isSessionExport(url: String, method: String): Boolean {
    if (method != "GET" || !isEngineSource(url)) return false
    return try {
      Uri.parse(url).path == SESSION_EXPORT_PATH
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Atomic, replay-guarded external-browser open (for non-export external
   * links). Best effort: a failed launch is silent (callers do not read the
   * return value); there is no MediaStore fallback contract here — the only
   * fallback path is the export route (inside downloadToDownloads).
   */
  private val exportLaunching = java.util.concurrent.atomic.AtomicBoolean(false)

  private fun openInExternalBrowser(uri: android.net.Uri): Boolean {
    if (!exportLaunching.compareAndSet(false, true)) return true // in flight: swallow the duplicate trigger
    return try {
      startActivity(Intent(Intent.ACTION_VIEW, uri))
      true
    } catch (_: Exception) {
      // No browser can handle it; callers ignore the result.
      false
    } finally {
      exportLaunching.set(false)
    }
  }

  private fun keepScreenOn(enable: Boolean) {
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val lock = wakeLock ?: power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "dsh:screen",
    ).also { wakeLock = it }
    if (enable && !lock.isHeld) lock.acquire()
    if (!enable && lock.isHeld) lock.release()
  }

  private fun showTestNotification(title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      pendingNotification = title to text
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
    postNotification(title, text)
  }

  /** Actually send the notification (called directly when the permission is
   *  held; the permission-callback re-send also lands here). */
  private fun postNotification(title: String, text: String) {
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(NotificationChannel("dsh", "dsh", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val pending = android.app.PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(
      1,
      NotificationCompat.Builder(this, "dsh")
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build(),
    )
  }

  /** Tactile press feedback: a light scale-down while pressed. */
  private fun attachPressFeedback(view: View) {
    view.setOnTouchListener { v, event ->
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
          v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
      }
      false
    }
  }

  /** Pill button with the accent fill (primary action). */
  private fun accentButton(text: String): Button {
    return Button(this).apply {
      this.text = text
      setTextColor(0xFFFFFFFF.toInt())
      textSize = 14f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      background = getDrawable(com.dshmobile.shell.R.drawable.pill_accent)
      minHeight = (48 * resources.displayMetrics.density).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }
  }

  /** Pill button with a hairline border (secondary action). */
  private fun ghostButton(text: String): Button {
    return Button(this).apply {
      this.text = text
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 14f
      background = getDrawable(com.dshmobile.shell.R.drawable.pill_ghost)
      minHeight = (48 * resources.displayMetrics.density).toInt()
      isAllCaps = false
      stateListAnimator = null
      attachPressFeedback(this)
    }
  }

  /** Full-screen guide: brand, status card, action row. Shown on first launch
   *  (extraction) and when the engine fails while no Harness is reachable. */
  private fun buildGuideView(): LinearLayout {
    val d = resources.displayMetrics.density
    val pad = (24 * d).toInt()
    val guide = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(pad, pad, pad, pad)
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      visibility = View.GONE
      setBackgroundColor(resources.getColor(com.dshmobile.shell.R.color.bg_guide, null))
    }

    // Brand block.
    val logo = android.widget.ImageView(this).apply {
      setImageResource(com.dshmobile.shell.R.mipmap.ic_launcher)
      val size = (56 * d).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
    }
    val brandTitle = TextView(this).apply {
      text = getString(R.string.app_name)
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 26f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding(0, (16 * d).toInt(), 0, 0)
    }
    val brandSub = TextView(this).apply {
      text = getString(R.string.guide_brand_subtitle)
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
      textSize = 13f
      setPadding(0, (4 * d).toInt(), 0, 0)
    }
    val brand = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      setPadding(0, (72 * d).toInt(), 0, 0)
    }
    brand.addView(logo)
    brand.addView(brandTitle)
    brand.addView(brandSub)

    // Status card.
    val statusDot = View(this).apply {
      background = getDrawable(com.dshmobile.shell.R.drawable.status_dot)
      val size = (8 * d).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
    }
    val statusTitle = TextView(this).apply {
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 15f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding((8 * d).toInt(), 0, 0, 0)
    }
    engineStatus = statusTitle
    val statusRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
    }
    statusRow.addView(statusDot)
    statusRow.addView(statusTitle)
    val detail = TextView(this).apply {
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      setPadding(0, (10 * d).toInt(), 0, 0)
      maxLines = 3
      ellipsize = android.text.TextUtils.TruncateAt.END
    }
    statusDetail = detail
    val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      isIndeterminate = true
      progressTintList = android.content.res.ColorStateList.valueOf(0xFF4D6BFE.toInt())
      progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0xFF22304A.toInt())
      visibility = View.GONE
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * d).toInt())
      lp.topMargin = (16 * d).toInt()
      layoutParams = lp
    }
    progressBar = progress
    val cardBody = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding((20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
    }
    cardBody.addView(statusRow)
    cardBody.addView(detail)
    cardBody.addView(progress)
    val errorDetail = TextView(this).apply {
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.error, null))
      textSize = 12f
      typeface = android.graphics.Typeface.MONOSPACE
      setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
      maxLines = 4
      ellipsize = android.text.TextUtils.TruncateAt.END
    }
    errorText = errorDetail
    val errorBlock = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      background = getDrawable(com.dshmobile.shell.R.drawable.inset_bg)
      visibility = View.GONE
      val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
      lp.topMargin = (16 * d).toInt()
      layoutParams = lp
    }
    errorBlock.addView(errorDetail)
    this.errorBlock = errorBlock
    val card = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      background = getDrawable(com.dshmobile.shell.R.drawable.card_bg)
      layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
      ).apply {
        topMargin = (40 * d).toInt()
        leftMargin = (8 * d).toInt()
        rightMargin = (8 * d).toInt()
      }
    }
    card.addView(cardBody)
    card.addView(errorBlock)

    // Actions: one primary (launch / retry) + secondary utilities.
    val primary = accentButton(getString(R.string.button_launch_engine)).apply {
      visibility = View.GONE
      setOnClickListener {
        // After setup (manual gate) the primary action launches the engine;
        // in error states it re-runs the setup flow.
        if (manualLaunchRequired) launchEngine() else startEngineFlow()
      }
    }
    primaryButton = primary
    val update = ghostButton(getString(R.string.button_check_update)).apply {
      setOnClickListener {
        UpdateManager(this@MainActivity).checkAndApply { status ->
          runOnUiThread { showGuideStatus(status, null, true) }
        }
      }
    }
    val copyLog = ghostButton(getString(R.string.button_copy_log)).apply {
      setOnClickListener {
        val copied = AppLog.copyToClipboard(this@MainActivity)
        showTestNotification(
          getString(R.string.notif_log_copied),
          getString(R.string.notif_log_copied_detail, copied.lines().size.toString()),
        )
      }
    }
    val back = ghostButton(getString(R.string.button_back_to_harness)).apply {
      visibility = View.GONE
      setOnClickListener { showWeb() }
    }
    backButton = back
    val actions = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (32 * d).toInt()
        leftMargin = (8 * d).toInt()
        rightMargin = (8 * d).toInt()
      }
    }
    actions.addView(primary)
    actions.addView(update)
    actions.addView(copyLog)
    actions.addView(back)
    repeat(actions.childCount - 1) { i ->
      (actions.getChildAt(i).layoutParams as LinearLayout.LayoutParams).bottomMargin = (10 * d).toInt()
    }

    // Wizard step indicator: runtime → container → launch.
    val steps = buildStepIndicator()
    val spacer = View(this).apply {
      layoutParams = LinearLayout.LayoutParams(0, 0).apply { weight = 1f }
    }
    guide.addView(brand)
    guide.addView(steps)
    guide.addView(spacer)
    guide.addView(card)
    guide.addView(actions)
    return guide
  }

  /** Horizontal three-step indicator (runtime → container → launch). */
  private fun buildStepIndicator(): LinearLayout {
    val d = resources.displayMetrics.density
    val names = listOf(
      getString(R.string.step_runtime),
      getString(R.string.step_container),
      getString(R.string.step_launch),
    )
    val dots = arrayOfNulls<View>(3)
    val labels = arrayOfNulls<TextView>(3)
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_HORIZONTAL
      layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (36 * d).toInt()
        leftMargin = (24 * d).toInt()
        rightMargin = (24 * d).toInt()
      }
    }
    val circleSize = (28 * d).toInt()
    for (i in 0..2) {
      val dot = TextView(this).apply {
        textSize = 14f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = android.view.Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
      }
      dots[i] = dot
      val label = TextView(this).apply {
        text = names[i]
        textSize = 11f
        setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_secondary, null))
        gravity = android.view.Gravity.CENTER
        setPadding(0, (6 * d).toInt(), 0, 0)
      }
      labels[i] = label
      val cell = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
      }
      cell.addView(dot)
      cell.addView(label)
      row.addView(cell)
      if (i < 2) {
        val line = View(this).apply {
          background = getDrawable(com.dshmobile.shell.R.drawable.divider)
          val lp = LinearLayout.LayoutParams(0, (1 * d).toInt())
          lp.weight = 1f
          lp.topMargin = (circleSize / 2 - (1 * d).toInt()).toInt()
          layoutParams = lp
        }
        row.addView(line)
      }
    }
    stepDots = dots.map { it!! }.toTypedArray()
    stepLabels = labels.map { it!! }.toTypedArray()
    return row
  }

  /** Render the wizard steps: done (filled + check), active (ring), pending (outline). */
  private fun renderSteps(done: Int, active: Int) {
    for (i in stepDots.indices) {
      val dot = stepDots[i] as TextView
      dot.background = when {
        i < done -> getDrawable(com.dshmobile.shell.R.drawable.step_done)
        i == active -> getDrawable(com.dshmobile.shell.R.drawable.step_active)
        else -> getDrawable(com.dshmobile.shell.R.drawable.step_pending)
      }
      val label = stepLabels[i]
      label.setTextColor(resources.getColor(
        if (i <= done) com.dshmobile.shell.R.color.text_primary
        else com.dshmobile.shell.R.color.text_secondary, null,
      ))
      if (i < done) {
        dot.text = "✓"
        dot.setTextColor(0xFFFFFFFF.toInt())
      } else {
        dot.text = ""
      }
    }
  }

  /** Thin cold-start bar overlaying the Harness: pulse dot + status, taps to
   *  open the full-screen guide. */
  private fun buildTopStatusBar(): LinearLayout {
    val d = resources.displayMetrics.density
    val dot = View(this).apply {
      background = getDrawable(com.dshmobile.shell.R.drawable.status_dot)
      val size = (8 * d).toInt()
      layoutParams = LinearLayout.LayoutParams(size, size)
    }
    topPulseDot = dot
    val label = TextView(this).apply {
      setTextColor(resources.getColor(com.dshmobile.shell.R.color.text_primary, null))
      textSize = 13f
      typeface = android.graphics.Typeface.DEFAULT_BOLD
      setPadding((10 * d).toInt(), 0, 0, 0)
    }
    topStatusLabel = label
    val bar = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = android.view.Gravity.CENTER_VERTICAL
      setPadding((20 * d).toInt(), (10 * d).toInt(), (20 * d).toInt(), (10 * d).toInt())
      setBackgroundColor(0xE6FFFFFF.toInt())
      visibility = View.GONE
      setOnClickListener { openGuideFromTopBar() }
    }
    bar.addView(dot)
    bar.addView(label)
    bar.elevation = (6 * d)
    return bar
  }

  private fun openGuideFromTopBar() {
    guideFromTopBar = true
    primaryButton?.visibility = View.GONE
    backButton?.visibility = View.VISIBLE
    showGuide()
  }

  /** Set the guide's status row; shows the spinner when the status is
   *  indeterminate progress (extraction, container install, engine start). */
  private fun showGuideStatus(title: String, detail: String?, busy: Boolean) {
    engineStatus?.text = title
    statusDetail?.text = detail
    statusDetail?.visibility = if (detail.isNullOrEmpty()) View.GONE else View.VISIBLE
    progressBar?.visibility = if (busy) View.VISIBLE else View.GONE
    if (busy) {
      errorBlock?.visibility = View.GONE
      primaryButton?.visibility = View.GONE
    }
  }

  /** Ready state: everything installed → show the Launch engine button. */
  private fun showLaunchReady() {
    showGuideStatus(
      getString(R.string.status_ready_to_launch),
      getString(R.string.status_ready_to_launch_detail),
      false,
    )
    renderSteps(3, 3)
    primaryButton?.apply {
      visibility = View.VISIBLE
      text = getString(R.string.button_launch_engine)
      isEnabled = true
    }
  }

  private fun showGuideError(title: String) {
    showGuideStatus(title, null, false)
    primaryButton?.apply {
      visibility = View.VISIBLE
      text = getString(R.string.button_retry)
      isEnabled = true
    }
    // Surface the tail of the diagnostic log as inline error context.
    val tail = AppLog.tail(1200)
    errorText?.text = tail
    errorBlock?.visibility = if (tail.isBlank()) View.GONE else View.VISIBLE
  }

  /** Cross-fade between the guide surface and the Harness web view. */
  private fun showGuide() {
    webView.animate().alpha(0f).setDuration(150).start()
    webView.visibility = View.GONE
    stopTopBarPulse()
    topStatusBar.visibility = View.GONE
    guideView.visibility = View.VISIBLE
    guideView.animate().alpha(1f).setDuration(200).start()
  }

  private fun showWeb() {
    guideFromTopBar = false
    backButton?.visibility = View.GONE
    guideView.animate().alpha(0f).setDuration(150).withEndAction {
      guideView.visibility = View.GONE
    }.start()
    webView.visibility = View.VISIBLE
    webView.animate().alpha(1f).setDuration(200).start()
    // The WebView may have rendered an error page before the engine was
    // ready (engine boot takes seconds); reload now that it answers.
    webView.reload()
    if (topStatusBar.visibility == View.VISIBLE) hideTopBar()
  }

  /** Slide the thin status bar in (cold start over the Harness). */
  private fun showTopBar(title: String) {
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    webView.animate().alpha(1f).setDuration(150).start()
    topStatusLabel?.text = title
    topStatusBar.visibility = View.VISIBLE
    topStatusBar.animate().alpha(1f).setDuration(200).start()
    startTopBarPulse()
  }

  private fun hideTopBar() {
    stopTopBarPulse()
    topStatusBar.animate().alpha(0f).setDuration(250).withEndAction {
      topStatusBar.visibility = View.GONE
      topStatusBar.alpha = 1f
    }.start()
  }

  /** Breathing alpha on the status-bar dot (engine working). */
  private fun startTopBarPulse() {
    val dot = topPulseDot ?: return
    val animator = android.animation.ValueAnimator.ofFloat(1f, 0.25f)
    animator.duration = 900
    animator.repeatMode = android.animation.ValueAnimator.REVERSE
    animator.repeatCount = android.animation.ValueAnimator.INFINITE
    animator.addUpdateListener { dot.alpha = it.animatedValue as Float }
    animator.start()
    topPulseAnimator = animator
  }

  private fun stopTopBarPulse() {
    topPulseAnimator?.cancel()
    topPulseAnimator = null
    topPulseDot?.alpha = 1f
  }

  override fun onDestroy() {
    super.onDestroy()
    stopTopBarPulse()
    engineManager.stopEngine()
  }

  /**
   * Engine-first flow: use an already-running engine (Termux or prior
   * embedded), else extract the embedded snapshot and start the embedded
   * engine, then poll until the web service answers.
   */
  private fun startEngineFlow() {
    // Both onCreate and the following onResume trigger this flow; the
    // in-flight guard prevents a double-threaded extract/start race (observed
    // on device: a double start kills the engine process).
    if (!engineFlowRunning.compareAndSet(false, true)) return
    AppLog.log("boot", "engine flow start")
    Thread {
      try {
        val probe = EngineProbe.check()
        AppLog.log("boot", "probe before start: " + probe.optBoolean("running", false) +
          " latency=" + probe.optInt("latencyMs", -1) + " error=" + probe.optString("error", "-"))
        if (probe.optBoolean("running", false)) {
          runOnUiThread { showWeb() }
          return@Thread
        }
        var setupRan = false
        if (!engineManager.engineReady) {
          runOnUiThread {
            showGuide()
            renderSteps(0, 0)
            showGuideStatus(getString(R.string.status_first_extract), null, true)
          }
          AppLog.log("boot", "extracting snapshot to " + engineManager.usrDir)
          val ok = engineManager.extractSnapshot { done, _ ->
            runOnUiThread {
              // done is extracted bytes; total is the archive bytes (different
              // baselines) — show only the extracted amount.
              showGuideStatus(
                getString(R.string.status_extracting, done / 1024 / 1024), null, true,
              )
            }
          }
          if (!ok) {
            runOnUiThread {
              showGuideError(getString(R.string.status_extract_failed))
            }
            AppLog.log("boot", "extract FAILED")
            return@Thread
          }
          setupRan = true
          AppLog.log("boot", "extract ok, engineReady=" + engineManager.engineReady)
        }
        // Step 2 — Ubuntu container is mandatory: install it unless ready.
        val proot = ProotRuntime(this, engineManager.usrDir, File(engineManager.ensureDshDataHome(), "workspace"))
        if (!proot.rootfsReady()) {
          runOnUiThread {
            renderSteps(if (setupRan) 1 else 1, 1)
            showGuideStatus(
              getString(R.string.status_container_installing),
              getString(R.string.status_container_installing_detail),
              true,
            )
          }
          AppLog.log("boot", "installing mandatory Ubuntu container")
          val ok = RootfsDownloader.install(applicationContext)
          if (!ok) {
            AppLog.log("boot", "container install failed")
            runOnUiThread {
              renderSteps(1, 1)
              showGuideError(getString(R.string.container_install_failed))
            }
            return@Thread
          }
          setupRan = true
          AppLog.log("boot", "container ready")
        }
        // Step 3 — after any setup, the user launches the engine manually;
        // a fully provisioned install (snapshot + container) starts straight
        // into the Harness.
        if (setupRan) {
          runOnUiThread {
            manualLaunchRequired = true
            showGuide()
            renderSteps(2, 2)
            showLaunchReady()
          }
          AppLog.log("boot", "setup done, waiting for manual launch")
          return@Thread
        }
        // Quick path: everything provisioned → cold start under the thin bar.
        runOnUiThread {
          webView.visibility = View.VISIBLE
          showTopBar(getString(R.string.status_engine_starting))
        }
        launchEngineInternal()
      } catch (t: Throwable) {
        AppLog.log("boot", "engine flow exception", t)
        runOnUiThread {
          showGuideError(getString(R.string.status_engine_start_failed))
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** Manual launch action (guide primary button). */
  private fun launchEngine() {
    if (!launchInFlight.compareAndSet(false, true)) return
    primaryButton?.isEnabled = false
    showGuideStatus(getString(R.string.status_engine_starting), null, true)
    Thread {
      launchEngineInternal()
      launchInFlight.set(false)
    }.start()
  }

  /** Start the engine and poll until the web service answers. */
  private fun launchEngineInternal() {
    try {
      if (!engineManager.startEngine()) {
        runOnUiThread {
          showGuideError(getString(R.string.status_engine_start_failed))
        }
        AppLog.log("boot", "startEngine() returned false")
        return
      }
      // Poll up to 30s for the web service.
      var reached = false
      for (i in 0..30) {
        if (EngineProbe.check().optBoolean("running", false)) {
          reached = true
          startEngineService()
          applyShizukuKeepAlive()
          runOnUiThread { showWeb() }
          break
        }
        Thread.sleep(1000)
      }
      if (!reached) {
        AppLog.log("boot", "engine web service not reachable within 30s poll")
        val proc = EngineManager.engineProcess
        if (proc == null) {
          AppLog.log("boot", "engine process: null")
        } else if (!proc.isAlive) {
          val code = try { proc.exitValue() } catch (_: Exception) { -1 }
          AppLog.log("boot", "engine process DEAD exitValue=" + code)
        } else {
          AppLog.log("boot", "engine process alive but web service down")
        }
        AppLog.includeFile(java.io.File(filesDir, "engine.log"), "engine.log")
        runOnUiThread {
          showGuideError(getString(R.string.status_engine_timeout))
        }
      } else {
        AppLog.log("boot", "engine reachable, showing web")
      }
    } catch (t: Throwable) {
      AppLog.log("boot", "engine launch exception", t)
      runOnUiThread {
        showGuideError(getString(R.string.status_engine_start_failed))
      }
    }
  }

  /** Run the runtime snapshot update; status mirrored to a file for adb verification. */
  private fun runUpdate() {
    val statusFile = java.io.File(filesDir, "update-status.txt")
    val manager = UpdateManager(this)
    manager.checkAndApply { status ->
      runOnUiThread {
        showGuide()
        showGuideStatus(status, null, true)
      }
      try {
        statusFile.appendText(status + "\n")
      } catch (_: Exception) {
      }
    }
  }

  /** Start the foreground service (engine keep-alive + watchdog). */
  private fun startEngineService() {
    try {
      startForegroundService(Intent(this, EngineService::class.java))
    } catch (_: Exception) {
      // Foreground-service start limits: service will start on next launch.
    }
  }

  /** Best-effort Shizuku keep-alive boost; outcome logged only. */
  private fun applyShizukuKeepAlive() {
    try {
      Thread {
        val result = ShizukuSupport.status(this)
        Log.i("dsh-shizuku", result)
      }.start()
    } catch (_: Throwable) {
    }
  }
}
