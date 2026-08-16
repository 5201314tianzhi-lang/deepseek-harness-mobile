package com.dshmobile.shell

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    // First run: a fetcher that blocks until released, keeping the run
    // in-flight long enough for the subsequent calls to hit the CAS.
    val releaseFirst = CountDownLatch(1)
    val first = UpdateManager(ctx)
    first.fetcher = {
      try {
        releaseFirst.await(60, TimeUnit.SECONDS)
      } catch (_: InterruptedException) {
      }
      ""
    }
    first.checkAndApply { statuses.add(it) }
    // Give the first run a moment to flip the in-flight flag.
    Thread.sleep(300)
    val second = UpdateManager(ctx)
    second.checkAndApply { statuses.add(it) }
    // A third concurrent call must report "in progress" immediately (CAS).
    val inProgress =
      java.util.concurrent.atomic
        .AtomicBoolean(false)
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
    // Release the blocking first run so its in-flight flag resets before the
    // next test in this class.
    releaseFirst.countDown()
    Thread.sleep(1000)
  }

  @Test
  fun `unreachable manifest surfaces a failed status`() {
    val ctx = context()
    val manager = UpdateManager(ctx)
    // Robolectric cannot reach the network deterministically; inject a
    // fetcher that fails like an unreachable manifest URL would.
    manager.fetcher = { throw java.io.IOException("Connection refused") }
    val result =
      java.util.concurrent.atomic
        .AtomicReference<String>()
    val latch = CountDownLatch(1)
    manager.checkAndApply {
      result.set(it)
      // onStatus fires for every progress step (checking → failed); only the
      // final failure status releases the latch.
      if (it.contains(ctx.getString(R.string.update_failed, ""))) {
        latch.countDown()
      }
    }
    assertTrue("status must arrive", latch.await(20, TimeUnit.SECONDS))
    assertTrue(
      "expected a failure status, got: " + result.get(),
      result.get()!!.contains(ctx.getString(R.string.update_failed, "")),
    )
  }
}
