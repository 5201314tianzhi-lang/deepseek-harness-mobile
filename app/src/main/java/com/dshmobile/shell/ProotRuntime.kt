package com.dshmobile.shell

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Proot Ubuntu container runtime: extracts the Termux proot binary + its
 * dependencies (libtalloc, libandroid-shmem) from APK assets, generates the
 * bash wrapper that routes agent shell commands into the container, and
 * maintains resolv.conf / rootfs / workspace paths. Rootfs download itself
 * lives in RootfsDownloader.
 */
class ProotRuntime(
  private val context: Context,
  private val usrDir: File,
  private val workspaceDir: File,
) {

  val prootDir: File get() = File(context.filesDir, "proot")
  val prootBin: File get() = File(prootDir, "proot")
  val rootfsDir: File get() = File(context.filesDir, "rootfs")

  fun rootfsReady(): Boolean = File(rootfsDir, "bin/bash").isFile

  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile) {
      f.parentFile?.mkdirs()
      f.writeText("nameserver 8.8.8.8\nnameserver 223.5.5.5\n")
    }
    return f
  }

  private fun abiAsset(): String? = when {
    Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "arm64-v8a"
    Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
    else -> null
  }

  private fun extractAsset(name: String, target: File, exec: Boolean): Boolean {
    return try {
      val abi = abiAsset() ?: return false
      target.parentFile?.mkdirs()
      context.assets.open("proot/$abi/$name").use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
      target.setExecutable(exec, true)
      if (!exec) target.setWritable(false, false) // W^X policy
      true
    } catch (t: Throwable) {
      AppLog.log("proot", "extract failed: $name", t)
      false
    }
  }

  /** Extract proot + its shared libs from assets. True when the binary works. */
  fun ensureProot(): Boolean {
    if (prootBin.isFile && prootBin.length() > 0L) return true
    val ok = extractAsset("proot", prootBin, exec = true)
    val talloc = extractAsset("libtalloc.so.2", File(prootDir, "libtalloc.so.2"), exec = false)
    val shmem = extractAsset("libandroid-shmem.so", File(prootDir, "libandroid-shmem.so"), exec = false)
    AppLog.log("proot", "ensureProot executable=$ok libtalloc=$talloc shmem=$shmem")
    return ok && talloc && shmem
  }

  /**
   * Replace usr/bin/bash with a proot wrapper (original kept as bash.termux).
   * Idempotent. The wrapper is generated at runtime with dynamic paths so the
   * package id never appears in the repo. Scripts exec natively (exec-hook
   * only reroutes ELF), so no native wrapper code is needed.
   */
  fun ensureWrapper(): Boolean {
    if (!ensureProot()) return false
    val bash = File(usrDir, "bin/bash")
    val termux = File(usrDir, "bin/bash.termux")
    if (bash.isFile && termux.isFile) return true // already wrapped
    if (bash.isFile && !bash.readText(Charsets.US_ASCII).contains("#!")) {
      if (!bash.renameTo(termux)) return false
    }
    val sh = File(usrDir, "bin/sh").absolutePath
    val wrapper = """
      #!$sh
      if [ ! -x ${rootfsDir.absolutePath}/bin/bash ]; then
        echo "Ubuntu container not installed" >&2
        exit 127
      fi
      exec ${prootBin.absolutePath} -0 -r ${rootfsDir.absolutePath} \
        -b /proc -b /dev -b /sys \
        -b ${resolvConf().absolutePath}:/etc/resolv.conf \
        -b ${workspaceDir.absolutePath}:/root/workspace \
        -w /root/workspace /bin/bash "\$@"
    """.trimIndent()
    bash.writeText(wrapper)
    bash.setExecutable(true, true)
    AppLog.log("proot", "bash wrapper installed -> " + bash.absolutePath)
    return true
  }
}
