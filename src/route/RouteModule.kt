// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.ui.geometry.Offset

// Map layer of the selected route: the geometries of its legs. While the navigation runs the layer also follows the
// position along the way being travelled.

/**
* Draws the legs of the selected route, answers touches on them and follows the position while navigating.
*
* Public and subclass/module-facing members:
* - [start] - Subscribes to accepted GPS measurements.
* - [stop] - Removes the GPS subscription.
* - [draw] - Draws every leg of the selected route.
* - [hitTest] - Finds the geometry touched on the map.
*/
object RouteModule : IAppModule {

  private val main = Handler(Looper.getMainLooper())

  // The callback comes in the GPS worker, and the navigation changes the stops and their geometries, which the drawing
  // of the main thread walks through, so the change is postponed until the main thread.
  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    val fix = update.fix
    if (update.fixIsNew && fix != null) main.post { RouteStore.onPosition(GeoPoint(fix.lat, fix.lon), fix.speed) }
  }

  /**
  * Subscribes to accepted GPS measurements.
  * @return Unit; the navigation follows the position through this subscription in the main thread.
  */
  override fun start() = GpsDataManager.subscribe(onUpdate)

  /**
  * Removes the GPS subscription.
  * @return Unit; the navigation stops following the position.
  */
  override fun stop() = GpsDataManager.unsubscribe(onUpdate)

  // Marked rows of the list rule the map: while the list marks something, the legs of the other stops fade away.
  /**
  * Draws every leg of the selected route.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; nothing is drawn without a selected route.
  */
  override fun draw(context: MapDrawContext) {
    val route = RouteStore.selected ?: return
    val marked = route.entries.any { it.highlighted }
    route.entries.forEach { entry ->
      val alpha = if (!marked || entry.highlighted) null else ROUTE_UNSELECTED_ALPHA
      // The ways that were not chosen go first, so the active line stays on top of them.
      entry.geometries.filter { it.state == RouteGeometryState.INACTIVE }.forEach { it.draw(context, alpha) }
      entry.chosen()?.draw(context, alpha)
    }
  }

  /**
  * Finds the geometry touched on the map.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return The touched leg, or null.
  */
  override fun hitTest(screen: Offset, viewport: MapViewport): ISelectableMapObject? {
    val route = RouteStore.selected ?: return null
    val limit = ROUTE_TOUCH_LIMIT.value * viewport.density
    route.entries.forEach { entry ->
      entry.geometries.firstOrNull { it.hitTest(screen, viewport, limit) }?.let { return TouchedLeg(entry, it) }
    }
    return null
  }
}

// FOR LOCAL USE: a leg touched on the map. A way that was not chosen becomes the active way of its leg, which during
// the travel is asked about first; after that the list of the route opens at the stop the leg leads to.
private class TouchedLeg(private val entry: RouteEntry, private val geometry: RouteGeometry) : ISelectableMapObject {
  override fun selected(screen: Offset) {
    if (geometry.state == RouteGeometryState.ACTIVE) return showStop()
    if (!RouteStore.active) {
      RouteStore.choose(entry, geometry)
      return showStop()
    }
    AppDialog.confirm(R.string.route_switch_way) {
      RouteStore.chooseOnly(entry, geometry)
      showStop()
    }
  }

  private fun showStop() {
    RouteStore.focus(entry)
    if (AppCommands.showRoute.available()) AppCommands.showRoute.execute()
  }
}
