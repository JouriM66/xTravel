// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Точка трека для карточки; row - исходная строка файла, по ней удаление сверяет, что точка та же */
class TrackSample(
  val time: Long,
  val lat: Double,
  val lon: Double,
  val ele: Double?,
  val hacc: Double?,
  val sat: Int?,
  val row: String
)

/** Точки трека из файла; distances[i] - путь от начала до точки i, issues - точки с сомнительными данными.
    ascent и descent - суммы подъёмов и спусков между соседними точками, у которых обеих есть высота, м
*/
class TrackData(
  val samples: List<TrackSample>,
  val distances: DoubleArray,
  val issues: List<TrackIssue>,
  val ascent: Double,
  val descent: Double
) {
  val length get() = distances.lastOrNull() ?: 0.0 /** Суммарное расстояние, м */

  companion object {
    val EMPTY = TrackData(emptyList(), DoubleArray(0), emptyList(), 0.0, 0.0)
  }
}

enum class TrackIssueReason(@StringRes val text: Int) {
  INVALID_COORDINATES(R.string.track_issue_coordinates),
  LOW_SATELLITES(R.string.track_issue_satellites),
  POOR_ACCURACY(R.string.track_issue_accuracy),
  DUPLICATE(R.string.track_issue_duplicate),
  TIME_NOT_INCREASING(R.string.track_issue_time),
  POSITION_OUTLIER(R.string.track_issue_position),
  SPEED_OUTLIER(R.string.track_issue_speed),
  ELEVATION_OUTLIER(R.string.track_issue_elevation)
}

/** Точка с сомнительными данными; index - номер в TrackData.samples */
class TrackIssue(val index: Int, val reasons: List<TrackIssueReason>)

private const val MIN_SATELLITES = 3
private const val MAX_HACC = 50.0 /** Точность хуже этого, м - ошибка */
private const val OUTLIER_RATIO = 5.0 /** Во сколько раз скачок в точке больше шагов соседей */
private const val MIN_OUTLIER_METERS = 20.0 /** Меньшие скачки координат - шум стоянки, не выброс */
private const val MIN_OUTLIER_SPEED = 10.0 /** Меньшие скорости скачка, м/с - шум стоянки, не выброс */
private const val MIN_OUTLIER_ELE = 10.0 /** Меньшие скачки высоты, м - не выброс */
private const val MAX_VERTICAL_SPEED = 5.0 /** м/с */

/** Чтение точек трека и поиск сомнительных. Поток вызывающего, обычно фоновый */
object TrackAnalysis {

  fun read(id: String): TrackData {
    val samples = ArrayList<TrackSample>()
    var iEle = -1
    var iHacc = -1
    var iSat = -1
    TrackFiles.forEachSample(TrackFiles.dataFile(id), { fields ->
      iEle = fields.indexOf(TrackFields.ELE)
      iHacc = fields.indexOf(TrackFields.HACC)
      iSat = fields.indexOf(TrackFields.SAT)
    }) { time, lat, lon, parts ->
      samples += TrackSample(
        time, lat, lon,
        parts.getOrNull(iEle)?.toDoubleOrNull(),
        parts.getOrNull(iHacc)?.toDoubleOrNull(),
        parts.getOrNull(iSat)?.toIntOrNull(),
        parts.joinToString(";")
      )
    }
    val distances = DoubleArray(samples.size)
    var ascent = 0.0
    var descent = 0.0
    for (i in 1 until samples.size) {
      val a = samples[i - 1]
      val b = samples[i]
      distances[i] = distances[i - 1] + GeoMath.distance(a.lat, a.lon, b.lat, b.lon)
      if (a.ele != null && b.ele != null) {
        if (b.ele > a.ele) ascent += b.ele - a.ele else descent += a.ele - b.ele
      }
    }
    return TrackData(samples, distances, issues(samples, distances), ascent, descent)
  }

  private fun issues(s: List<TrackSample>, distances: DoubleArray): List<TrackIssue> {
    // Шаг до точки i и скорость на нём; у первой точки шага нет.
    fun step(i: Int) = if (i <= 0) 0.0 else distances[i] - distances[i - 1]
    fun speed(i: Int): Double? {
      val seconds = (s[i].time - s[i - 1].time) / 1000.0
      return if (s[i].time == 0L || s[i - 1].time == 0L || seconds <= 0) null else step(i) / seconds
    }
    // Значение в точке i на порядок отличается от соседей с обеих сторон, при этом соседи спокойны.
    fun spike(into: Double, out: Double, around: Double, minimum: Double): Boolean {
      val jump = min(into, out)
      return jump >= minimum && jump >= OUTLIER_RATIO * around
    }

    val result = ArrayList<TrackIssue>()
    for (i in s.indices) {
      val p = s[i]
      val reasons = ArrayList<TrackIssueReason>()
      if (abs(p.lat) > 90 || abs(p.lon) > 180 || (p.lat == 0.0 && p.lon == 0.0)) reasons += TrackIssueReason.INVALID_COORDINATES
      if (p.sat != null && p.sat < MIN_SATELLITES) reasons += TrackIssueReason.LOW_SATELLITES
      if (p.hacc != null && p.hacc > MAX_HACC) reasons += TrackIssueReason.POOR_ACCURACY
      if (i > 0) {
        val prev = s[i - 1]
        when {
          p.time == prev.time && GeoMath.samePlace(p.lat, p.lon, prev.lat, prev.lon) -> reasons += TrackIssueReason.DUPLICATE
          p.time != 0L && prev.time != 0L && p.time <= prev.time -> reasons += TrackIssueReason.TIME_NOT_INCREASING
        }
      }
      if (i >= 2 && i + 2 < s.size) {
        if (spike(step(i), step(i + 1), max(step(i - 1), step(i + 2)), MIN_OUTLIER_METERS)) reasons += TrackIssueReason.POSITION_OUTLIER
        val speeds = listOf(speed(i), speed(i + 1), speed(i - 1), speed(i + 2))
        if (speeds.all { it != null } && spike(speeds[0]!!, speeds[1]!!, max(speeds[2]!!, speeds[3]!!), MIN_OUTLIER_SPEED)) {
          reasons += TrackIssueReason.SPEED_OUTLIER
        }
        val e = (i - 2..i + 2).map { s[it].ele }
        if (e.all { it != null }) {
          val around = max(abs(e[1]!! - e[0]!!), abs(e[4]!! - e[3]!!))
          if (spike(abs(e[2]!! - e[1]!!), abs(e[3]!! - e[2]!!), around, MIN_OUTLIER_ELE)) reasons += TrackIssueReason.ELEVATION_OUTLIER
        }
      }
      if (p.ele != null && p.ele < 0 && TrackIssueReason.ELEVATION_OUTLIER !in reasons) reasons += TrackIssueReason.ELEVATION_OUTLIER
      if (i > 0 && TrackIssueReason.ELEVATION_OUTLIER !in reasons) {
        val prev = s[i - 1]
        val seconds = (p.time - prev.time) / 1000.0
        if (p.ele != null && prev.ele != null && p.time != 0L && prev.time != 0L && seconds > 0 &&
          abs(p.ele - prev.ele) / seconds > MAX_VERTICAL_SPEED) reasons += TrackIssueReason.ELEVATION_OUTLIER
      }
      if (reasons.isNotEmpty()) result += TrackIssue(i, reasons)
    }
    return result
  }
}
