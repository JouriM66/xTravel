// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

private const val HIT_DISTANCE_DP = 12

// How close to a track line a tap counts as a hit, in pixels.
private fun hitLimit(viewport: MapViewport) = HIT_DISTANCE_DP * viewport.density

// Draws a track line.
private fun MapDrawContext.drawTrack(line: TrackLine, widthDp: Int, color: Color) {
  if (widthDp <= 0 || line.size - line.from < 2) return
  val view = viewport
  with(scope) {
    val width = widthDp.dp.toPx()
    val path = Path()
    for (i in line.from until line.size) {
      val x = view.screenX(line.wx[i], line.wy[i])
      val y = view.screenY(line.wx[i], line.wy[i])
      if (i == line.from) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
  }
}

// Tap on a track line opens the menu of this track; a name of null is the track being recorded now.
private class TrackObject(private val name: String?) : ISelectableMapObject {
  override fun selected(screen: Offset) {
    if (!toggleSelectedTrack(name)) MapPopup.show(screen) { TrackMapMenu(name) { MapPopup.close() } }
  }
}

/**
* Draws and hit-tests visible saved-track geometry.
*
* Public and subclass/module-facing members:
* - [draw] - Draws and hit-tests visible saved-track geometry Draws the current layer snapshot in registry order.
* - [hitTest] - Tests visible geometry of SavedTracksLayer against the supplied screen point.
*/
object SavedTracksLayer : IAppModule {

  /**
  * Draws and hit-tests visible saved-track geometry Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    val color = Color(Settings.savedTrackColor.value)
    val width = Settings.savedTrackWidth.value
    TrackStorage.loaded.values.forEach { context.drawTrack(it, width, color) }
  }

  /**
  * Tests visible geometry of SavedTracksLayer against the supplied screen point.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return A selectable map object, or null when no object is within the hit tolerance.
  */
  override fun hitTest(screen: Offset, viewport: MapViewport): ISelectableMapObject? {
    if (Settings.savedTrackWidth.value <= 0) return null
    val name = TrackStorage.loaded.entries.firstOrNull { nearLine(it.value, screen, viewport, hitLimit(viewport)) }?.key ?: return null
    return TrackObject(name)
  }
}

/**
* Draws and hit-tests the current recording's visible tail.
*
* Public and subclass/module-facing members:
* - [draw] - Draws and hit-tests the current recording's visible tail Draws the current layer snapshot in registry order.
* - [hitTest] - Tests visible geometry of CurrentTrackLayer against the supplied screen point.
*/
object CurrentTrackLayer : IAppModule {

  /**
  * Draws and hit-tests the current recording's visible tail Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    if (!TrackRecorder.visible) return
    context.drawTrack(TrackRecorder.line, Settings.currentTrackWidth.value, Color(Settings.currentTrackColor.value))
  }

  /**
  * Tests visible geometry of CurrentTrackLayer against the supplied screen point.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return A selectable map object, or null when no object is within the hit tolerance.
  */
  override fun hitTest(screen: Offset, viewport: MapViewport): ISelectableMapObject? {
    if (!TrackRecorder.visible || Settings.currentTrackWidth.value <= 0) return null
    return if (nearLine(TrackRecorder.line, screen, viewport, hitLimit(viewport))) TrackObject(null) else null
  }
}

// Current position: dot, or an arrow along the course while moving; grey when the device sends no data. Rings with the fixed step.
/**
* Draws the accepted GPS position, movement arrow and configured distance rings.
*
* Public and subclass/module-facing members:
* - [start] - Subscribes to GPS updates to redraw the position marker and movement state.
* - [stop] - Removes the GPS redraw subscription.
* - [draw] - Draws the accepted GPS position, movement arrow and configured distance rings Draws the current layer snapshot in registry order.
*/
object PositionModule : IAppModule {

  private val MARKER_COLOR = Color(0xFF185FA5)
  private val RING_COLOR = Color(0x99378ADD)

  // The arrow goes away without any new data, when the accepted sample grows old, so its state is watched as well.
  @Volatile
  private var arrowShown = false

  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    if (update.fixIsNew || arrowShown != (update.fix?.isMoving() == true)) ModuleHost.requestRedraw()
  }

  /**
  * Subscribes to GPS updates to redraw the position marker and movement state.
  * @return Unit; called by the module lifecycle.
  */
  override fun start() = GpsDataManager.subscribe(onUpdate)

  /**
  * Removes the GPS redraw subscription.
  * @return Unit; called by the module lifecycle.
  */
  override fun stop() = GpsDataManager.unsubscribe(onUpdate)

  /**
  * Draws the accepted GPS position, movement arrow and configured distance rings Draws the current layer snapshot in registry order.
  *
  * Usage: Called on the drawing/UI thread; keep the referenced frame context only for this call.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; draws through the supplied MapDrawContext.
  */
  override fun draw(context: MapDrawContext) {
    val fix = GpsDataManager.lastFix ?: return
    val view = context.viewport
    val center = view.toScreen(GeoPoint(fix.lat, fix.lon))
    val color = if (GpsDataManager.status == GpsStatus.OK) MARKER_COLOR else INACTIVE_COLOR
    with(context.scope) {
      val rings = Settings.rings.value
      val step = Settings.ringStepValue.value.toDouble()
      val metersPerPixel = view.metersPerPixel()
      if (rings > 0 && step > 0 && metersPerPixel > 0) {
        for (k in 1..rings) {
          drawCircle(RING_COLOR, radius = (step * k / metersPerPixel).toFloat(), center = center, style = Stroke(1.5f.dp.toPx()))
        }
      }
      val course = fix.course
      arrowShown = course != null && fix.isMoving()
      if (course != null && arrowShown) {
        val size = 22.dp.toPx()
        val arrow = Path().apply {
          moveTo(center.x, center.y - size)
          lineTo(center.x + size * 0.75f, center.y + size * 0.8f)
          lineTo(center.x, center.y + size * 0.35f)
          lineTo(center.x - size * 0.75f, center.y + size * 0.8f)
          close()
        }
        rotate(view.screenAngle(course), pivot = center) {
          drawPath(arrow, Color.White, style = Stroke(3.dp.toPx(), join = StrokeJoin.Round))
          drawPath(arrow, color)
        }
      } else {
        drawCircle(Color.White, radius = 9.dp.toPx(), center = center)
        drawCircle(color, radius = 7.dp.toPx(), center = center)
      }
    }
  }
}

// Moves the map after the location by the position rules of Maps.
/**
* Forwards newly accepted GPS positions to the map camera on the main thread.
*
* Public and subclass/module-facing members:
* - [start] - Subscribes to accepted GPS positions and posts camera updates to the main thread.
* - [stop] - Removes the accepted-position subscription.
*/
object MoverModule : IAppModule {

  private val main = Handler(Looper.getMainLooper())
  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    val fix = update.fix
    if (update.fixIsNew && fix != null) main.post { Maps.onLocation(GeoPoint(fix.lat, fix.lon)) }
  }

  /**
  * Subscribes to accepted GPS positions and posts camera updates to the main thread.
  * @return Unit; called by the module lifecycle.
  */
  override fun start() = GpsDataManager.subscribe(onUpdate)

  /**
  * Removes the accepted-position subscription.
  * @return Unit; called by the module lifecycle.
  */
  override fun stop() = GpsDataManager.unsubscribe(onUpdate)
}
