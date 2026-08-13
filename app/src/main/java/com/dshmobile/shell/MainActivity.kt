package com.dshmobile.shell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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

/** Shell activity: WebView over the local dsh engine + engine guide fallback. */
class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private lateinit var guideView: LinearLayout
  private lateinit var engineStatus: TextView
  private lateinit var progressText: TextView
  private val engineManager by lazy { EngineManager(this) }
  private val engineFlowRunning = java.util.concurrent.atomic.AtomicBoolean(false)
  private var pendingPickCallback: String? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    if (uri != null && pendingPickCallback != null) {
      val path = AndroidBridge.resolvePickedPath(uri)
      webView.evaluateJavascript(
        "window.__dshBridge?.onDirectoryPicked?.(" + jsString(pendingPickCallback!!) + ", " + jsString(path) + ")", null,
      )
    }
    pendingPickCallback = null
  }

  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* test channel only */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    webView = WebView(this).apply { id = View.generateViewId() }
    root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    guideView = buildGuideView()
    root.addView(guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
    configureWebView()
    startEngineFlow()
  }

  override fun onResume() {
    super.onResume()
    // Back from the directory picker / Termux: re-route if the engine came up.
    if (!EngineProbe.check().optBoolean("running", false)) startEngineFlow()
  }

  override fun onDestroy() {
    super.onDestroy()
    engineManager.stopEngine()
  }

  override fun onBackPressed() {
    if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
  }

  private fun configureWebView() {
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        view.loadUrl(request.url.toString())
        return true
      }

      override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        if (failingUrl.startsWith(EngineProbe.ENGINE_URL)) showGuide()
      }
    }
    webView.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams,
      ): Boolean {
        this@MainActivity.filePathCallback?.onReceiveValue(null)
        this@MainActivity.filePathCallback = filePathCallback
        directoryPicker.launch(null)
        return true
      }

      override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.confirm()
        return true
      }
    }
    webView.addJavascriptInterface(
      AndroidBridge(
        onPickRequest = { callbackId -> pendingPickCallback = callbackId; directoryPicker.launch(null) },
        onKeepScreen = { enable -> keepScreenOn(enable) },
        onNotify = { title, text -> showTestNotification(title, text) },
      ),
      "androidBridge",
    )
    webView.loadUrl(EngineProbe.ENGINE_URL)
  }

  private fun keepScreenOn(enable: Boolean) {
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val wakeLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "dsh:screen")
    if (enable && !wakeLock.isHeld) wakeLock.acquire()
    if (!enable && wakeLock.isHeld) wakeLock.release()
  }

  private fun showTestNotification(title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
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
      text = "打开 Termux"
      setOnClickListener { launchTermux() }
    }
    val retry = Button(this).apply {
      text = "重试"
      setOnClickListener { startEngineFlow() }
    }
    guide.addView(engineStatus)
    guide.addView(progressText)
    guide.addView(openTermux)
    guide.addView(retry)
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
    // onCreate 与随后的 onResume 都会触发本流程；in-flight 守卫防止
    // 双线程竞态解压/启动（设备实证：双启动导致引擎进程死亡）。
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
          engineStatus.text = "首次启动：正在解压运行时（约 70MB）…"
        }
        val ok = engineManager.extractSnapshot { done, total ->
          runOnUiThread {
            engineStatus.text = "正在解压运行时… " + done / 1024 / 1024 + "/" + total / 1024 / 1024 + " MB"
          }
        }
        if (!ok) {
          runOnUiThread {
            engineStatus.text = "运行时解压失败，请重试。"
            showGuide()
          }
          return@Thread
        }
      }
      if (!engineManager.startEngine()) {
        runOnUiThread {
          engineStatus.text = "引擎启动失败，请重试。"
          showGuide()
        }
        return@Thread
      }
      // Poll up to 30s for the web service.
      for (i in 0..30) {
        if (EngineProbe.check().optBoolean("running", false)) {
          runOnUiThread { showWeb() }
          return@Thread
        }
        Thread.sleep(1000)
      }
      runOnUiThread {
        engineStatus.text = "引擎启动超时，请重试。"
        showGuide()
      }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
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