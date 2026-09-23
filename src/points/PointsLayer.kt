// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Points on the map: pin filled by the status color (a visited independent point has its own), the icon of the point white
// inside, halo of the pin shape for availability.
// Above the pin two lines: the text, then the time until opening/closing; both in the time color, black without the time.
/**
* Draws point pins and schedule labels and manages hit-testing and drag previews.
*
* Public and subclass/module-facing members:
* - [start] - Subscribes the point-pin alarm blinker to the shared timer.
* - [stop] - Unsubscribes the point-pin alarm blinker.
* - [drag] - Active point ID and geographic drag-preview position, or null; changes are runtime-only until committed.
* - [draw] - Draws point pins and schedule labels and manages hit-testing and drag previews Draws the current layer snapshot in registry order.
* - [hitTest] - Tests visible geometry of PointsLayer against the supplied screen point.
* - [formatMinutes] - Chooses minute, hour/minute or day/hour formatting for a duration.
* - [drawPointPin] - Draws a point pin with status, visit and alarm styling and its selected icon.
* - [drawPlainPin] - Draws an independent pin for a place not stored as a point.
* - [startDrag] - Starts a preview at an existing point's coordinates.
* - [dragTo] - Updates the active point's drag-preview position.
* - [endDrag] - Clears a drag preview and applies its final coordinates when the requested ID matches.
*/
object PointsLayer : IAppModule {

  private const val PIN_SIZE_DP = 34
  private const val DRAG_ALPHA = 0.6f
  private val PIN = PathParser().parsePathString("M12,2C8.1,2 5,5.1 5,9c0,5.2 7,13 7,13s7,-7.8 7,-13c0,-3.9 -3.1,-7 -7,-7z").toPath()
  private val HEAD = Offset(12f, 9f)
  // Icon size in pin units (24 = pin size): fits the wide part of the pin.
  private const val ICON_SIZE = 11f
  // Availability halo follows the pin shape, in pin units (24 = pin size): halo width outside the pin and the white gap.
  private const val HALO_WIDTH = 3f
  private const val HALO_GAP = 0.8f

  // Alarm points blink by the system timer: the alarm color on blinkOn, the status color on blinkOff.
  @Volatile
  private var blinkOn = true

  private val blinker = object : IBlinkListener {
    override fun onBlinkOn() = setBlink(true)
    override fun onBlinkOff() = setBlink(false)
  }

  private fun setBlink(on: Boolean) {
    blinkOn = on
    if (PointStore.points.any { it.visible && it.alarm() }) ModuleHost.requestRedraw()
  }

  /**
  * Subscribes the point-pin alarm blinker to the shared timer.
  * @return Unit; module lifecycle hook.
  */
  override fun start() = TimerManager.subscribe(blinker)

  /**
  * Unsubscribes the point-pin alarm blinker.
  * @return Unit; module lifecycle hook.
  */
  override fun stop() = TimerManager.unsubscribe(blinker)

  // Point being dragged and its current position.
  /**
  * Active point ID and geographic drag-preview position, or null; changes are runtime-only until committed.
  * @return Active point ID and geographic drag-preview position, or null; changes are runtime-only until committed.
  */
  var drag by mutableStateOf<Pair<Long, GeoPoint>?>(null)
    private set

  /**
  * Draws point pins and schedule labels and manages hit-testing and drag previews Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    val view = context.viewport
    val dragging = drag
    val blinkOn = PointsLayer.blinkOn
    with(context.scope) {
      val size = PIN_SIZE_DP.dp.toPx()
      // The first point of the list is drawn last, on top.
      PointStore.points.asReversed().forEach { point ->
        if (!point.visible) return@forEach
        val position = if (dragging?.first == point.id) dragging.second else point.geo
        val tip = view.toScreen(position)
        if (tip.x < -size || tip.y < -size || tip.x > this.size.width + size || tip.y > this.size.height + size * 2) return@forEach
        val alpha = if (dragging?.first == point.id) DRAG_ALPHA else 1f
        drawPointPin(point, tip, size, blinkOn, alpha)
        val time = timeLabel(point, context.texts)
        val color = time?.second ?: Color.Black
        var bottom = tip.y - size - 2.dp.toPx()
        time?.let { bottom -= context.drawOutlinedText(it.first, Settings.pointTimeSize.value, color, Offset(tip.x, bottom), 0.5f, 1f) }
        point.captionOnly()?.let { context.drawOutlinedText(it, Settings.pointNameSize.value, color, Offset(tip.x, bottom), 0.5f, 1f) }
      }
    }
  }

  /**
  * Tests visible geometry of PointsLayer against the supplied screen point.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return A selectable map object, or null when no object is within the hit tolerance.
  */
  override fun hitTest(screen: Offset, viewport: MapViewport): ISelectableMapObject? {
    val size = PIN_SIZE_DP * viewport.density
    val margin = 6 * viewport.density
    val hit = PointStore.points.firstOrNull { point ->
      if (!point.visible) return@firstOrNull false
      val tip = viewport.toScreen(point.geo)
      screen.x in tip.x - size / 2 - margin..tip.x + size / 2 + margin && screen.y in tip.y - size - margin..tip.y + margin
    } ?: return null
    return PointObject(hit.id)
  }

  // The planned and the active states keep their colors even for a visited point; the visited color belongs to the independent one.
  private fun fillColor(point: MapPoint, blinkOn: Boolean): Color = Color(
    when {
      point.alarm() && blinkOn -> Settings.pointAlarmColor.value
      point.status == PointStatus.ACTIVE -> Settings.pointActiveColor.value
      point.status == PointStatus.PLANNED -> Settings.pointPlannedColor.value
      point.visited -> Settings.pointVisitedColor.value
      else -> Settings.pointIndependentColor.value
    }
  )

  private fun haloColor(point: MapPoint): Color? = when (point.availability()) {
    Availability.OPEN -> Color(Settings.availabilityOpenColor.value)
    Availability.CLOSED -> Color(Settings.availabilityClosedColor.value)
    Availability.NONE -> null
  }

  // Open: time until closing; closed: time until opening. What is shown follows the display chosen for the point.
  private fun timeLabel(point: MapPoint, texts: MapTexts): Pair<String, Color>? {
    val state = PointSchedule.shownTime(point) ?: return null
    val color = when (state.availability) {
      Availability.OPEN -> Settings.timeToCloseColor.value
      Availability.CLOSED -> Settings.timeToOpenColor.value
      Availability.NONE -> return null
    }
    return formatMinutes(state.minutesLeft, texts) to Color(color)
  }

  /**
  * Chooses minute, hour/minute or day/hour formatting for a duration.
  * @param minutes Duration or local minute-of-day value in minutes, as described by the operation.
  * @param texts Localized format strings for map labels.
  * @return A localized duration label.
  */
  fun formatMinutes(minutes: Int, texts: MapTexts): String = when {
    minutes < 60 -> texts.minutes.format(minutes)
    minutes < 24 * 60 -> texts.hoursMinutes.format(minutes / 60, minutes % 60)
    else -> texts.daysHours.format(minutes / (24 * 60), minutes % (24 * 60) / 60)
  }

  // The same pin is drawn on the map and in the point list.
  /**
  * Draws a point pin with status, visit and alarm styling and its selected icon.
  * @param point Point snapshot whose attachments, schedule or metadata are processed; null represents absent loaded notes where accepted.
  * @param tip Pin tip position in screen pixels.
  * @param size Drawing size in screen pixels unless the function documents another unit.
  * @param blinkOn Whether the alarm pin is in its highlighted blink phase. Default: true.
  * @param alpha Drawing opacity multiplier, normally in 0..1. Default: 1f.
  * @receiver Active canvas drawing scope for this frame.
  * @return Unit; draws into the receiver DrawScope.
  */
  fun DrawScope.drawPointPin(point: MapPoint, tip: Offset, size: Float, blinkOn: Boolean = true, alpha: Float = 1f) =
    drawPin(tip, size, fillColor(point, blinkOn), point.icon, haloColor(point), alpha)

  // Pin of a point the application does not hold yet, for example one offered by an import: independent status without a halo.
  /**
  * Draws an independent pin for a place not stored as a point.
  * @param tip Pin tip position in screen pixels.
  * @param size Drawing size in screen pixels unless the function documents another unit.
  * @param visited Whether the point has been visited.
  * @receiver Active canvas drawing scope for this frame.
  * @return Unit; draws into the receiver DrawScope.
  */
  fun DrawScope.drawPlainPin(tip: Offset, size: Float, visited: Boolean) =
    drawPin(tip, size, Color(if (visited) Settings.pointVisitedColor.value else Settings.pointIndependentColor.value), "", null, 1f)

  private fun DrawScope.drawPin(tip: Offset, size: Float, fill: Color, icon: String, halo: Color?, alpha: Float) {
    val unit = size / 24f
    translate(tip.x - 12f * unit, tip.y - 22f * unit) {
      scale(unit, pivot = Offset.Zero) {
        // A wide stroke of the pin outline gives a halo of the same shape; a white stroke separates it from the fill.
        halo?.let {
          drawPath(PIN, it, alpha = alpha, style = Stroke((HALO_WIDTH + HALO_GAP) * 2, join = StrokeJoin.Round))
          drawPath(PIN, Color.White, alpha = alpha, style = Stroke(HALO_GAP * 2, join = StrokeJoin.Round))
        }
        drawPath(PIN, fill, alpha = alpha)
        drawPath(PIN, darker(fill), alpha = alpha, style = Stroke(0.8f))
        with(PointIcons) { drawPointIcon(icon, HEAD, ICON_SIZE, alpha) }
      }
    }
  }

  private fun darker(color: Color) = Color(color.red * 0.65f, color.green * 0.65f, color.blue * 0.65f, color.alpha)

  /**
  * Starts a preview at an existing point's coordinates.
  * @param id Stable point identifier.
  * @return Unit; an unknown ID clears the preview.
  */
  fun startDrag(id: Long) {
    drag = PointStore.find(id)?.let { id to it.geo }
  }

  /**
  * Updates the active point's drag-preview position.
  * @param id Stable point identifier.
  * @param point Geographic position in latitude/longitude degrees.
  * @return Unit; an ID other than the active drag is ignored.
  */
  fun dragTo(id: Long, point: GeoPoint) {
    if (drag?.first == id) drag = id to point
  }

  // Coordinates are saved on every release.
  /**
  * Clears a drag preview and applies its final coordinates when the requested ID matches.
  * @param id Stable point identifier.
  * @return Unit; matching moves are persisted through PointStore.
  */
  fun endDrag(id: Long) {
    val moved = drag ?: return
    drag = null
    if (moved.first == id) PointStore.update(id) { it.copy(lat = moved.second.lat, lon = moved.second.lon) }
  }
}

private class PointObject(private val id: Long) : ISelectableMapObject {
  override val dragId: Long get() = id
  override fun selected(screen: Offset) {
    if (!toggleSelectedPoint(id)) MapPopup.show(screen) { PointPopupMenu(id, PointMenuSource.MAP) { MapPopup.close() } }
  }
  override fun dragStart() = PointsLayer.startDrag(id)
  override fun dragTo(point: GeoPoint) = PointsLayer.dragTo(id, point)
  override fun dragEnd() = PointsLayer.endDrag(id)
}
