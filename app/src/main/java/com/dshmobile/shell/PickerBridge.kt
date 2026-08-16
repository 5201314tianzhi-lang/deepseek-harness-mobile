package com.dshmobile.shell

import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * SAF-based directory and file picking for the Harness bridge.
 *
 * Directory picking (workspaces) goes through OpenDocumentTree with an All
 * Files Access walkthrough; file uploads (<input type=file>) use
 * OpenMultipleDocuments. The two flows must stay separate (a single-slot
 * pending callback would otherwise overwrite the in-flight request).
 * Results are delivered back to the page through injected callbacks.
 */
class PickerBridge(
  private val activity: ComponentActivity,
  private val onDirectoryPicked: (callbackId: String, path: String?) -> Unit,
  private val onPermissionRequired: () -> Unit,
  private val notify: (title: String, text: String) -> Unit,
) {

  private var pendingPickCallback: String? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null

  private val directoryPicker =
    activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      val callback = pendingPickCallback
      pendingPickCallback = null
      if (callback != null) {
        if (uri != null) {
          val path = AndroidBridge.resolvePickedPath(uri)
          onDirectoryPicked(callback, path)
        } else {
          // User cancelled: report null so the engine-side pick() settles as a
          // cancellation (otherwise the page polling the same request would keep
          // re-opening the picker — observed picker stacking on device).
          onDirectoryPicked(callback, null)
        }
      }
    }

  private val filePicker =
    activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      val callback = filePathCallback
      filePathCallback = null
      if (callback != null) {
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
      }
    }

  /** WebChromeClient.onShowFileChooser entry: multi-select, any type. */
  fun handleFileChooser(filePathCallback: ValueCallback<Array<Uri>>): Boolean {
    this.filePathCallback?.onReceiveValue(null)
    this.filePathCallback = filePathCallback
    filePicker.launch(emptyArray())
    return true
  }

  /**
   * SAF directory picking (with an All Files Access walkthrough): the external
   * workspace requires the bash process to reach the picked real path directly;
   * when the permission is missing, jump to the system grant screen and let
   * the page prompt the user to retry.
   */
  fun pickDirectoryWithPermissionCheck(callbackId: String) {
    // Concurrency guard: reject a new request while one is in flight (the
    // single-slot pendingPickCallback would be overwritten and the earlier
    // engine pick would never settle).
    if (pendingPickCallback != null) {
      onDirectoryPicked(callbackId, null)
      return
    }
    if (android.os.Build.VERSION.SDK_INT < 30) {
      // Android 10 and below have no All Files Access model: the external
      // workspace is unavailable. Report null so the engine-side pick settles
      // as a cancellation — no crash, no silent hang.
      onDirectoryPicked(callbackId, null)
      notify(
        activity.getString(R.string.notif_workspace_unavailable),
        activity.getString(R.string.notif_workspace_unavailable_detail),
      )
      return
    }
    if (android.os.Environment.isExternalStorageManager()) {
      pendingPickCallback = callbackId
      directoryPicker.launch(null)
      return
    }
    openAllFilesAccessSettings()
    onPermissionRequired()
  }

  /** Open the system All Files Access screen for this app. */
  fun openAllFilesAccessSettings() {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    try {
      activity.startActivity(
        android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
          .setData(Uri.parse("package:" + activity.packageName)),
      )
    } catch (_: Exception) {
      // Some OEMs lack the per-app screen; fall back to the global one.
      try {
        activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      } catch (_: Exception) {
        // No entry point at all: ignore silently (the engine side settles as
        // a cancellation).
      }
    }
  }
}
