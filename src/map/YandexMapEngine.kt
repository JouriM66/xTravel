// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.mapview.MapView
import kotlin.math.hypot
import kotlin.math.roundToInt

// Yandex MapKit as a passive background: own gestures are off, the camera comes from the application.
// Every MapKit call is guarded: a failure switches the map off.
/**
* Adapts Yandex MapKit as a passive map background with calibrated overlay coordinates.
*
* Usage: MapKit operations run on the main thread. A consumed API key cannot be replaced without process restart; call ensureReady before creating MapView.
*
* Public and subclass/module-facing members:
* - [licenseInfo] - Supplies localized provider attribution only when its API key is configured.
* - [Companion] - Shared factory, state and lifecycle operations for YandexMapEngine.
* - [setCamera] - Stores the camera and moves the attached MapKit view.
* - [calibration] - Measures actual camera-center projection and map scale through MapKit worldToScreen.
* - [settingsChanged] - Applies 2D/3D settings to the attached map.
* - [openExternal] - Launches Yandex Maps for the request, falling back to its website.
* - [requestRoute] - Asks the Yandex routers for the ways from one coordinate to another.
* - [handleTap] - Checks the Yandex logo hit area and opens the provider when touched.
* - [start] - Marks the engine active and starts its attached view and MapKit as needed.
* - [stop] - Marks the engine inactive and stops its attached view and MapKit as needed.
* - [release] - Detaches the current MapView and stops it when necessary.
* - [Backdrop] - Creates, attaches and displays a MapView, or a blank background if creation fails.
*/
class YandexMapEngine : IMapEngine, ILicenseInfo {

  /**
  * Supplies localized provider attribution only when its API key is configured.
  * @return Current license entries, or an empty array without a key.
  */
  override fun licenseInfo(): Array<LicenseInfo> = Companion.licenseInfo()

  /**
  * Shared factory, state and lifecycle operations for YandexMapEngine.
  *
  * Public and subclass/module-facing members:
  * - [licenseInfo] - Supplies localized provider attribution only when its API key is configured.
  * - [ready] - Whether MapKit initialization succeeded in this process.
  * - [restartNeeded] - Whether an already-consumed API key changed and requires process restart.
  * - [locale] - MapKit locale built from the device language and country.
  * - [ensureReady] - Sets the configured API key and locale and initializes MapKit once in the process.
  * - [keyChanged] - Marks a consumed key as requiring restart or initializes the first key for the selected provider.
  */
  companion object : ILicenseInfo {
    /**
    * Supplies localized provider attribution only when its API key is configured.
    * @return Current license entries, or an empty array without a key.
    */
    override fun licenseInfo(): Array<LicenseInfo> = if (Settings.mapkitKey.value.isBlank()) emptyArray() else arrayOf(
      LicenseInfo("Yandex MapKit", AppSession.context.getString(R.string.license_yandex_mapkit, BuildConfig.YANDEX_MAPKIT_VERSION))
    )

    // Step along the meridian the scale of MapKit is measured with: small enough to stay near the center, large enough to measure.
    private const val PROBE_WORLD = 1e-4

    // Logo area in the bottom right corner of the map, approximate.
    private const val LOGO_WIDTH_DP = 110
    private const val LOGO_HEIGHT_DP = 40

    // MapKit got its key and language; without them the engine is not created.
    /**
    * Whether MapKit initialization succeeded in this process.
    * @return Whether MapKit initialization succeeded in this process.
    */
    var ready = false
      private set

    // MapKit takes a key once per process: a changed key works only in a new process.
    /**
    * Whether an already-consumed API key changed and requires process restart.
    * @return Whether an already-consumed API key changed and requires process restart.
    */
    var restartNeeded = false
      private set
    private var keyTaken = false

    // Language of the map labels: the phone one, as "lang_COUNTRY" that MapKit needs.
    /**
    * MapKit locale built from the device language and country.
    * @return MapKit locale built from the device language and country.
    */
    val locale: String by lazy {
      val system = Languages.systemLocale()
      "${system.language}_${system.country.ifEmpty { system.language.uppercase() }}"
    }

    // Key, language and initialization at the first need; the first key entered works at once.
    /**
    * Sets the configured API key and locale and initializes MapKit once in the process.
    *
    * Usage: Call on the main thread. After a failed key-consuming attempt, a fresh process may be required.
    * @return True when MapKit is ready; false for missing key or initialization failure.
    */
    fun ensureReady(): Boolean {
      if (ready) return true
      if (keyTaken) return false
      val key = Settings.mapkitKey.value.trim()
      if (key.isEmpty()) return false
      keyTaken = true
      ready = Failures.guard(FailureSource.MAP) {
        MapKitFactory.setApiKey(key)
        MapKitFactory.setLocale(locale)
        MapKitFactory.initialize(AppSession.app)
      } != null
      return ready
    }

    // The key in the settings changed: the first one starts the map, a changed one waits for a new process.
    /**
    * Marks a consumed key as requiring restart or initializes the first key for the selected provider.
    * @return Unit; may show a restart message.
    */
    fun keyChanged() {
      if (keyTaken) {
        restartNeeded = true
        AppSession.message(R.string.key_restart)
      } else if (Maps.type == MapType.YANDEX) {
        Maps.select(MapType.YANDEX)
      }
    }
  }

  private var camera = CameraState(GeoPoint(0.0, 0.0), 1f, 0f)
  private var mapView: MapView? = null
  private var active = false
  private var running = false

  /**
  * Stores the camera and moves the attached MapKit view.
  * @param state New immutable camera snapshot.
  * @return Unit; guarded map operations report failures.
  */
  override fun setCamera(state: CameraState) {
    camera = state
    guard { mapView?.mapWindow?.map?.move(state.toYandex()) }
  }

  // MapKit is asked where it put the center and how far a known step of the world went: its window and its scale are its own.
  /**
  * Measures actual camera-center projection and map scale through MapKit worldToScreen.
  * @param camera Camera snapshot used for this projection or background measurement.
  * @return Measured calibration, or null when no usable view/projection exists.
  */
  override fun calibration(camera: CameraState): MapCalibration? = runCatching {
    val window = mapView?.mapWindow ?: return null
    val center = MercatorProjection.toWorld(camera.center)
    val probe = MercatorProjection.toGeo(WorldPoint(center.x, (center.y + PROBE_WORLD).coerceIn(0.0, 1.0)))
    val screenCenter = window.worldToScreen(Point(camera.center.lat, camera.center.lon)) ?: return null
    val screenProbe = window.worldToScreen(Point(probe.lat, probe.lon)) ?: return null
    val step = hypot((screenProbe.x - screenCenter.x).toDouble(), (screenProbe.y - screenCenter.y).toDouble())
    if (step <= 0.0) return null
    MapCalibration(Offset(screenCenter.x, screenCenter.y), step / PROBE_WORLD)
  }.getOrNull()

  /**
  * Applies 2D/3D settings to the attached map.
  * @return Unit; does nothing without an attached view.
  */
  override fun settingsChanged() {
    guard { mapView?.mapWindow?.map?.let(::applyMode) }
  }

  private fun guard(block: () -> Unit) {
    Failures.guard(FailureSource.MAP, block)
  }

  /**
  * Asks the Yandex routers for the ways from one coordinate to another.
  * @param from Coordinate the way starts at.
  * @param to Coordinate the way ends at.
  * @param transport Way of travelling to request.
  * @param listener Receiver of the answer or of the failure reason.
  * @return True when the request was submitted, false when MapKit is not ready or the request failed.
  */
  override fun requestRoute(from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener): Boolean =
    YandexRoutes.request(from, to, transport, listener)

  // The Yandex Maps application when it is installed, otherwise the site. Center, zoom and the marked point are passed.
  /**
  * Launches Yandex Maps for the request, falling back to its website.
  * @param request Camera and optional target information for the external map.
  * @return Unit; reports an unavailable browser when both launches fail.
  */
  override fun openExternal(request: MapOpenRequest) {
    val query = buildString {
      append("ll=${request.center.lon},${request.center.lat}")
      request.zoom?.let { append("&z=${it.roundToInt()}") }
      request.point?.let { append("&pt=${it.lon},${it.lat}") }
    }
    val context = AppSession.context
    val newTask = if (context === AppSession.app) Intent.FLAG_ACTIVITY_NEW_TASK else 0
    try {
      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?$query")).addFlags(newTask))
    } catch (_: ActivityNotFoundException) {
      try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yandex.ru/maps/?$query")).addFlags(newTask))
      } catch (_: ActivityNotFoundException) {
        Notify.error(R.string.browser_unavailable)
      }
    }
  }

  // The Yandex terms require the logo to lead to Yandex Maps; the gesture layer above the map takes its taps.
  /**
  * Checks the Yandex logo hit area and opens the provider when touched.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return True for a logo tap, false otherwise.
  */
  override fun handleTap(screen: Offset, viewport: MapViewport): Boolean {
    val inLogo = screen.x > viewport.width - LOGO_WIDTH_DP * viewport.density && screen.y > viewport.height - LOGO_HEIGHT_DP * viewport.density
    if (inLogo) openExternal(MapOpenRequest(camera.center, camera.zoom, camera.azimuth))
    return inLogo
  }

  // 2D mode switches off the 3D buildings and the automatic tilt at large zoom.
  private fun applyMode(map: com.yandex.mapkit.map.Map) {
    val use3d = Settings.map3d.value
    map.set2DMode(!use3d)
    map.isAwesomeModelsEnabled = use3d
  }

  /**
  * Marks the engine active and starts its attached view and MapKit as needed.
  * @return Unit; call on the main thread.
  */
  override fun start() {
    active = true
    updateRunning()
  }

  /**
  * Marks the engine inactive and stops its attached view and MapKit as needed.
  * @return Unit; call on the main thread.
  */
  override fun stop() {
    active = false
    updateRunning()
  }

  /**
  * Detaches the current MapView and stops it when necessary.
  * @return Unit; use when replacing the engine.
  */
  override fun release() {
    mapView?.let(::detach)
  }

  // Without a view (it failed to be created) the background stays white.
  /**
  * Creates, attaches and displays a MapView, or a blank background if creation fails.
  * @param modifier Compose layout and drawing modifier applied to the emitted host.
  * @return Unit; view attachment follows composition lifetime.
  */
  @Composable
  override fun Backdrop(modifier: Modifier) {
    val context = LocalContext.current
    val view = remember { Failures.guard(FailureSource.MAP) { MapView(context) } }
    if (view == null) {
      Box(modifier.background(Color.White))
      return
    }
    DisposableEffect(view) {
      attach(view)
      onDispose { detach(view) }
    }
    AndroidView(factory = { view }, modifier = modifier)
  }

  private fun attach(view: MapView) = guard {
    mapView = view
    val map = view.mapWindow.map
    map.isScrollGesturesEnabled = false
    map.isZoomGesturesEnabled = false
    map.isRotateGesturesEnabled = false
    map.isTiltGesturesEnabled = false
    applyMode(map)
    map.move(camera.toYandex())
    updateRunning()
  }

  private fun detach(view: MapView) {
    if (mapView !== view) return
    mapView = null
    if (running) {
      running = false
      guard {
        view.onStop()
        MapKitFactory.getInstance().onStop()
      }
    }
  }

  private fun updateRunning() {
    val view = mapView ?: return
    if (active == running) return
    running = active
    guard {
      if (running) {
        MapKitFactory.getInstance().onStart()
        view.onStart()
      } else {
        view.onStop()
        MapKitFactory.getInstance().onStop()
      }
    }
  }

  private fun CameraState.toYandex() = CameraPosition(Point(center.lat, center.lon), zoom, azimuth, 0f)
}
