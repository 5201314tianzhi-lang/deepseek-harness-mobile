package com.dshmobile.shell

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Owns the single embedded Debian glibc rootfs: first-launch extraction into
 * filesDir/rootfs and the dsh engine process lifecycle. The engine (node +
 * dsh) runs INSIDE the rootfs via proot — the agent shell and the engine
 * share one container, no wrappers, no exec hooks.
 */
class EngineManager(
  private val context: Context,
) {
  val rootfsDir = File(context.filesDir, DshPaths.ROOTFS_DIR)
  val homeDir = File(context.filesDir, "home")

  /**
   * Shared persistent directory: /storage/emulated/0/Documents/dshdata.
   * User data (settings, plugin configs, session history, attachments) lands
   * here by default so it is visible to file managers, can be backed up, and
   * survives app uninstall/reinstall.
   */
  val dshDataDir: File
    get() {
      val publicDocs =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
          ?: File(context.filesDir, "dshdata-fallback")
      return File(publicDocs, "dshdata")
    }
  private val prootRuntime by lazy { ProotRuntime(context) }

  /** Engine entry binary inside the rootfs. */
  private val dshEntry = File(rootfsDir, DshPaths.DSH_ENTRY)

  /** True once the rootfs is extracted and holds the dsh entry. */
  val engineReady: Boolean get() = dshEntry.isFile

  /**
   * Extract the bundled rootfs archive into filesDir. Runs on any thread;
   * callers own the progress UI.
   * @param onProgress bytesDone, bytesTotal.
   * @returns true on success.
   */
  fun extractRootfs(onProgress: (Long, Long) -> Unit): Boolean =
    try {
      context.assets.openFd(DshPaths.ROOTFS_ASSET).use { fd ->
        // Rootfs archive holds usr/, root/, etc/ … which must land under the
        // single-runtime rootfs directory (rootfsDir = filesDir/rootfs): proot
        // is later started with -r rootfsDir, and the engine entry is resolved
        // as File(rootfsDir, DSH_ENTRY). Extracting straight into filesDir made
        // files/ hold the rootfs directly while proot looked for files/rootfs,
        // which does not exist → "proot warning: can't sanitize binding
        // .../files/rootfs: No such file or directory".
        rootfsDir.mkdirs()
        AppLog.log("extract", "archive size=" + fd.length + " bytes, dest=" + rootfsDir)
        SnapshotExtractor.extract(
          context.assets.open(DshPaths.ROOTFS_ASSET),
          fd.length,
          rootfsDir,
          onProgress,
        )
      }
      homeDir.mkdirs()
      AppLog.log("extract", "done, engineReady=" + engineReady)
      true
    } catch (t: Throwable) {
      Log.e(TAG, "rootfs extract failed", t)
      AppLog.log("extract", "FAILED", t)
      false
    }

  /**
   * Ensure the shared persistent directory is wired up (idempotent; call from
   * a background thread).
   *
   * Design (issue apk#8): DSH_HOME itself MUST stay in the private domain —
   * dsh maintains a flat-module fallback under `$DSH_HOME/profiles/node_modules`
   * on every start (one symlink per dependency pointing at the engine install),
   * and the public storage (/storage/emulated/0) FUSE layer forbids symlink
   * creation (observed Permission denied), so a wholesale migration would
   * break the engine.
   *
   * Instead we do item-level migration: move user data to Documents/dshdata
   * and place a symlink at the original private location (symlinks work in the
   * app-private domain, verified on device), so dsh reads/writes land on the
   * public directory through the symlink:
   *  - settings.yaml: copied to public (the settings-file config.path in
   *    cordis.patch.yml points straight at the public file, avoiding the
   *    atomic-rewrite-replaces-symlink problem)
   *  - sessions/, storages/, attachments/: moved wholesale + private symlink
   *    (writing files inside a directory does not replace the directory symlink)
   *  - profiles/{web,headless}/cordis.yml + cordis.patch.yml: copied to public
   *    + private replaced with a symlink (dsh only reads these two files)
   *  - .credentials.yaml (API key): NOT migrated — the public FUSE forces mode
   *    660, which the credentials-local permission check rejects, and the key
   *    would be exposed to other apps; it stays as the private entity, pointed
   *    to by the credentials path in cordis.patch.yml.
   * After migration the private locations hold only symlinks/kept entities;
   * the public copies are never deleted.
   */
  /**
   * Keep DSH_HOME fully inside the rootfs private f2fs and never migrate it to
   * public /storage/emulated/0 (FUSE).
   *
   * Why this whole migration is now removed: the engine writes session logs
   * under $DSH_HOME/sessions via an atomic "write .tmp then link() to the final
   * name" scheme. When sessions/ was a symlink pointing at the public FUSE
   * directory (the old migration built exactly that), link(2) hit the sdcard
   * FUSE hard-link ban and the engine failed with "EACCES: permission denied,
   * link". Hard links need one real filesystem; only the app-private f2fs
   * (/data/data/<pkg>) supports them. So DSH_HOME must stay private. Any leftover
   * host-absolute symlink dirs from a previous install are rolled back to real
   * rootfs directories (public copies, if any, are pulled back so no data is lost).
   */
  fun ensureDshDataHome(): File {
    val dshData = dshDataDir
    val privateDsh = File(rootfsDir, DshPaths.CONTAINER_DSH_HOME)
    privateDsh.mkdirs()
    try {
      restoreDirsLocal(privateDsh, dshData)
      restoreProfilesLocal(privateDsh, dshData)
    } catch (t: Throwable) {
      Log.w(TAG, "ensureDshDataHome local-restore step failed", t)
    }
    return privateDsh
  }

  /** Roll any previously-migrated data dir (now a host-absolute symlink) back
   *  to a real directory inside the rootfs; copy the public backup back if
   *  present so pre-existing data survives. */
  private fun restoreDirsLocal(
    privateDsh: File,
    dshData: File,
  ) {
    for (name in listOf("sessions", "storages", "attachments")) {
      val priv = File(privateDsh, name)
      val pub = File(dshData, name)
      if (java.nio.file.Files.isSymbolicLink(priv.toPath())) {
        priv.delete()
        if (pub.isDirectory) {
          priv.mkdirs()
          copyTree(pub, priv)
        }
      } else if (!priv.isDirectory) {
        priv.mkdirs()
      }
    }
  }

  /**
   * Ensure the container's `profiles/{web,headless}/cordis.yml` (and
   * cordis.patch.yml) exist as REAL files inside the rootfs (under DSH_HOME),
   * never as symlinks pointing at the HOST absolute public path.
   *
   * Why: node's prepareProfile() rewrites cordis.yml on every boot. If the file
   * is a symlink to /storage/emulated/0/Documents/dshdata/... that path cannot
   * be resolved inside the proot container (container root is the rootfs), so
   * the write fails with ENOENT and the engine exits 1 (observed). Restoring a
   * real file reproduces the "first boot" state where the engine runs fine.
   * A public backup (if any) is copied back so prior settings survive.
   */
  private fun restoreProfilesLocal(
    privateDsh: File,
    dshData: File,
  ) {
    for (profile in listOf("web", "headless")) {
      val dir = File(privateDsh, "profiles/$profile")
      dir.mkdirs()
      for (name in listOf("cordis.yml", "cordis.patch.yml")) {
        val sf = File(dir, name)
        val pf = File(dshData, "profiles/$profile/$name")
        if (java.nio.file.Files.isSymbolicLink(sf.toPath())) {
          // Replace the dangling host-absolute symlink with a real local file.
          sf.delete()
          if (pf.isFile) {
            try {
              sf.createNewFile()
              pf.copyTo(sf, overwrite = true)
            } catch (t: Throwable) {
              Log.w(TAG, "restore " + sf.absolutePath + " from public failed", t)
            }
          }
        } else if (!sf.isFile) {
          // Fresh boot with no profile yet: leave it absent — node generates it.
          if (pf.isFile) pf.copyTo(sf, overwrite = true)
        }
      }
    }
  }
  /** Recursively copy a directory tree (real file contents). */
  private fun copyTree(
    src: File,
    dst: File,
  ) {
    src.listFiles()?.forEach { f ->
      val target = File(dst, f.name)
      if (f.isDirectory) {
        target.mkdirs()
        copyTree(f, target)
      } else {
        f.copyTo(target, overwrite = true)
      }
    }
  }

  /** Start the dsh web engine inside the single runtime rootfs. */
  fun startEngine(port: Int = 3080): Boolean {
    val now = System.currentTimeMillis()
    // Process-level CAS: only one concurrent caller actually starts the engine
    // (device-observed EADDRINUSE on double start). The losing caller returns
    // immediately — even the proot check is skipped for it (single-flight).
    if (!STARTING.compareAndSet(false, true)) return true
    if (!prootRuntime.ensureProot()) {
      AppLog.log("engine", "start refused: proot runtime unavailable")
      STARTING.set(false)
      return false
    }
    // I-11: when the engine process is dead (or was never started) there is no
    // double-start race — clear the cooldown immediately, otherwise the 5s
    // watchdog polls would keep hitting the 90s cooldown window and crash
    // recovery would take up to 90s.
    if (engineProcess?.isAlive != true) EngineManager.lastStartAttemptAt = 0
    // Cooldown window: no new start within this window of the last attempt
    // (cold node boot takes 20-45s).
    if (now - EngineManager.lastStartAttemptAt < START_COOLDOWN_MS) {
      STARTING.set(false)
      return true
    }
    // Cooldown expired but the process is still alive: it is hung (a healthy
    // boot always answers within the 90s window) and holds the port — kill it
    // or every subsequent start dies with EADDRINUSE and the watchdog loops
    // forever restarting a corpse.
    EngineManager.engineProcess?.let { p ->
      if (p.isAlive) {
        AppLog.log("engine", "previous engine process alive past cooldown, killing hung process")
        p.destroyForcibly()
        try {
          p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
      }
    }
    EngineManager.engineProcess = null
    return try {
      // projects 工作目录放宿主私有目录（rootfs 之外），proot 用 -b 把它
      // bind 进容器 /root/projects。注意 SRC 必须是 rootfs 外的真实宿主路径，
      // 否则 proot "can't sanitize binding" 报错。
      val projectsDir = File(context.filesDir, DshPaths.PROJECTS_DIR).apply { mkdirs() }
      val (args, env) = prootRuntime.buildEngineArgs(rootfsDir, projectsDir, port, pickToken)
      engineProcess = startWithArgs(args, env)
      // The cooldown is only set after a real start; a failed path does not
      // consume the window so a retry can happen immediately.
      EngineManager.lastStartAttemptAt = now
      AppLog.log(
        "engine",
        "started port=" + port +
          " proot=" + prootRuntime.prootBin.absolutePath +
          " rootfs=" + rootfsDir.absolutePath + " arch=" +
          android.os.Build.SUPPORTED_ABIS
            .joinToString(","),
      )
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      AppLog.log("engine", "start FAILED", t)
      AppLog.includeFile(File(context.filesDir, DshPaths.ENGINE_LOG), DshPaths.ENGINE_LOG)
      false
    } finally {
      STARTING.set(false)
    }
  }

  /**
   * Spawn the engine, falling back to the system linker when the direct exec
   * is denied. proot is NDK/bionic-linked, so /system/bin/linker64 can load
   * it; the rootfs's glibc binaries are never exec'd from app data (they run
   * under proot inside the container).
   */
  private fun startWithArgs(
    args: Array<String>,
    env: Map<String, String>,
  ): Process {
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
      AppLog.log("engine", "direct exec denied (" + e.message + "), falling back to linker64")
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  /** Stop the engine process (best-effort). Guarded by the start CAS so a
   *  concurrent start cannot race a destroy-then-null. */
  fun stopEngine() {
    if (!STARTING.compareAndSet(false, true)) {
      AppLog.log("engine", "stop skipped: engine start in progress")
      return
    }
    try {
      EngineManager.engineProcess?.let { p ->
        p.destroy()
        try {
          p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
      }
      EngineManager.engineProcess = null
      // Reset the cooldown after a manual stop: the user returning to the
      // foreground should be allowed to restart immediately.
      EngineManager.lastStartAttemptAt = 0
    } finally {
      STARTING.set(false)
    }
  }

  companion object {
    private const val TAG = "dsh-engine"

    /**
     * Process-wide auth token for the directory-pick bridge. Single source of
     * truth: MainActivity's WebView bridge and every EngineManager instance
     * (including the EngineService watchdog's) read the same value, so an
     * engine restart never leaves the bridge token mismatched. Random per
     * process start, exactly as before — just shared.
     */
    val pickToken: String =
      java.util.UUID
        .randomUUID()
        .toString()

    /** Watchdog/retry backoff: no new start within this window of the last
     *  attempt. Cold node boot on the phone takes 20-45s (plugin tree + first
     *  bind); a 5s watchdog poll would otherwise race a healthy boot and
     *  double-start the engine (device-observed EADDRINUSE). 90s covers the
     *  slowest observed boot with margin. */
    const val START_COOLDOWN_MS = 90_000L

    /** Process-level start CAS: visible across EngineManager instances
     *  (double-start race protection). */
    val STARTING =
      java.util.concurrent.atomic
        .AtomicBoolean(false)

    /** Epoch ms of the last real start; baseline for the watchdog cooldown. */
    @Volatile
    var lastStartAttemptAt: Long = 0

    /** Engine process, shared at the process level (I-11): MainActivity and
     *  EngineService hold separate EngineManager instances whose instance
     *  fields are invisible to each other — like STARTING, this lives on the
     *  companion so both instances can see and manage the same process. */
    @Volatile
    var engineProcess: Process? = null
  }
}
