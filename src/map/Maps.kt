// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlin.math.log2

// START: restored from the last session, the first GPS position moves the map.
// AUTO: the map follows the device location.
// CUSTOM: the user moved the map, GPS does not touch it.
/**
* Distinguishes initial positioning, user-controlled camera and automatic GPS following.
*/
enum class PositionMode { 
  /** Restored camera awaits its first accepted GPS positioning update. */
  START, 
  /** User-controlled camera does not follow incoming GPS positions. */
  CUSTOM, 
  /** Camera follows accepted GPS positions when interaction holds allow it. */
  AUTO }

// NORTH: north is always up. AUTO: while the position arrow is shown, the map turns so that the arrow points up.
// MANUAL: the map turns only by the gesture.
/**
* Selects north-up, automatic course rotation or manually controlled map direction.
* @param label String resource identifying the displayed label.
* @param shortLabel String resource for the compact toolbar label.
* @property label String resource identifying the displayed label.
* @property shortLabel String resource for the compact toolbar label.
*/
enum class MapDirection(@StringRes val label: Int, @StringRes val shortLabel: Int) {
  
  /** Keep north at the top of the screen. */
  NORTH(R.string.direction_north, R.string.north_short),
  
  /** Rotate to the accepted movement course after user inactivity. */
  AUTO(R.string.direction_auto, R.string.direction_auto_short),
  
  /** Retain orientation chosen by gestures. */
  MANUAL(R.string.direction_manual, R.string.direction_manual_short)
}

private val INDICATOR_GREEN = Color(0xFF639922)
private val INDICATOR_BLUE = Color(0xFF378ADD)

// State of the position button and of the GPS notification: no GPS / waiting for data / the map follows the position / moved by hand.
/**
* Combines GPS availability and following mode into toolbar and notification presentation.
*
* Public and subclass/module-facing members:
* - [manualShape] - Whether the position control should use the manual/searching icon shape.
* - [notificationIcon] - Drawable resource matching the GPS/following state for the service notification.
* - [Companion] - Shared factory, state and lifecycle operations for PositionIndicator.
* @param label String resource identifying the displayed label.
* @param color Color used for the drawn text or indicator.
* @property label String resource identifying the displayed label.
* @property color Color used for the drawn text or indicator.
*/
enum class PositionIndicator(@StringRes val label: Int, val color: Color) {
  
  /** GPS is unavailable; the control offers GPS setup. */
  NONE(R.string.follow_none, INACTIVE_COLOR),
  
  /** GPS is available but raw measurements are stale or absent. */
  WAITING(R.string.follow_wait, INACTIVE_COLOR),
  
  /** GPS is fresh and the camera is not in manual mode. */
  AUTO(R.string.follow_auto, INDICATOR_GREEN),
  
  /** GPS is fresh while camera positioning is manual. */
  MANUAL(R.string.follow_manual, INDICATOR_BLUE);

  // Waiting keeps the shape of the position mode.
  /**
  * Whether the position control should use the manual/searching icon shape.
  * @return Whether the position control should use the manual/searching icon shape.
  */
  val manualShape: Boolean get() = this == MANUAL || (this == WAITING && Maps.positionMode == PositionMode.CUSTOM)

  /**
  * Drawable resource matching the GPS/following state for the service notification.
  * @return Drawable resource matching the GPS/following state for the service notification.
  */
  @get:DrawableRes
  val notificationIcon: Int
    get() = when {
      this == NONE -> R.drawable.ic_gps_off
      manualShape -> R.drawable.ic_gps_searching
      else -> R.drawable.ic_gps_fixed
    }

  /**
  * Shared factory, state and lifecycle operations for PositionIndicator.
  *
  * Public and subclass/module-facing members:
  * - [current] - Derives the current position-control appearance from GPS status and camera-follow mode.
  */
  companion object {
    /**
    * Derives the current position-control appearance from GPS status and camera-follow mode.
    * @return The matching indicator state.
    */
    fun current(): PositionIndicator = when (GpsDataManager.status) {
      GpsStatus.UNAVAILABLE -> NONE
      GpsStatus.WAITING -> WAITING
      GpsStatus.OK -> if (Maps.positionMode == PositionMode.CUSTOM) MANUAL else AUTO
    }
  }
}

// Camera of the application, the same for every engine. All calls on the main thread.
/**
* Owns map camera state, background selection and GPS-following behavior on the main thread.
*
* Public and subclass/module-facing members:
* - [type] - Configured map provider type from Settings.
* - [shownType] - Actually displayed provider type, including blank fallback when the configured map is unavailable.
* - [engine] - Current passive map engine; managed by Maps and externally read-only.
* - [camera] - Observable current camera snapshot; change it through map actions.
* - [positionMode] - Observable initial, manual or automatic positioning mode; managed by Maps.
* - [init] - Restores the camera, creates the selected engine and binds map-setting callbacks.
* - [select] - Selects and replaces the map engine, resetting map failure counts when enabled manually.
* - [disableEngine] - Switches a failed map provider and its setting to the blank background.
* - [move] - Applies camera state, enforces north-up mode and schedules camera persistence.
* - [moveByGesture] - Applies gesture camera changes while tracking whether a one-finger drag should disable GPS following.
* - [touch] - Accepts brief activity notifications without changing camera state.
* - [hold] - Suspends GPS following while nested user interactions are active and captures the initial map center.
* - [resume] - Releases a hold and determines whether the completed gesture should enter manual positioning.
* - [centerOn] - Centers the camera at a geographic location and enters manual mode.
* - [showArea] - Centers and scales the camera so that all the given places fit the visible map.
* - [lastViewport] - Coordinate converter of the last drawn frame, or null before the first one.
* - [followGps] - Restores automatic following from manual mode and moves to the last accepted fix if present.
* - [onLocation] - Applies a newly accepted GPS position while automatic following is allowed.
* - [rotateTo] - Rotates the camera only in automatic map-direction mode.
* - [start] - Subscribes to user activity and starts the current map engine.
* - [stop] - Unsubscribes, clears interaction state, stops the map engine and saves the camera.
* - [save] - Stores camera center, zoom and azimuth in SharedPreferences.
*/
object Maps : IUserListener {

  private const val SAVE_DELAY_MS = 2000L
  // Part of the visible map an area is fitted into, so its ends do not touch the edges.
  private const val AREA_FILL = 0.85f

  // Objects are drawn above their place - a pin stands on it - so the fitted area keeps this much room at every edge.
  private val AREA_MARGIN = 40.dp
  private const val KEY_LAT = "camera_lat"
  private const val KEY_LON = "camera_lon"
  private const val KEY_ZOOM = "camera_zoom"
  private const val KEY_AZIMUTH = "camera_azimuth"
  private val DEFAULT_CAMERA = CameraState(GeoPoint(55.751244, 37.618423), 13f, 0f)

  /**
  * Configured map provider type from Settings.
  * @return Configured map provider type from Settings.
  */
  val type: MapType get() = Settings.mapType.value

  // What is on the screen: the grid stands in for a map that could not start.
  /**
  * Actually displayed provider type, including blank fallback when the configured map is unavailable.
  * @return Actually displayed provider type, including blank fallback when the configured map is unavailable.
  */
  val shownType: MapType get() = if (engine is BlankMapEngine) MapType.NONE else type

  /**
  * Current passive map engine; managed by Maps and externally read-only.
  * @return Current passive map engine; managed by Maps and externally read-only.
  */
  var engine: IMapEngine by mutableStateOf(BlankMapEngine())
    private set

  /**
  * Observable current camera snapshot; change it through map actions.
  * @return Observable current camera snapshot; change it through map actions.
  */
  var camera by mutableStateOf(DEFAULT_CAMERA)
    private set

  // The map area publishes the converter of the frame it drew; showArea needs the size of the canvas.
  /**
  * Coordinate converter of the last drawn frame, or null before the first one.
  * @return Coordinate converter of the last drawn frame, or null before the first one.
  */
  var lastViewport: MapViewport? = null

  /**
  * Observable initial, manual or automatic positioning mode; managed by Maps.
  * @return Observable initial, manual or automatic positioning mode; managed by Maps.
  */
  var positionMode by mutableStateOf(PositionMode.START)
    private set

  private val northUp: Boolean get() = Settings.mapDirection.value == MapDirection.NORTH

  private var started = false
  private val handler = Handler(Looper.getMainLooper())
  private val saveTask = Runnable { save() }
  private var userHolds = 0
  private var heldCenter: GeoPoint? = null
  private var gestureViewport: MapViewport? = null
  private var manualPositionAllowed = true

  /**
  * Restores the camera, creates the selected engine and binds map-setting callbacks.
  * @return Unit; call on the main thread after Settings initialization.
  */
  fun init() {
    val restored = loadCamera() ?: DEFAULT_CAMERA
    camera = if (northUp) restored.copy(azimuth = 0f) else restored
    engine = type.createEngine().also { it.setCamera(camera) }
    Settings.mapDirection.onChange { if (northUp) move(camera.copy(azimuth = 0f), userGesture = false) }
    Settings.map3d.onChange { engine.settingsChanged() }
  }

  // Chosen by hand: failures of the map are counted anew.
  /**
  * Selects and replaces the map engine, resetting map failure counts when enabled manually.
  * @param newType Requested map-background provider type.
  * @return Unit; a missing MapKit key leaves the selection unchanged and opens a message.
  */
  fun select(newType: MapType) {
    if (newType == MapType.YANDEX && !YandexMapEngine.ensureReady()) {
      AppSession.message(if (YandexMapEngine.restartNeeded) R.string.key_restart else R.string.mapkit_key_missing)
      return
    }
    if (newType == shownType && newType == type) return
    Failures.reset(FailureSource.MAP)
    switchTo(newType)
  }

  // A failed engine is replaced by the grid, the setting too; the map comes back only when chosen by hand.
  /**
  * Switches a failed map provider and its setting to the blank background.
  * @return Unit; leaves the camera available for overlays.
  */
  fun disableEngine() {
    if (type != MapType.NONE || engine !is BlankMapEngine) switchTo(MapType.NONE)
  }

  // The new engine is set before the old one stops, so a failure while stopping does not come back here.
  private fun switchTo(newType: MapType) {
    val old = engine
    Settings.mapType.value = newType
    engine = newType.createEngine().also {
      it.setCamera(camera)
      if (started) it.start()
    }
    if (started) old.stop()
    old.release()
  }

  /**
  * Applies camera state, enforces north-up mode and schedules camera persistence.
  * @param state New immutable camera snapshot.
  * @param userGesture Whether this camera change should immediately enter manual positioning.
  * @return Unit; a user gesture also switches to manual positioning.
  */
  fun move(state: CameraState, userGesture: Boolean) {
    val applied = if (northUp) state.copy(azimuth = 0f) else state
    camera = applied
    engine.setCamera(applied)
    if (userGesture) setMode(PositionMode.CUSTOM)
    handler.removeCallbacks(saveTask)
    handler.postDelayed(saveTask, SAVE_DELAY_MS)
  }

  // FOR LOCAL USE: records map gestures for the position-mode check when the user releases the screen.
  /**
  * Applies gesture camera changes while tracking whether a one-finger drag should disable GPS following.
  * @param state New immutable camera snapshot.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @param panGesture Whether the change is a one-finger pan eligible to switch from GPS following to manual positioning.
  * @return Unit; use instead of move for gesture-driven camera changes.
  */
  fun moveByGesture(state: CameraState, viewport: MapViewport, panGesture: Boolean) {
    if (userHolds > 0) {
      gestureViewport = viewport
      if (!panGesture) manualPositionAllowed = false
    }
    move(state, userGesture = false)
  }

  /**
  * Accepts brief activity notifications without changing camera state.
  * @return Unit; intentionally performs no action.
  */
  override fun touch() {}

  /**
  * Suspends GPS following while nested user interactions are active and captures the initial map center.
  * @return Unit; balance with resume.
  */
  override fun hold() {
    if (userHolds++ > 0) return
    heldCenter = camera.center
    gestureViewport = null
    manualPositionAllowed = true
  }

  /**
  * Releases a hold and determines whether the completed gesture should enter manual positioning.
  * @return Unit; nested holds defer the final decision.
  */
  override fun resume() {
    if (userHolds == 0 || --userHolds > 0) return
    val start = heldCenter
    val viewport = gestureViewport
    heldCenter = null
    gestureViewport = null
    if (!manualPositionAllowed || positionMode == PositionMode.CUSTOM || start == null || viewport == null) return
    val current = MapViewport(viewport.width, viewport.height, camera, viewport.density, engine.projection, engine.calibration(camera))
    val displacement = (current.toScreen(start) - current.toScreen(camera.center)).getDistance()
    if (displacement > minOf(viewport.width, viewport.height) / 4f) setMode(PositionMode.CUSTOM)
  }

  // Shows a place chosen by the user: the map stops following GPS.
  /**
  * Centers the camera at a geographic location and enters manual mode.
  * @param point Geographic position in latitude/longitude degrees.
  * @return Unit; automatic GPS following is disabled.
  */
  fun centerOn(point: GeoPoint) {
    setMode(PositionMode.CUSTOM)
    move(camera.copy(center = point), userGesture = false)
  }

  // Fits a set of places: the zoom is chosen for the part of the map the sheet leaves visible.
  /**
  * Centers and scales the camera so that all the given places fit the visible map.
  * @param places Geographic places that must all be visible.
  * @return Unit; an empty list does nothing, and without a drawn frame only the center is applied.
  */
  fun showArea(places: List<GeoPoint>) {
    if (places.isEmpty()) return
    val worlds = places.map { engine.projection.toWorld(it) }
    val minX = worlds.minOf { it.x }
    val maxX = worlds.maxOf { it.x }
    val minY = worlds.minOf { it.y }
    val maxY = worlds.maxOf { it.y }
    val center = engine.projection.toGeo(WorldPoint((minX + maxX) / 2, (minY + maxY) / 2))
    setMode(PositionMode.CUSTOM)
    val viewport = lastViewport
    if (viewport == null) {
      move(camera.copy(center = center), userGesture = false)
      return
    }
    val margin = AREA_MARGIN.value * viewport.density * 2
    val width = viewport.width * AREA_FILL - margin
    val height = (viewport.height - BottomSheet.coveredPx(viewport.height)) * AREA_FILL - margin
    if (width <= 0f || height <= 0f) {
      move(camera.copy(center = center), userGesture = false)
      return
    }
    val byX = if (maxX > minX) width / (maxX - minX) else Double.MAX_VALUE
    val byY = if (maxY > minY) height / (maxY - minY) else Double.MAX_VALUE
    val worldPx = minOf(byX, byY)
    val zoom = if (worldPx == Double.MAX_VALUE) camera.zoom
    else log2(worldPx / (engine.projection.tileSizeDp * viewport.density)).toFloat()
    move(camera.copy(center = center, zoom = zoom.coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)), userGesture = false)
  }

  // GPS button: back to following the location.
  /**
  * Restores automatic following from manual mode and moves to the last accepted fix if present.
  * @return Unit; does nothing when already outside manual mode.
  */
  fun followGps() {
    if (positionMode != PositionMode.CUSTOM) return
    setMode(PositionMode.AUTO)
    GpsDataManager.lastFix?.let { move(camera.copy(center = GeoPoint(it.lat, it.lon)), userGesture = false) }
  }

  /**
  * Applies a newly accepted GPS position while automatic following is allowed.
  * @param point Geographic position in latitude/longitude degrees.
  * @return Unit; manual mode and active interaction holds suppress the move.
  */
  fun onLocation(point: GeoPoint) {
    if (positionMode == PositionMode.CUSTOM || userHolds > 0) return
    setMode(PositionMode.AUTO)
    move(camera.copy(center = point), userGesture = false)
  }

  // Auto rotation: the course goes up.
  /**
  * Rotates the camera only in automatic map-direction mode.
  * @param azimuth Direction of the screen top in degrees clockwise from north.
  * @return Unit; identical azimuths are ignored.
  */
  fun rotateTo(azimuth: Float) {
    if (Settings.mapDirection.value != MapDirection.AUTO || camera.azimuth == azimuth) return
    move(camera.copy(azimuth = azimuth), userGesture = false)
  }

  private fun setMode(mode: PositionMode) {
    if (positionMode == mode) return
    positionMode = mode
    GpsService.refresh()
  }

  /**
  * Subscribes to user activity and starts the current map engine.
  * @return Unit; follows activity visibility.
  */
  fun start() {
    started = true
    UserActivity.subscribe(this)
    engine.start()
  }

  /**
  * Unsubscribes, clears interaction state, stops the map engine and saves the camera.
  * @return Unit; call when activity visibility ends.
  */
  fun stop() {
    started = false
    UserActivity.unsubscribe(this)
    userHolds = 0
    heldCenter = null
    gestureViewport = null
    engine.stop()
    save()
  }

  /**
  * Stores camera center, zoom and azimuth in SharedPreferences.
  * @return Unit; cancels a pending delayed camera save.
  */
  fun save() {
    handler.removeCallbacks(saveTask)
    val state = camera
    Settings.prefs.edit {
      putString(KEY_LAT, state.center.lat.toString())
      putString(KEY_LON, state.center.lon.toString())
      putFloat(KEY_ZOOM, state.zoom)
      putFloat(KEY_AZIMUTH, state.azimuth)
    }
  }

  private fun loadCamera(): CameraState? {
    val prefs = Settings.prefs
    val lat = prefs.getString(KEY_LAT, null)?.toDoubleOrNull() ?: return null
    val lon = prefs.getString(KEY_LON, null)?.toDoubleOrNull() ?: return null
    return CameraState(GeoPoint(lat, lon), prefs.getFloat(KEY_ZOOM, 13f), prefs.getFloat(KEY_AZIMUTH, 0f))
  }
}
