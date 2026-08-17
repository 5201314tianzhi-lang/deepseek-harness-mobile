package com.dshmobile.shell

import org.json.JSONObject
import java.io.File

/**
 * Container smoke test: runs a real command inside the proot container
 * through the exact chain the agent uses (node → bash wrapper → proot →
 * container bash), so a failure here means the container is genuinely
 * unusable — proot binary, its shared libs, PROOT_TMP_DIR, the wrapper and
 * the rootfs are all exercised. Runs outside the engine process (fresh
 * linker64 + node, same env shape as the engine) and reports the tail of the
 * output for the boot log.
 */
class ContainerProbe(
  private val usrDir: File,
  private val homeDir: File,
  private val nodeBin: File,
  private val execHookPath: String?,
  private val opensslConfEnv: Map<String, String>,
  private val workspaceDir: File,
) {
  /** Returns null on success, or the combined output tail on failure. */
  fun smokeTest(): String? =
    try {
      val bash = File(usrDir, DshPaths.BASH_BIN).absolutePath
      val script =
        "var cp=require('child_process');" +
          "try{var r=cp.execFileSync(" + JSONObject.quote(bash) + ",['-c','echo CONTAINER_OK; id']," +
          "{timeout:30000,encoding:'utf8'});process.stdout.write(r)}catch(e){console.log('CONTAINER_FAIL: '+e.message)}"
      val env =
        mapOf(
          "PATH" to (usrDir.absolutePath + "/bin:/system/bin"),
          "LD_LIBRARY_PATH" to (usrDir.absolutePath + "/lib"),
          "HOME" to homeDir.absolutePath,
          "TMPDIR" to File(homeDir, "tmp").apply { mkdirs() }.absolutePath,
          // Same vars the engine gets: the bash wrapper ELF resolves all dsh
          // paths from these.
          "DSH_FILES_DIR" to usrDir.parentFile.absolutePath,
          "DSH_WORKSPACE" to workspaceDir.absolutePath,
        ) + opensslConfEnv
      val pb =
        ProcessBuilder("/system/bin/linker64", nodeBin.absolutePath, "-e", script).also { b ->
          b.environment().putAll(env)
          if (execHookPath != null) b.environment()["LD_PRELOAD"] = execHookPath
          b.redirectErrorStream(true)
        }
      val proc = pb.start()
      // Bounded wait: a hung container chain must not freeze the boot flow
      // forever (node-side timeout only covers execFileSync itself).
      if (!proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
        AppLog.log("boot", "container smoke probe hung, killing")
        proc.destroyForcibly()
      }
      val out = proc.inputStream.bufferedReader().readText()
      if (out.contains("CONTAINER_OK")) null else out.trim().take(600)
    } catch (t: Throwable) {
      (t.message ?: t.javaClass.simpleName).take(600)
    }
}
