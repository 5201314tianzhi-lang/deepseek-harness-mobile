package com.dshmobile.shell

import android.content.Context
import android.util.Log
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Owns the embedded Termux environment snapshot: first-launch extraction into
 * filesDir/usr and the dsh engine process lifecycle (PATH/LD_LIBRARY_PATH/HOME
 * injected explicitly — the snapshot is self-sufficient, no Termux app needed).
 */
class EngineManager(private val context: Context, private val pickToken: String? = null) {

  val usrDir = File(context.filesDir, "usr")
  val homeDir = File(context.filesDir, "home")
  private val nodeBin = File(usrDir, "bin/node")
  private val dshBin = File(usrDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
  private var engineProcess: Process? = null

  val engineReady: Boolean get() = nodeBin.exists()

  /** 进程级启动守卫（MainActivity 与 EngineService 各自 new EngineManager，
   *  实例字段互不可见——双启动竞态必须用 companion 级 CAS）。 */
  private val starting: Boolean
    get() = STARTING.get()

  /** Last start attempt (epoch ms); watchdog backoff window. */
  @Volatile
  private var lastStartAttemptAt = 0L

  /**
   * Extract the bundled snapshot archive into filesDir. Runs on any thread;
   * callers own the progress UI.
   * @param onProgress bytesDone, bytesTotal.
   * @returns true on success.
   */
  fun extractSnapshot(onProgress: (Long, Long) -> Unit): Boolean {
    return try {
      val fd = context.assets.openFd("snapshot.tar.xz")
      SnapshotExtractor.extract(context.assets.open("snapshot.tar.xz"), fd.length, usrDir.parentFile, onProgress)
      homeDir.mkdirs()
      true
    } catch (t: Throwable) {
      Log.e(TAG, "snapshot extract failed", t)
      false
    }
  }

  /** Start the dsh web engine from the embedded snapshot. */
  fun startEngine(port: Int = 3080): Boolean {
    // LD_PRELOAD 依赖快照内的 termux-exec 库：缺失时所有子进程 exec 会失败，
    // 且叠加冷却窗口 = 引擎静默停摆 90s——启动前显式断言，缺失即 loud fail。
    val preload = File(usrDir, "lib/libtermux-exec-ld-preload.so")
    if (!preload.exists()) {
      Log.e(TAG, "engine start failed: termux-exec preload missing at " + preload.absolutePath)
      return false
    }
    val now = System.currentTimeMillis()
    // 进程级 CAS：并发调用只有一个能真正启动（设备实证 EADDRINUSE 双启动）。
    if (!STARTING.compareAndSet(false, true)) return true
    // 冷却窗口：上次尝试后 90s 内不重复启动（冷启动 boot 需 20-45s）。
    if (now - lastStartAttemptAt < START_COOLDOWN_MS) {
      STARTING.set(false)
      return true
    }
    return try {
      val args = arrayOf(
        nodeBin.absolutePath, "--expose-internals", dshBin.absolutePath, "web", "--port", port.toString(),
      )
      val env = mapOf(
        "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
        "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
        "HOME" to homeDir.absolutePath,
        // os.tmpdir() falls back to the baked-in Termux tmp on Android
        // (unwritable from the app domain); keep spill inside filesDir.
        "TMPDIR" to File(homeDir, "tmp").apply { mkdirs() }.absolutePath,
        // Android 16 forbids exec of app-data ELF regardless of targetSdk
        // (observed on Android 16/vivo: direct exec EACCES even at targetSdk
        // 34). Termux's execve hook re-routes denied execs through
        // /system/bin/linker64 (same mechanism as JNI libs); the snapshot
        // ships libtermux-exec-*-ld-preload.so. The hook only rewrites for
        // untrusted_app_25/27 SELinux domains, so force mode is required.
        "LD_PRELOAD" to preload.absolutePath,
        "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
        "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
        "TERMUX__ROOTFS" to usrDir.parentFile.absolutePath,
        "TERMUX__PREFIX" to usrDir.absolutePath,
        "TERMUX_APP__DATA_DIR" to context.filesDir.parentFile.absolutePath,
        "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/com.dshmobile.shell",
        "TERMUX_VERSION" to "0.118.3",
        // 目录选择桥端点鉴权 token（web-compat 插件校验 x-dsh-pick-token）。
        "DSH_PICK_TOKEN" to (pickToken ?: ""),
      )
      engineProcess = startWithArgs(args, env)
      // 冷却只在真实启动后写入：失败路径不占用冷却窗口（可立即重试）。
      lastStartAttemptAt = now
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      false
    } finally {
      STARTING.set(false)
    }
  }

  /**
   * Spawn the engine, falling back to the system linker when the direct exec
   * is denied: Android 15+ apps targeting SDK 35+ may not exec app-data ELF
   * binaries, but loading them through /system/bin/linker64 is the same
   * mechanism as native libraries (always permitted for app data).
   */
  private fun startWithArgs(args: Array<String>, env: Map<String, String>): Process {
    val log = File(context.filesDir, "engine.log")
    fun build(argv: List<String>): ProcessBuilder =
      ProcessBuilder(argv).also { b ->
        b.environment().putAll(env)
        b.redirectErrorStream(true)
        b.redirectOutput(log)
      }
    return try {
      build(args.toList()).start()
    } catch (e: java.io.IOException) {
      if (e.message?.contains("Permission denied") != true) throw e
      Log.w(TAG, "direct exec denied, falling back to linker64: " + e.message)
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  /** Stop the engine process (best-effort). */
  fun stopEngine() {
    engineProcess?.destroy()
    engineProcess = null
    // 手动停止后重置冷却：用户回前台应立即允许重新启动。
    lastStartAttemptAt = 0
  }

  companion object {
    private const val TAG = "dsh-engine"

    /** Watchdog/retry backoff: no new start within this window of the last
     *  attempt. Cold node boot on the phone takes 20-45s (plugin tree + first
     *  bind); a 5s watchdog poll would otherwise race a healthy boot and
     *  double-start the engine (device-observed EADDRINUSE). 90s covers the
     *  slowest observed boot with margin. */
    const val START_COOLDOWN_MS = 90_000L

    /** 进程级启动 CAS：跨 EngineManager 实例可见（双启动竞态防护）。 */
    val STARTING = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 上次真实启动时刻（epoch ms）；watchdog 冷却窗口基准。 */
    @Volatile
    var lastStartAttemptAt: Long = 0
  }
}