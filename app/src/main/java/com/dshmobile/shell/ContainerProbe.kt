package com.dshmobile.shell

import java.io.File

/**
 * Container smoke test: runs a real command inside the single rootfs via the
 * exact proot argv the engine uses, so a failure here means the container is
 * genuinely unusable — proot binary, its shared libs and the rootfs node are
 * all exercised. Runs outside the engine process (fresh ProcessBuilder).
 */
class ContainerProbe(
  private val prootRuntime: ProotRuntime,
  private val rootfsDir: File,
  private val projectsDir: File,
  private val pickToken: String,
) {
  /** Returns null on success, or the combined output tail on failure. */
  fun smokeTest(): String? =
    try {
      // Build the proot argv DIRECTLY from prootOptions + a bounded smoke
      // command — do NOT slice buildEngineArgs' args on a "--" separator: this
      // proot build has no "--", so args.indexOf("--")==-1 and take(0) dropped
      // all proot options, leaving a bare `ProcessBuilder(["/bin/bash",...])`
      // that android resolves as the host /bin/bash (which does not exist →
      // "Cannot run program /bin/bash: No such file or directory").
      val smokeArgs =
        (
          prootRuntime.prootOptions(rootfsDir, projectsDir) +
            listOf(
              "/bin/bash",
              "-c",
              "echo CONTAINER_OK; id -u",
            )
          ).toTypedArray()
      val pb =
        ProcessBuilder(smokeArgs).also { b ->
          b.environment().putAll(prootRuntime.buildEngineEnv())
          b.redirectErrorStream(true)
        }
      val proc = pb.start()
      // Bounded wait: a hung container chain must not freeze the boot flow.
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
