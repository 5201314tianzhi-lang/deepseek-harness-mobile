package com.dshmobile.shell

/** Bottom-of-guide version line: app version + ABI + snapshot dsh version. */
object VersionLine {
  fun format(
    appVersion: String,
    abi: String,
    dshVersion: String,
  ): String = "v" + appVersion + " · " + abi + " · dsh " + dshVersion
}