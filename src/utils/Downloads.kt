// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.IOException
import java.io.OutputStream

/** MIME-тип по расширению имени файла; неизвестный - application/octet-stream */
fun mimeTypeOf(name: String): String =
  MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

/** Пишет файл в общий каталог Download через MediaStore (Android 10+, ниже - IOException). Файл скрыт, пока write
    не закончит; исключение из write удаляет недописанный файл и уходит дальше. При совпадении имён новое имя даёт система,
    его и возвращает.
*/
fun saveToDownloads(name: String, mime: String = mimeTypeOf(name), write: (OutputStream) -> Unit): String {
  if (Build.VERSION.SDK_INT < 29) throw IOException("Download folder needs Android 10")
  val resolver = AppSession.app.contentResolver
  val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
    put(MediaStore.MediaColumns.MIME_TYPE, mime)
    put(MediaStore.MediaColumns.IS_PENDING, 1)
  }) ?: throw IOException("File is not created")
  try {
    (resolver.openOutputStream(target) ?: throw IOException("File is not opened")).use(write)
    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
  } catch (e: Throwable) {
    runCatching { resolver.delete(target, null, null) }
    throw e
  }
  return displayNameOf(target) ?: name
}

/** Имя документа по content-uri от его поставщика; null - не узнать */
fun displayNameOf(uri: Uri): String? = runCatching {
  AppSession.app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else null
  }
}.getOrNull()
