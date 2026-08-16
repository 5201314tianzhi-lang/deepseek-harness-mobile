package com.dshmobile.shell

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shizuku integration degrades gracefully when the server is absent
 * (emulator/CI environment): never throws, reports the absent status, and
 * the battery-optimization check reflects the (default) non-exempt state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShizukuSupportTest {

  @Test
  fun `isAvailable is false without a Shizuku binder`() {
    assertFalse(ShizukuSupport.isAvailable())
  }

  @Test
  fun `status reports absent without throwing`() {
    val context: Context = RuntimeEnvironment.getApplication()
    val s = ShizukuSupport.status(context)
    assertTrue(s.isNotEmpty())
  }

  @Test
  fun `applyAppOpsBoost reports absent without throwing`() {
    val context: Context = RuntimeEnvironment.getApplication()
    val result = java.util.concurrent.atomic.AtomicReference<String>()
    val latch = java.util.concurrent.CountDownLatch(1)
    ShizukuSupport.applyAppOpsBoost(context) {
      result.set(it)
      latch.countDown()
    }
    assertTrue("callback must fire", latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    assertTrue(result.get()!!.isNotEmpty())
  }

  @Test
  fun `battery optimization is not ignored by default`() {
    val context: Context = RuntimeEnvironment.getApplication()
    assertFalse(ShizukuSupport.isBatteryOptimizationIgnored(context))
  }
}
