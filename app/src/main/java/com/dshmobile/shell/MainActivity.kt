package com.dshmobile.shell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import java.io.File

/**
 * Shell activity: wires the Harness WebView, the boot wizard and the engine
 * lifecycle together. Pure orchestration — WebView plumbing lives in
 * HarnessWebView, the wizard UI in GuideWizard, picking in PickerBridge,
 * export in ExportFlow, notifications in NotificationHelper.
 */
class MainActivity : ComponentActivity() {

  private lateinit var harness: HarnessWebView
  private lateinit var wizard: GuideWizard
  private lateinit var picker: PickerBridge
  private lateinit var export: ExportFlow
  private lateinit var notifyHelper: NotificationHelper

  /** One-time auth token for the directory-pick bridge (random per process
   *  start; held in the engine env and the JS bridge). */
  private val pickToken: String = java.util.UUID.randomUUID().toString()

  private val engineManager by lazy { EngineManager(this, pickToken) }
  private val engineFlowRunning = java.util.concurrent.atomic.AtomicBoolean(false)
  /** Engine launch in flight (guards the Launch button against double taps). */
  private val launchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
  /** True while the setup wizard asked the user to press Launch manually. */
  private var manualLaunchRequired = false
  /** Screen-on wake lock: reuse a single instance (I-04 — otherwise the lock
   *  could never be released and multiple locks would leak). */
  private var wakeLock: PowerManager.WakeLock? = null

  /** Container smoke probe: runs a real command inside the proot container
   *  through the same chain the agent uses (node → bash wrapper → proot). */
  private val containerProbe by lazy {
    ContainerProbe(
      engineManager.usrDir,
      engineManager.homeDir,
      engineManager.nodeBin,
      engineManager.execHookPath,
      engineManager.opensslConfEnv(),
    )
  }

  /** Debug-only update trigger (see onCreate); derived from the package so
   *  a package rename never leaves a stale action literal. */
  private val actionUpdate: String get() = packageName + ".action.UPDATE"


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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AppLog.init(this)
    logDeviceInfo()

    notifyHelper = NotificationHelper(this)
    val onNotify: (String, String) -> Unit = { title, text ->
      runOnUiThread { notifyHelper.showTestNotification(title, text) }
    }
    export = ExportFlow(this, onNotify, { ok, detail -> pushExportResult(ok, detail) })
    picker = PickerBridge(
      this,
      onDirectoryPicked = { callbackId, path ->
        harness.postScript(
          "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callbackId) + ", " +
            (path?.let { jsString(it) } ?: "null") + ")",
        )
      },
      onPermissionRequired = { harness.postScript("window.__dshBridge?.onPermissionRequired?.()") },
      notify = onNotify,
    )
    harness = HarnessWebView(
      this, picker, export, onNotify,
      onEngineError = { showGuide() },
      onKeepScreen = { keepScreenOn(it) },
      pickToken = pickToken,
    )
    wizard = GuideWizard(
      this, harness.view,
      onPrimaryAction = { if (manualLaunchRequired) launchEngine() else startEngineFlow() },
      onCheckUpdate = { statusCb -> UpdateManager(this).checkAndApply(statusCb) },
      onCopyLog = { copyLog() },
      onBackToHarness = { showWeb() },
    )

    val root = FrameLayout(this).apply {
      setBackgroundColor(0xFFFFFFFF.toInt())
    }
    root.addView(harness.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    root.addView(wizard.topStatusBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP))
    root.addView(wizard.guideView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    setContentView(root)
    harness.configure()

    // Quick path: snapshot AND mandatory container already provisioned →
    // go straight to the Harness; the cold start is covered by the thin
    // status bar, not the full-screen guide.
    val provisioned = File(filesDir, DshPaths.USR_DIR + "/" + DshPaths.NODE_BIN).isFile &&
      File(filesDir, DshPaths.ROOTFS_DIR + "/" + DshPaths.ROOTFS_BASH).isFile
    if (provisioned) {
      harness.view.visibility = View.VISIBLE
    } else {
      wizard.guideView.visibility = View.VISIBLE
    }
    // Testable update trigger: adb am start -n .../.MainActivity -a com.dshmobile.shell.action.UPDATE
    if (intent?.action == actionUpdate) {
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
    harness.pushSystemDark()
  }

  override fun onBackPressed() {
    if (harness.canGoBack()) harness.goBack() else super.onBackPressed()
  }

  override fun onDestroy() {
    super.onDestroy()
    wizard.onDestroy()
    engineManager.stopEngine()
  }

  /** Report the export result to the WebView: the UI plugin shows an in-app
   *  result dialog via window.__dshExportResult. */
  private fun pushExportResult(ok: Boolean, detail: String) {
    val title = if (ok) getString(R.string.export_success) else getString(R.string.export_failed)
    val payload = "{\"ok\":" + ok + ",\"title\":" + jsString(title) + ",\"detail\":" + jsString(detail) + "}"
    harness.postScript("window.__dshExportResult && window.__dshExportResult(" + payload + ")")
  }

  private fun copyLog() {
    val copied = AppLog.copyToClipboard(this)
    notifyHelper.showTestNotification(
      getString(R.string.notif_log_copied),
      getString(R.string.notif_log_copied_detail, copied.lines().size.toString()),
    )
  }

  private fun keepScreenOn(enable: Boolean) {
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    val lock = wakeLock ?: power.newWakeLock(
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, DshPaths.WAKE_LOCK_TAG,
    ).also { wakeLock = it }
    if (enable && !lock.isHeld) lock.acquire()
    if (!enable && lock.isHeld) lock.release()
  }

  // ---- Engine flow --------------------------------------------------------

  /**
   * Engine-first flow: use an already-running engine (Termux or prior
   * embedded), else extract the embedded snapshot and install the mandatory
   * Ubuntu container, then either wait for the manual Launch action (any
   * setup ran) or cold-start straight under the thin status bar.
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
            wizard.renderSteps(0, 0)
            wizard.showGuideStatus(getString(R.string.status_first_extract), null, true)
          }
          AppLog.log("boot", "extracting snapshot to " + engineManager.usrDir)
          val ok = engineManager.extractSnapshot { done, _ ->
            runOnUiThread {
              // done is extracted bytes; total is the archive bytes (different
              // baselines) — show only the extracted amount.
              wizard.showGuideStatus(
                getString(R.string.status_extracting, done / 1024 / 1024), null, true,
              )
            }
          }
          if (!ok) {
            runOnUiThread {
              wizard.showGuideError(getString(R.string.status_extract_failed))
            }
            AppLog.log("boot", "extract FAILED")
            return@Thread
          }
          setupRan = true
          AppLog.log("boot", "extract ok, engineReady=" + engineManager.engineReady)
        }
        // Step 2 — Ubuntu container is mandatory: install when missing, then
        // initialize (proot runtime + wrapper) and smoke-test the full chain
        // (proot deps, PROOT_TMP_DIR, wrapper, rootfs bash) with a real
        // in-container command. A failing container counts as an engine start
        // failure — the engine is not started without it. Every sub-step is
        // logged under boot: so the container init is visible in diagnostics.
        val proot = ProotRuntime(this, engineManager.usrDir, File(engineManager.ensureDshDataHome(), "workspace"))
        if (!proot.rootfsReady()) {
          runOnUiThread {
            wizard.renderSteps(1, 1)
            wizard.showGuideStatus(
              getString(R.string.status_container_installing),
              getString(R.string.status_container_installing_detail),
              true,
            )
          }
          AppLog.log("boot", "container init: rootfs missing, downloading")
          val ok = RootfsDownloader.install(applicationContext)
          if (!ok) {
            AppLog.log("boot", "container init FAILED: rootfs install failed")
            runOnUiThread {
              wizard.renderSteps(1, 1)
              wizard.showGuideError(getString(R.string.container_install_failed))
            }
            return@Thread
          }
          setupRan = true
          AppLog.log("boot", "container init: rootfs installed")
        }
        AppLog.log("boot", "container init: proot runtime=" + proot.ensureInitialized() +
          " rootfs=" + proot.rootfsReady())
        val smoke = containerProbe.smokeTest()
        if (smoke != null) {
          AppLog.log("boot", "container init FAILED: " + smoke)
          runOnUiThread {
            wizard.renderSteps(1, 1)
            wizard.showGuideError(getString(R.string.status_container_init_failed))
          }
          return@Thread
        }
        AppLog.log("boot", "container init: smoke test pass")
        // Step 3 — after any setup, the user launches the engine manually;
        // a fully provisioned install (snapshot + container) starts straight
        // into the Harness.
        if (setupRan) {
          runOnUiThread {
            manualLaunchRequired = true
            showGuide()
            wizard.renderSteps(2, 2)
            wizard.showLaunchReady()
          }
          AppLog.log("boot", "setup done, waiting for manual launch")
          return@Thread
        }
        // Quick path: everything provisioned → cold start under the thin bar.
        runOnUiThread {
          harness.view.visibility = View.VISIBLE
          wizard.showTopBar(getString(R.string.status_engine_starting))
        }
        launchEngineInternal()
      } catch (t: Throwable) {
        AppLog.log("boot", "engine flow exception", t)
        runOnUiThread {
          wizard.showGuide()
          wizard.showGuideError(getString(R.string.status_engine_start_failed))
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** Manual launch action (guide primary button). */
  private fun launchEngine() {
    if (!launchInFlight.compareAndSet(false, true)) return
    wizard.showGuideStatus(getString(R.string.status_engine_starting), null, true)
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
          wizard.showGuide()
          wizard.showGuideError(getString(R.string.status_engine_start_failed))
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
        AppLog.includeFile(java.io.File(filesDir, DshPaths.ENGINE_LOG), DshPaths.ENGINE_LOG)
        runOnUiThread {
          wizard.showGuide()
          wizard.showGuideError(getString(R.string.status_engine_timeout))
        }
      } else {
        AppLog.log("boot", "engine reachable, showing web")
      }
    } catch (t: Throwable) {
      AppLog.log("boot", "engine launch exception", t)
      runOnUiThread {
        wizard.showGuide()
        wizard.showGuideError(getString(R.string.status_engine_start_failed))
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
        wizard.showGuideStatus(status, null, true)
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
    wizard.showWeb()
  }

  private fun showGuide() {
    wizard.showGuide()
  }
}
