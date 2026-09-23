// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.util.Xml
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val NS = "http://www.topografix.com/GPX/1/1"

/** Отдача в GPX одним файлом: точки элементами wpt, маршруты - rte, треки - trk с одним trkseg. Чтение - в GpxImport.kt.
    Записки GPX не несёт. Маршрут держит места остановок в себе и на точки приложения не ссылается.
*/
object GpxShare : IGeoDataShare {

  override val label get() = R.string.share_gpx
  override val supportedTypes = setOf(GeoDataType.POINTS, GeoDataType.ROUTES, GeoDataType.TRACKS)

  override fun checkData(set: DataSet) = set.points.isNotEmpty() || set.routes.isNotEmpty() || set.tracks.isNotEmpty()

  override fun share(set: DataSet) = GeoDataShareManager.shareFiles(XFileProvider.GPX_TYPE) { listOf(write(set)) }

  private fun write(set: DataSet): File {
    val single = set.tracks.singleOrNull()?.takeIf { set.points.isEmpty() && set.routes.isEmpty() }
    val name = single?.name ?: ("xTravelData-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MMM-dd", Locale.US)))
    val file = File(AppDirs.share, "$name.gpx")
    file.outputStream().use { out ->
      val xml = Xml.newSerializer()
      xml.setOutput(out, "UTF-8")
      xml.startDocument("UTF-8", true)
      xml.setPrefix("", NS)
      xml.startTag(NS, "gpx")
      xml.attribute(null, "version", "1.1")
      xml.attribute(null, "creator", "xTravel")
      set.points.forEach { point ->
        val text = point.elements.filterIsInstance<PointElement.Text>().firstOrNull()?.text?.trim()
        writePlace(xml, "wpt", point, text?.takeIf { it != point.captionOnly() })
      }
      set.routes.forEach { route ->
        xml.startTag(NS, "rte")
        if (route.name.isNotEmpty()) xml.startTag(NS, "name").text(route.name).endTag(NS, "name")
        route.places.forEach { place -> writePlace(xml, "rtept", place, place.elements.filterIsInstance<PointElement.Text>().getOrNull(1)?.text?.trim()) }
        xml.endTag(NS, "rte")
      }
      set.tracks.forEach { writeTrack(xml, it) }
      xml.endTag(NS, "gpx")
      xml.endDocument()
    }
    return file
  }

  /** Место: высота, первая строка первого текста именем и описание. FOR LOCAL USE */
  private fun writePlace(xml: XmlSerializer, tag: String, point: MapPoint, description: String?) {
    xml.startTag(NS, tag)
    xml.attribute(null, "lat", point.lat.toString())
    xml.attribute(null, "lon", point.lon.toString())
    point.alt?.let { xml.startTag(NS, "ele").text(it.toString()).endTag(NS, "ele") }
    point.captionOnly()?.let { xml.startTag(NS, "name").text(it).endTag(NS, "name") }
    if (!description.isNullOrEmpty()) xml.startTag(NS, "desc").text(description).endTag(NS, "desc")
    xml.endTag(NS, tag)
  }

  /** Трек: только стандартные поля отсчёта - time, lat, lon, ele, sat. FOR LOCAL USE */
  private fun writeTrack(xml: XmlSerializer, track: TrackHeader) {
    xml.startTag(NS, "trk")
    xml.startTag(NS, "name").text(track.name).endTag(NS, "name")
    xml.startTag(NS, "trkseg")
    var fields: List<String> = emptyList()
    TrackFiles.forEachSample(TrackFiles.dataFile(track.name), { fields = it }) { _, lat, lon, parts ->
      fun value(field: String) = fields.indexOf(field).let { if (it >= 0) parts.getOrNull(it).orEmpty() else "" }
      xml.startTag(NS, "trkpt")
      xml.attribute(null, "lat", lat.toString())
      xml.attribute(null, "lon", lon.toString())
      value(TrackFields.ELE).takeIf { it.isNotEmpty() }?.let { xml.startTag(NS, "ele").text(it).endTag(NS, "ele") }
      value(TrackFields.TIME).takeIf { it.isNotEmpty() }?.let { xml.startTag(NS, "time").text(it).endTag(NS, "time") }
      value(TrackFields.SAT).takeIf { it.isNotEmpty() }?.let { xml.startTag(NS, "sat").text(it).endTag(NS, "sat") }
      xml.endTag(NS, "trkpt")
    }
    xml.endTag(NS, "trkseg")
    xml.endTag(NS, "trk")
  }
}
