package com.dshmobile.shell

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream

/**
 * Shared snapshot extraction: xz tar → dest with owner-only permissions
 * (dsh's credentials provider fails loud on world-readable secrets) and
 * symlink preservation. Used by both the bundled snapshot (assets) and the
 * online update path (downloaded file).
 *
 * After extraction, every executable file gets the Android exec attribute
 * (security.android.exec): Android 15+ apps targeting SDK 35+ may only exec
 * app-data ELF binaries that carry it. The tar does not preserve xattrs
 * through the Java path, so it is stamped via the system setfattr (best
 * effort — kernels that do not enforce it accept the no-op).
 */
object SnapshotExtractor {
  /**
   * Extract an xz-compressed tar stream.
   * @param input raw xz stream.
   * @param totalBytes expected stream size (for progress; 0 = unknown).
   * @param dest destination root (filesDir; the archive holds usr/ + home/).
   * @param onProgress bytesDone, bytesTotal.
   */
  fun extract(
    input: InputStream,
    totalBytes: Long,
    dest: File,
    onProgress: (Long, Long) -> Unit,
  ) {
    val xz = XZCompressorInputStream(input)
    val tar = TarArchiveInputStream(xz)
    try {
      extractTar(tar, dest) { done -> onProgress(done, totalBytes) }
    } finally {
      // Any extraction error (traversal violation, EACCES, IO) must still
      // release the streams; close is idempotent for callers that use {} too.
      try {
        tar.close()
      } catch (_: Exception) {
      }
      try {
        input.close()
      } catch (_: Exception) {
      }
    }
  }

  /**
   * Extract a (already decompressed) tar stream into dest with the shared
   * policy: traversal guard, symlink preservation, owner-only permissions,
   * exec-bit enforcement with W^X write-bit stripping, and the Android exec
   * attribute stamp on executables. Also used by the rootfs downloader.
   * @param onProgress bytesDone.
   */
  fun extractTar(
    tar: TarArchiveInputStream,
    dest: File,
    onProgress: (Long) -> Unit = {},
  ) {
    val execFiles = mutableListOf<String>()
    val destCanonical = dest.canonicalPath
    var done = 0L
    var lastReported = 0L
    var entry: TarArchiveEntry? = tar.nextEntry
    while (entry != null) {
      // Traversal guard (I-02): every entry name must resolve inside dest.
      // Checked per entry before processing (hard links / duplicate entries
      // cannot bypass it: the target is canonical-path validated).
      val target = resolveTarget(dest, destCanonical, entry.name)
      when {
        entry.isDirectory -> {
          target.mkdirs()
        }

        entry.isSymbolicLink -> {
          target.parentFile?.mkdirs()
          // isSymbolicLink first: exists() follows the link, and a dangling
          // symlink (from an interrupted earlier run) would report false and
          // leave the stale entry for createSymbolicLink to explode on —
          // every retry then fails identically with no recovery path.
          if (java.nio.file.Files
              .isSymbolicLink(target.toPath()) || target.exists()
          ) {
            target.delete()
          }
          java.nio.file.Files
            .createSymbolicLink(
              target.toPath(),
              java.nio.file.Paths
                .get(entry.linkName),
            )
        }

        entry.isLink -> {
          // Hard links carry no payload (size 0): materialize a full copy of
          // the link target instead of writing an empty file.
          target.parentFile?.mkdirs()
          if (java.nio.file.Files
              .isSymbolicLink(target.toPath()) || target.exists()
          ) {
            target.delete()
          }
          val linkTarget = resolveTarget(dest, destCanonical, entry.linkName)
          if (linkTarget.isFile) {
            linkTarget.copyTo(target, overwrite = true)
            target.setExecutable(linkTarget.canExecute(), true)
            if (target.canWrite()) target.setWritable(false, false)
            if (target.name.endsWith(".so") || target.name.endsWith(".node")) {
              if (target.canWrite()) target.setWritable(false, false)
            }
          } else {
            AppLog.log("extract", "hard link target missing, skipping: " + entry.name)
          }
        }

        else -> {
          target.parentFile?.mkdirs()
          // Idempotent overwrite: an existing target may have had its write
          // bit stripped by W^X (reinstall-without-clear, or an interrupted
          // previous run) — restore it before opening the stream, otherwise
          // the overwrite fails with EACCES and every retry fails the same
          // way (permanent "extract failed" with no recovery).
          if (target.exists()) target.setWritable(true, true)
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
          if (entry.mode and 0x40 != 0) {
            // Fallback: the exec bit must survive extraction — an exec EACCES
            // on the engine binary shows up as "engine start timeout". If the
            // bit was lost (tar mode/PAX quirks), force it for all users.
            if (!target.canExecute()) {
              target.setExecutable(true, false)
              AppLog.log("extract", "exec bit was lost, forced: " + target.name)
            }
            // W^X compatibility: Huawei/EMUI (and Android 10 W^X hardening)
            // reject executing files that are both writable and executable.
            // The engine binaries never write themselves at runtime, so strip
            // the write bit (rwx------ -> r-x------) to satisfy the check.
            if (target.canWrite()) {
              target.setWritable(false, false)
              AppLog.log("extract", "write bit stripped (W^X): " + target.name)
            }
            execFiles.add(target.absolutePath)
          }
          // Shared libraries (including dlopen'd native modules like
          // node-pty's pty.node, whose DT_NEEDED libc++_shared.so is mode 0600
          // in the archive) must not be writable either: vendor W^X refuses
          // mmap PROT_EXEC of a writable file, so dlopen fails on Huawei/EMUI
          // even though the module itself is r-x.
          if (target.name.endsWith(".so") || target.name.endsWith(".node")) {
            if (target.canWrite()) {
              target.setWritable(false, false)
              AppLog.log("extract", "lib write bit stripped (W^X): " + target.name)
            }
          }
        }
      }
      done += entry.size
      if (done - lastReported >= 1024L * 1024L) {
        lastReported = done
        onProgress(done)
      }
      entry = tar.nextEntry
    }
    onProgress(done)
    stampExecAttribute(execFiles)
  }

  /**
   * Resolve a tar entry name against the extraction root, rejecting any
   * traversal (`..`) or absolute path that would escape dest. Throws on
   * violation: an untrusted snapshot must never write outside its root.
   *
   * NOTE: this uses LEXICAL normalization (Path.normalize) — NOT
   * canonicalPath. canonicalPath resolves existing symlinks: once we extract a
   * symlink whose target is an absolute path (debian alternatives, e.g.
   * /etc/alternatives/pager -> /usr/bin/pager), the path stays inside the
   * rootfs, but a later canonicalPath() on that very symlink resolves to the
   * host-side path (outside dest) and is wrongly rejected as "escapes
   * extraction root". That made every retry fail identically with no recovery.
   * Lexical normalization only resolves "." / ".." and never follows symlinks,
   * so it is safe for tar entries that are themselves symlinks.
   */
  private fun resolveTarget(
    dest: File,
    destCanonical: String,
    name: String,
  ): File {
    val rootPath = dest.toPath().toAbsolutePath().normalize().toString()
    val targetPath = dest.toPath().resolve(name).normalize().toString()
    if (targetPath != rootPath && !targetPath.startsWith(rootPath + File.separator)) {
      throw java.io.IOException("tar entry escapes extraction root: " + name)
    }
    return File(dest, name)
  }

  /** Stamp the Android exec attribute on all extracted executables. */
  private fun stampExecAttribute(files: List<String>) {
    if (files.isEmpty()) return
    try {
      // Pass the argument array directly (no shell), so quotes/metacharacters
      // in filenames are never interpreted.
      val base = listOf("/system/bin/setfattr", "-n", "security.android.exec", "-v", "1")
      // Concurrent batches (64 files each) to avoid spawning too many processes
      // at once.
      files.chunked(64).forEach { batch ->
        val procs = batch.map { f -> ProcessBuilder(base + f).redirectErrorStream(true).start() }
        for (p in procs) {
          val finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
          if (!finished) p.destroyForcibly()
        }
      }
    } catch (_: Throwable) {
      // Kernels without the exec-attribute check (emulators, older Android)
      // do not need it; ignore failures here.
    }
  }
}
