// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream

// GPX read in two passes: the first gives the points, the routes and the track summaries, the same data an exchange file gives,
// so the selection tree and the merging work on them as usual; the second writes the chosen tracks straight to their place.
/** Чтение GPX в два прохода: read - данные для выбора, readTracks - файлы выбранных треков.
    Трек получает id gpx<номер элемента trk в файле>, по нему второй проход находит выбранные.
*/
object GpxImport {

  /** Точки, маршруты и сводки треков; точки треков не сохраняются. null - корень не GPX или данных нет */
  fun read(context: Context, uri: Uri): LoadedData? {
    val result = LoadedData(null).also { it.fromGpx = true }
    val ok = runCatching {
      context.contentResolver.openInputStream(uri)?.use { input ->
        forEachItem(input) { name, parser, track ->
          when (name) {
            "wpt" -> readPoint(parser)?.let { result.points += it.copy(id = result.points.size + 1L) }
            "trk" -> readSummary(parser, trackId(track))?.let { result.tracks += it }
            "rte" -> readRoute(parser, result)
          }
        }
        true
      } ?: false
    }.getOrDefault(false)
    if (!ok || (result.points.isEmpty() && result.tracks.isEmpty() && result.routes.isEmpty())) return null
    result.nextId = (result.points.maxOfOrNull { it.id } ?: 0L) + 1
    return result
  }

  /** Второй проход: треки с путями DataIO.trackPath из wanted отдаются onFile по мере чтения, write пишет файл точек трека */
  fun readTracks(input: InputStream, wanted: Set<String>, onFile: (path: String, write: (File) -> Unit) -> Unit) {
    forEachItem(input) { name, parser, track ->
      if (name != "trk") return@forEachItem
      val path = DataIO.trackPath(trackId(track))
      if (path !in wanted) return@forEachItem parser.forEachChild {}
      val fixes = mutableListOf<GpsMeasure>()
      readTrack(parser) { fixes += it }
      if (fixes.isNotEmpty()) onFile(path) { target ->
        target.bufferedWriter().use { writer ->
          TrackFiles.writeFieldLine(writer)
          fixes.forEach { TrackFiles.writeFix(writer, it) }
        }
      }
    }
  }

  private fun trackId(index: Int) = "gpx$index"

  /** Начальные теги внутри gpx; track - номер очередного trk. Непрочитанный onItem элемент обходится вглубь. FOR LOCAL USE */
  private fun forEachItem(input: InputStream, onItem: (name: String, parser: XmlPullParser, track: Int) -> Unit) {
    val parser = Xml.newPullParser()
    parser.setInput(input, null)
    var track = 0
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType != XmlPullParser.START_TAG || parser.name != "gpx") continue
      parser.forEachChild { name ->
        onItem(name, parser, track)
        if (name == "trk") track++
      }
    }
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

  // Имя трека - из файла или по времени начала.
  private fun readSummary(parser: XmlPullParser, id: String): TrackHeader? {
    val builder = HeaderBuilder()
    val name = readTrack(parser) { builder.add(it.time, it.lat, it.lon) }
    if (builder.count == 0) return null
    return builder.build(id, name?.takeIf { it.isNotEmpty() } ?: TrackTime.name(builder.startTime), true)
  }

  /** Отметки трека в onFix; возвращает имя трека из файла */
  private fun readTrack(parser: XmlPullParser, onFix: (GpsMeasure) -> Unit): String? {
    var name: String? = null
    parser.forEachChild { tag ->
      when (tag) {
        "name" -> name = parser.nextText().trim()
        "trkseg" -> parser.forEachChild { child -> if (child == "trkpt") readFix(parser)?.let(onFix) }
      }
    }
    return name
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
}
