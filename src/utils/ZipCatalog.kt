// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Запись оглавления zip: size - распакованный размер, packedSize - сжатый, method - 0 без сжатия, 8 deflate,
    offset - начало локального заголовка записи.
*/
class ZipCatalogEntry(val name: String, val size: Long, val packedSize: Long, val method: Int, val offset: Long)

/** Оглавление zip без чтения содержимого. null - канал без произвольного доступа или оглавление не прочитано
    (в том числе ZIP64). Записи, поля которых вынесены в ZIP64, в ответ не входят.
*/
fun readZipCatalog(channel: FileChannel): Map<String, ZipCatalogEntry>? = runCatching {
  val size = channel.size()
  if (size < ZIP_END_SIZE) return null
  val tailSize = minOf(size, ZIP_END_SIZE + ZIP_MAX_COMMENT).toInt()
  val tail = readZipBlock(channel, size - tailSize, tailSize)
  val end = (tailSize - ZIP_END_SIZE.toInt() downTo 0).firstOrNull { tail.getInt(it) == ZIP_END_SIGNATURE } ?: return null
  val count = tail.getShort(end + 10).toInt() and 0xFFFF
  val catalogSize = tail.getInt(end + 12).toLong() and ZIP_UNKNOWN
  val catalogOffset = tail.getInt(end + 16).toLong() and ZIP_UNKNOWN
  if (catalogOffset == ZIP_UNKNOWN || catalogOffset + catalogSize > size) return null
  val catalog = readZipBlock(channel, catalogOffset, catalogSize.toInt())
  val entries = mutableMapOf<String, ZipCatalogEntry>()
  var at = 0
  repeat(count) {
    if (catalog.getInt(at) != ZIP_ENTRY_SIGNATURE) return null
    val method = catalog.getShort(at + 10).toInt() and 0xFFFF
    val packed = catalog.getInt(at + 20).toLong() and ZIP_UNKNOWN
    val length = catalog.getInt(at + 24).toLong() and ZIP_UNKNOWN
    val nameLength = catalog.getShort(at + 28).toInt() and 0xFFFF
    val extraLength = catalog.getShort(at + 30).toInt() and 0xFFFF
    val commentLength = catalog.getShort(at + 32).toInt() and 0xFFFF
    val offset = catalog.getInt(at + 42).toLong() and ZIP_UNKNOWN
    val name = String(catalog.array(), at + 46, nameLength, Charsets.UTF_8)
    if (length != ZIP_UNKNOWN && packed != ZIP_UNKNOWN && offset != ZIP_UNKNOWN) {
      entries[name] = ZipCatalogEntry(name, length, packed, method, offset)
    }
    at += 46 + nameLength + extraLength + commentLength
  }
  entries
}.getOrNull()

/** Распакованное содержимое записи целиком в памяти, по её месту из оглавления, не читая остальной архив.
    Для небольших записей.
*/
fun readZipEntry(channel: FileChannel, entry: ZipCatalogEntry): ByteArray = openZipEntry(channel, entry).use { it.readBytes() }

/** Поток распакованного содержимого записи прямо с её места в канале; канал не закрывается.
    Сжатие кроме deflate - исключение. Читать крупными блоками: каждое чтение - обращение к каналу.
*/
fun openZipEntry(channel: FileChannel, entry: ZipCatalogEntry): InputStream {
  val header = readZipBlock(channel, entry.offset, ZIP_LOCAL_SIZE)
  if (header.getInt(0) != ZIP_LOCAL_SIGNATURE) error("Bad zip entry header: ${entry.name}")
  val nameLength = header.getShort(26).toInt() and 0xFFFF
  val extraLength = header.getShort(28).toInt() and 0xFFFF
  val start = entry.offset + ZIP_LOCAL_SIZE + nameLength + extraLength
  return when (entry.method) {
    0 -> ZipEntryInput(channel, start, entry.packedSize, false)
    8 -> {
      val inflater = Inflater(true)
      object : InflaterInputStream(ZipEntryInput(channel, start, entry.packedSize, true), inflater, ZIP_INFLATE_BUFFER) {
        override fun close() {
          super.close()
          inflater.end()
        }
      }
    }
    else -> error("Unsupported zip method ${entry.method}: ${entry.name}")
  }
}

/** FOR LOCAL USE
    Сжатые данные записи: left байт канала с позиции position. dummy - лишний нулевой байт в конце, его ждёт
    Inflater без заголовка (так же делает ZipFile).
*/
private class ZipEntryInput(private val channel: FileChannel, private var position: Long, private var left: Long, private var dummy: Boolean) : InputStream() {

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    if (length == 0) return 0
    if (left <= 0) {
      if (!dummy) return -1
      dummy = false
      buffer[offset] = 0
      return 1
    }
    val count = channel.read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), left).toInt()), position)
    if (count < 0) throw EOFException("Unexpected end of zip")
    position += count
    left -= count
    return count
  }

  override fun available() = minOf(left, Int.MAX_VALUE.toLong()).toInt()
}

/** Блок канала с позиции from. FOR LOCAL USE */
private fun readZipBlock(channel: FileChannel, from: Long, size: Int): ByteBuffer {
  val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
  while (buffer.hasRemaining()) {
    if (channel.read(buffer, from + buffer.position()) < 0) error("Unexpected end of zip")
  }
  return buffer
}

private const val ZIP_END_SIGNATURE = 0x06054b50 /** Начало записи конца оглавления. FOR LOCAL USE */
private const val ZIP_ENTRY_SIGNATURE = 0x02014b50 /** Начало записи оглавления. FOR LOCAL USE */
private const val ZIP_LOCAL_SIGNATURE = 0x04034b50 /** Начало локального заголовка записи. FOR LOCAL USE */
private const val ZIP_LOCAL_SIZE = 30 /** Размер локального заголовка без имени и дополнительных полей. FOR LOCAL USE */
private const val ZIP_END_SIZE = 22L /** Размер записи конца оглавления без комментария. FOR LOCAL USE */
private const val ZIP_MAX_COMMENT = 0xFFFFL /** Наибольший комментарий архива за записью конца оглавления. FOR LOCAL USE */
private const val ZIP_UNKNOWN = 0xFFFFFFFFL /** Значение поля, вынесенного в ZIP64. FOR LOCAL USE */
private const val ZIP_INFLATE_BUFFER = 64 * 1024 /** Блок сжатых данных на одно обращение распаковщика. FOR LOCAL USE */
