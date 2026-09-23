// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.net.Uri
import androidx.core.content.FileProvider

/**
 * Supplies stable MIME types for GPX and xTravel exchange files shared through Android FileProvider. Android does not
 * recognize these extensions, so without explicit types receivers may append ".bin" or ".null" to shared files.
 */
class XFileProvider : FileProvider() {
  companion object {
    const val GPX_TYPE = "application/gpx+xml"
    const val XTRAVEL_TYPE = "application/zip"
  }

  /** Returns an explicit MIME type for supported exchange files. */
  override fun getType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
    "gpx" -> GPX_TYPE
    "zip" -> XTRAVEL_TYPE
    else -> super.getType(uri)
  }
}
