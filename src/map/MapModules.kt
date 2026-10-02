// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/** Метка места, для которого открыто меню: кольцо с точкой в белой обводке, видна на любой карте. */
object TapMarkerModule : IAppModule {

  private val MARK_COLOR = Color(0xFF2C2C2A)
  private val OUTLINE_COLOR = Color.White

  override fun draw(context: MapDrawContext) {
    val point = MapPopup.marker ?: return
    val center = context.viewport.toScreen(point)
    with(context.scope) {
      val radius = 10.dp.toPx()
      val line = 2.dp.toPx()
      drawCircle(OUTLINE_COLOR, radius = radius, center = center, style = Stroke(line + 3.dp.toPx()))
      drawCircle(MARK_COLOR, radius = radius, center = center, style = Stroke(line))
      drawCircle(OUTLINE_COLOR, radius = 3.5f.dp.toPx(), center = center)
      drawCircle(MARK_COLOR, radius = 2.dp.toPx(), center = center)
    }
  }
}

/** Крест в центре карты в ручном режиме: место, куда встают новые точки. */
object CrosshairModule : IAppModule {

  private val LINE_COLOR = Color(0xFF444441)
  private val OUTLINE_COLOR = Color.White

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

/** Линейка масштаба в левом нижнем углу видимой части карты: при открытой шторке - над ней. */
object ScaleBarModule : IAppModule {

  private const val EM_SP = 14
  private val BAR_COLOR = Color(0xFF444441)
  private val OUTLINE_COLOR = Color.White
  private val MANTISSAS = intArrayOf(5, 2, 1)

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

      // Сначала обводка, потом сама линейка: так она читается на любой карте.
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

  /** Наибольшее значение ряда 1-2-5, не больше limit. */
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
