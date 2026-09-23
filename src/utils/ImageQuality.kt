// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.InputStream

/**
* Lowest picture quality the settings allow; full quality keeps the original file untouched.
* @return Lowest picture quality the settings allow.
*/
const val MIN_IMAGE_QUALITY = 50

/**
* Picture quality of the settings, brought into the allowed range.
* @return Quality percentage between MIN_IMAGE_QUALITY and 100.
*/
fun imageQuality(): Int = Settings.imageQuality.value.coerceIn(MIN_IMAGE_QUALITY, 100)

/**
* Writes a picture file again as JPEG with the quality of the settings; the resolution is not changed.
*
* Usage: Call on a background thread, for files the application has just received from the camera. Full quality leaves the file alone.
* @param file Picture file rewritten in place.
* @return True when the file is left usable, whether it was rewritten or kept as it was.
*/
fun recompressPicture(file: File): Boolean {
  val quality = imageQuality()
  if (quality >= 100 || !file.isFile) return true
  val bitmap = BitmapFactory.decodeFile(file.path) ?: return true
  val temporary = File(file.parentFile, "${file.name}.tmp")
  val written = runCatching {
    temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
  }.getOrDefault(false) && temporary.length() > 0
  if (!written) {
    temporary.delete()
    return true
  }
  return temporary.renameTo(file) || temporary.delete()
}

/**
* Writes a picture read from a stream as JPEG with the quality of the settings.
*
* Usage: Call on a background thread; the caller decides what to do when the source cannot be decoded.
* @param source Stream of the original picture.
* @param file File the JPEG is written to.
* @return True when the picture was written.
*/
fun writeCompressedPicture(source: InputStream, file: File): Boolean {
  val bitmap = BitmapFactory.decodeStream(source) ?: return false
  return runCatching {
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, imageQuality(), it) }
  }.getOrDefault(false) && file.length() > 0
}
