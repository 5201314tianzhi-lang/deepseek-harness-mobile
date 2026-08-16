package com.dshmobile.shell

import android.content.Context
import java.io.File
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

/**
 * Container provisioning: bash wrapper generation (proot routing, env
 * injection, /root/projects workspace) and the China mirror config files.
 * The proot binary is stubbed (ensureProot short-circuits on an existing
 * file) so the wrapper/mirror logic runs without assets.
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
  }

  private fun fakeRootfs() {
    File(context.filesDir, DshPaths.ROOTFS_DIR + "/bin/bash").apply {
      parentFile!!.mkdirs()
      writeText("stub")
    }
  }

  private fun wrapperText(): String =
    File(context.filesDir, DshPaths.USR_DIR + "/" + DshPaths.BASH_BIN).readText(Charsets.US_ASCII)

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    // Pre-place the files ensureProot/rootfsReady look for, so no assets are
    // consulted and no real network is touched.
    fakeProotBin()
    fakeRootfs()
    File(context.filesDir, DshPaths.USR_DIR + "/bin").mkdirs()
    File(context.filesDir, "usr/bin/bash").writeText("original-bash")
    File(context.filesDir, "usr/bin/sh").writeText("sh")
    runtime = ProotRuntime(
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
  fun `wrapper routes into proot with workspace and env`() {
    assertTrue(runtime.ensureWrapper())
    val w = wrapperText()
    // The original bash must be preserved as bash.termux.
    assertTrue(File(context.filesDir, DshPaths.USR_DIR + "/bin/bash.termux").isFile)
    // Core routing + env invariants.
    assertTrue(w.contains("proot/proot"))
    assertTrue(w.contains("-0"))
    assertTrue(w.contains("-r " + File(context.filesDir, DshPaths.ROOTFS_DIR).absolutePath))
    assertTrue(w.contains("/root/projects"))
    assertTrue(w.contains("LD_LIBRARY_PATH=" + File(context.filesDir, "proot").absolutePath))
    assertTrue(w.contains("PROOT_TMP_DIR=" + File(context.filesDir, "home/tmp").absolutePath))
    assertTrue(w.contains("TMPDIR=/tmp"))
    assertTrue(w.contains("resolv.conf"))
    assertTrue(w.startsWith("#!/"))
    assertTrue(File(context.filesDir, DshPaths.USR_DIR + "/" + DshPaths.BASH_BIN).canExecute())
  }

  @Test
  fun `wrapper is idempotent and detects the projects marker`() {
    assertTrue(runtime.ensureWrapper())
    val first = wrapperText()
    assertTrue(runtime.ensureWrapper())
    assertEquals(first, wrapperText())
  }

  @Test
  fun `wrapper upgrade rewrites an old workspace-based wrapper`() {
    File(context.filesDir, DshPaths.USR_DIR + "/bin/bash.termux").writeText("orig")
    File(context.filesDir, DshPaths.USR_DIR + "/bin/bash").writeText(
      "#!/x\nold wrapper with /root/workspace and PROOT_TMP_DIR=yes\n",
    )
    assertTrue(runtime.ensureWrapper())
    assertTrue(wrapperText().contains("/root/projects"))
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
