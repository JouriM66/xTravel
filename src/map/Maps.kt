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

/** Режим расположения карты. */
enum class PositionMode {
  START, /** Камера из прошлого сеанса, первая позиция GPS сдвигает карту */
  CUSTOM, /** Карту сдвинул пользователь, GPS её не трогает */
  AUTO /** Карта следует за позицией */
}

/** Направление карты. */
enum class MapDirection(@StringRes val label: Int, @StringRes val shortLabel: Int) {
  NORTH(R.string.direction_north, R.string.north_short), /** Север всегда вверху */
  AUTO(R.string.direction_auto, R.string.direction_auto_short), /** Пока видна стрелка позиции, карта поворачивается курсом вверх */
  MANUAL(R.string.direction_manual, R.string.direction_manual_short) /** Поворот только жестом */
}

private val INDICATOR_GREEN = Color(0xFF639922)
private val INDICATOR_BLUE = Color(0xFF378ADD)

/** Состояние кнопки позиции и уведомления GPS. */
enum class PositionIndicator(@StringRes val label: Int, val color: Color) {
  NONE(R.string.follow_none, INACTIVE_COLOR), /** GPS нет, кнопка предлагает его настроить */
  WAITING(R.string.follow_wait, INACTIVE_COLOR), /** GPS есть, но данных нет или они устарели */
  AUTO(R.string.follow_auto, INDICATOR_GREEN), /** Карта следует за позицией */
  MANUAL(R.string.follow_manual, INDICATOR_BLUE); /** Карту сдвинули вручную */

  /** Форма значка ручного режима; ожидание сохраняет форму текущего режима. */
  val manualShape: Boolean get() = this == MANUAL || (this == WAITING && Maps.positionMode == PositionMode.CUSTOM)

  @get:DrawableRes
  val notificationIcon: Int
    get() = when {
      this == NONE -> R.drawable.ic_gps_off
      manualShape -> R.drawable.ic_gps_searching
      else -> R.drawable.ic_gps_fixed
    }

  companion object {
    fun current(): PositionIndicator = when (GpsDataManager.status) {
      GpsStatus.UNAVAILABLE -> NONE
      GpsStatus.WAITING -> WAITING
      GpsStatus.OK -> if (Maps.positionMode == PositionMode.CUSTOM) MANUAL else AUTO
    }
  }
}

/** Камера приложения, одна для всех движков, выбор движка и слежение за позицией. Все вызовы - в главном потоке. */
object Maps : IUserListener {

  private const val SAVE_DELAY_MS = 2000L
  private const val AREA_FILL = 0.85f /** Доля видимой карты под показываемую область, чтобы её края не касались краёв */
  private val AREA_MARGIN = 40.dp /** Запас по краям области: объекты рисуются над своим местом (булавка стоит на нём) */
  private const val KEY_LAT = "camera_lat"
  private const val KEY_LON = "camera_lon"
  private const val KEY_ZOOM = "camera_zoom"
  private const val KEY_AZIMUTH = "camera_azimuth"
  private val DEFAULT_CAMERA = CameraState(GeoPoint(55.751244, 37.618423), 13f, 0f)

  val type: MapType get() = Settings.mapType.value

  /** Движок на экране; до создания выбранного - белая подложка. */
  var engine: IMapEngine by mutableStateOf(BlankMapEngine())
    private set

  var camera by mutableStateOf(DEFAULT_CAMERA)
    private set

  /** Пересчёт координат последнего нарисованного кадра (публикует MapArea), null - кадра ещё не было; нужен showArea. */
  var lastViewport: MapViewport? = null

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

  /** Восстанавливает камеру, создаёт движок выбранного поставщика и подписывается на настройки карты. После Settings.init. */
  fun init() {
    val restored = loadCamera() ?: DEFAULT_CAMERA
    camera = if (northUp) restored.copy(azimuth = 0f) else restored
    replace(type)
    Settings.mapDirection.onChange { if (northUp) move(camera.copy(azimuth = 0f), userGesture = false) }
    Settings.map3d.onChange { engine.settingsChanged() }
    Settings.vectorMapFile.onChange { engine.settingsChanged() }
    Settings.vectorMapStyle.onChange { engine.settingsChanged() }
  }

  /** Выбор вручную: сбои карты считаются заново. Недоступный поставщик заменяется встроенной картой с сообщением. */
  fun select(newType: MapType) {
    if (newType == type) return
    Failures.reset(FailureSource.MAP)
    replace(newType)
  }

  /** Сбойный поставщик заменяется встроенной картой, настройка тоже; прежний возвращается только выбором вручную. */
  fun disableEngine() {
    if (type != MapType.NONE) replace(MapType.NONE)
  }

  // Новый движок ставится до остановки старого: сбой при остановке сюда уже не вернётся.
  private fun replace(newType: MapType) {
    val created = runCatching { newType.createEngine() }.getOrNull()
    if (created == null) {
      AppSession.message(newType.unavailableMessage())
      if (newType != MapType.NONE) replace(MapType.NONE)
      return
    }
    val old = engine
    Settings.mapType.value = newType
    engine = created.also {
      it.setCamera(camera)
      if (started) it.start()
    }
    if (started) old.stop()
    old.release()
  }

  /** userGesture - сразу перейти в ручной режим. */
  fun move(state: CameraState, userGesture: Boolean) {
    val applied = if (northUp) state.copy(azimuth = 0f) else state
    camera = applied
    engine.setCamera(applied)
    if (userGesture) setMode(PositionMode.CUSTOM)
    handler.removeCallbacks(saveTask)
    handler.postDelayed(saveTask, SAVE_DELAY_MS)
  }

  /** Сдвиг жестом вместо move: запоминает жест, чтобы по отпусканию решить, включать ли ручной режим.
      panGesture - сдвиг одним пальцем; только он может выключить слежение за GPS.
  */
  fun moveByGesture(state: CameraState, viewport: MapViewport, panGesture: Boolean) {
    if (userHolds > 0) {
      gestureViewport = viewport
      if (!panGesture) manualPositionAllowed = false
    }
    move(state, userGesture = false)
  }

  override fun touch() {}

  /** Пока пользователь держит экран, слежение за GPS приостановлено; запоминается начальный центр. Вложенные - со счётчиком. */
  override fun hold() {
    if (userHolds++ > 0) return
    heldCenter = camera.center
    gestureViewport = null
    manualPositionAllowed = true
  }

  /** Конец удержания: ручной режим включается, только если карту заметно сдвинули одним пальцем. */
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

  /** Место, выбранное пользователем: карта перестаёт следовать за GPS. */
  fun centerOn(point: GeoPoint) {
    setMode(PositionMode.CUSTOM)
    move(camera.copy(center = point), userGesture = false)
  }

  /** Центр и масштаб, при которых все места видны в части карты, не закрытой шторкой.
      Без нарисованного кадра ставится только центр.
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

  /** Кнопка GPS: из ручного режима обратно к слежению, сразу к последней позиции. */
  fun followGps() {
    if (positionMode != PositionMode.CUSTOM) return
    setMode(PositionMode.AUTO)
    GpsDataManager.lastFix?.let { move(camera.copy(center = GeoPoint(it.lat, it.lon)), userGesture = false) }
  }

  /** Новая позиция GPS; в ручном режиме и пока экран удерживают - не двигает карту. */
  fun onLocation(point: GeoPoint) {
    if (positionMode == PositionMode.CUSTOM || userHolds > 0) return
    setMode(PositionMode.AUTO)
    move(camera.copy(center = point), userGesture = false)
  }

  /** Автоповорот курсом вверх; действует только в режиме MapDirection.AUTO. */
  fun rotateTo(azimuth: Float) {
    if (Settings.mapDirection.value != MapDirection.AUTO || camera.azimuth == azimuth) return
    move(camera.copy(azimuth = azimuth), userGesture = false)
  }

  private fun setMode(mode: PositionMode) {
    if (positionMode == mode) return
    positionMode = mode
    GpsService.refresh()
  }

  /** Активити видна. */
  fun start() {
    started = true
    UserActivity.subscribe(this)
    engine.start()
  }

  /** Активити скрыта: удержания сбрасываются, камера сохраняется. */
  fun stop() {
    started = false
    UserActivity.unsubscribe(this)
    userHolds = 0
    heldCenter = null
    gestureViewport = null
    engine.stop()
    save()
  }

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
