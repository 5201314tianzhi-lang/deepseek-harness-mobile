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

  private fun wrapperText(): String = File(context.filesDir, DshPaths.USR_DIR + "/" + DshPaths.BASH_BIN).readText(Charsets.US_ASCII)

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
  fun `wrapper routes into proot with workspace and env`() {
    assertTrue(runtime.ensureWrapper())
    val w = wrapperText()
    // The original bash must be preserved as bash.termux.
    assertTrue(File(context.filesDir, DshPaths.USR_DIR + "/bin/bash.termux").isFile)
    // Core routing + env invariants.
    assertTrue(w.contains("proot/proot"))
    assertTrue(w.contains("-0"))
    assertTrue(w.contains(File(context.filesDir, DshPaths.ROOTFS_DIR).absolutePath))
    assertTrue(w.contains("/root/projects"))
    assertTrue(w.contains("LD_LIBRARY_PATH=" + File(context.filesDir, "proot").absolutePath))
    assertTrue(w.contains("PROOT_TMP_DIR=" + File(context.filesDir, "home/tmp").absolutePath))
    assertTrue(w.contains("TMPDIR=/tmp"))
    assertTrue(w.contains("resolv.conf"))
    // The interpreter must be the system shell, not the snapshot's usr/bin/sh
    // (an app-data ELF: devices with the exec ban refuse its kernel shebang
    // exec — the LD_PRELOAD hook cannot intercept it).
    assertTrue(w.startsWith("#!/system/bin/sh"))
    val bash = File(context.filesDir, DshPaths.USR_DIR + "/" + DshPaths.BASH_BIN)
    assertTrue(bash.canExecute())
    // W^X: the wrapper itself must not stay writable (vendor W^X rejects
    // exec of writable files, mirroring the snapshot write-bit strip).
    assertFalse(bash.canWrite())
  }

  @Test
  fun `ensureProot requires the shared libs too`() {
    // proot binary present but libtalloc missing: cannot short-circuit, and
    // with empty assets the extraction cannot succeed either.
    File(context.filesDir, "proot/libtalloc.so.2").delete()
    assertFalse(runtime.ensureProot())
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
  fun `wrapper upgrade rewrites the v0-1-1 app-data interpreter`() {
    // The v0.1.x wrapper shebang pointed at the snapshot's usr/bin/sh — an
    // app-data ELF whose kernel-level shebang exec the exec-hook cannot
    // reroute, failing the container chain with EACCES on exec-ban devices.
    // The old "/root/projects" marker alone must NOT short-circuit the
    // rewrite (that marker is present in the broken wrapper too).
    File(context.filesDir, DshPaths.USR_DIR + "/bin/bash.termux").writeText("orig")
    val oldInterpreter = File(context.filesDir, DshPaths.USR_DIR + "/bin/sh").absolutePath
    File(context.filesDir, DshPaths.USR_DIR + "/bin/bash").writeText(
      "#!/$oldInterpreter\n" +
        "if [ ! -x \"${File(context.filesDir, DshPaths.ROOTFS_DIR).absolutePath}/bin/bash\" ]; then\n" +
        "  echo \"Ubuntu container not installed\" >&2\n  exit 127\nfi\n" +
        "LD_LIBRARY_PATH=${File(context.filesDir, "proot").absolutePath}:\$LD_LIBRARY_PATH\n" +
        "exec \"${File(context.filesDir, "proot").absolutePath}/proot\" -0 " +
        "-r \"${File(context.filesDir, DshPaths.ROOTFS_DIR).absolutePath}\" " +
        "-w /root/projects /bin/bash \"\$@\"\n",
    )
    assertTrue(runtime.ensureWrapper())
    val w = wrapperText()
    assertTrue(w.startsWith("#!/system/bin/sh"))
    assertFalse(w.contains(oldInterpreter))
    assertTrue(w.contains("/root/projects"))
  }

  @Test
  fun `wrapper rewrite survives a read-only wrapper`() {
    // W^X strips the write bit after generation; a later rewrite (upgrade)
    // must restore write access before writing the new content.
    assertTrue(runtime.ensureWrapper())
    val bash = File(context.filesDir, DshPaths.USR_DIR + "/bin/bash")
    bash.setWritable(true, false)
    File(context.filesDir, DshPaths.USR_DIR + "/bin/bash.termux").writeText("orig")
    bash.writeText("#!/x\nstale marker\n")
    bash.setWritable(false, false) // simulate the W^X-stripped stale wrapper
    assertTrue(runtime.ensureWrapper())
    assertTrue(wrapperText().startsWith("#!/system/bin/sh"))
    assertTrue(wrapperText().contains("/root/projects"))
    assertFalse(bash.canWrite())
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
