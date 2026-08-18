package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuidePaletteTest {
  @Test
  fun lightThemeByDefault() {
    val p = GuidePalette(RuntimeEnvironment.getApplication())
    assertFalse(p.dark)
    assertEquals(0xFFFAFAFC.toInt(), p.background)
    assertEquals(0xFF171A21.toInt(), p.textPrimary)
    assertEquals(0xFF4D6BFE.toInt(), p.accent)
    assertEquals(0xFF8B5CF6.toInt(), p.accentEnd)
    assertEquals(0xFF17A26A.toInt(), p.success)
    assertEquals(0xFFD64545.toInt(), p.error)
  }

  @Test
  @Config(qualifiers = "night")
  fun darkThemeFollowsConfiguration() {
    val p = GuidePalette(RuntimeEnvironment.getApplication())
    assertTrue(p.dark)
    assertEquals(0xFF0E1116.toInt(), p.background)
    assertEquals(0xFF161B22.toInt(), p.card)
    assertEquals(0xFFECEEF3.toInt(), p.textPrimary)
    assertEquals(0xFF5B7CFF.toInt(), p.accent)
    assertEquals(0xFF9D6BFF.toInt(), p.accentEnd)
    assertEquals(0xFF2FBF85.toInt(), p.success)
    assertEquals(0xFFE85D5D.toInt(), p.error)
  }
}
