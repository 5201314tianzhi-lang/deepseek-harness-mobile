package com.dshmobile.shell

/**
 * Central registry of app-relative paths and asset names. Every path that is
 * not a fixed system path (/system/bin, /system/lib64) resolves through here
 * so package renames, multi-user setups and layout changes never require
 * hunting string literals. System paths are deliberately NOT centralized —
 * they are platform-fixed and appear once per usage site.
 */
object DshPaths {
  /** Single runtime rootfs under filesDir (rootfs/). */
  const val ROOTFS_DIR = "rootfs"

  /** Rootfs archive (assets). */
  const val ROOTFS_ASSET = "rootfs.tar.xz"

  /** Node inside the rootfs. */
  const val ROOTFS_NODE = "usr/local/bin/node"

  /** dsh entry inside the rootfs (overlay at /root/.dsh-arm64). */
  const val DSH_ENTRY = "root/.dsh-arm64/node_modules/@deepseek-ai/dsh/lib/bin.js"

  /** Rootfs bash (container /bin/bash). */
  const val ROOTFS_BASH = "bin/bash"

  /** Container dsh home (DSH_HOME, private inside the rootfs). */
  const val CONTAINER_DSH_HOME = "root/.dsh"

  /** Agent workspace inside the container: /root/projects (host-backed by
   *  Documents/dshdata/projects through the proot bind mount). */
  const val CONTAINER_PROJECTS = "root/projects"

  /** Host-side projects directory (inside the public dshdata). */
  const val PROJECTS_DIR = "projects"

  /** Old-WebView compatibility layer (assets). */
  const val COMPAT_JS_ASSET = "js/compat-polyfills.js"

  /** Engine log file inside filesDir (kept out of DSH_HOME migration). */
  const val ENGINE_LOG = "engine.log"

  /** Test/status notification channel id. */
  const val NOTIFICATION_CHANNEL = "dsh"

  /** Wake-lock tag. */
  const val WAKE_LOCK_TAG = "dsh:screen"
}