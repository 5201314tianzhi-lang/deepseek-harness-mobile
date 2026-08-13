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
    probeAndRoute()
  }

  override fun onResume() {
    super.onResume()
    probeAndRoute()
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
    val openTermux = Button(this).apply {
      text = "打开 Termux"
      setOnClickListener { launchTermux() }
    }
    val retry = Button(this).apply {
      text = "重试"
      setOnClickListener { probeAndRoute() }
    }
    guide.addView(engineStatus)
    guide.addView(openTermux)
    guide.addView(retry)
    return guide
  }

  private fun launchTermux() {
    val intent = packageManager.getLaunchIntentForPackage("com.termux")
    if (intent != null) startActivity(intent)
  }

  private fun probeAndRoute() {
    Thread {
      val result = EngineProbe.check()
      val running = result.optBoolean("running", false)
      runOnUiThread {
        if (running) {
          showWeb()
        } else {
          engineStatus.text = "未检测到 dsh 引擎（127.0.0.1:3080）\n请先在 Termux 中启动服务。"
          showGuide()
        }
      }
    }.start()
  }

  private fun showWeb() {
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
  }

  private fun showGuide() {
    webView.visibility = View.GONE
    guideView.visibility = View.VISIBLE
  }
}