// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

// All elements of the application. The order is the start order (a module used by another one goes first)
// and the drawing order on the map (first is drawn at the bottom).
/**
* Defines the ordered application module registry used for lifecycle, drawing and hit testing.
*
* Public and subclass/module-facing members:
* - [all] - Ordered modules: startup and drawing use forward order, shutdown and hit testing use reverse order.
*/
object AppModules {
  /**
  * Ordered modules: startup and drawing use forward order, shutdown and hit testing use reverse order.
  * @return Ordered modules: startup and drawing use forward order, shutdown and hit testing use reverse order.
  */
  val all: List<IAppModule> = listOf(
    AppClock,
    GridModule,
    CrosshairModule,
    ScaleBarModule,
    SavedTracksLayer,
    CurrentTrackLayer,
    RouteModule,
    PositionModule,
    PointSchedule,
    PointsLayer,
    TapMarkerModule,
    MoverModule,
    MapRotation,
    AutoPosition
  )
}
