package com.dshmobile.shell

import java.io.File
import java.io.InputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * Shared snapshot extraction: xz tar → dest with owner-only permissions
 * (dsh's credentials provider fails loud on world-readable secrets) and
 * symlink preservation. Used by both the bundled snapshot (assets) and the
 * online update path (downloaded file).
 */
object SnapshotExtractor {

  /**
   * Extract an xz-compressed tar stream.
   * @param input raw xz stream.
   * @param totalBytes expected stream size (for progress; 0 = unknown).
   * @param dest destination root (filesDir; the archive holds usr/ + home/).
   * @param onProgress bytesDone, bytesTotal.
   */
  fun extract(input: InputStream, totalBytes: Long, dest: File, onProgress: (Long, Long) -> Unit) {
    val xz = XZCompressorInputStream(input)
    val tar = TarArchiveInputStream(xz)
    var done = 0L
    var entry: TarArchiveEntry? = tar.nextEntry
    while (entry != null) {
      val target = File(dest, entry.name)
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
          target.setReadable(false, false)
          target.setReadable(true, true)
          target.setWritable(true, true)
          target.setExecutable(entry.mode and 0x40 != 0, true)
        }
      }
      done += entry.size
      if (done % (1024 * 1024) < entry.size) onProgress(done, totalBytes)
      entry = tar.nextEntry
    }
    tar.close()
  }
}
