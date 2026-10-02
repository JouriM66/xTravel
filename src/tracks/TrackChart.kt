// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_VISIBLE_POINTS = 10
private val CHART_TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

/** График высот по номерам точек. Растяжение двумя пальцами и сдвиг по горизонтали, двойное касание - весь трек.
    Высота подбирается по видимой части; точки без высоты рисуются нулём. Видимая часть хранится долями трека,
    поэтому при новых точках полный вид остаётся полным.
*/
@Composable
fun ElevationChart(data: TrackData) {
  val samples = data.samples
  val count = samples.size
  var start by remember { mutableFloatStateOf(0f) }
  var end by remember { mutableFloatStateOf(1f) }
  val slop = LocalViewConfiguration.current.touchSlop
  val lineColor = MaterialTheme.colorScheme.primary
  val frameColor = MaterialTheme.colorScheme.outlineVariant

  val last = (count - 1).coerceAtLeast(0)
  val from = floor(start * last).toInt().coerceIn(0, last)
  val to = ceil(end * last).toInt().coerceIn(from, last)
  // Жест не перезапускается с приходом новых точек, число точек он берёт отсюда.
  val lastPoint by rememberUpdatedState(last)
  var low = Double.MAX_VALUE
  var high = -Double.MAX_VALUE
  for (i in from..to) {
    if (count == 0) break
    val e = samples[i].ele ?: 0.0
    low = min(low, e)
    high = max(high, e)
  }

  Column(Modifier.fillMaxWidth()) {
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(140.dp)
        .border(1.dp, frameColor, RoundedCornerShape(4.dp))
        .pointerInput(Unit) {
          detectTapGestures(onDoubleTap = {
            start = 0f
            end = 1f
          })
        }
        .pointerInput(Unit) {
          // Свой жест: вертикальное движение одним пальцем отдаётся прокрутке шторки.
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var mine: Boolean? = null
            var moved = Offset.Zero
            do {
              val event = awaitPointerEvent()
              val pressed = event.changes.count { it.pressed }
              val pan = event.calculatePan()
              if (mine == null) {
                moved += pan
                mine = when {
                  pressed >= 2 -> true
                  abs(moved.x) > slop && abs(moved.x) > abs(moved.y) -> true
                  abs(moved.y) > slop -> false
                  else -> null
                }
              }
              if (mine == true && lastPoint > 0 && pressed > 0) {
                val width = size.width.toFloat()
                val span = end - start
                val anchor = event.calculateCentroid().x.coerceIn(0f, width) / width
                val minSpan = min(1f, MIN_VISIBLE_POINTS.toFloat() / lastPoint)
                val newSpan = (span / event.calculateZoom()).coerceIn(minSpan, 1f)
                val newStart = (start + anchor * span - anchor * newSpan - pan.x / width * newSpan).coerceIn(0f, 1f - newSpan)
                start = newStart
                end = newStart + newSpan
                event.changes.forEach { if (it.positionChanged()) it.consume() }
              }
            } while (event.changes.any { it.pressed })
          }
        }
    ) {
      if (count < 2 || to <= from) return@Canvas
      val pad = 4.dp.toPx()
      val h = size.height - 2 * pad
      val range = max(high - low, 1.0)
      val mid = (high + low) / 2
      fun y(e: Double) = (pad + h / 2 - (e - mid) / range * h).toFloat()
      val visible = end - start
      fun x(i: Int) = ((i.toFloat() / last - start) / visible * size.width)
      // На пиксель приходится много точек: по каждому столбцу рисуются его наименьшая и наибольшая высоты.
      val path = Path()
      val perPixel = (to - from + 1) / size.width
      if (perPixel <= 2f) {
        for (i in from..to) {
          val px = x(i)
          val py = y(samples[i].ele ?: 0.0)
          if (i == from) path.moveTo(px, py) else path.lineTo(px, py)
        }
      } else {
        var i = from
        var first = true
        while (i <= to) {
          val column = x(i).roundToInt()
          var a = Double.MAX_VALUE
          var b = -Double.MAX_VALUE
          while (i <= to && x(i).roundToInt() == column) {
            val e = samples[i].ele ?: 0.0
            a = min(a, e)
            b = max(b, e)
            i++
          }
          if (first) path.moveTo(column.toFloat(), y(a)) else path.lineTo(column.toFloat(), y(a))
          path.lineTo(column.toFloat(), y(b))
          first = false
        }
      }
      drawPath(path, lineColor, style = Stroke(width = 1.5.dp.toPx()))
    }
    if (count > 0) {
      val time = { i: Int -> CHART_TIME.format(Instant.ofEpochMilli(samples[i].time).atZone(ZoneId.systemDefault())) }
      Text(
        stringResource(R.string.track_chart_range, from + 1, to + 1, time(from), time(to), low.roundToInt(), high.roundToInt()),
        style = MaterialTheme.typography.bodySmall
      )
    }
  }
}
