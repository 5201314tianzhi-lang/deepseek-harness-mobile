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
  /**
   * Interpreter of the generated bash wrapper. MUST be a system binary:
   * the snapshot's usr/bin/sh is an app-data ELF whose kernel-level shebang
   * exec bypasses libc (the LD_PRELOAD exec-hook cannot reroute it), and
   * devices with the app-data exec ban refuse it with EACCES. The system
   * shell is always exec-able, and its exec of proot goes through libc so
   * the hook reroute still applies.
   */
  private val bashWrapperShebang = "#!/system/bin/sh"

  val prootDir: File get() = File(context.filesDir, "proot")
  val prootBin: File get() = File(prootDir, "proot")
  val rootfsDir: File get() = File(context.filesDir, DshPaths.ROOTFS_DIR)

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
   * Replace usr/bin/bash with a proot wrapper (original kept as bash.termux).
   * Idempotent. The wrapper is generated at runtime with dynamic paths so the
   * package id never appears in the repo. Scripts exec natively (exec-hook
   * only reroutes ELF), so no native wrapper code is needed.
   */
  fun ensureWrapper(): Boolean {
    if (!ensureProot()) return false
    workspaceDir.mkdirs() // host side of the /root/projects bind mount
    val bash = File(usrDir, DshPaths.BASH_BIN)
    val termux = File(usrDir, DshPaths.BASH_BIN + ".termux")
    // Wrapper considered current when it routes via proot AND uses the system
    // shell as its interpreter AND is W^X-hardened (not writable). The
    // v0.1.x wrapper shebang pointed at the snapshot's usr/bin/sh — an
    // app-data ELF whose kernel-level shebang exec the exec-hook cannot
    // reroute (LD_PRELOAD only sees libc execve), so on devices with the
    // app-data exec ban (Android 15+, vendor W^X) the whole container chain
    // died with EACCES. The system shell is always exec-able; its exec of
    // proot goes through libc and the hook, so the reroute still applies.
    // Older installs are rewritten in place; a current-format wrapper that
    // regained the write bit (e.g. snapshot re-extraction restoring modes)
    // is re-hardened, not skipped by the marker check.
    val wrapperUpToDate =
      bash.isFile && termux.isFile &&
        bash.readText(Charsets.US_ASCII).contains(bashWrapperShebang) &&
        !bash.canWrite()
    if (wrapperUpToDate) return true
    if (bash.isFile && !bash.readText(Charsets.US_ASCII).contains("#!")) {
      if (!bash.renameTo(termux)) return false
    }
    // Host-side temp dir: writable app storage. PROOT_TMP_DIR is proot's own
    // scratch space (glue rootfs, f2fs probe, mkdtemp); the container-internal
    // TMPDIR points at /tmp, which lives in the (writable) rootfs.
    val hostTmp = File(context.filesDir, "home/tmp").apply { mkdirs() }.absolutePath
    val wrapper =
      """
      $bashWrapperShebang
      if [ ! -x "${rootfsDir.absolutePath}/${DshPaths.ROOTFS_BASH}" ]; then
        echo "Ubuntu container not installed" >&2
        exit 127
      fi
      LD_LIBRARY_PATH=${prootDir.absolutePath}:${'$'}LD_LIBRARY_PATH
      export LD_LIBRARY_PATH
      PROOT_TMP_DIR=$hostTmp
      export PROOT_TMP_DIR
      TMPDIR=/tmp
      export TMPDIR
      exec "${prootBin.absolutePath}" -0 -r "${rootfsDir.absolutePath}" \
        -b /proc -b /dev -b /sys \
        -b "${resolvConf().absolutePath}:/etc/resolv.conf" \
        -b "${workspaceDir.absolutePath}:/root/projects" \
        -w /root/projects /bin/bash "${'$'}@"
      """.trimIndent()
    bash.setWritable(true, false) // a W^X-stripped wrapper from a prior run is read-only
    bash.writeText(wrapper)
    // W^X hardening — explicit 555: read+exec for owner/group/others, write
    // bit removed for everyone. Vendor security policies (EMUI W^X) refuse
    // to exec a writable+executable file, so the write bit must be gone.
    bash.setReadable(true, false)
    bash.setExecutable(true, false)
    bash.setWritable(false, false)
    AppLog.log("proot", "bash wrapper installed -> " + bash.absolutePath)
    return true
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
