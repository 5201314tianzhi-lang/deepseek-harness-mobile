package com.dshmobile.shell

import android.content.Context
import java.io.File

/**
 * Proot single-runtime container (B 路线修正版).
 *
 * 关键变更：proot 及其依赖（libandroid-shmem、libtalloc）不再放在 assets/ 由
 * App 手动解压后 exec（那会触发 exec-app-data-ELF / W^X 安全策略导致进程强杀
 * = 一打开就闪退）。改为打包进 jniLibs/arm64-v8a/，作为原生库由 Android 系统
 * 装载器在安装时释放并授予可执行权限，运行时 mmap PROT_EXEC 完全合规。
 * 对照 Operit：其 proot 正是 lib/arm64/liboperit_proot.so（系统装载器加载）。
 */
class ProotRuntime(
  private val context: Context,
) {
  val prootDir: File get() = File(context.filesDir, "proot")
  /** nativeLibraryDir = 系统释放的 lib/arm64/ 目录（jniLibs 打进去）。 */
  val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)
  val prootBin: File get() = File(nativeLibDir, "libproot.so")

  fun resolvConf(): File {
    val f = File(context.filesDir, "etc/resolv.conf")
    if (!f.isFile) {
      f.parentFile?.mkdirs()
      f.writeText("nameserver 223.5.5.5\nnameserver 8.8.8.8\n")
    }
    return f
  }

  /** proot 与依赖已由系统装载器就位在 nativeLibraryDir（jniLibs）。
   *
   * libtalloc 的 SONAME 是 libtalloc.so.2，但带版本号的文件名在 jniLibs 下
   * 会被 AGP 当作非标准原生库而漏打包（lib/ 里只剩 libproot.so +
   * libandroid-shmem.so，运行时 ensureProot 因 libtalloc.so.2 缺失而失败）。
   * 因此 jniLibs 以标准命名 libtalloc.so 打进 lib/，这里在启动前把它镜像为
   * filesDir/libtalloc.so.2（proot 的 linker 按 soname 查找的名字），并把
   * filesDir 加入 LD_LIBRARY_PATH，使 proot exec 时能解析到它。
   */
  fun ensureProot(): Boolean {
    val shmem = File(nativeLibDir, "libandroid-shmem.so")
    val ok = prootBin.isFile && probinUsable(prootBin)
    val shmemOk = shmem.isFile && shmem.length() > 0L
    // Mirror libtalloc.so (packed standard-name) to filesDir/libtalloc.so.2
    // (the soname proot's linker actually resolves).
    val tallocSrc = File(nativeLibDir, "libtalloc.so")
    val talloc = File(context.filesDir, "libtalloc.so.2")
    var tallocOk = talloc.isFile && talloc.length() > 0L
    if (!tallocOk && tallocSrc.isFile) {
      try {
        tallocSrc.copyTo(talloc, overwrite = true)
        tallocOk = talloc.isFile && talloc.length() > 0L
      } catch (_: Throwable) {
        tallocOk = false
      }
    }
    AppLog.log("proot", "ensureProot(nativeLibraryDir) proot=$ok talloc=$tallocOk shmem=$shmemOk dir=" + nativeLibDir.absolutePath)
    return ok && tallocOk && shmemOk
  }

  /** proot 二进制需可被系统 linker 执行加载。 */
  private fun probinUsable(f: File): Boolean = f.isFile && f.length() > 0L

  /**
   * proot 固定选项（-0 / -r rootfs / bind / -w），不含要执行的命令。
   * 构建引擎 argv 时在它后面拼引擎命令；smoke test 在它后面拼 /bin/bash。
   * 注意：此 proot 二进制不接受 `--` 命令分隔符，proot 把第一个非选项 token
   * 当作要运行的命令，因此命令必须直接跟在所有选项之后（不能用 `--` 隔开）。
   */
  fun prootOptions(
    rootfsDir: File,
    projectsDir: File,
  ): List<String> {
    resolvConf()
    return listOf(
      prootBin.absolutePath,
      "-0",
      "-r",
      rootfsDir.absolutePath,
      "-b",
      "/dev:/dev",
      "-b",
      "/proc:/proc",
      "-b",
      "/sys:/sys",
      "-b",
      resolvConf().absolutePath + ":/etc/resolv.conf",
      "-b",
      projectsDir.absolutePath + ":/root/projects",
      "-w",
      "/root",
    )
  }

    /**
   * proot 引擎进程的环境变量。
   *
   * - LD_LIBRARY_PATH: 同时指向 nativeLibraryDir（libproot.so + shmem + 标准命名
   *   libtalloc.so）和 filesDir（镜像出的 libtalloc.so.2），使 proot 的 Android
   *   linker 能在 exec 时按 soname 解析到它全部 DT_NEEDED 依赖。
   * - PROOT_TMP_DIR: 指向宿主上真实可写的临时目录。proot 启动时会用它做
   *   f2fs bug probe 并创建自己的临时文件；若缺失，proot 用 /tmp（容器内/
   *   宿主不可写）导致 “can't create temporary file” 和 execve ENOSYS。
   */
  fun buildEngineEnv(): Map<String, String> {
    val libPath = nativeLibDir.absolutePath + ":" + context.filesDir.absolutePath
    val tmpDir = File(context.cacheDir, "proot-tmp")
    tmpDir.mkdirs()
    return mapOf(
      "LD_LIBRARY_PATH" to libPath,
      "PROOT_TMP_DIR" to tmpDir.absolutePath,
      "TMPDIR" to tmpDir.absolutePath,
    )
  }

  /**
   * 构建引擎 argv + env：proot + 单一 rootfs，容器内启动 node 引擎。
   * LD_LIBRARY_PATH 指向 nativeLibraryDir，使 proot exec 时能定位其 bionic 依赖
   * （libandroid-shmem.so 等）。
   */
  fun buildEngineArgs(
    rootfsDir: File,
    projectsDir: File,
    port: Int,
    pickToken: String,
  ): Pair<Array<String>, Map<String, String>> {
    resolvConf()
    val cmdList =
      prootOptions(rootfsDir, projectsDir) +
        listOf(
          "/usr/bin/env",
          "-i",
          "HOME=/root",
          "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
          "TERM=xterm-256color",
          "DSH_HOME=/root/.dsh",
          "DSH_PICK_TOKEN=" + pickToken,
          "node",
          "--expose-internals",
          "/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js",
          "web",
          "--port",
          port.toString(),
        )
    val args = cmdList.toTypedArray()
    // LD_LIBRARY_PATH spans both nativeLibraryDir (libproot.so + shmem + the
    // standard-named libtalloc.so) and filesDir (where libtalloc.so.2 is
    // mirrored) so proot's Android linker can resolve all its DT_NEEDED by
    // soname at exec time.
    val env = buildEngineEnv()
    return args to env
  }
}