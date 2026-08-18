package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class VersionLineTest {
  @Test
  fun formatAssemblesAllParts() {
    assertEquals(
      "v0.1.5 · arm64-v8a · dsh 0.1.0-rc.6",
      VersionLine.format("0.1.5", "arm64-v8a", "0.1.0-rc.6"),
    )
  }
}
