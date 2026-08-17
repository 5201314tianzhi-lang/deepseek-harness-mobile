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
  val rootfsDir: File get() = File(context.filesDir, DshPaths.ROOTFS_DIR)

  /**
   * Sidecar marker next to usr/bin/bash: present iff the current bash is the
   * NDK-built ELF wrapper. The snapshot swap replaces the whole usr/ tree
   * (marker included), so a stale marker cannot survive an update.
   */
  private val bashWrapperMarker = File(usrDir, "bin/.bash-wrapper")

  /**
   * Replace usr/bin/bash with the proot ELF wrapper (NDK-built, shipped at
   * lib/<abi>/bash-wrapper). Idempotent.
   *
   * The historical wrapper was a shell script; on hardened devices (Android
   * 15+ / vendor W^X / SELinux) the kernel refuses the exec of a script
   * inside app data with EACCES, and the exec-hook cannot reroute it — it
   * only intercepts libc execve, while script exec is kernel-handled
   * (binfmt_script). An ELF wrapper is rerouted through /system/bin/linker64
   * (the dlopen-style load that already runs the engine's node), so the
   * chain never needs a kernel-level script exec.
   */
  fun ensureWrapper(): Boolean {
    if (!ensureProot()) return false
    workspaceDir.mkdirs() // host side of the /root/projects bind mount
    resolvConf() // the ELF wrapper binds it at runtime; must exist beforehand
    val bash = File(usrDir, DshPaths.BASH_BIN)
    if (bash.isFile && bashWrapperMarker.isFile && bash.length() > 0L) {
      // Current wrapper: re-harden in place when a snapshot re-extraction
      // restored the write bit (vendors refuse to exec writable files).
      if (bash.canWrite()) harden(bash)
      return true
    }
    if (!extractWrapper(bash)) return false
    bashWrapperMarker.writeText("1")
    harden(bash)
    AppLog.log("proot", "bash wrapper installed -> " + bash.absolutePath)
    return true
  }

  /** Extract the NDK-built ELF wrapper from the APK (lib/<abi>/bash-wrapper)
   *  into usr/bin/bash, replacing the snapshot's real bash or a stale script
   *  wrapper. Writes through the W^X write-bit strip when present. */
  private fun extractWrapper(target: File): Boolean =
    try {
      val abi = abiAsset() ?: return false
      target.parentFile?.mkdirs()
      target.setWritable(true, false) // overwrite a W^X-stripped previous file
      java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
        val entry = zip.getEntry("lib/$abi/bash-wrapper") ?: return false
        zip.getInputStream(entry).use { input ->
          target.outputStream().use { out -> input.copyTo(out) }
        }
      }
      true
    } catch (t: Throwable) {
      AppLog.log("proot", "wrapper extract failed", t)
      false
    }

  /** W^X hardening — explicit 555: read+exec for owner/group/others, write
   *  bit removed for everyone. Vendor security policies (EMUI W^X) refuse
   *  to exec a writable+executable file. */
  private fun harden(file: File) {
    file.setReadable(true, false)
    file.setExecutable(true, false)
    file.setWritable(false, false)
  }

  fun rootfsReady(): Boolean = File(rootfsDir, DshPaths.ROOTFS_BASH).isFile

  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile) {
      f.parentFile?.mkdirs()
      // AliDNS first: reachable in CN networks, where 8.8.8.8 would stall
      // every first lookup. Google DNS kept as a secondary.
      f.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
    }
    return f
  }

  private fun abiAsset(): String? =
    when {
      Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "arm64-v8a"
      Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
      else -> null
    }

  private fun extractAsset(
    name: String,
    target: File,
    exec: Boolean,
  ): Boolean {
    // Reuse an already-extracted asset: overwriting one whose write bit was
    // stripped (W^X policy) fails with EACCES on reinstall-without-clear.
    if (target.isFile && target.length() > 0L) return true
    return try {
      val abi = abiAsset() ?: return false
      target.parentFile?.mkdirs()
      context.assets.open("proot/$abi/$name").use { input ->
        target.outputStream().use { out -> input.copyTo(out) }
      }
      target.setExecutable(exec, true)
      // W^X: proot AND its shared libs must not stay writable — Huawei/EMUI
      // refuse to exec (and mmap PROT_EXEC) a writable file, so a left-writable
      // proot binary makes the whole container chain fail on those devices
      // (mirrors SnapshotExtractor's write-bit strip on the snapshot ELFs).
      target.setWritable(false, false)
      true
    } catch (t: Throwable) {
      AppLog.log("proot", "extract failed: $name", t)
      false
    }
  }

  /** Extract proot + its shared libs from assets. True when the binary works. */
  fun ensureProot(): Boolean {
    val talloc = File(prootDir, "libtalloc.so.2")
    val shmem = File(prootDir, "libandroid-shmem.so")
    // All three must be present: a partial extraction (interrupted) that left
    // proot but missed a lib would otherwise pass the short-circuit and then
    // fail at exec time with a confusing dynamic-loader error.
    if (prootBin.isFile && prootBin.length() > 0L &&
      talloc.isFile && talloc.length() > 0L &&
      shmem.isFile && shmem.length() > 0L
    ) {
      return true
    }
    val ok = extractAsset("proot", prootBin, exec = true)
    val tallocOk = extractAsset("libtalloc.so.2", talloc, exec = false)
    val shmemOk = extractAsset("libandroid-shmem.so", shmem, exec = false)
    AppLog.log("proot", "ensureProot executable=$ok libtalloc=$tallocOk shmem=$shmemOk")
    return ok && tallocOk && shmemOk
  }

  /** Full container initialization: proot runtime + deps + bash wrapper
   *  (idempotent). Rootfs presence is checked separately (rootfsReady). */
  fun ensureInitialized(): Boolean {
    val ok = ensureProot() && ensureWrapper()
    applyMirrors() // best-effort: never blocks the engine on mirror config
    return ok
  }

  /**
   * Pre-provision the container for smooth out-of-the-box use (once, guarded
   * by a marker file so user edits to the mirror configs survive):
   *  - /root/projects workspace directory (host-backed by the bind mount)
   *  - China mirror sources for every major package manager, in their
   *    STANDARD config locations — they take effect the moment the manager
   *    is installed (apt, pip, npm, cargo, go, gem, composer, conda), no
   *    user setup needed.
   */
  private fun applyMirrors() {
    if (!rootfsReady()) return
    val rootfs = rootfsDir
    val marker = File(rootfs, "etc/dsh-mirrors-applied")
    if (marker.isFile) return
    try {
      File(rootfs, DshPaths.CONTAINER_PROJECTS).mkdirs()
      // apt (Ubuntu 24.04 deb822) -> Tsinghua TUNA; Aliyun kept as a comment
      // for manual switching.
      write(
        File(rootfs, "etc/apt/sources.list.d/ubuntu.sources"),
        """
        |Types: deb
        |URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu/
        |Suites: noble noble-updates noble-backports noble-security
        |Components: main restricted universe multiverse
        |Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
        |
        |# Alternative: Aliyun
        |# Types: deb
        |# URIs: https://mirrors.aliyun.com/ubuntu/
        |# Suites: noble noble-updates noble-backports noble-security
        |# Components: main restricted universe multiverse
        |# Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "etc/pip.conf"),
        """
        |[global]
        |index-url = https://pypi.tuna.tsinghua.edu.cn/simple
        |trusted-host = pypi.tuna.tsinghua.edu.cn
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "etc/npmrc"),
        """
        |registry=https://registry.npmmirror.com
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "root/.cargo/config.toml"),
        """
        |[source.crates-io]
        |replace-with = 'tuna'
        |
        |[source.tuna]
        |registry = "sparse+https://mirrors.tuna.tsinghua.edu.cn/crates.io-index/"
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "etc/profile.d/dsh-mirrors.sh"),
        """
        |export GOPROXY=https://goproxy.cn,direct
        |export GO111MODULE=on
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "root/.bashrc"),
        """
        |export GOPROXY=https://goproxy.cn,direct
        |export GO111MODULE=on
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "root/.gemrc"),
        """
        |---
        |:sources:
        |- https://mirrors.tuna.tsinghua.edu.cn/rubygems/
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "root/.config/composer/config.json"),
        """
        |{
        |  "repositories": [
        |    { "type": "composer", "url": "https://mirrors.aliyun.com/composer/" }
        |  ]
        |}
        |
        """.trimMargin(),
      )
      write(
        File(rootfs, "root/.condarc"),
        """
        |channels:
        |  - defaults
        |show_channel_urls: true
        |default_channels:
        |  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/main
        |  - https://mirrors.tuna.tsinghua.edu.cn/anaconda/pkgs/r
        |custom_channels:
        |  conda-forge: https://mirrors.tuna.tsinghua.edu.cn/anaconda/cloud
        |
        """.trimMargin(),
      )
      marker.writeText("1")
      AppLog.log("proot", "mirror configs + /root/projects applied to rootfs")
    } catch (t: Throwable) {
      AppLog.log("proot", "mirror config apply failed", t)
    }
  }

  private fun write(
    file: File,
    content: String,
  ) {
    file.parentFile?.mkdirs()
    file.writeText(content)
  }
}
