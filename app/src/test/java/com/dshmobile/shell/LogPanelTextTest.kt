package com.dshmobile.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class LogPanelTextTest {
  @Test
  fun shareTextCarriesVersionLineAndLog() {
    val text =
      LogPanelText.shareText(
        "v0.1.5 · arm64-v8a · dsh 0.1.0-rc.6",
        "boot: engine flow start",
      )
    assertEquals("dsh v0.1.5 · arm64-v8a · dsh 0.1.0-rc.6\n\nboot: engine flow start\n", text)
  }

  @Test
  fun trailingNewlineNotDuplicated() {
    assertEquals("dsh v1\n\nlog\n", LogPanelText.shareText("v1", "log\n"))
  }
}