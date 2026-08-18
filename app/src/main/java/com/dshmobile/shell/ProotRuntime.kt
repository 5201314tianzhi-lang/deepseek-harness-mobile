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

  /** proot 与依赖已由系统装载器就位在 nativeLibraryDir（jniLibs）。 */
  fun ensureProot(): Boolean {
    val talloc = File(nativeLibDir, "libtalloc.so.2")
    val shmem = File(nativeLibDir, "libandroid-shmem.so")
    val ok = prootBin.isFile && prootBin.length() > 0L
    val tallocOk = talloc.isFile && talloc.length() > 0L
    val shmemOk = shmem.isFile && shmem.length() > 0L
    AppLog.log("proot", "ensureProot(nativeLibraryDir) proot=$ok talloc=$tallocOk shmem=$shmemOk dir=" + nativeLibDir.absolutePath)
    return ok && tallocOk && shmemOk
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
    val args =
      arrayOf(
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
        "--kill-on-exit",
        "--",
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
    val env = mapOf("LD_LIBRARY_PATH" to nativeLibDir.absolutePath)
    return args to env
  }
}