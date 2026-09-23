// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File

// GPX read into an import directory: the points and the tracks of the file become the same data an exchange file gives,
// so the selection tree, the merging and the track files work on them as usual. Runs on the "io" thread.
/**
* Parses GPX waypoints and tracks into an import directory and LoadedData accumulator.
*
* Public and subclass/module-facing members:
* - [read] - Reads a GPX content URI and writes imported track files into the supplied temporary data directory.
*/
object GpxImport {

  /**
  * Reads a GPX content URI and writes imported track files into the supplied temporary data directory.
  *
  * Usage: Run on an IO thread with URI read permission; parsing and IO failures may propagate.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @param uri Content, file or resource URI consumed by the operation.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return LoadedData for a GPX document, or null when the root is not GPX.
  */
  fun read(context: Context, uri: Uri, dir: File): LoadedData? {
    val result = LoadedData(dir.also { it.mkdirs() })
    val ok = runCatching {
      context.contentResolver.openInputStream(uri)?.use { input ->
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
          if (parser.eventType != XmlPullParser.START_TAG || parser.name != "gpx") continue
          parser.forEachChild { name ->
            when (name) {
              "wpt" -> readPoint(parser)?.let { result.points += it.copy(id = result.points.size + 1L) }
              "trk" -> readTrack(parser, dir)?.let { result.tracks += it }
              "rte" -> readRoute(parser, result)
            }
          }
        }
        true
      } ?: false
    }.getOrDefault(false)
    if (!ok || (result.points.isEmpty() && result.tracks.isEmpty() && result.routes.isEmpty())) return null
    result.nextId = (result.points.maxOfOrNull { it.id } ?: 0L) + 1
    return result
  }

  // Everything the format keeps about a place: the name first, then the descriptions, the link and the type; the symbol
  // becomes the icon when the set has one, the time has nowhere to go.
  private fun readPoint(parser: XmlPullParser): MapPoint? {
    val lat = parser.attr("lat")?.toDoubleOrNull() ?: return null
    val lon = parser.attr("lon")?.toDoubleOrNull() ?: return null
    var ele: Double? = null
    var icon = ""
    var name: String? = null
    val texts = mutableListOf<String>()
    var type: String? = null
    parser.forEachChild { tag ->
      when (tag) {
        "ele" -> ele = parser.nextText().trim().toDoubleOrNull()
        "name" -> name = parser.nextText().trim()
        "desc", "cmt" -> parser.nextText().trim().takeIf { it.isNotEmpty() }?.let { texts += it }
        "sym" -> icon = iconOf(parser.nextText())
        "type" -> type = parser.nextText().trim()
        "link" -> parser.attr("href")?.trim()?.takeIf { it.isNotEmpty() }?.let { texts += it }
      }
    }
    val elements = buildList {
      name?.takeIf { it.isNotEmpty() }?.let { add(PointElement.Text(it)) }
      texts.forEach { add(PointElement.Text(it)) }
      type?.takeIf { it.isNotEmpty() }?.let { add(PointElement.Text(it)) }
    }
    return MapPoint(id = 0, lat = lat, lon = lon, alt = ele, icon = icon, info = MetaInfo(elements))
  }

  // A route of the format keeps its places itself; every one of them is read as a point, and the point manager
  // decides later whether such a point already stands there.
  private fun readRoute(parser: XmlPullParser, result: LoadedData) {
    var name = ""
    val stops = mutableListOf<RouteStopRecord>()
    parser.forEachChild { tag ->
      when (tag) {
        "name" -> name = parser.nextText().trim()
        "rtept" -> readPoint(parser)?.let { stops += RouteStopRecord(RouteStore.stopPlace(it, "GPX", stops.size + 1), emptyList()) }
      }
    }
    if (stops.isNotEmpty()) result.routes += RouteRecord(name, stops)
  }

  // The symbol of another program: taken only when its name is one of ours.
  private fun iconOf(symbol: String): String {
    val text = symbol.trim().lowercase().replace(" ", "")
    return PointIcons.names.firstOrNull { it == text } ?: ""
  }

  // Track files are written into the import directory; the name of the track comes from the file or from its first time.
  private fun readTrack(parser: XmlPullParser, dir: File): TrackHeader? {
    var name: String? = null
    val builder = HeaderBuilder()
    val fixes = mutableListOf<GpsMeasure>()
    parser.forEachChild { tag ->
      when (tag) {
        "name" -> name = parser.nextText().trim()
        "trkseg" -> parser.forEachChild { child -> if (child == "trkpt") readFix(parser)?.let { fixes += it } }
      }
    }
    if (fixes.isEmpty()) return null
    fixes.forEach { builder.add(it.time, it.lat, it.lon) }
    val fileName = uniqueFileName(latin(name.orEmpty()).ifEmpty { TrackTime.name(builder.startTime) }, dir)
    val tracks = DataIO.tracksDir(dir).also { it.mkdirs() }
    TrackFiles.dataFile(fileName, tracks).bufferedWriter().use { writer ->
      TrackFiles.writeFieldLine(writer)
      fixes.forEach { TrackFiles.writeFix(writer, it) }
    }
    val header = builder.build(fileName, true, TrackFields.ALL)
    TrackFiles.writeHeader(header, tracks)
    return header
  }

  private fun readFix(parser: XmlPullParser): GpsMeasure? {
    val lat = parser.attr("lat")?.toDoubleOrNull() ?: return null
    val lon = parser.attr("lon")?.toDoubleOrNull() ?: return null
    var ele: Double? = null
    var time = 0L
    parser.forEachChild { tag ->
      when (tag) {
        "ele" -> ele = parser.nextText().trim().toDoubleOrNull()
        "time" -> time = TrackTime.parse(parser.nextText().trim()) ?: 0L
      }
    }
    return GpsMeasure(time, lat, lon, ele)
  }

  private fun uniqueFileName(name: String, dir: File): String {
    val tracks = DataIO.tracksDir(dir)
    var result = name
    var index = 1
    while (TrackFiles.dataFile(result, tracks).exists()) result = "$name ($index)".also { index++ }
    return result
  }

  // Track files are named by the track, so the name is turned into latin letters a file system takes anywhere.
  private fun latin(name: String): String {
    val text = name.trim().lowercase().map { TextSearch.TRANSLIT[it] ?: it.toString() }.joinToString("")
    return text.filter { it.isLetterOrDigit() || it in " -_" }.trim().take(64)
  }

}
