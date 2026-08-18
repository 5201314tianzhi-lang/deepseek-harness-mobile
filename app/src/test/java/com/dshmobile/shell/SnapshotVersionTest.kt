package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotVersionTest {
  private val context get() = RuntimeEnvironment.getApplication()

  private fun snapshotPackageJson(): File =
    File(
      context.filesDir,
      DshPaths.ROOTFS_DIR + "/root/.dsh-arm64/node_modules/@deepseek-ai/dsh/package.json",
    )

  @Test
  fun readsVersionFromSnapshotPackageJson() {
    snapshotPackageJson().apply {
      parentFile.mkdirs()
      writeText("{\"name\":\"@deepseek-ai/dsh\",\"version\":\"0.1.0-rc.6\"}")
    }
    SnapshotVersion.clearCache()
    assertEquals("0.1.0-rc.6", SnapshotVersion.read(context))
  }

  @Test
  fun missingSnapshotYieldsQuestionMark() {
    SnapshotVersion.clearCache()
    assertEquals("?", SnapshotVersion.read(context))
  }

  @Test
  fun cachedValueNotReRead() {
    snapshotPackageJson().apply {
      parentFile.mkdirs()
      writeText("{\"version\":\"1.2.3\"}")
    }
    SnapshotVersion.clearCache()
    assertEquals("1.2.3", SnapshotVersion.read(context))
    snapshotPackageJson().delete()
    assertEquals("1.2.3", SnapshotVersion.read(context))
  }
}
