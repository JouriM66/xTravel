// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.GZIPInputStream

/** Байты файла с произвольным доступом: кусок base..base+size канала. Позиционное чтение потокобезопасно. */
class ChannelBytes(private val channel: FileChannel, private val base: Long, val size: Long, private val owner: Closeable) : Closeable {

  fun read(offset: Long, length: Int): ByteArray {
    if (offset < 0 || length < 0 || offset + length > size) throw IOException("read outside of file")
    val buffer = ByteBuffer.allocate(length)
    var position = base + offset
    while (buffer.hasRemaining()) {
      val count = channel.read(buffer, position)
      if (count < 0) throw IOException("unexpected end of file")
      position += count
    }
    return buffer.array()
  }

  override fun close() = owner.close()

  companion object {
    const val BUILTIN_MAP = "world.pmtiles" /** Встроенный архив карты в res\assets */

    /** Файл из assets; читается на месте, поэтому в пакете он должен лежать без сжатия (noCompress). */
    fun asset(name: String): ChannelBytes {
      val fd: AssetFileDescriptor = AppSession.app.assets.openFd(name)
      val stream = fd.createInputStream()
      return ChannelBytes(stream.channel, fd.startOffset, fd.length, fd)
    }

    fun uri(uri: Uri): ChannelBytes {
      val pfd: ParcelFileDescriptor = AppSession.app.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("cannot open $uri")
      val channel = FileInputStream(pfd.fileDescriptor).channel
      return ChannelBytes(channel, 0, channel.size(), pfd)
    }
  }
}

/** Архив PMTiles v3 (векторные тайлы MVT). Тайлы и каталоги отдаются распакованными. Потокобезопасен.
    Формат: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
*/
class PmTiles(private val bytes: ChannelBytes) : Closeable {

  /** FOR LOCAL USE: запись каталога; runLength 0 - ссылка на вложенный каталог. */
  private class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

  val fileSize: Long get() = bytes.size
  val minZoom: Int
  val maxZoom: Int
  val minLon: Double
  val minLat: Double
  val maxLon: Double
  val maxLat: Double

  private val rootOffset: Long
  private val rootLength: Int
  private val leafOffset: Long
  private val dataOffset: Long
  private val internalGzip: Boolean
  private val tileGzip: Boolean
  private val root: Array<Entry>
  private val leaves = LruCache<Long, Array<Entry>>(LEAF_CACHE)

  init {
    if (bytes.size < HEADER_SIZE) throw IOException("not a PMTiles file")
    val header = ByteBuffer.wrap(bytes.read(0, HEADER_SIZE)).order(ByteOrder.LITTLE_ENDIAN)
    val magic = String(ByteArray(7) { header.get(it) }, Charsets.US_ASCII)
    if (magic != "PMTiles" || header.get(7).toInt() != 3) throw IOException("not a PMTiles v3 file")
    rootOffset = header.getLong(8)
    rootLength = header.getLong(16).toInt()
    leafOffset = header.getLong(40)
    dataOffset = header.getLong(56)
    internalGzip = compression(header.get(97).toInt())
    tileGzip = compression(header.get(98).toInt())
    if (header.get(99).toInt() != TILE_TYPE_MVT) throw IOException("PMTiles archive is not a vector map")
    minZoom = header.get(100).toInt() and 0xFF
    maxZoom = header.get(101).toInt() and 0xFF
    minLon = header.getInt(102) / 1e7
    minLat = header.getInt(106) / 1e7
    maxLon = header.getInt(110) / 1e7
    maxLat = header.getInt(114) / 1e7
    root = directory(rootOffset, rootLength)
  }

  /** Распакованный тайл MVT; null - тайла в архиве нет. */
  fun tile(z: Int, x: Int, y: Int): ByteArray? {
    if (z < minZoom || z > maxZoom) return null
    val id = tileId(z, x, y)
    var dir = root
    repeat(MAX_DEPTH) {
      val entry = find(dir, id) ?: return null
      if (entry.runLength > 0) return unpack(bytes.read(dataOffset + entry.offset, entry.length), tileGzip)
      val key = leafOffset + entry.offset
      dir = leaves.get(key) ?: directory(key, entry.length).also { leaves.put(key, it) }
    }
    return null
  }

  /** Все тайлы уровня z, распакованными. Серия тайлов с одним содержимым отдаётся один раз - первым тайлом:
      одинаковое содержимое бывает только у пустых мест (океан, суша без объектов).
  */
  fun forEachTile(z: Int, action: (x: Int, y: Int, data: ByteArray) -> Unit) {
    if (z < minZoom || z > maxZoom) return
    val first = tileId(z, 0, 0)
    val last = first + (1L shl (2 * z))
    fun walk(dir: Array<Entry>, depth: Int) {
      dir.forEachIndexed { index, entry ->
        if (entry.runLength == 0) {
          // Вложенный каталог покрывает номера до начала следующей записи.
          val end = dir.getOrNull(index + 1)?.tileId ?: Long.MAX_VALUE
          if (depth < MAX_DEPTH && entry.tileId < last && end > first) walk(directory(leafOffset + entry.offset, entry.length), depth + 1)
        } else if (entry.tileId in first until last) {
          val t = entry.tileId - first
          val (x, y) = position(z, t)
          action(x, y, unpack(bytes.read(dataOffset + entry.offset, entry.length), tileGzip))
        }
      }
    }
    walk(root, 1)
  }

  override fun close() = bytes.close()

  // Обратное к tileId: положение тайла уровня z по его номеру внутри уровня.
  private fun position(z: Int, index: Long): Pair<Int, Int> {
    var t = index
    var x = 0L
    var y = 0L
    for (a in 0 until z) {
      val s = 1L shl a
      val rx = 1L and (t / 2)
      val ry = 1L and (t xor rx)
      if (ry == 0L) {
        if (rx == 1L) {
          x = s - 1 - x
          y = s - 1 - y
        }
        val swap = x
        x = y
        y = swap
      }
      x += s * rx
      y += s * ry
      t /= 4
    }
    return x.toInt() to y.toInt()
  }

  private fun compression(code: Int): Boolean = when (code) {
    COMPRESSION_NONE, COMPRESSION_UNKNOWN -> false
    COMPRESSION_GZIP -> true
    else -> throw IOException("unsupported PMTiles compression $code")
  }

  private fun directory(offset: Long, length: Int): Array<Entry> {
    val data = unpack(bytes.read(offset, length), internalGzip)
    var pos = 0
    fun varint(): Long {
      var result = 0L
      var shift = 0
      while (true) {
        val b = data[pos++].toInt()
        result = result or ((b and 0x7F).toLong() shl shift)
        if (b and 0x80 == 0) return result
        shift += 7
      }
    }
    val count = varint().toInt()
    val ids = LongArray(count)
    var last = 0L
    for (i in 0 until count) { last += varint(); ids[i] = last }
    val runs = IntArray(count) { varint().toInt() }
    val lengths = IntArray(count) { varint().toInt() }
    val offsets = LongArray(count)
    for (i in 0 until count) {
      val value = varint()
      offsets[i] = if (value == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else value - 1
    }
    return Array(count) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
  }

  // Ближайшая запись не дальше id: прямое попадание, начало серии, в которую он входит, или вложенный каталог.
  private fun find(entries: Array<Entry>, id: Long): Entry? {
    var low = 0
    var high = entries.size - 1
    while (low <= high) {
      val middle = (low + high) ushr 1
      val diff = id - entries[middle].tileId
      when {
        diff > 0 -> low = middle + 1
        diff < 0 -> high = middle - 1
        else -> return entries[middle]
      }
    }
    if (high < 0) return null
    val entry = entries[high]
    return if (entry.runLength == 0 || id - entry.tileId < entry.runLength) entry else null
  }

  companion object {
    private const val HEADER_SIZE = 127
    private const val MAX_DEPTH = 4
    private const val LEAF_CACHE = 64
    private const val TILE_TYPE_MVT = 1
    private const val COMPRESSION_UNKNOWN = 0
    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_GZIP = 2

    fun unpack(data: ByteArray, gzip: Boolean): ByteArray =
      if (gzip) GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() } else data

    /** Номер тайла по кривой Гильберта, как в спецификации PMTiles. */
    fun tileId(z: Int, x: Int, y: Int): Long {
      var acc = 0L
      for (level in 0 until z) acc += 1L shl (2 * level)
      var tx = x.toLong()
      var ty = y.toLong()
      var d = 0L
      var s = (1L shl z) / 2
      while (s > 0) {
        val rx = if ((tx and s) != 0L) 1L else 0L
        val ry = if ((ty and s) != 0L) 1L else 0L
        d += s * s * ((3 * rx) xor ry)
        if (ry == 0L) {
          if (rx == 1L) {
            tx = s - 1 - tx
            ty = s - 1 - ty
          }
          val t = tx
          tx = ty
          ty = t
        }
        s /= 2
      }
      return acc + d
    }
  }
}
