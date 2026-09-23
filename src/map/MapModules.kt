// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

// Latitude/longitude grid when no map engine is selected: 3 to 5 lines of each kind on the screen.
/**
* Draws a coordinate grid when no map background is available.
*
* Public and subclass/module-facing members:
* - [draw] - Draws a coordinate grid when no map background is available Draws the current layer snapshot in registry order.
*/
object GridModule : IAppModule {

  private const val MAX_GRID_LINES = 5
  private val STEP_MANTISSAS = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 5.0, 7.5)

  /**
  * Draws a coordinate grid when no map background is available Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    if (Maps.shownType != MapType.NONE) return
    val view = context.viewport
    val projection = view.projection
    with(context.scope) {
      val corners = listOf(
        Offset.Zero, Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height)
      ).map { view.toGeo(it) }
      val latMin = corners.minOf { it.lat }.coerceAtLeast(-MercatorProjection.MAX_LAT)
      val latMax = corners.maxOf { it.lat }.coerceAtMost(MercatorProjection.MAX_LAT)
      val lonMin = corners.minOf { it.lon }
      val lonMax = corners.maxOf { it.lon }
      val stroke = 1.dp.toPx()

      val lonStep = gridStep(lonMax - lonMin)
      var i = ceil(lonMin / lonStep).toLong()
      while (i * lonStep <= lonMax) {
        val lon = i * lonStep
        drawLine(PASSIVE_COLOR, view.toScreen(projection.toWorld(latMin, lon)), view.toScreen(projection.toWorld(latMax, lon)), stroke)
        i++
      }

      val latStep = gridStep(latMax - latMin)
      i = ceil(latMin / latStep).toLong()
      while (i * latStep <= latMax) {
        val lat = i * latStep
        drawLine(PASSIVE_COLOR, view.toScreen(projection.toWorld(lat, lonMin)), view.toScreen(projection.toWorld(lat, lonMax)), stroke)
        i++
      }
    }
  }

  // Smallest step from the 1-1.5-2-3-5-7.5 series that gives at most MAX_GRID_LINES lines.
  private fun gridStep(span: Double): Double {
    val target = span / MAX_GRID_LINES
    if (!(target > 0)) return 1.0
    val magnitude = 10.0.pow(floor(log10(target)))
    STEP_MANTISSAS.forEach { if (it * magnitude >= target) return it * magnitude }
    return 10 * magnitude
  }
}

// Marks the tapped place while its menu is open: a ring with a dot, outlined for any map.
/**
* Draws the marker for the map popup's geographic position.
*
* Public and subclass/module-facing members:
* - [draw] - Draws the marker for the map popup's geographic position Draws the current layer snapshot in registry order.
*/
object TapMarkerModule : IAppModule {

  private val MARK_COLOR = Color(0xFF2C2C2A)
  private val OUTLINE_COLOR = Color.White

  /**
  * Draws the marker for the map popup's geographic position Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    val point = MapPopup.marker ?: return
    val center = context.viewport.toScreen(point)
    with(context.scope) {
      val radius = 10.dp.toPx()
      val line = 2.dp.toPx()
      drawCircle(OUTLINE_COLOR, radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(line + 3.dp.toPx()))
      drawCircle(MARK_COLOR, radius = radius, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(line))
      drawCircle(OUTLINE_COLOR, radius = 3.5f.dp.toPx(), center = center)
      drawCircle(MARK_COLOR, radius = 2.dp.toPx(), center = center)
    }
  }
}

// Cross in the middle of the map while it is moved by hand: the place new points go to.
/**
* Draws the map-center crosshair during manual positioning.
*
* Public and subclass/module-facing members:
* - [draw] - Draws the map-center crosshair during manual positioning Draws the current layer snapshot in registry order.
*/
object CrosshairModule : IAppModule {

  private val LINE_COLOR = Color(0xFF444441)
  private val OUTLINE_COLOR = Color.White

  /**
  * Draws the map-center crosshair during manual positioning Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    if (Maps.positionMode != PositionMode.CUSTOM) return
    with(context.scope) {
      val half = 5.dp.toPx()
      val width = 2.dp.toPx()
      val outline = width + 2.dp.toPx()
      val c = Offset(size.width / 2, size.height / 2)
      listOf(OUTLINE_COLOR to outline, LINE_COLOR to width).forEach { (color, stroke) ->
        drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), stroke, StrokeCap.Square)
        drawLine(color, Offset(c.x, c.y - half), Offset(c.x, c.y + half), stroke, StrokeCap.Square)
      }
    }
  }
}

// Metric scale bar in the lower left corner of the visible map: above the sheet while it is open.
/**
* Draws a distance scale above the portion of the map covered by the bottom sheet.
*
* Public and subclass/module-facing members:
* - [draw] - Draws a distance scale above the portion of the map covered by the bottom sheet Draws the current layer snapshot in registry order.
*/
object ScaleBarModule : IAppModule {

  private const val EM_SP = 14
  private val BAR_COLOR = Color(0xFF444441)
  private val OUTLINE_COLOR = Color.White
  private val MANTISSAS = intArrayOf(5, 2, 1)

  /**
  * Draws a distance scale above the portion of the map covered by the bottom sheet Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    val view = context.viewport
    with(context.scope) {
      val em = EM_SP.sp.toPx()
      val maxLength = min(size.width / 4, 10 * em)
      val metersPerPixel = view.metersPerPixel()
      if (!(metersPerPixel > 0)) return
      val meters = niceDistance(maxLength * metersPerPixel)
      if (meters <= 0) return
      val length = (meters / metersPerPixel).toFloat()
      val thickness = em / 3
      val margin = 12.dp.toPx()
      val left = margin
      val baseline = size.height - BottomSheet.coveredPx(size.height) - margin
      val tick = em * 0.8f
      if (baseline < 3 * em) return
      val outline = thickness + 4.dp.toPx()

      // Outline first, then the bar itself, so the bar reads on any map.
      drawLine(OUTLINE_COLOR, Offset(left, baseline), Offset(left + length, baseline), outline, StrokeCap.Round)
      drawLine(OUTLINE_COLOR, Offset(left, baseline), Offset(left, baseline - tick), outline, StrokeCap.Round)
      drawLine(OUTLINE_COLOR, Offset(left + length, baseline), Offset(left + length, baseline - tick), outline, StrokeCap.Round)
      drawLine(BAR_COLOR, Offset(left, baseline), Offset(left + length, baseline), thickness, StrokeCap.Round)
      drawLine(BAR_COLOR, Offset(left, baseline), Offset(left, baseline - tick), thickness, StrokeCap.Round)
      drawLine(BAR_COLOR, Offset(left + length, baseline), Offset(left + length, baseline - tick), thickness, StrokeCap.Round)

      val label = if (meters >= 1000) context.texts.kilometers.format(meters / 1000) else context.texts.meters.format(meters)
      context.drawOutlinedText(label, EM_SP, BAR_COLOR, Offset(left + thickness, baseline - thickness), alignX = 0f, alignY = 1f)
    }
  }

  // Largest value of the 1-2-5 series not exceeding the limit.
  private fun niceDistance(limit: Double): Int {
    if (limit < 1) return 0
    var magnitude = 10.0.pow(floor(log10(limit))).toInt()
    while (magnitude >= 1) {
      MANTISSAS.forEach { if (it * magnitude <= limit) return it * magnitude }
      magnitude /= 10
    }
    return 0
  }
}
