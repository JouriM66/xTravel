// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.ui.geometry.Offset
import java.util.Locale
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Provides geographic distances, bearings and the shared coordinate equality rule. */
object GeoMath {
  private const val EARTH_RADIUS = 6371008.8

  /** Coordinates from different sources vary in trailing digits, so compare them only to five decimal places. */
  private const val PLACE_SCALE = 1e5

  fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * EARTH_RADIUS * atan(sqrt(a) / sqrt(1 - a))
  }

  fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
  }

  fun samePlace(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Boolean =
    (lat1 * PLACE_SCALE).roundToLong() == (lat2 * PLACE_SCALE).roundToLong() &&
      (lon1 * PLACE_SCALE).roundToLong() == (lon2 * PLACE_SCALE).roundToLong()
}

/** Computes the distance from a screen point to a segment; a zero-length segment is a point. */
fun segmentDistance(x: Float, y: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
  val dx = x2 - x1
  val dy = y2 - y1
  val length = dx * dx + dy * dy
  val t = if (length == 0f) 0f else (((x - x1) * dx + (y - y1) * dy) / length).coerceIn(0f, 1f)
  return hypot(x - (x1 + t * dx), y - (y1 + t * dy))
}

/** Tests whether a screen position is within a pixel tolerance of a projected track polyline. */
fun nearLine(line: TrackLine, screen: Offset, viewport: MapViewport, limit: Float): Boolean {
  var px = 0f
  var py = 0f
  for (i in line.from until line.size) {
    val x = viewport.screenX(line.wx[i], line.wy[i])
    val y = viewport.screenY(line.wx[i], line.wy[i])
    if (i > line.from && segmentDistance(screen.x, screen.y, px, py, x, y) < limit) return true
    px = x
    py = y
  }
  return line.size - line.from == 1 && hypot(screen.x - px, screen.y - py) < limit
}

/** Tests whether a screen position is within a pixel tolerance of a geographic polyline. */
fun nearPolyline(line: List<GeoPoint>, screen: Offset, viewport: MapViewport, limit: Float): Boolean {
  if (line.isEmpty()) return false
  var previous = viewport.toScreen(line[0])
  for (i in 1 until line.size) {
    val current = viewport.toScreen(line[i])
    if (segmentDistance(screen.x, screen.y, previous.x, previous.y, current.x, current.y) < limit) return true
    previous = current
  }
  return line.size == 1 && hypot(screen.x - previous.x, screen.y - previous.y) < limit
}

/** Formats coordinates with five decimal places independently of the decimal-separator locale. */
fun formatCoordinates(point: GeoPoint): String = String.format(Locale.ROOT, "%.5f, %.5f", point.lat, point.lon)

/**
* Tells where a place falls on a line and how far it lies from it.
* @param index Index of the point the segment the place fell on starts at.
* @param place Geographic position in latitude/longitude degrees.
* @param meters Distance from the given place to the projection in meters.
* @property index Index of the point the segment the place fell on starts at.
* @property place Geographic position in latitude/longitude degrees.
* @property meters Distance from the given place to the projection in meters.
*/
class LineProjection(val index: Int, val place: GeoPoint, val meters: Double)

// Over the few hundred meters a projection covers the earth is flat enough: degrees become meters at the latitude of
// the place, which costs one cosine instead of a distance call per segment.
private const val METERS_PER_DEGREE = 111320.0

/**
* Projects a place onto the segments of a geographic line.
*
* Usage: Pass the index reached before and a window to follow a moving position without walking the whole line.
* @param line Line of at least two coordinates.
* @param place Geographic position in latitude/longitude degrees.
* @param from Index of the first segment to look at. Default: 0.
* @param ahead Number of segments to look at, counted from that index. Default: Int.MAX_VALUE.
* @return The nearest projection, or null when the line is too short.
*/
fun projectOnLine(line: List<GeoPoint>, place: GeoPoint, from: Int = 0, ahead: Int = Int.MAX_VALUE): LineProjection? {
  if (line.size < 2) return null
  val first = from.coerceIn(0, line.size - 2)
  val last = if (ahead >= line.size) line.size - 2 else minOf(first + ahead, line.size - 2)
  val scale = cos(Math.toRadians(place.lat))
  val px = place.lon * scale
  val py = place.lat
  var best: LineProjection? = null
  for (index in first..last) {
    val a = line[index]
    val b = line[index + 1]
    val ax = a.lon * scale
    val bx = b.lon * scale
    val dx = bx - ax
    val dy = b.lat - a.lat
    val length = dx * dx + dy * dy
    val t = if (length == 0.0) 0.0 else (((px - ax) * dx + (py - a.lat) * dy) / length).coerceIn(0.0, 1.0)
    val on = GeoPoint(a.lat + t * dy, a.lon + t * (b.lon - a.lon))
    val meters = hypot(px - (ax + t * dx), py - (a.lat + t * dy)) * METERS_PER_DEGREE
    if (best == null || meters < best.meters) best = LineProjection(index, on, meters)
  }
  return best
}
