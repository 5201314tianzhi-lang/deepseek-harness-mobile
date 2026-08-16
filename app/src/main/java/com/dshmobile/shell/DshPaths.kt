package com.dshmobile.shell

/**
 * Central registry of app-relative paths and asset names. Every path that is
 * not a fixed system path (/system/bin, /system/lib64) resolves through here
 * so package renames, multi-user setups and layout changes never require
 * hunting string literals. System paths are deliberately NOT centralized —
 * they are platform-fixed and appear once per usage site.
 */
object DshPaths {
  /** Snapshot root under filesDir (usr/). */
  const val USR_DIR = "usr"

  /** Rootfs root under filesDir (rootfs/). */
  const val ROOTFS_DIR = "rootfs"

  /** Agent workspace inside the container: /root/projects (host-backed by
   *  Documents/dshdata/projects through the proot bind mount). */
  const val CONTAINER_PROJECTS = "root/projects"

  /** Host-side projects directory (inside the public dshdata). */
  const val PROJECTS_DIR = "projects"

  /** Node executable inside the snapshot. */
  const val NODE_BIN = "bin/node"

  /** Bash (the proot wrapper) inside the snapshot. */
  const val BASH_BIN = "bin/bash"

  /** POSIX sh inside the snapshot (wrapper shebang interpreter). */
  const val SH_BIN = "bin/sh"

  /** Rootfs bash (container /bin/bash). */
  const val ROOTFS_BASH = "bin/bash"

  /** node-pty native module inside the snapshot. */
  const val PTY_NODE = "lib/node_modules/@deepseek-ai/dsh/node_modules/node-pty/build/Release/pty.node"

  /** Embedded snapshot archive (assets). */
  const val SNAPSHOT_ASSET = "snapshot.tar.xz"

  /** Old-WebView compatibility layer (assets). */
  const val COMPAT_JS_ASSET = "js/compat-polyfills.js"

  /** Engine log file inside filesDir (kept out of DSH_HOME migration). */
  const val ENGINE_LOG = "engine.log"

  /** Test/status notification channel id. */
  const val NOTIFICATION_CHANNEL = "dsh"

  /** Wake-lock tag. */
  const val WAKE_LOCK_TAG = "dsh:screen"
}
