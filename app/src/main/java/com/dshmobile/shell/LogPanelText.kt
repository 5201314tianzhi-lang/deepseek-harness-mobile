package com.dshmobile.shell

/** Text assembly for the diagnostic log panel (share payload). */
object LogPanelText {
  fun shareText(
    versionLine: String,
    logText: String,
  ): String = "dsh " + versionLine + "\n\n" + logText + if (logText.endsWith("\n")) "" else "\n"
}
