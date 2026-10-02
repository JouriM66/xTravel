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

/** Яндекс MapKit как пассивная подложка: свои жесты выключены, камера - от приложения.
    Каждый вызов MapKit под Failures.guard(MAP): сбой переключает на встроенную карту. Главный поток.
    Ключ MapKit берётся один раз за процесс: перед созданием MapView нужен ensureReady.
*/
class YandexMapEngine : IMapEngine, ILicenseInfo {

  override fun licenseInfo(): Array<LicenseInfo> = Companion.licenseInfo()

  companion object : ILicenseInfo {
    /** Сведения о MapKit, только когда введён ключ. */
    override fun licenseInfo(): Array<LicenseInfo> = if (Settings.mapkitKey.value.isBlank()) emptyArray() else arrayOf(
      LicenseInfo("Yandex MapKit", AppSession.context.getString(R.string.license_yandex_mapkit, BuildConfig.YANDEX_MAPKIT_VERSION))
    )

    private const val PROBE_WORLD = 1e-4 /** Шаг по меридиану для замера масштаба MapKit: мал, чтобы остаться у центра, и измерим */
    private const val LOGO_WIDTH_DP = 110 /** Примерная область логотипа в правом нижнем углу */
    private const val LOGO_HEIGHT_DP = 40

    var ready = false /** MapKit получил ключ и язык; без этого движок не создаётся */
      private set

    var restartNeeded = false /** Ключ сменили после того, как MapKit взял прежний: новый заработает в новом процессе */
      private set
    private var keyTaken = false

    /** Язык подписей карты - язык телефона в виде "lang_COUNTRY", как требует MapKit. */
    val locale: String by lazy {
      val system = Languages.systemLocale()
      "${system.language}_${system.country.ifEmpty { system.language.uppercase() }}"
    }

    /** Ключ, язык и инициализация при первой надобности; false - нет ключа или инициализация не удалась. */
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

    /** Ключ в настройках изменён: если MapKit уже взял прежний, новый заработает после перезапуска. */
    fun keyChanged() {
      if (!keyTaken) return
      restartNeeded = true
      AppSession.message(R.string.key_restart)
    }
  }

  private var camera = CameraState(GeoPoint(0.0, 0.0), 1f, 0f)
  private var mapView: MapView? = null
  private var active = false
  private var running = false

  override fun setCamera(state: CameraState) {
    camera = state
    guard { mapView?.mapWindow?.map?.move(state.toYandex()) }
  }

  /** У MapKit спрашивается, где он нарисовал центр и куда ушёл известный шаг мира: окно и масштаб у него свои. */
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

  /** Режим 2D/3D. */
  override fun settingsChanged() {
    guard { mapView?.mapWindow?.map?.let(::applyMode) }
  }

  private fun guard(block: () -> Unit) {
    Failures.guard(FailureSource.MAP, block)
  }

  /** Приложение Яндекс Карт, если установлено, иначе сайт; передаются центр, масштаб и отмеченная точка. */
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

  /** Условия Яндекса требуют, чтобы логотип вёл в Яндекс Карты; касания забирает слой жестов над картой, поэтому проверка здесь. */
  override fun handleTap(screen: Offset, viewport: MapViewport): Boolean {
    val inLogo = screen.x > viewport.width - LOGO_WIDTH_DP * viewport.density && screen.y > viewport.height - LOGO_HEIGHT_DP * viewport.density
    if (inLogo) openExternal(MapOpenRequest(camera.center, camera.zoom, camera.azimuth))
    return inLogo
  }

  /** Режим 2D выключает 3D-здания и автоматический наклон на крупном масштабе. */
  private fun applyMode(map: com.yandex.mapkit.map.Map) {
    val use3d = Settings.map3d.value
    map.set2DMode(!use3d)
    map.isAwesomeModelsEnabled = use3d
  }

  override fun start() {
    active = true
    updateRunning()
  }

  override fun stop() {
    active = false
    updateRunning()
  }

  override fun release() {
    mapView?.let(::detach)
  }

  /** Если MapView не создался, подложка остаётся белой. */
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

  // MapKit и вид запущены, пока движок активен и вид прикреплён.
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
