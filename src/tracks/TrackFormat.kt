// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// Field names of a track sample, described in PROJECT.md.
/**
* Defines field identifiers and their canonical order in track sample files.
*
* Public and subclass/module-facing members:
* - [TIME] - Track column identifier for the sample timestamp.
* - [LAT] - Track column identifier for latitude in degrees.
* - [LON] - Track column identifier for longitude in degrees.
* - [ELE] - Track column identifier for elevation in meters.
* - [SPEED] - Track column identifier for speed in meters per second.
* - [COURSE] - Track column identifier for course clockwise from north in degrees.
* - [HACC] - Track column identifier for horizontal accuracy in meters.
* - [VACC] - Track column identifier for vertical accuracy in meters.
* - [SACC] - Track column identifier for speed accuracy in meters per second.
* - [CACC] - Track column identifier for bearing accuracy in degrees.
* - [SAT] - Track column identifier for the reported satellite count.
* - [ALL] - Canonical ordered field list used when writing and resuming recordings.
*/
object TrackFields {
  /**
  * Track column identifier for the sample timestamp.
  * @return Track column identifier for the sample timestamp.
  */
  const val TIME = "time"
  /**
  * Track column identifier for latitude in degrees.
  * @return Track column identifier for latitude in degrees.
  */
  const val LAT = "lat"
  /**
  * Track column identifier for longitude in degrees.
  * @return Track column identifier for longitude in degrees.
  */
  const val LON = "lon"
  /**
  * Track column identifier for elevation in meters.
  * @return Track column identifier for elevation in meters.
  */
  const val ELE = "ele"
  /**
  * Track column identifier for speed in meters per second.
  * @return Track column identifier for speed in meters per second.
  */
  const val SPEED = "speed"
  /**
  * Track column identifier for course clockwise from north in degrees.
  * @return Track column identifier for course clockwise from north in degrees.
  */
  const val COURSE = "course"
  /**
  * Track column identifier for horizontal accuracy in meters.
  * @return Track column identifier for horizontal accuracy in meters.
  */
  const val HACC = "hacc"
  /**
  * Track column identifier for vertical accuracy in meters.
  * @return Track column identifier for vertical accuracy in meters.
  */
  const val VACC = "vacc"
  /**
  * Track column identifier for speed accuracy in meters per second.
  * @return Track column identifier for speed accuracy in meters per second.
  */
  const val SACC = "sacc"
  /**
  * Track column identifier for bearing accuracy in degrees.
  * @return Track column identifier for bearing accuracy in degrees.
  */
  const val CACC = "cacc"
  /**
  * Track column identifier for the reported satellite count.
  * @return Track column identifier for the reported satellite count.
  */
  const val SAT = "sat"
  /**
  * Canonical ordered field list used when writing and resuming recordings.
  * @return Canonical ordered field list used when writing and resuming recordings.
  */
  val ALL = listOf(TIME, LAT, LON, ELE, SPEED, COURSE, HACC, VACC, SACC, CACC, SAT)
}

const val TRACK_DATA_EXT = "xtrack" /** Расширение файла точек трека */
private const val SEPARATOR = ";"

/**
* Formats sample timestamps and filename-safe track names and parses ISO timestamps.
*
* Public and subclass/module-facing members:
* - [format] - Formats an epoch timestamp with milliseconds and the device's zone offset.
* - [parse] - Parses an offset date-time or UTC instant.
* - [name] - Имя трека по умолчанию: местные дата и минута начала.
*/
object TrackTime {

  private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
  private val NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")

  /**
  * Formats an epoch timestamp with milliseconds and the device's zone offset.
  * @param ms Timestamp in milliseconds since the Unix epoch.
  * @return An ISO-style timestamp string.
  */
  fun format(ms: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(ISO)

  /**
  * Parses an offset date-time or UTC instant.
  * @param text ISO offset date-time or instant to parse.
  * @return Epoch milliseconds, or null when neither format parses.
  */
  fun parse(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
    ?: runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

  /** Имя трека по умолчанию: местные дата и минута начала */
  fun name(ms: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(NAME)
}

/** Трек: id - имя файла точек (UUID), name - имя для показа. Начало, конец и число точек хранятся в data.xml,
    поиск дублей сравнивает их все. highlighted живёт только в памяти.
*/
data class TrackHeader(
  val id: String,
  val name: String,
  val start: Long,
  val end: Long,
  val startLat: Double,
  val startLon: Double,
  val endLat: Double,
  val endLon: Double,
  val points: Int,
  val visible: Boolean,
  val highlighted: Boolean = false
)

// Track in world coordinates for drawing: points from `from` to size - 1.
/**
* Holds projected track coordinates for drawing; only indices from from to size minus one are valid.
*
* Usage: The arrays may have unused capacity or an appended tail. Read only indices from from until size and do not mutate published coordinates.
*
* Public and subclass/module-facing members:
* - [Companion] - Shared factory, state and lifecycle operations for TrackLine.
* @param wx Projected horizontal coordinate, or array of those coordinates.
* @param wy Projected vertical coordinate, or array of those coordinates.
* @param size Number of initialized coordinate pairs, no larger than either array length.
* @param from First visible array index, inclusive; must lie in 0..size.
* @property wx Projected horizontal coordinate, or array of those coordinates.
* @property wy Projected vertical coordinate, or array of those coordinates.
* @property size Number of initialized coordinate pairs, no larger than either array length.
* @property from First visible array index, inclusive; must lie in 0..size.
*/
class TrackLine(
  val wx: DoubleArray,
  val wy: DoubleArray,
  val size: Int,
  val from: Int = 0,
  val projection: IMapProjection = MercatorProjection /** Проекция координат; рисовать можно только в ней же */
) {

  /** Та же линия в другой проекции, новыми массивами */
  fun reprojected(target: IMapProjection): TrackLine {
    if (target === projection) return this
    val x = DoubleArray(size)
    val y = DoubleArray(size)
    for (i in 0 until size) {
      val world = target.toWorld(projection.toGeo(WorldPoint(wx[i], wy[i])))
      x[i] = world.x
      y[i] = world.y
    }
    return TrackLine(x, y, size, from, target)
  }

  /**
  * Shared factory, state and lifecycle operations for TrackLine.
  *
  * Public and subclass/module-facing members:
  * - [EMPTY] - Empty projected track snapshot shared before any geometry is available.
  */
  companion object {
    /**
    * Empty projected track snapshot shared before any geometry is available.
    * @return Empty projected track snapshot shared before any geometry is available.
    */
    val EMPTY = TrackLine(DoubleArray(0), DoubleArray(0), 0)
  }
}

/** Сводка трека по мере чтения или записи точек: начало, конец, число точек */
class HeaderBuilder {
  var count = 0
    private set
  private var start = 0L
  private var end = 0L
  private var startLat = 0.0
  private var startLon = 0.0
  private var endLat = 0.0
  private var endLon = 0.0

  val startTime get() = start /** Время первой точки; 0 - точек нет */

  fun add(time: Long, lat: Double, lon: Double) {
    if (count == 0) {
      start = time
      startLat = lat
      startLon = lon
    }
    count++
    end = time
    endLat = lat
    endLon = lon
  }

  fun build(id: String, name: String, visible: Boolean) =
    TrackHeader(id, name, start, end, startLat, startLon, endLat, endLon, count, visible)
}

// Data file: first line is the field list, then one sample per line, fields separated by ";".
/** Файлы точек треков: tracks/<id>.xtrack. Первая строка - список полей, по нему разбор находит нужные столбцы.
    Выбор потока и сериализацию делает вызывающий.
*/
object TrackFiles {

  fun newId(): String = UUID.randomUUID().toString()

  fun dataFile(id: String, dir: File = AppDirs.tracks) = File(dir, "$id.$TRACK_DATA_EXT")

  /**
  * Writes the semicolon-separated sample field names and a newline.
  * @param writer Caller-owned text writer; the function leaves it open.
  * @param fields Ordered sample-column identifiers. Default: TrackFields.ALL.
  * @return Unit; leaves the caller-owned writer open.
  */
  fun writeFieldLine(writer: Writer, fields: List<String> = TrackFields.ALL) {
    writer.write(fields.joinToString(SEPARATOR))
    writer.write("\n")
  }

  /**
  * Writes a GPS sample in TrackFields.ALL order, leaving unavailable values empty.
  *
  * Usage: The writer's header must use the same canonical field order.
  * @param writer Caller-owned text writer; the function leaves it open.
  * @param fix GPS sample whose coordinates and optional sensor fields are processed.
  * @return Unit; leaves the caller-owned writer open.
  */
  fun writeFix(writer: Writer, fix: GpsMeasure) {
    val values = listOf(
     TrackTime.format(fix.time),
     fix.lat.toString(),
     fix.lon.toString(),
     fix.elevation?.toString().orEmpty(),
     fix.speed?.toString().orEmpty(),
     fix.course?.toString().orEmpty(),
     fix.hAccuracy?.toString().orEmpty(),
     fix.vAccuracy?.toString().orEmpty(),
     fix.speedAccuracy?.toString().orEmpty(),
     fix.bearingAccuracy?.toString().orEmpty(),
     fix.nSats?.toString().orEmpty() )
    writer.write(values.joinToString(SEPARATOR))
    writer.write("\n")
  }

  /** Сводка трека полным чтением файла точек; name null - имя по времени начала. null - точек нет */
  fun scan(id: String, name: String?, visible: Boolean, dir: File = AppDirs.tracks): TrackHeader? {
    val builder = HeaderBuilder()
    forEachSample(dataFile(id, dir)) { time, lat, lon, _ -> builder.add(time, lat, lon) }
    if (builder.count == 0) return null
    return builder.build(id, name ?: TrackTime.name(builder.startTime), visible)
  }

  /**
  * Loads sample coordinates into projected arrays.
  *
  * Usage: Run on an IO thread; invalid sample rows are skipped.
  * @param id Track file id in AppDirs.tracks.
  * @param header Optional accumulator updated for every valid sample while geometry is loaded.
  * @return A TrackLine containing all valid positions.
  */
  fun readLine(id: String, projection: IMapProjection, header: HeaderBuilder? = null): TrackLine {
    var wx = DoubleArray(1024)
    var wy = DoubleArray(1024)
    var size = 0
    forEachSample(dataFile(id)) { time, lat, lon, _ ->
      header?.add(time, lat, lon)
      if (size == wx.size) {
        wx = wx.copyOf(size * 2)
        wy = wy.copyOf(size * 2)
      }
      val world = projection.toWorld(lat, lon)
      wx[size] = world.x
      wy[size] = world.y
      size++
    }
    return TrackLine(wx, wy, size, projection = projection)
  }

  /** Удаляет точки: ключ - номер в счёте forEachSample, значение - строка точки. Файл переписывается через временный.
      false - файла нет или какая-то точка не та (файл изменился после чтения), тогда файл не меняется
  */
  fun removeSamples(file: File, rows: Map<Int, String>): Boolean {
    if (!file.isFile) return false
    val temp = File(file.parentFile, "${file.name}.tmp")
    var removed = 0
    file.bufferedReader().use { reader ->
      val fieldLine = reader.readLine() ?: return false
      val fields = fieldLine.split(SEPARATOR)
      val iLat = fields.indexOf(TrackFields.LAT)
      val iLon = fields.indexOf(TrackFields.LON)
      temp.bufferedWriter().use { out ->
        out.write(fieldLine)
        out.write("\n")
        var number = 0
        reader.lineSequence().forEach { line ->
          val parts = line.split(SEPARATOR)
          val valid = parts.getOrNull(iLat)?.toDoubleOrNull() != null && parts.getOrNull(iLon)?.toDoubleOrNull() != null
          if (valid && rows[number++] == line) {
            removed++
            return@forEach
          }
          out.write(line)
          out.write("\n")
        }
      }
    }
    if (removed == rows.size && temp.renameTo(file)) return true
    temp.delete()
    return false
  }

  // Calls action for every sample with a valid position; parts follow the field list passed to onFields.
  /**
  * Reads field names and invokes a callback for each row with valid latitude and longitude.
  *
  * Usage: Callbacks run synchronously on the calling thread; malformed or missing time is supplied as zero.
  * @param file File to read, copy, share, decode or release as described by the operation.
  * @param onFields Receives the sample file's ordered column identifiers before any samples. Default: {}.
  * @param action Receives epoch milliseconds, latitude/longitude in degrees and raw columns aligned with onFields; called synchronously for valid positions.
  * @return Unit; missing files or missing coordinate columns produce no samples.
  */
  fun forEachSample(
    file: File,
    onFields: (List<String>) -> Unit = {},
    action: (time: Long, lat: Double, lon: Double, parts: List<String>) -> Unit
  ) {
    if (!file.exists()) return
    file.bufferedReader().use { reader ->
      val fields = reader.readLine()?.split(SEPARATOR) ?: return
      onFields(fields)
      val iTime = fields.indexOf(TrackFields.TIME)
      val iLat = fields.indexOf(TrackFields.LAT)
      val iLon = fields.indexOf(TrackFields.LON)
      if (iLat < 0 || iLon < 0) return
      reader.lineSequence().forEach { line ->
        val parts = line.split(SEPARATOR)
        val lat = parts.getOrNull(iLat)?.toDoubleOrNull() ?: return@forEach
        val lon = parts.getOrNull(iLon)?.toDoubleOrNull() ?: return@forEach
        val time = parts.getOrNull(iTime)?.let(TrackTime::parse) ?: 0L
        action(time, lat, lon, parts)
      }
    }
  }
}
