package com.dshmobile.shell

import android.content.Context
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
class EngineManager(private val context: Context) {

  val usrDir = File(context.filesDir, "usr")
  val homeDir = File(context.filesDir, "home")
  private val nodeBin = File(usrDir, "bin/node")
  private val dshBin = File(usrDir, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
  private var engineProcess: Process? = null

  val engineReady: Boolean get() = nodeBin.exists()

  /**
   * Extract the bundled snapshot archive into filesDir. Runs on any thread;
   * callers own the progress UI.
   * @param onProgress bytesDone, bytesTotal.
   * @returns true on success.
   */
  fun extractSnapshot(onProgress: (Long, Long) -> Unit): Boolean {
    return try {
      val fd = context.assets.openFd("snapshot.tar.xz")
      val total = fd.length
      val input = XZCompressorInputStream(context.assets.open("snapshot.tar.xz"))
      val tar = TarArchiveInputStream(input)
      var done = 0L
      var entry: TarArchiveEntry? = tar.nextEntry
      while (entry != null) {
        val target = File(usrDir.parentFile, entry.name)
        when {
          entry.isDirectory -> target.mkdirs()
          entry.isSymbolicLink -> {
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(entry.linkName))
          }
          else -> {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
              val buf = ByteArray(64 * 1024)
              var n = tar.read(buf)
              while (n >= 0) {
                out.write(buf, 0, n)
                n = tar.read(buf)
              }
            }
            // Owner-only permissions: dsh's credentials provider fails
            // loud on world-readable secrets (mode 644 refused at boot).
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(true, true)
            target.setExecutable(entry.mode and 0x40 != 0, true)
          }
        }
        done += entry.size
        if (done % (1024 * 1024) < entry.size) onProgress(done, total)
        entry = tar.nextEntry
      }
      tar.close()
      homeDir.mkdirs()
      true
    } catch (t: Throwable) {
      Log.e(TAG, "snapshot extract failed", t)
      false
    }
  }

  /** Start the dsh web engine from the embedded snapshot. */
  fun startEngine(port: Int = 3080): Boolean {
    return try {
      val builder = ProcessBuilder(
        nodeBin.absolutePath, "--expose-internals", dshBin.absolutePath, "web", "--port", port.toString(),
      )
      builder.environment().apply {
        put("PATH", usrDir.absolutePath + "/bin:/system/bin")
        put("LD_LIBRARY_PATH", usrDir.absolutePath + "/lib")
        put("HOME", homeDir.absolutePath)
        // os.tmpdir() falls back to the baked-in Termux tmp on Android
        // (unwritable from the app domain); keep spill inside filesDir.
        val tmp = File(homeDir, "tmp")
        tmp.mkdirs()
        put("TMPDIR", tmp.absolutePath)
      }
      builder.redirectErrorStream(true)
      // Capture engine output to a file (an unread pipe would fill and block).
      builder.redirectOutput(File(context.filesDir, "engine.log"))
      engineProcess = builder.start()
      true
    } catch (t: Throwable) {
      Log.e(TAG, "engine start failed", t)
      false
    }
  }

  /** Stop the engine process (best-effort). */
  fun stopEngine() {
    engineProcess?.destroy()
    engineProcess = null
  }

  companion object {
    private const val TAG = "dsh-engine"
  }
}