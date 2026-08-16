package com.dshmobile.shell

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Update protocol guards: HTTPS-only manifest, single-flight concurrency
 * protection and the failure status path (unreachable default manifest).
 * Full download+swap runs are network-bound (HTTPS-only by design) and are
 * covered by the on-device acceptance checklist instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateManagerTest {

  private fun context(): Context = org.robolectric.RuntimeEnvironment.getApplication()

  @Test
  fun `plaintext manifest URL is rejected at the setter`() {
    val manager = UpdateManager(context())
    assertThrows(IllegalArgumentException::class.java) {
      manager.manifestUrl = "http://example.com/manifest.json"
    }
  }

  @Test
  fun `https manifest URL is accepted`() {
    val manager = UpdateManager(context())
    manager.manifestUrl = "https://example.com/manifest.json"
    assertEquals("https://example.com/manifest.json", manager.manifestUrl)
  }

  @Test
  fun `concurrent runs are single-flighted`() {
    val ctx = context()
    val statuses = java.util.concurrent.ConcurrentLinkedQueue<String>()
    // First run: points at an unreachable host so it stays in-flight long
    // enough for the subsequent calls to hit the single-flight CAS.
    val first = UpdateManager(ctx).apply { manifestUrl = "https://127.0.0.1:1/manifest.json" }
    first.checkAndApply { statuses.add(it) }
    // Give the first run a moment to flip the in-flight flag.
    Thread.sleep(300)
    val second = UpdateManager(ctx)
    second.checkAndApply { statuses.add(it) }
    // A third concurrent call must report "in progress" immediately (CAS).
    val inProgress = java.util.concurrent.atomic.AtomicBoolean(false)
    val latch = CountDownLatch(1)
    val third = UpdateManager(ctx)
    third.checkAndApply {
      if (it == ctx.getString(R.string.update_in_progress)) {
        inProgress.set(true)
        latch.countDown()
      }
    }
    assertTrue("third concurrent run must report in-progress", latch.await(5, TimeUnit.SECONDS))
    assertTrue(inProgress.get())
  }

  @Test
  fun `unreachable manifest surfaces a failed status`() {
    val ctx = context()
    val manager = UpdateManager(ctx).apply { manifestUrl = "https://127.0.0.1:1/manifest.json" }
    val result = java.util.concurrent.atomic.AtomicReference<String>()
    val latch = CountDownLatch(1)
    manager.checkAndApply {
      result.set(it)
      latch.countDown()
    }
    assertTrue("status must arrive", latch.await(20, TimeUnit.SECONDS))
    assertTrue(
      "expected a failure status, got: " + result.get(),
      result.get()!!.contains(ctx.getString(R.string.update_failed, "")),
    )
  }
}
