// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/**
* Stores latitude and longitude in geographic degrees.
* @param lat Latitude in geographic degrees.
* @param lon Longitude in geographic degrees.
* @property lat Latitude in geographic degrees.
* @property lon Longitude in geographic degrees.
*/
data class GeoPoint(val lat: Double, val lon: Double)

// Azimuth is the direction of the screen top in degrees clockwise from north, as in MapKit.
/**
* Stores the camera center, zoom and clockwise-from-north direction of the screen top.
* @param center Geographic camera center in latitude/longitude degrees.
* @param zoom Map zoom level; an omitted external-map zoom leaves the provider default.
* @param azimuth Direction of the screen top in degrees clockwise from north.
* @property center Geographic camera center in latitude/longitude degrees.
* @property zoom Map zoom level; an omitted external-map zoom leaves the provider default.
* @property azimuth Direction of the screen top in degrees clockwise from north.
*/
data class CameraState(val center: GeoPoint, val zoom: Float, val azimuth: Float)

// What the map engine knows about a place; for now only its coordinates.
/**
* Describes the geographic place selected on a map background.
* @param point Geographic position in latitude/longitude degrees.
* @property point Geographic position in latitude/longitude degrees.
*/
class MapPointInfo(val point: GeoPoint)

// Everything the application can tell another map about a place; an engine passes on what its provider understands.
/**
* Carries camera and optional target information for opening an external map application or site.
* @param center Geographic camera center supplied to the external map.
* @param zoom Map zoom level; an omitted external-map zoom leaves the provider default.
* @param azimuth Direction of the screen top in degrees clockwise from north. Default: null.
* @param point Optional marked geographic target distinct from the camera center.
* @param text Optional target label passed to a provider that supports it.
* @property center Geographic camera center supplied to the external map.
* @property zoom Map zoom level; an omitted external-map zoom leaves the provider default.
* @property azimuth Direction of the screen top in degrees clockwise from north. Default: null.
* @property point Optional marked geographic target distinct from the camera center.
* @property text Optional target label passed to a provider that supports it.
*/
class MapOpenRequest(
  val center: GeoPoint,
  val zoom: Float? = null,
  val azimuth: Float? = null,
  val point: GeoPoint? = null,
  val text: String? = null
)

// Projection and scale of an engine: world coordinates 0..1, world width in pixels = tileSizeDp * 2^zoom * density.
/**
* Defines conversion to normalized world coordinates and latitude-dependent geographic scale.
*
* Public and subclass/module-facing members:
* - [tileSizeDp] - Tile width in dp at zoom zero, used with density and zoom to calculate world scale.
* - [toWorld] - Projects geographic degrees into normalized world coordinates.
* - [toGeo] - Inverts normalized world coordinates into geographic degrees.
* - [metersPerWorldUnit] - Computes local ground scale at a geographic latitude.
*/
interface IMapProjection {
  /**
  * Tile width in dp at zoom zero, used with density and zoom to calculate world scale.
  * @return Tile width in dp at zoom zero, used with density and zoom to calculate world scale.
  */
  val tileSizeDp: Double
  /**
  * Projects geographic degrees into normalized world coordinates.
  * @param lat Latitude in geographic degrees.
  * @param lon Longitude in geographic degrees.
  * @return A WorldPoint in the projection's coordinate system.
  */
  fun toWorld(lat: Double, lon: Double): WorldPoint
  /**
  * Projects geographic degrees into normalized world coordinates.
  * @param point Geographic position in latitude/longitude degrees.
  * @return A WorldPoint in the projection's coordinate system.
  */
  fun toWorld(point: GeoPoint): WorldPoint = toWorld(point.lat, point.lon)
  /**
  * Inverts normalized world coordinates into geographic degrees.
  * @param world Position in normalized projected world coordinates.
  * @return The geographic position.
  */
  fun toGeo(world: WorldPoint): GeoPoint
  /**
  * Computes local ground scale at a geographic latitude.
  * @param lat Latitude in geographic degrees.
  * @return Meters represented by one world-coordinate unit.
  */
  fun metersPerWorldUnit(lat: Double): Double
}

// Where the background really drew the camera center and with which world width in pixels: its view size and its own scale
// need not match what the application computes, and objects must lie on the map, not near it.
/**
* Captures the actual screen center and world width reported by a map background.
*
* Usage: Use actual engine projection when available so overlays match the background's scale and view placement.
* @param center Actual screen position of the camera center in pixels, as measured by the background engine.
* @param worldPx Actual pixel width of one normalized world-coordinate unit.
* @property center Actual screen position of the camera center in pixels, as measured by the background engine.
* @property worldPx Actual pixel width of one normalized world-coordinate unit.
*/
class MapCalibration(val center: Offset, val worldPx: Double)

// Answer of a provider to a route request; both methods are called in the main thread, exactly one of them once.
/**
* Receives the geometries of a finished route request or the reason it failed.
*
* Public and subclass/module-facing members:
* - [onRouteReady] - Accepts the ways the provider found.
* - [onRouteFailed] - Accepts the reason a route request failed.
*/
interface IRouteListener {
  /**
  * Accepts the ways the provider found.
  * @param geometries Ways from the requested start to the requested end, in the provider's order.
  * @return Unit; an empty list means the provider found nothing.
  */
  fun onRouteReady(geometries: List<RouteGeometry>)

  /**
  * Accepts the reason a route request failed.
  * @param message Human-readable failure description.
  * @return Unit; called in the main thread.
  */
  fun onRouteFailed(message: String)
}

// Passive map background: draws the map for the camera it is given. Gestures and own objects are drawn above it by the application.
/**
* Defines a passive map background driven by the application's camera and gesture handling.
*
* Public and subclass/module-facing members:
* - [projection] - Projection used by the engine; defaults to the shared ellipsoidal Mercator implementation.
* - [calibration] - Supplies the background's actual screen center and world-pixel width for overlay alignment.
* - [setCamera] - Applies application camera state to the passive map background.
* - [getInfoForPoint] - Builds the background's available information for a selected place.
* - [settingsChanged] - Applies changed map settings such as 3D mode.
* - [openExternal] - Opens a place in the provider's external application or website.
* - [handleTap] - Offers a screen tap to provider-specific UI such as its logo.
* - [start] - Starts map activity while its hosting activity is visible.
* - [stop] - Stops map activity when its hosting activity is not visible.
* - [release] - Releases the current map view and provider resources.
* - [requestRoute] - Asks the provider for the ways from one coordinate to another.
* - [Backdrop] - Emits the passive map background within the supplied layout modifier.
*/
interface IMapEngine {

  // Route requests answer through the listener in the main thread; an engine without routing simply says no.
  /**
  * Asks the provider for the ways from one coordinate to another.
  * @param from Coordinate the way starts at.
  * @param to Coordinate the way ends at.
  * @param transport Way of travelling to request.
  * @param listener Receiver of the answer or of the failure reason.
  * @return True when the request was submitted, false when the provider cannot route or refused the request.
  */
  fun requestRoute(from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener): Boolean = false

  /**
  * Projection used by the engine; defaults to the shared ellipsoidal Mercator implementation.
  * @return Projection used by the engine; defaults to the shared ellipsoidal Mercator implementation.
  */
  val projection: IMapProjection get() = MercatorProjection

  // Asked for every drawing; null means the application draws by its own computation.
  /**
  * Supplies the background's actual screen center and world-pixel width for overlay alignment.
  * @param camera Camera snapshot used for this projection or background measurement.
  * @return Calibration, or null to use computed viewport geometry.
  */
  fun calibration(camera: CameraState): MapCalibration? = null

  /**
  * Applies application camera state to the passive map background.
  * @param state New immutable camera snapshot.
  * @return Unit; call on the main thread.
  */
  fun setCamera(state: CameraState)

  /**
  * Builds the background's available information for a selected place.
  * @param point Geographic position in latitude/longitude degrees.
  * @return MapPointInfo; the default supplies coordinates only.
  */
  fun getInfoForPoint(point: GeoPoint): MapPointInfo = MapPointInfo(point)

  // A map setting changed (for example 3D mode).
  /**
  * Applies changed map settings such as 3D mode.
  * @return Unit; default implementations may do nothing.
  */
  fun settingsChanged() {}

  // Shows the place in the application or on the site of the map provider.
  /**
  * Opens a place in the provider's external application or website.
  * @param request Camera and optional target information for the external map.
  * @return Unit; unsupported engines may do nothing.
  */
  fun openExternal(request: MapOpenRequest) {}

  // A tap the engine handles itself, for example on its logo; true when handled.
  /**
  * Offers a screen tap to provider-specific UI such as its logo.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return True when consumed, false when normal map handling should continue.
  */
  fun handleTap(screen: Offset, viewport: MapViewport): Boolean = false

  // Follow the activity visibility.
  /**
  * Starts map activity while its hosting activity is visible.
  * @return Unit; the default implementation does nothing.
  */
  fun start() {}
  /**
  * Stops map activity when its hosting activity is not visible.
  * @return Unit; the default implementation does nothing.
  */
  fun stop() {}

  /**
  * Releases the current map view and provider resources.
  * @return Unit; call before abandoning an engine.
  */
  fun release() {}

  /**
  * Emits the passive map background within the supplied layout modifier.
  * @param modifier Compose layout and drawing modifier applied to the emitted host.
  * @return Unit; call from composition.
  */
  @Composable
  fun Backdrop(modifier: Modifier)
}

/**
* Selects a background provider and creates the corresponding map engine.
*
* Public and subclass/module-facing members:
* - [createEngine] - Creates the selected background, falling back to a blank engine when MapKit cannot initialize.
* @param label String resource identifying the displayed label.
* @property label String resource identifying the displayed label.
*/
enum class MapType(@StringRes val label: Int) {
  
  /** Use the blank background with application grid overlays. */
  NONE(R.string.map_none) {
    /**
    * Creates the blank background engine.
    * @return A new passive map engine.
    */
    override fun createEngine(): IMapEngine = BlankMapEngine()
  },
  // Without the MapKit key the grid is shown instead.
  
  /** Use Yandex MapKit, falling back to a blank background if initialization is unavailable. */
  YANDEX(R.string.map_yandex) {
    /**
    * Creates Yandex MapKit when initialized, otherwise a blank engine.
    * @return A new passive map engine.
    */
    override fun createEngine(): IMapEngine = if (YandexMapEngine.ensureReady()) YandexMapEngine() else BlankMapEngine()
  };

  /**
  * Creates the selected background, falling back to a blank engine when MapKit cannot initialize.
  * @return A new IMapEngine instance.
  */
  abstract fun createEngine(): IMapEngine
}
