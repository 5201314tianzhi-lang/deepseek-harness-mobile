package com.dshmobile.shell

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import java.util.concurrent.TimeUnit

/**
 * Engine process governance: process-level pickToken singleton, companion
 * state reset, stopEngine destroy semantics and the hung-process kill path
 * in startEngine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineManagerTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    // Reset process-level state so tests are independent.
    EngineManager.engineProcess = null
    EngineManager.STARTING.set(false)
    EngineManager.lastStartAttemptAt = 0
  }

  @Test
  fun `pickToken is a process-level singleton`() {
    val a = EngineManager.pickToken
    val b = EngineManager.pickToken
    assertEquals(a, b)
    assertTrue(a.isNotEmpty())
  }

  @Test
  fun `engineReady reflects snapshot presence`() {
    val manager = EngineManager(context)
    assertFalse(manager.engineReady)
    File(context.filesDir, DshPaths.USR_DIR + "/" + DshPaths.NODE_BIN).apply {
      parentFile!!.mkdirs()
      writeText("node")
    }
    assertTrue(manager.engineReady)
  }

  @Test
  fun `stopEngine destroys the process and clears cooldown`() {
    val process = mockk<Process>(relaxed = true)
    every { process.isAlive } returns true
    EngineManager.engineProcess = process
    EngineManager.lastStartAttemptAt = System.currentTimeMillis()

    EngineManager(context).stopEngine()

    verify { process.destroy() }
    assertNull(EngineManager.engineProcess)
    assertEquals(0L, EngineManager.lastStartAttemptAt)
  }

  @Test
  fun `stopEngine is skipped while a start is in flight`() {
    EngineManager.STARTING.set(true)
    val process = mockk<Process>(relaxed = true)
    EngineManager.engineProcess = process

    EngineManager(context).stopEngine()

    verify(exactly = 0) { process.destroy() }
    assertNotNull(EngineManager.engineProcess)
  }

  @Test
  fun `startEngine kills a hung process past cooldown`() {
    val hung = mockk<Process>(relaxed = true)
    every { hung.isAlive } returns true
    EngineManager.engineProcess = hung
    // Cooldown long expired (last attempt a day ago) -> the process is hung.
    EngineManager.lastStartAttemptAt = System.currentTimeMillis() - 24 * 3600 * 1000L

    // startEngine needs the exec hook; make the hook resolvable by stubbing
    // nativeLibraryDir with a fake hook file.
    val hook = File(context.filesDir, "libexec-hook.so")
    hook.writeText("stub")
    // execHook() reads applicationInfo.nativeLibraryDir first — point it at a
    // non-existent dir so it falls back to filesDir/libexec-hook.so.
    val appInfo = context.applicationInfo
    appInfo.nativeLibraryDir = File(context.filesDir, "no-such-native").absolutePath

    val result = EngineManager(context).startEngine()

    // The engine start itself fails later (node missing) — the important
    // part is the hung process got destroyed before that.
    assertFalse(result)
    verify { hung.destroyForcibly() }
    assertNull(EngineManager.engineProcess)
  }

  @Test
  fun `concurrent starts are single-flighted`() {
    // Make the exec hook resolvable so startEngine gets past the gate.
    File(context.filesDir, "libexec-hook.so").writeText("stub")
    val appInfo = context.applicationInfo
    appInfo.nativeLibraryDir = File(context.filesDir, "no-such-native").absolutePath
    val first = EngineManager(context)
    val second = EngineManager(context)
    // Both try to acquire the CAS; only one can hold it at a time.
    assertTrue(EngineManager.STARTING.compareAndSet(false, true))
    // The second caller must not start (returns true = "deferred/ignored").
    val before = EngineManager.engineProcess
    val result = second.startEngine()
    assertTrue(result)
    assertEquals(before, EngineManager.engineProcess)
    EngineManager.STARTING.set(false)
  }
}
