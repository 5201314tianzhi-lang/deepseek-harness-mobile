package com.dshmobile.shell

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SAF tree-URI → real-path mapping. The mapped path must be derived at
 * runtime (no hardcoded /storage/emulated/0) and non-primary volumes must
 * fall back to the opaque URI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidBridgeTest {

  @Test
  fun `primary volume maps to external storage root`() {
    val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AProjects")
    val path = AndroidBridge.resolvePickedPath(uri)
    assertEquals("/storage/emulated/0/Projects", path)
  }

  @Test
  fun `primary volume with nested path maps fully`() {
    val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fcode%2Fmy-app")
    val path = AndroidBridge.resolvePickedPath(uri)
    assertEquals("/storage/emulated/0/Documents/code/my-app", path)
  }

  @Test
  fun `primary volume with empty rel falls back to uri`() {
    val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
    assertEquals(uri.toString(), AndroidBridge.resolvePickedPath(uri))
  }

  @Test
  fun `non-primary volume falls back to opaque uri`() {
    val uri = Uri.parse("content://com.android.externalstorage.documents/tree/sdcard1%3AFoo")
    assertEquals(uri.toString(), AndroidBridge.resolvePickedPath(uri))
  }

  @Test
  fun `malformed uri falls back to its own string`() {
    val uri = Uri.parse("content://authority/tree/none")
    assertEquals(uri.toString(), AndroidBridge.resolvePickedPath(uri))
  }
}
