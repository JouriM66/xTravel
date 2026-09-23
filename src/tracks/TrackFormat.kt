// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Build
import java.io.File
import java.io.Writer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

/**
* Extension without a dot for track sample files.
* @return Extension without a dot for track sample files.
*/
const val TRACK_DATA_EXT = "xtrack"
/**
* Extension without a dot for computed track metadata files.
* @return Extension without a dot for computed track metadata files.
*/
const val TRACK_HEADER_EXT = "xtrackh"
private const val SEPARATOR = ";"

/**
* Formats sample timestamps and filename-safe track names and parses ISO timestamps.
*
* Public and subclass/module-facing members:
* - [format] - Formats an epoch timestamp with milliseconds and the device's zone offset.
* - [parse] - Parses an offset date-time or UTC instant.
* - [name] - Formats a timestamp as a filename-safe device-local minute.
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

  // Track names: no seconds; a track started in the same minute replaces the file.
  /**
  * Formats a timestamp as a filename-safe device-local minute.
  *
  * Usage: Names collide within the same minute; use TrackStorage.uniqueName when uniqueness is required.
  * @param ms Epoch timestamp converted to a local minute for the filename.
  * @return A yyyy-MM-dd HH-mm name.
  */
  fun name(ms: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).format(NAME)
}

// An unfinished track (complete = false) knows only its start and fields; the other values repeat the start point and 0 points.
/**
* Stores track bounds, endpoints, timestamps and metadata; unfinished headers contain only initial information.
*
* Usage: For complete=false, endpoint/bounds repeat the starting point and points is zero in the stored open header. highlighted is runtime-only.
* @param name Filename stem identifying the paired sample and header files.
* @param start First sample timestamp in milliseconds since the Unix epoch.
* @param end Last sample timestamp in milliseconds since the Unix epoch.
* @param startLat First sample latitude in degrees.
* @param startLon First sample longitude in degrees.
* @param endLat Last sample latitude in degrees.
* @param endLon Last sample longitude in degrees.
* @param minLat Southern bound in geographic degrees.
* @param minLon Western bound in geographic degrees.
* @param maxLat Northern bound in geographic degrees.
* @param maxLon Eastern bound in geographic degrees.
* @param visible Whether the point, route or track should be displayed.
* @param points Number of valid track samples represented by the header.
* @param fields Ordered sample-field identifiers found in the data file.
* @param complete Whether endpoint, bounds and sample count describe a completed track. Default: true.
* @param highlighted Runtime-only list highlight; not persisted to data files. Default: false.
* @property name Filename stem identifying the paired sample and header files.
* @property start First sample timestamp in milliseconds since the Unix epoch.
* @property end Last sample timestamp in milliseconds since the Unix epoch.
* @property startLat First sample latitude in degrees.
* @property startLon First sample longitude in degrees.
* @property endLat Last sample latitude in degrees.
* @property endLon Last sample longitude in degrees.
* @property minLat Southern bound in geographic degrees.
* @property minLon Western bound in geographic degrees.
* @property maxLat Northern bound in geographic degrees.
* @property maxLon Eastern bound in geographic degrees.
* @property visible Whether the point, route or track should be displayed.
* @property points Number of valid track samples represented by the header.
* @property fields Ordered sample-field identifiers found in the data file.
* @property complete Whether endpoint, bounds and sample count describe a completed track. Default: true.
* @property highlighted Runtime-only list highlight; not persisted to data files. Default: false.
*/
data class TrackHeader(
  val name: String,
  val start: Long,
  val end: Long,
  val startLat: Double,
  val startLon: Double,
  val endLat: Double,
  val endLon: Double,
  val minLat: Double,
  val minLon: Double,
  val maxLat: Double,
  val maxLon: Double,
  val visible: Boolean,
  val points: Int,
  val fields: List<String>,
  val complete: Boolean = true,
  // Runtime only, never written to the header file: the row of this track is marked in the list.
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
class TrackLine(val wx: DoubleArray, val wy: DoubleArray, val size: Int, val from: Int = 0) {
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

// Accumulates a header while samples are written.
/**
* Accumulates sample counts, endpoints and bounds while reading or recording a track.
*
* Public and subclass/module-facing members:
* - [count] - Number of accumulated valid samples; updated only by add.
* - [firstLat] - Latitude of the first accumulated sample in degrees, initially zero.
* - [firstLon] - Longitude of the first accumulated sample in degrees, initially zero.
* - [startTime] - Timestamp of the first accumulated sample in epoch milliseconds, initially zero.
* - [add] - Includes one sample in the count, time range, endpoints and coordinate bounds.
* - [build] - Creates a completed track header from all accumulated samples.
* - [buildOpen] - Creates a minimal unfinished header using the first accumulated sample.
*/
class HeaderBuilder {
  /**
  * Number of accumulated valid samples; updated only by add.
  * @return Number of accumulated valid samples; updated only by add.
  */
  var count = 0
    private set
  private var start = 0L
  private var end = 0L
  private var startLat = 0.0
  private var startLon = 0.0
  private var endLat = 0.0
  private var endLon = 0.0
  private var minLat = 90.0
  private var minLon = 180.0
  private var maxLat = -90.0
  private var maxLon = -180.0

  /**
  * Latitude of the first accumulated sample in degrees, initially zero.
  * @return Latitude of the first accumulated sample in degrees, initially zero.
  */
  val firstLat get() = startLat
  /**
  * Longitude of the first accumulated sample in degrees, initially zero.
  * @return Longitude of the first accumulated sample in degrees, initially zero.
  */
  val firstLon get() = startLon
  /**
  * Timestamp of the first accumulated sample in epoch milliseconds, initially zero.
  * @return Timestamp of the first accumulated sample in epoch milliseconds, initially zero.
  */
  val startTime get() = start

  /**
  * Includes one sample in the count, time range, endpoints and coordinate bounds.
  * @param time Sample timestamp in milliseconds since the Unix epoch.
  * @param lat Latitude in geographic degrees.
  * @param lon Longitude in geographic degrees.
  * @return Unit; mutate the builder on one worker.
  */
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
    minLat = minOf(minLat, lat)
    minLon = minOf(minLon, lon)
    maxLat = maxOf(maxLat, lat)
    maxLon = maxOf(maxLon, lon)
  }

  /**
  * Creates a completed track header from all accumulated samples.
  *
  * Usage: Add samples before building meaningful endpoint and bounding-box values.
  * @param name Name or filename stem identifying the requested object.
  * @param visible Whether the point, route or track should be displayed.
  * @param fields Ordered sample-column identifiers.
  * @return A completed TrackHeader.
  */
  fun build(name: String, visible: Boolean, fields: List<String>) =
    TrackHeader(name, start, end, startLat, startLon, endLat, endLon, minLat, minLon, maxLat, maxLon, visible, count, fields)

  /**
  * Creates a minimal unfinished header using the first accumulated sample.
  * @param name Name or filename stem identifying the requested object.
  * @param visible Whether the point, route or track should be displayed.
  * @param fields Ordered sample-column identifiers.
  * @return A TrackHeader with complete false.
  */
  fun buildOpen(name: String, visible: Boolean, fields: List<String>) = openHeader(name, start, startLat, startLon, visible, fields)
}

/**
* Creates an unfinished header whose end and bounds repeat its start and whose count is zero.
* @param name Name or filename stem identifying the requested object.
* @param start First sample timestamp in milliseconds since the Unix epoch.
* @param lat Latitude in geographic degrees.
* @param lon Longitude in geographic degrees.
* @param visible Whether the point, route or track should be displayed.
* @param fields Ordered sample-column identifiers.
* @return The unfinished TrackHeader.
*/
fun openHeader(name: String, start: Long, lat: Double, lon: Double, visible: Boolean, fields: List<String>) =
  TrackHeader(name, start, start, lat, lon, lat, lon, lat, lon, lat, lon, visible, 0, fields, complete = false)

// Data file: first line is the field list, then one sample per line, fields separated by ";".
// Header file: lines "key=value", values computed from the data; the visibility is kept in data.xml.
// The header of an unfinished track has only start, start_lat, start_lon and fields.
/**
* Reads and writes paired track sample and header files; callers own serialization and IO dispatch.
*
* Public and subclass/module-facing members:
* - [dataFile] - Resolves the sample-file path for a track name.
* - [headerFile] - Resolves the metadata-file path for a track name.
* - [writeFieldLine] - Writes the semicolon-separated sample field names and a newline.
* - [writeFix] - Writes a GPS sample in TrackFields.ALL order, leaving unavailable values empty.
* - [writeHeader] - Writes computed track metadata to its paired header file.
* - [readHeader] - Reads a track header and supplies externally stored visibility.
* - [buildHeader] - Recomputes completed metadata from a track's valid samples.
* - [readLine] - Loads sample coordinates into projected arrays, optionally accumulating header metadata.
* - [forEachSample] - Reads field names and invokes a callback for each row with valid latitude and longitude.
*/
object TrackFiles {

  /**
  * Resolves the sample-file path for a track name.
  * @param name Track filename stem without extension.
  * @param dir Directory from which referenced files are read or into which files are written. Default: AppDirs.tracks.
  * @return A File ending in the track data extension; no file is created.
  */
  fun dataFile(name: String, dir: File = AppDirs.tracks) = File(dir, "$name.$TRACK_DATA_EXT")
  /**
  * Resolves the metadata-file path for a track name.
  * @param name Track filename stem without extension.
  * @param dir Directory from which referenced files are read or into which files are written. Default: AppDirs.tracks.
  * @return A File ending in the track header extension; no file is created.
  */
  fun headerFile(name: String, dir: File = AppDirs.tracks) = File(dir, "$name.$TRACK_HEADER_EXT")

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

  /**
  * Writes computed track metadata to its paired header file.
  *
  * Usage: Visibility and runtime highlighting are not stored in the header file.
  * @param header Track metadata used by the requested operation.
  * @param dir Directory from which referenced files are read or into which files are written. Default: AppDirs.tracks.
  * @return Unit; overwrites the header.
  */
  fun writeHeader(header: TrackHeader, dir: File = AppDirs.tracks) {
    val text = buildString {
      appendLine("start=${TrackTime.format(header.start)}")
      if (header.complete) appendLine("end=${TrackTime.format(header.end)}")
      appendLine("start_lat=${header.startLat}")
      appendLine("start_lon=${header.startLon}")
      if (!header.complete) {
        appendLine("fields=${header.fields.joinToString(SEPARATOR)}")
        return@buildString
      }
      appendLine("end_lat=${header.endLat}")
      appendLine("end_lon=${header.endLon}")
      appendLine("min_lat=${header.minLat}")
      appendLine("min_lon=${header.minLon}")
      appendLine("max_lat=${header.maxLat}")
      appendLine("max_lon=${header.maxLon}")
      appendLine("points=${header.points}")
      appendLine("fields=${header.fields.joinToString(SEPARATOR)}")
    }
    headerFile(header.name, dir).writeText(text)
  }

  /**
  * Reads a track header and supplies externally stored visibility.
  * @param name Track filename stem identifying the header to read.
  * @param visible Whether the point, route or track should be displayed.
  * @param dir Directory from which referenced files are read or into which files are written. Default: AppDirs.tracks.
  * @return The parsed header, or null on missing or malformed data.
  */
  fun readHeader(name: String, visible: Boolean, dir: File = AppDirs.tracks): TrackHeader? = runCatching {
    val values = headerFile(name, dir).readLines().mapNotNull { line ->
      val i = line.indexOf('=')
      if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
    }.toMap()
    val fields = values["fields"]?.split(SEPARATOR) ?: TrackFields.ALL
    val start = TrackTime.parse(values.getValue("start"))!!
    if (values["end"] == null) {
      return@runCatching openHeader(name, start, values.getValue("start_lat").toDouble(), values.getValue("start_lon").toDouble(), visible, fields)
    }
    TrackHeader(
      name = name,
      start = start,
      end = TrackTime.parse(values.getValue("end"))!!,
      startLat = values.getValue("start_lat").toDouble(),
      startLon = values.getValue("start_lon").toDouble(),
      endLat = values.getValue("end_lat").toDouble(),
      endLon = values.getValue("end_lon").toDouble(),
      minLat = values.getValue("min_lat").toDouble(),
      minLon = values.getValue("min_lon").toDouble(),
      maxLat = values.getValue("max_lat").toDouble(),
      maxLon = values.getValue("max_lon").toDouble(),
      visible = visible,
      points = values.getValue("points").toInt(),
      fields = fields
    )
  }.getOrNull()

  // Rebuilds the header from the data, for example after a crash.
  /**
  * Recomputes completed metadata from a track's valid samples.
  *
  * Usage: Does not write the resulting header automatically.
  * @param name Track filename stem identifying the samples to scan.
  * @param visible Whether the point, route or track should be displayed.
  * @param dir Directory from which referenced files are read or into which files are written. Default: AppDirs.tracks.
  * @return The rebuilt header, or null when no sample can be read.
  */
  fun buildHeader(name: String, visible: Boolean, dir: File = AppDirs.tracks): TrackHeader? {
    val builder = HeaderBuilder()
    var fields: List<String> = TrackFields.ALL
    forEachSample(dataFile(name, dir), { fields = it }) { time, lat, lon, _ -> builder.add(time, lat, lon) }
    if (builder.count == 0) return null
    return builder.build(name, visible, fields)
  }

  // The header, when given, is built along the way.
  /**
  * Loads sample coordinates into projected arrays, optionally accumulating header metadata.
  *
  * Usage: Run on an IO thread; invalid sample rows are skipped.
  * @param name Track filename stem in AppDirs.tracks.
  * @param header Optional accumulator updated for every valid sample while geometry is loaded.
  * @return A TrackLine containing all valid positions.
  */
  fun readLine(name: String, header: HeaderBuilder? = null): TrackLine {
    var wx = DoubleArray(1024)
    var wy = DoubleArray(1024)
    var size = 0
    forEachSample(dataFile(name)) { time, lat, lon, _ ->
      header?.add(time, lat, lon)
      if (size == wx.size) {
        wx = wx.copyOf(size * 2)
        wy = wy.copyOf(size * 2)
      }
      val world = MercatorProjection.toWorld(lat, lon)
      wx[size] = world.x
      wy[size] = world.y
      size++
    }
    return TrackLine(wx, wy, size)
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
