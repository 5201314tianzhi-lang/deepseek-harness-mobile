package com.dshmobile.shell

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Rootfs state machine (missing/ready/failed) and single-flight flag.
 * The network install path (download + SHA256 + staging swap) is exercised
 * on-device; the state transitions that gate it are verified here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RootfsDownloaderTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val context: Context get() = RuntimeEnvironment.getApplication()

  @Test
  fun `state is missing when nothing installed`() {
    // Fresh Robolectric app dir: no rootfs.
    assertEquals("missing", RootfsDownloader.state(context))
  }

  @Test
  fun `state is ready when marker present`() {
    val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)
    rootfs.mkdirs()
    File(rootfs, ".ready").writeText("x")
    assertEquals("ready", RootfsDownloader.state(context))
  }

  @Test
  fun `state is failed when rootfs exists without marker`() {
    val rootfs = File(context.filesDir, DshPaths.ROOTFS_DIR)
    rootfs.mkdirs()
    File(rootfs, "bin").mkdirs()
    assertEquals("failed", RootfsDownloader.state(context))
  }

  @Test
  fun `isInstalling is false when idle`() {
    assertFalse(RootfsDownloader.isInstalling())
  }
}
