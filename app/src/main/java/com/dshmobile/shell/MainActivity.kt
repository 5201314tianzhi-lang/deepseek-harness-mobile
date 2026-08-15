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
  /** One-time auth token for the directory-pick bridge (random per process
   *  start; held in the engine env and the JS bridge). */
  private val pickToken: String = java.util.UUID.randomUUID().toString()
  private lateinit var engineStatus: TextView
  private lateinit var progressText: TextView
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

  /** AGP 8 does not generate BuildConfig by default; use the debuggable flag. */
  private val isDebuggable: Boolean
    get() = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

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
    val root = FrameLayout(this)
    webView = WebView(this).apply { id = View.generateViewId() }
    root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    guideView = buildGuideView()
    root.addView(guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
    configureWebView()
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

  override fun onDestroy() {
    super.onDestroy()
    engineManager.stopEngine()
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

  private fun buildGuideView(): LinearLayout {
    val padding = (24 * resources.displayMetrics.density).toInt()
    val guide = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
      gravity = android.view.Gravity.CENTER
      visibility = View.GONE
    }
    engineStatus = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, padding) }
    progressText = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, padding); visibility = View.GONE }
    val openTermux = Button(this).apply {
      text = getString(R.string.button_open_termux)
      setOnClickListener { launchTermux() }
    }
    val retry = Button(this).apply {
      text = getString(R.string.button_retry)
      setOnClickListener { startEngineFlow() }
    }
    val update = Button(this).apply {
      text = getString(R.string.button_check_update)
      setOnClickListener {
        UpdateManager(this@MainActivity).checkAndApply { status ->
          runOnUiThread { engineStatus.text = status }
        }
      }
    }
    guide.addView(engineStatus)
    guide.addView(progressText)
    guide.addView(openTermux)
    guide.addView(retry)
    guide.addView(update)
    return guide
  }

  private fun launchTermux() {
    val intent = packageManager.getLaunchIntentForPackage("com.termux")
    if (intent != null) startActivity(intent)
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
    Thread {
      try {
        if (EngineProbe.check().optBoolean("running", false)) {
          runOnUiThread { showWeb() }
          return@Thread
        }
        if (!engineManager.engineReady) {
          runOnUiThread {
            progressText.visibility = View.VISIBLE
            guideView.visibility = View.VISIBLE
            engineStatus.text = getString(R.string.status_first_extract)
          }
          val ok = engineManager.extractSnapshot { done, total ->
            runOnUiThread {
              // done is extracted bytes, total is the archive bytes — different
              // baselines; show only the extracted amount.
              engineStatus.text = getString(R.string.status_extracting, done / 1024 / 1024)
            }
          }
          if (!ok) {
            runOnUiThread {
              engineStatus.text = getString(R.string.status_extract_failed)
              showGuide()
            }
            return@Thread
          }
        }
        if (!engineManager.startEngine()) {
          runOnUiThread {
            engineStatus.text = getString(R.string.status_engine_start_failed)
            showGuide()
          }
          return@Thread
        }
        // Poll up to 30s for the web service.
        for (i in 0..30) {
          if (EngineProbe.check().optBoolean("running", false)) {
            startEngineService()
            applyShizukuKeepAlive()
            runOnUiThread { showWeb() }
            return@Thread
          }
          Thread.sleep(1000)
        }
        runOnUiThread {
          engineStatus.text = getString(R.string.status_engine_timeout)
          showGuide()
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** Run the runtime snapshot update; status mirrored to a file for adb verification. */
  private fun runUpdate() {
    val statusFile = java.io.File(filesDir, "update-status.txt")
    val manager = UpdateManager(this)
    manager.checkAndApply { status ->
      runOnUiThread {
        engineStatus.text = status
        progressText.visibility = View.VISIBLE
        guideView.visibility = View.VISIBLE
        webView.visibility = View.GONE
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

  private fun showWeb() {
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    // The WebView may have rendered an error page before the engine was
    // ready (engine boot takes seconds); reload now that it answers.
    webView.reload()
  }

  private fun showGuide() {
    webView.visibility = View.GONE
    guideView.visibility = View.VISIBLE
  }
}
