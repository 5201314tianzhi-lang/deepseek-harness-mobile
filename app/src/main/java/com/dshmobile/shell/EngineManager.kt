package com.dshmobile.shell

import android.content.Context
import android.os.Environment
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

  /**
   * Shared persistent directory: /storage/emulated/0/Documents/dshdata.
   * User data (settings, plugin configs, session history, attachments) lands
   * here by default so it is visible to file managers, can be backed up, and
   * survives app uninstall/reinstall.
   */
  val dshDataDir: File
    get() {
      val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        ?: File(context.filesDir, "dshdata-fallback")
      return File(publicDocs, "dshdata")
    }
  private val nodeBin = File(usrDir, "bin/node")
  private val dshBin = File(usrDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

  val engineReady: Boolean get() = nodeBin.exists()

  /**
   * Process-wide start guard. MainActivity and EngineService each construct
   * their own EngineManager instance, so instance fields are not shared —
   * double-start protection must live on the companion object (CAS).
   */
  private val starting: Boolean
    get() = STARTING.get()

  /**
   * Extract the bundled snapshot archive into filesDir. Runs on any thread;
   * callers own the progress UI.
   * @param onProgress bytesDone, bytesTotal.
   * @returns true on success.
   */
  fun extractSnapshot(onProgress: (Long, Long) -> Unit): Boolean {
    return try {
      val fd = context.assets.openFd("snapshot.tar.xz")
      AppLog.log("extract", "archive size=" + fd.length + " bytes, dest=" + usrDir.parentFile)
      SnapshotExtractor.extract(context.assets.open("snapshot.tar.xz"), fd.length, usrDir.parentFile, onProgress)
      homeDir.mkdirs()
      installBundledPtyNode()
      AppLog.log("extract", "done")
      true
    } catch (t: Throwable) {
      Log.e(TAG, "snapshot extract failed", t)
      AppLog.log("extract", "FAILED", t)
      false
    }
  }

  /**
   * Replace the snapshot's node-pty native module with the one cross-compiled
   * in CI (node v24.18.0 headers + NDK, static libc++). The snapshot copy
   * fails to dlopen on some devices ("Failed to load native module: pty.node"
   * → engine exits); the bundled copy depends only on system libraries.
   */
  private fun installBundledPtyNode() {
    val abi = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64-v8a" else "x86_64"
    val asset = "pty/pty-node-$abi.so"
    val target = File(
      usrDir,
      "lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/build/Release/pty.node",
    )
    try {
      context.assets.open(asset).use { input ->
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> input.copyTo(out) }
      }
      // Executable for dlopen, not writable (W^X compliance, like the
      // extractor strips on executables).
      target.setExecutable(true, true)
      target.setWritable(false, false)
      AppLog.log("engine", "installed bundled pty.node (" + target.length() + " bytes) -> " + target.absolutePath)
    } catch (t: Throwable) {
      AppLog.log("engine", "bundled pty.node install failed; keeping the snapshot copy", t)
    }
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
  fun ensureDshDataHome(): File {
    val dshData = dshDataDir
    val privateDsh = File(homeDir, ".dsh")
    // Android < 11 has no All Files Access model and the public Documents
    // directory is unwritable (scoped storage); migration is impossible, so
    // keep DSH_HOME fully private. Observed on Android 10 (Huawei): the
    // migration used to fail with FileNotFoundException on every start.
    if (android.os.Build.VERSION.SDK_INT < 30) {
      AppLog.log("migrate", "skipped: Android < 11 (no All Files Access), public dshdata unwritable")
      return privateDsh
    }
    val marker = File(dshData, ".migrated-from")
    if (privateDsh.isDirectory) {
      if (marker.exists()) {
        // Re-link (I-10): uninstall wipes the private symlinks but the public
        // data and marker persist. Idempotently rebuild the private links so
        // the data becomes visible again; missing public targets are skipped
        // and already-correct links cost nothing.
        relink(File(privateDsh, "sessions"), File(dshData, "sessions"))
        relink(File(privateDsh, "storages"), File(dshData, "storages"))
        relink(File(privateDsh, "attachments"), File(dshData, "attachments"))
        for (profile in listOf("web", "headless")) {
          for (name in listOf("cordis.yml", "cordis.patch.yml")) {
            relinkFile(File(privateDsh, "profiles/$profile/$name"), File(dshData, "profiles/$profile/$name"))
          }
        }
      } else {
        try {
          dshData.mkdirs()
          // 1) settings.yaml: public entity + plugin config.path points at it (see patch)
          copyFileIfExists(File(privateDsh, "settings.yaml"), File(dshData, "settings.yaml"))
          // 2) directory-level data: move wholesale + private symlink
          relocateDir(File(privateDsh, "sessions"), File(dshData, "sessions"))
          relocateDir(File(privateDsh, "storages"), File(dshData, "storages"))
          relocateDir(File(privateDsh, "attachments"), File(dshData, "attachments"))
          // 3) plugin configs: copy to public + replace private with a symlink (dsh only reads)
          for (profile in listOf("web", "headless")) {
            for (name in listOf("cordis.yml", "cordis.patch.yml")) {
              val sf = File(privateDsh, "profiles/$profile/$name")
              if (sf.exists() && sf.isFile) {
                val pf = File(dshData, "profiles/$profile/$name")
                pf.parentFile?.mkdirs()
                sf.copyTo(pf, overwrite = true)
                sf.delete()
                try {
                  java.nio.file.Files.createSymbolicLink(sf.toPath(), pf.toPath())
                } catch (t: Throwable) {
                  // Symlink failed (edge case): keep the private entity, discard the public copy.
                  pf.delete()
                  Log.w(TAG, "symlink failed for " + sf.absolutePath + "; keeping private copy")
                }
              }
            }
          }
          marker.writeText(privateDsh.absolutePath)
          Log.i(TAG, "dshdata migration done -> " + dshData.absolutePath)
        } catch (t: Throwable) {
          // A failed migration must not block startup: DSH_HOME stays private,
          // the engine still works, and the migration retries next time.
          Log.e(TAG, "dshdata migration failed", t)
          AppLog.log("migrate", "dshdata migration FAILED", t)
        }
      }
    }
    return privateDsh
  }

  /**
   * Re-link (I-10): when the public target exists, ensure the private item is
   * a symlink pointing at it. Already-correct symlink → no-op; private real
   * empty directory (fresh shell created by dsh after reinstall) → replaced
   * with a symlink; private non-empty directory (may hold new data) →
   * conservatively skipped.
   */
  private fun relink(src: File, dst: File) {
    if (!dst.exists()) return
    val srcPath = src.toPath()
    if (java.nio.file.Files.isSymbolicLink(srcPath)) {
      if (src.canonicalPath == dst.canonicalPath) return
      src.delete()
    } else if (src.exists()) {
      val children = src.listFiles()
      if (children != null && children.isEmpty()) {
        src.delete()
      } else {
        Log.w(TAG, "relink skipped (non-empty): " + src.absolutePath)
        return
      }
    }
    src.parentFile?.mkdirs()
    try {
      java.nio.file.Files.createSymbolicLink(srcPath, dst.toPath())
    } catch (t: Throwable) {
      Log.w(TAG, "relink failed for " + src.absolutePath, t)
    }
  }

  /** Re-link (I-10), file variant: when the public target exists, replace the
   *  private file with a symlink pointing at it. */
  private fun relinkFile(src: File, dst: File) {
    if (!dst.isFile) return
    val srcPath = src.toPath()
    if (java.nio.file.Files.isSymbolicLink(srcPath)) {
      if (src.canonicalPath == dst.canonicalPath) return
      src.delete()
    } else if (src.exists()) {
      src.delete()
    }
    src.parentFile?.mkdirs()
    try {
      java.nio.file.Files.createSymbolicLink(srcPath, dst.toPath())
    } catch (t: Throwable) {
      Log.w(TAG, "relinkFile failed for " + src.absolutePath, t)
    }
  }

  /** Copy a single file when it exists. */
  private fun copyFileIfExists(src: File, dst: File) {
    if (src.isFile) {
      dst.parentFile?.mkdirs()
      src.copyTo(dst, overwrite = true)
    }
  }

  /** Move a directory wholesale to public (copy+delete-source when a cross-
   *  mount rename fails), then leave a symlink at the original location. */
  private fun relocateDir(src: File, dst: File) {
    if (!src.isDirectory || dst.exists()) return
    dst.parentFile?.mkdirs()
    if (!src.renameTo(dst)) {
      copyTree(src, dst, emptySet())
      src.deleteRecursively()
    }
    try {
      java.nio.file.Files.createSymbolicLink(src.toPath(), dst.toPath())
    } catch (t: Throwable) {
      Log.w(TAG, "symlink failed for dir " + src.absolutePath)
    }
  }

  /** Recursively copy a directory tree (real file contents). */
  private fun copyTree(src: File, dst: File, skip: Set<String>) {
    src.listFiles()?.forEach { f ->
      if (f.name in skip) return@forEach
      val target = File(dst, f.name)
      if (f.isDirectory) {
        target.mkdirs()
        copyTree(f, target, skip)
      } else {
        f.copyTo(target, overwrite = true)
      }
    }
  }

  /** Start the dsh web engine from the embedded snapshot. */
  fun startEngine(port: Int = 3080): Boolean {
    // LD_PRELOAD: prefer the bundled universal exec-reroute hook (covers every
    // SELinux domain and vendor, unlike the snapshot's termux-exec which only
    // handles untrusted_app_25/27). Fall back to the snapshot hook when the
    // bundled one is missing (should not happen on release builds).
    val bundledHook = File(context.applicationInfo.nativeLibraryDir, "libexec-hook.so")
    val snapshotHook = File(usrDir, "lib/libtermux-exec-ld-preload.so")
    val preloadPath: String
    val termuxExecEnv: Map<String, String>
    if (bundledHook.exists()) {
      preloadPath = bundledHook.absolutePath
      termuxExecEnv = emptyMap()
      AppLog.log("engine", "using bundled exec hook: " + bundledHook.absolutePath)
    } else {
      preloadPath = snapshotHook.absolutePath
      termuxExecEnv = mapOf(
        "TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE" to "force",
        "TERMUX_EXEC__EXECVE_CALL__INTERCEPT" to "1",
      )
      AppLog.log("engine", "bundled exec hook missing, using snapshot termux-exec: " + snapshotHook.absolutePath)
      if (!snapshotHook.exists()) {
        Log.e(TAG, "engine start failed: no exec hook available at " + snapshotHook.absolutePath)
        AppLog.log("engine", "start refused: no exec hook available")
        return false
      }
    }
    // Executability diagnostics: an exec EACCES on the engine binary is the
    // #1 cause of "engine start timeout". Record the actual permission bits.
    AppLog.log("engine", "node.canExecute=" + nodeBin.canExecute() +
      " hook.canExecute=" + File(preloadPath).canExecute() +
      " node.length=" + nodeBin.length() + " usr=" + usrDir.canRead() + "/" + usrDir.canExecute())
    val now = System.currentTimeMillis()
    // Process-level CAS: only one concurrent caller actually starts the engine
    // (device-observed EADDRINUSE on double start).
    if (!STARTING.compareAndSet(false, true)) return true
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
    return try {
      val args = arrayOf(
        nodeBin.absolutePath, "--expose-internals", dshBin.absolutePath, "web", "--port", port.toString(),
      )
      val env = mapOf(
        "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
        "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
        "HOME" to homeDir.absolutePath,
        // DSH_HOME stays in the private domain (public FUSE forbids symlinks,
        // so the profiles/node_modules flat fallback cannot live there); user
        // data is routed to public Documents/dshdata via migration + symlinks
        // and plugin configs (see ensureDshDataHome).
        "DSH_HOME" to ensureDshDataHome().absolutePath,
        // OPENSSL_CONF: the snapshot's openssl library has the Termux build
        // path (/data/data/com.termux/files/usr/etc/tls) compiled in, which is
        // unreadable from this package — node aborts at startup when it cannot
        // load the config (observed exit code 13). Point it at the config file
        // shipped inside our own tree when present; otherwise leave it unset
        // (OpenSSL tolerates a missing default config, not a broken OPENSSL_CONF).
        // os.tmpdir() falls back to the baked-in Termux tmp on Android
        // (unwritable from the app domain); keep spill inside filesDir.
        "TMPDIR" to File(homeDir, "tmp").apply { mkdirs() }.absolutePath,
        // LD_PRELOAD: the exec-reroute hook. Every process loaded via
        // linker64 inherits it, so child execs are rerouted across the whole
        // engine tree. The snapshot's termux-exec variant additionally needs
        // the TERMUX_EXEC__* env (see termuxExecEnv above).
        "LD_PRELOAD" to preloadPath,
        "TERMUX__ROOTFS" to usrDir.parentFile.absolutePath,
        "TERMUX__PREFIX" to usrDir.absolutePath,
        "TERMUX_APP__DATA_DIR" to context.filesDir.parentFile.absolutePath,
        "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/com.dshmobile.shell",
        "TERMUX_VERSION" to "0.118.3",
        // Auth token for the directory-pick bridge endpoint (validated by the
        // web-compat plugin as x-dsh-pick-token).
        "DSH_PICK_TOKEN" to (pickToken ?: ""),
      ) + opensslConfEnv() + termuxExecEnv
      engineProcess = startWithArgs(args, env)
      // The cooldown is only set after a real start; a failed path does not
      // consume the window so a retry can happen immediately.
      EngineManager.lastStartAttemptAt = now
      AppLog.log("engine", "started port=" + port +
        " node=" + nodeBin.absolutePath + " arch=" + android.os.Build.SUPPORTED_ABIS.joinToString(","))
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      AppLog.log("engine", "start FAILED", t)
      AppLog.includeFile(File(context.filesDir, "engine.log"), "engine.log")
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
      AppLog.log("engine", "direct exec denied (" + e.message + "), falling back to linker64")
      build(listOf("/system/bin/linker64") + args.toList()).start()
    }
  }

  /** OPENSSL_CONF env override: point at the snapshot's own config when it
   *  exists (the compiled-in Termux path is unreadable from this package).
   *  Returns a single-entry map or an empty map.
   *
   *  NOTE: the archive entries carry a usr/ prefix and are extracted into
   *  filesDir (usrDir.parentFile), so the file lives at filesDir/usr/etc/... —
   *  the candidates below are relative to usrDir and must NOT repeat "usr".
   */
  private fun opensslConfEnv(): Map<String, String> {
    for (candidate in listOf(
      "etc/tls/openssl.cnf",
      "etc/ssl/openssl.cnf",
    )) {
      val f = File(usrDir, candidate)
      if (f.isFile) {
        AppLog.log("engine", "OPENSSL_CONF -> " + f.absolutePath)
        return mapOf("OPENSSL_CONF" to f.absolutePath)
      }
    }
    AppLog.log("engine", "no openssl.cnf found in snapshot (checked " +
      listOf("etc/tls/openssl.cnf", "etc/ssl/openssl.cnf").joinToString(", ") +
      " under " + usrDir.absolutePath + "); leaving OPENSSL_CONF unset")
    return emptyMap()
  }

  /** Stop the engine process (best-effort). */
  fun stopEngine() {    EngineManager.engineProcess?.destroy()
    EngineManager.engineProcess = null
    // Reset the cooldown after a manual stop: the user returning to the
    // foreground should be allowed to restart immediately.
    EngineManager.lastStartAttemptAt = 0
  }

  companion object {
    private const val TAG = "dsh-engine"

    /** Watchdog/retry backoff: no new start within this window of the last
     *  attempt. Cold node boot on the phone takes 20-45s (plugin tree + first
     *  bind); a 5s watchdog poll would otherwise race a healthy boot and
     *  double-start the engine (device-observed EADDRINUSE). 90s covers the
     *  slowest observed boot with margin. */
    const val START_COOLDOWN_MS = 90_000L

    /** Process-level start CAS: visible across EngineManager instances
     *  (double-start race protection). */
    val STARTING = java.util.concurrent.atomic.AtomicBoolean(false)

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
