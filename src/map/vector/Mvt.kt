// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

/** Векторный тайл Mapbox Vector Tile 2.1 (protobuf), разобранный в память без библиотек.
    Геометрия остаётся командами MVT в координатах 0..extent; её раскрывает MvtFeature.geometry.
*/
class MvtTile(val layers: Map<String, MvtLayer>) {
  companion object {
    /** only - разобрать только слои с этими именами (остальные пропускаются без разбора объектов); null - все. */
    fun parse(data: ByteArray, only: Set<String>? = null): MvtTile {
      val layers = HashMap<String, MvtLayer>()
      val reader = ProtoReader(data, 0, data.size)
      while (reader.hasMore()) {
        val tag = reader.tag()
        if (tag != (3 shl 3 or 2)) {
          reader.skip(tag)
          continue
        }
        val layer = reader.sub()
        if (only != null && MvtLayer.name(layer.copy()) !in only) continue
        MvtLayer.parse(layer).let { layers[it.name] = it }
      }
      return MvtTile(layers)
    }
  }
}

class MvtLayer(val name: String, val extent: Int, private val keys: List<String>, private val values: List<Any?>, val features: List<MvtFeature>) {

  /** Значение атрибута: String, Double, Long или Boolean; null - атрибута нет. */
  fun attr(feature: MvtFeature, key: String): Any? {
    val tags = feature.tags
    var i = 0
    while (i + 1 < tags.size) {
      if (keys.getOrNull(tags[i]) == key) return values.getOrNull(tags[i + 1])
      i += 2
    }
    return null
  }

  fun string(feature: MvtFeature, key: String): String? = attr(feature, key) as? String

  fun number(feature: MvtFeature, key: String): Double? = (attr(feature, key) as? Number)?.toDouble()

  /** Признак есть и не false (в данных Protomaps признаки вида is_tunnel пишутся только как true). */
  fun flag(feature: MvtFeature, key: String): Boolean = attr(feature, key).let { it != null && it != false }

  companion object {
    /** Только имя слоя, без разбора объектов. */
    internal fun name(reader: ProtoReader): String {
      while (reader.hasMore()) {
        val tag = reader.tag()
        if (tag == (1 shl 3 or 2)) return reader.string() else reader.skip(tag)
      }
      return ""
    }

    internal fun parse(reader: ProtoReader): MvtLayer {
      var name = ""
      var extent = 4096
      val keys = ArrayList<String>()
      val values = ArrayList<Any?>()
      val features = ArrayList<MvtFeature>()
      while (reader.hasMore()) {
        val tag = reader.tag()
        when (tag) {
          1 shl 3 or 2 -> name = reader.string()
          2 shl 3 or 2 -> features += MvtFeature.parse(reader.sub())
          3 shl 3 or 2 -> keys += reader.string()
          4 shl 3 or 2 -> values += value(reader.sub())
          5 shl 3 -> extent = reader.varint().toInt()
          else -> reader.skip(tag)
        }
      }
      return MvtLayer(name, extent, keys, values, features)
    }

    private fun value(reader: ProtoReader): Any? {
      var result: Any? = null
      while (reader.hasMore()) {
        val tag = reader.tag()
        result = when (tag) {
          1 shl 3 or 2 -> reader.string()
          2 shl 3 or 5 -> Float.fromBits(reader.fixed32()).toDouble()
          3 shl 3 or 1 -> Double.fromBits(reader.fixed64())
          4 shl 3 -> reader.varint()
          5 shl 3 -> reader.varint()
          6 shl 3 -> reader.varint().let { (it ushr 1) xor -(it and 1) }
          7 shl 3 -> reader.varint() != 0L
          else -> { reader.skip(tag); result }
        }
      }
      return result
    }
  }
}

class MvtFeature(val type: Int, val tags: IntArray, private val commands: IntArray) {

  /** Части геометрии: кольца полигона или линии - массивы x0,y0,x1,y1... в координатах тайла; для точек - по точке на массив. */
  fun geometry(): List<FloatArray> {
    val parts = ArrayList<FloatArray>()
    var current = FloatArrayBuilder()
    var x = 0
    var y = 0
    var i = 0
    while (i < commands.size) {
      val header = commands[i++]
      val id = header and 0x7
      val count = header ushr 3
      when (id) {
        MOVE_TO, LINE_TO -> repeat(count) {
          if (i + 1 >= commands.size) return parts
          x += zigzag(commands[i++])
          y += zigzag(commands[i++])
          if (id == MOVE_TO && current.size > 0) {
            parts += current.toArray()
            current = FloatArrayBuilder()
          }
          current.add(x.toFloat(), y.toFloat())
        }
        CLOSE_PATH -> if (current.size >= 2) current.add(current[0], current[1])
        else -> return parts
      }
    }
    if (current.size > 0) parts += current.toArray()
    return parts
  }

  companion object {
    const val POINT = 1
    const val LINE = 2
    const val POLYGON = 3

    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7

    private fun zigzag(value: Int) = (value ushr 1) xor -(value and 1)

    internal fun parse(reader: ProtoReader): MvtFeature {
      var type = 0
      var tags = IntArray(0)
      var commands = IntArray(0)
      while (reader.hasMore()) {
        val tag = reader.tag()
        when (tag) {
          2 shl 3 or 2 -> tags = reader.packed()
          3 shl 3 -> type = reader.varint().toInt()
          4 shl 3 or 2 -> commands = reader.packed()
          else -> reader.skip(tag)
        }
      }
      return MvtFeature(type, tags, commands)
    }
  }
}

/** FOR LOCAL USE: растущий массив координат без упаковки в Float. */
private class FloatArrayBuilder {
  private var data = FloatArray(16)
  var size = 0
    private set

  operator fun get(index: Int) = data[index]

  fun add(x: Float, y: Float) {
    if (size + 2 > data.size) data = data.copyOf(data.size * 2)
    data[size++] = x
    data[size++] = y
  }

  fun toArray(): FloatArray = data.copyOf(size)
}

/** Чтение protobuf: поля, varint, вложенные сообщения. Неизвестные поля пропускаются по типу. FOR LOCAL USE */
internal class ProtoReader(private val data: ByteArray, private var pos: Int, private val end: Int) {

  fun hasMore() = pos < end

  /** Независимая копия с той же позицией. */
  fun copy() = ProtoReader(data, pos, end)

  fun tag(): Int = varint().toInt()

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

  fun fixed32(): Int {
    var result = 0
    for (i in 0 until 4) result = result or ((data[pos++].toInt() and 0xFF) shl (8 * i))
    return result
  }

  fun fixed64(): Long {
    var result = 0L
    for (i in 0 until 8) result = result or ((data[pos++].toLong() and 0xFF) shl (8 * i))
    return result
  }

  fun sub(): ProtoReader {
    val length = varint().toInt()
    val reader = ProtoReader(data, pos, pos + length)
    pos += length
    return reader
  }

  fun string(): String {
    val length = varint().toInt()
    val text = String(data, pos, length, Charsets.UTF_8)
    pos += length
    return text
  }

  fun packed(): IntArray {
    val reader = sub()
    var result = IntArray(16)
    var size = 0
    while (reader.hasMore()) {
      if (size == result.size) result = result.copyOf(size * 2)
      result[size++] = reader.varint().toInt()
    }
    return result.copyOf(size)
  }

  fun skip(tag: Int) {
    when (tag and 0x7) {
      0 -> varint()
      1 -> pos += 8
      2 -> pos += varint().toInt()
      5 -> pos += 4
      else -> throw IllegalStateException("unsupported protobuf wire type")
    }
  }
}
