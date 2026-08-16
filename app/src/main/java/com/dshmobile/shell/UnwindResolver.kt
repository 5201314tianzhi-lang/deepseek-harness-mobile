package com.dshmobile.shell

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Node runtime diagnostics for the embedded snapshot: probes whether
 * node-pty's native module (pty.node) actually loads inside the real node
 * environment and resolves a library that supplies the missing C++ unwind
 * symbol (_Unwind_Resume) for the engine LD_PRELOAD.
 *
 * The snapshot's libc++_shared.so exports __cxa_* but not the unwind runtime
 * (Termux links libunwind statically), so dlopen of pty.node fails on every
 * device. The preferred provider is the bundled libunwind-patch.so
 * (archive-linked from the snapshot's own libunwind.a in CI); system
 * libraries are probed as fallbacks for ROMs that ship one.
 */
class UnwindResolver(
  private val context: Context,
  private val usrDir: File,
  private val homeDir: File,
  private val nodeBin: File,
) {
  /** System library found that makes pty.node loadable (null = none). */
  @Volatile
  private var unwindLib: String? = null

  val resolved: String? get() = unwindLib

  /** OPENSSL_CONF for the snapshot's own config (the Termux build path is
   *  unreadable from this package — node aborts at startup when it cannot
   *  load the config). Empty map when the snapshot has no config. */
  fun opensslConfEnv(): Map<String, String> {
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
    AppLog.log(
      "engine",
      "no openssl.cnf found in snapshot (checked " +
        listOf("etc/tls/openssl.cnf", "etc/ssl/openssl.cnf").joinToString(", ") +
        " under " + usrDir.absolutePath + "); leaving OPENSSL_CONF unset",
    )
    return emptyMap()
  }

  /**
   * Probe pty.node loading inside the real node environment (same env as the
   * engine): Java's System.load cannot represent it (no LD_LIBRARY_PATH, no
   * node symbols). This reports the actual error node-pty's loader swallows —
   * missing dependency vs NAPI/ABI mismatch vs loader path issue.
   */
  fun probeInNodeIfPresent() {
    try {
      val pty = ptyNodeFile() ?: return
      resolveIfNeeded(pty)
    } catch (t: Throwable) {
      AppLog.log("diag", "node pty probe failed to run", t)
    }
  }

  /** Resolve lazily but guard against concurrent probe rounds: extraction and
   *  engine-start threads can both reach here — without a lock each spawns
   *  duplicate node probe processes (wasted, same result). */
  @Synchronized
  fun resolveIfNeeded(pty: File): String? {
    if (unwindLib != null) return unwindLib
    for (candidate in unwindCandidates()) {
      val (code, out) = probePtyNode(pty, candidate)
      AppLog.log(
        "diag",
        "node pty probe (preload=" + (candidate ?: "none") + ") exit=" + code +
          " output: " + out.trim().take(300),
      )
      if (out.contains("NODE_PTY_OK")) {
        unwindLib = candidate
        if (candidate != null) {
          AppLog.log("diag", "pty.node loads with preload=" + candidate)
        }
        return unwindLib
      }
    }
    return null
  }

  private fun ptyNodeFile(): File? {
    val f =
      File(
        usrDir,
        DshPaths.PTY_NODE,
      )
    return if (f.isFile) f else null
  }

  /**
   * Diagnostic for "Failed to load native module: pty.node": report whether
   * the module exists after extraction and probe dlopen from the Java side
   * (System.load). The node-pty loader swallows the real dlopen error, so
   * this is the only way to see whether the failure is a missing file, a
   * linker rejection (deps/permissions/format) or a node-side ABI check.
   */
  fun diagnosePtyNode() {
    try {
      val pty = ptyNodeFile()
      AppLog.log(
        "diag",
        "pty.node exists=" + (pty != null) + " size=" + (pty?.length() ?: -1) +
          " canRead=" + (pty?.canRead() ?: false) + " canExec=" + (pty?.canExecute() ?: false),
      )
      if (pty != null) {
        try {
          System.load(pty.absolutePath)
          AppLog.log("diag", "Java dlopen pty.node OK (linker accepts it)")
        } catch (t: Throwable) {
          AppLog.log("diag", "Java dlopen pty.node FAILED: " + t.message)
        }
        // Also mirror the module into prebuilds/android-arm64 (the loader's
        // last fallback path) in case the build/Release require has a path quirk.
        val prebuilt = File(pty.parentFile.parentFile.parentFile, "prebuilds/android-arm64/pty.node")
        if (!prebuilt.isFile) {
          prebuilt.parentFile?.mkdirs()
          pty.copyTo(prebuilt, overwrite = true)
          prebuilt.setExecutable(true, true)
          prebuilt.setWritable(false, false)
          AppLog.log("diag", "mirrored pty.node -> " + prebuilt.absolutePath)
        }
      }
    } catch (t: Throwable) {
      AppLog.log("diag", "pty.node diagnostic failed", t)
    }
  }

  /**
   * Candidates that may provide the missing C++ unwind symbol
   * (_Unwind_Resume): the bundled archive-linked libunwind-patch.so first,
   * then system libraries as fallbacks for ROMs that ship one.
   */
  private fun unwindCandidates(): List<String?> =
    listOfNotNull(
      bundledUnwindPatch()?.absolutePath,
      null,
      "/system/lib64/libgcc.so",
      "/system/lib64/libc++.so",
      "/system/lib64/libc++_shared.so",
      "/system/lib64/libunwind.so",
    )

  /** Extract the bundled libunwind-patch.so (per-ABI) from APK assets. */
  private fun bundledUnwindPatch(): File? {
    return try {
      val target = File(context.filesDir, "libunwind-patch.so")
      if (target.isFile && target.length() > 0L) return target
      val abi =
        when {
          android.os.Build.SUPPORTED_ABIS
            .any { it.startsWith("arm64") } -> "arm64-v8a"

          android.os.Build.SUPPORTED_ABIS
            .any { it.startsWith("x86_64") } -> "x86_64"

          else -> null
        } ?: return null
      context.assets.open("unwind/libunwind-patch-$abi.so").use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
      target.setExecutable(true, true)
      target.setWritable(false, false) // W^X: preload libs must not be writable
      AppLog.log("diag", "extracted bundled unwind patch -> " + target.absolutePath)
      target
    } catch (t: Throwable) {
      AppLog.log("diag", "bundled unwind patch unavailable", t)
      null
    }
  }

  private fun probePtyNode(
    pty: File,
    preload: String?,
  ): Pair<Int, String> {
    val script =
      "try{require(" + JSONObject.quote(pty.absolutePath) +
        ");console.log('NODE_PTY_OK')}catch(e){console.log('NODE_PTY_FAIL: '+e.message)}"
    val env =
      mapOf(
        "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
        "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
        "HOME" to homeDir.absolutePath,
        "TMPDIR" to File(homeDir, "tmp").apply { mkdirs() }.absolutePath,
      ) + opensslConfEnv()
    val pb =
      ProcessBuilder(
        "/system/bin/linker64",
        nodeBin.absolutePath,
        "-e",
        script,
      ).also { b ->
        b.environment().putAll(env)
        if (preload != null) b.environment()["LD_PRELOAD"] = preload
        b.redirectErrorStream(true)
      }
    val proc = pb.start()
    // Bounded wait: a hung node probe must not block its caller forever (the
    // watchdog thread would be permanently stuck and the engine never
    // restarted). Kill after 30s, then drain whatever was written.
    if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
      AppLog.log("diag", "pty probe hung, killing")
      proc.destroyForcibly()
    }
    val out = proc.inputStream.bufferedReader().readText()
    val code =
      try {
        proc.exitValue()
      } catch (_: Exception) {
        -1
      }
    return code to out
  }
}
