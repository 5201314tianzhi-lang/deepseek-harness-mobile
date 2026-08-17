package com.dshmobile.shell

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Container provisioning: bash wrapper (ELF, marker-based idempotence +
 * W^X hardening) and the China mirror config files. The proot binary is
 * stubbed (ensureProot short-circuits on an existing file); the wrapper ELF
 * itself cannot be extracted in the Robolectric env (no APK lib/ entry), so
 * the extraction path is covered by the C tests (tests/c/bash-wrapper-test.c)
 * and the marker/up-to-date logic is covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProotRuntimeTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var runtime: ProotRuntime

  private fun fakeProotBin() {
    File(context.filesDir, "proot/proot").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
    File(context.filesDir, "proot/libtalloc.so.2").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
    File(context.filesDir, "proot/libandroid-shmem.so").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
  }

  private fun fakeRootfs() {
    File(context.filesDir, DshPaths.ROOTFS_DIR + "/bin/bash").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
  }

  private fun wrapperFile(): File = File(context.filesDir, DshPaths.USR_DIR + "/" + DshPaths.BASH_BIN)

  /** Seed a current wrapper: file + marker present, write bit stripped. */
  private fun seedCurrentWrapper() {
    File(context.filesDir, DshPaths.USR_DIR + "/bin").mkdirs()
    wrapperFile().apply {
      writeText("stub-wrapper")
      setWritable(false, false) // W^X-stripped like a hardened wrapper
    }
    File(context.filesDir, DshPaths.USR_DIR + "/bin/.bash-wrapper").writeText("1")
  }

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    // Pre-place the files ensureProot/rootfsReady look for, so no assets are
    // consulted and no real network is touched.
    fakeProotBin()
    fakeRootfs()
    seedCurrentWrapper()
    runtime =
      ProotRuntime(
        context,
        File(context.filesDir, DshPaths.USR_DIR),
        File(context.filesDir, "dshdata-projects"),
      )
  }

  @Test
  fun `rootfsReady requires container bash`() {
    assertTrue(runtime.rootfsReady())
    File(context.filesDir, DshPaths.ROOTFS_DIR + "/bin/bash").delete()
    assertFalse(runtime.rootfsReady())
  }

  @Test
  fun `wrapper short-circuits when marker present`() {
    assertTrue(runtime.ensureWrapper())
    // The seeded wrapper is left untouched (no extraction, no rewrite).
    assertEquals("stub-wrapper", wrapperFile().readText(Charsets.US_ASCII))
  }

  @Test
  fun `ensureProot requires the shared libs too`() {
    // proot binary present but libtalloc missing: cannot short-circuit, and
    // with empty assets the extraction cannot succeed either.
    File(context.filesDir, "proot/libtalloc.so.2").delete()
    assertFalse(runtime.ensureProot())
  }

  @Test
  fun `wrapper is idempotent`() {
    assertTrue(runtime.ensureWrapper())
    val first = wrapperFile().readText(Charsets.US_ASCII)
    assertTrue(runtime.ensureWrapper())
    assertEquals(first, wrapperFile().readText(Charsets.US_ASCII))
  }

  @Test
  fun `wrapper needs re-extraction when the marker is missing`() {
    // Marker gone (snapshot swap / first run of a new version): the wrapper
    // must be re-extracted from the APK. The Robolectric env has no APK lib/
    // entry, so extraction fails cleanly instead of short-circuiting.
    File(context.filesDir, DshPaths.USR_DIR + "/bin/.bash-wrapper").delete()
    assertFalse(runtime.ensureWrapper())
  }

  @Test
  fun `wrapper re-hardens a writable current wrapper`() {
    // A current wrapper that regained the write bit (e.g. snapshot
    // re-extraction restoring modes) must be re-hardened in place, not left
    // writable — vendors refuse to exec writable files.
    wrapperFile().setWritable(true, false)
    assertTrue(wrapperFile().canWrite())
    assertTrue(runtime.ensureWrapper())
    assertFalse(wrapperFile().canWrite())
    assertEquals("stub-wrapper", wrapperFile().readText(Charsets.US_ASCII))
  }

  @Test
  fun `workspace host dir is created`() {
    runtime.ensureWrapper()
    assertTrue(File(context.filesDir, "dshdata-projects").isDirectory)
  }

  @Test
  fun `mirror configs are written once and user edits survive`() {
    assertTrue(runtime.ensureInitialized())
    val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)
    assertTrue(File(rootfs, DshPaths.CONTAINER_PROJECTS).isDirectory)
    // apt source switched to TUNA with Aliyun commented alternative.
    val apt = File(rootfs, "etc/apt/sources.list.d/ubuntu.sources").readText()
    assertTrue(apt.contains("mirrors.tuna.tsinghua.edu.cn/ubuntu"))
    assertTrue(apt.contains("mirrors.aliyun.com/ubuntu"))
    assertTrue(apt.contains("noble-security"))
    // pip / npm / cargo / go / gem / composer / conda in standard locations.
    assertTrue(File(rootfs, "etc/pip.conf").readText().contains("pypi.tuna.tsinghua.edu.cn"))
    assertTrue(File(rootfs, "etc/npmrc").readText().contains("registry.npmmirror.com"))
    assertTrue(File(rootfs, "root/.cargo/config.toml").readText().contains("crates.io-index"))
    assertTrue(File(rootfs, "etc/profile.d/dsh-mirrors.sh").readText().contains("goproxy.cn"))
    assertTrue(File(rootfs, "root/.bashrc").readText().contains("GOPROXY"))
    assertTrue(File(rootfs, "root/.gemrc").readText().contains("rubygems"))
    assertTrue(File(rootfs, "root/.config/composer/config.json").readText().contains("mirrors.aliyun.com/composer"))
    assertTrue(File(rootfs, "root/.condarc").readText().contains("anaconda"))
    // Marker present: a second init must NOT clobber user edits.
    assertTrue(File(rootfs, "etc/dsh-mirrors-applied").isFile)
    File(rootfs, "etc/pip.conf").writeText("[global]\nindex-url = https://user.example/simple\n")
    runtime.ensureInitialized()
    assertTrue(File(rootfs, "etc/pip.conf").readText().contains("user.example"))
  }

  @Test
  fun `mirror config skips when rootfs missing`() {
    File(context.filesDir, DshPaths.ROOTFS_DIR + "/bin/bash").delete()
    runtime.ensureInitialized()
    assertFalse(File(context.filesDir, DshPaths.ROOTFS_DIR + "/etc/pip.conf").exists())
  }

  @Test
  fun `resolvConf is created once and reused`() {
    val f = runtime.resolvConf()
    assertTrue(f.isFile)
    assertTrue(f.readText().contains("nameserver"))
    val before = f.readText()
    runtime.resolvConf()
    assertEquals(before, f.readText())
  }
}
