// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

/** Географическая точка в градусах: основной тип места во всём приложении. */
data class GeoPoint(val lat: Double, val lon: Double)

/** Камера: центр, масштаб и направление верха экрана в градусах по часовой от севера, как в MapKit. */
data class CameraState(val center: GeoPoint, val zoom: Float, val azimuth: Float)

/** Что подложка знает о месте; пока только координаты. */
class MapPointInfo(val point: GeoPoint)

/** Всё, что приложение может передать другой карте о месте; движок отдаёт то, что понимает его поставщик.
    zoom null - масштаб поставщика по умолчанию; point - отмеченное место, отличное от центра; text - его подпись.
*/
class MapOpenRequest(
  val center: GeoPoint,
  val zoom: Float? = null,
  val azimuth: Float? = null,
  val point: GeoPoint? = null,
  val text: String? = null
)

/** Проекция и масштаб движка: мировые координаты 0..1, ширина мира в пикселях = tileSizeDp * 2^zoom * density. */
interface IMapProjection {
  val tileSizeDp: Double /** Ширина тайла в dp на нулевом масштабе */
  fun toWorld(lat: Double, lon: Double): WorldPoint
  fun toWorld(point: GeoPoint): WorldPoint = toWorld(point.lat, point.lon)
  fun toGeo(world: WorldPoint): GeoPoint

  /** Метры в одной единице мировых координат на широте lat. */
  fun metersPerWorldUnit(lat: Double): Double
}

/** Где подложка на самом деле нарисовала центр камеры и сколько пикселей занимает единица мира.
    Размер вида и масштаб подложки могут не совпасть с расчётом приложения, а свои объекты должны лежать на карте, а не рядом.
*/
class MapCalibration(val center: Offset, val worldPx: Double)

/** Пассивная подложка: рисует карту для заданной камеры. Жесты и свои объекты приложение рисует поверх.
    Все методы - в главном потоке.
*/
interface IMapEngine {

  val projection: IMapProjection get() = MercatorProjection

  /** Спрашивается при каждом рисовании; null - приложение считает экранные позиции само. */
  fun calibration(camera: CameraState): MapCalibration? = null

  fun setCamera(state: CameraState)

  fun getInfoForPoint(point: GeoPoint): MapPointInfo = MapPointInfo(point)

  /** Изменилась настройка карты (например, 3D или оформление). */
  fun settingsChanged() {}

  /** Показ места в приложении или на сайте поставщика карты. */
  fun openExternal(request: MapOpenRequest) {}

  /** Касание, которое движок обрабатывает сам (например, по логотипу); true - обработано. */
  fun handleTap(screen: Offset, viewport: MapViewport): Boolean = false

  /** Активити видна. */
  fun start() {}

  /** Активити скрыта. */
  fun stop() {}

  /** Движок больше не нужен: освободить вид и ресурсы поставщика. */
  fun release() {}

  @Composable
  fun Backdrop(modifier: Modifier)
}

/** Поставщик подложки. Недоступный поставщик (нет ключа, сбой) заменяется встроенной картой - это делает Maps. */
enum class MapType(@StringRes val label: Int) {

  /** Встроенная векторная карта без сети (map\vector), работает всегда. */
  NONE(R.string.map_none) {
    override fun createEngine(): IMapEngine = VectorMapEngine()

    override val settings: (@Composable ColumnScope.() -> Unit) = { VectorMapSettings.Rows() }
  },

  YANDEX(R.string.map_yandex) {
    override fun createEngine(): IMapEngine? = if (YandexMapEngine.ensureReady()) YandexMapEngine() else null

    override fun unavailableMessage() = if (YandexMapEngine.restartNeeded) R.string.key_restart else R.string.mapkit_key_missing

    override val settings: (@Composable ColumnScope.() -> Unit) = { YandexSettings.MapRows() }
  };

  /** Новый движок; null - поставщик недоступен (например, нет ключа). */
  abstract fun createEngine(): IMapEngine?

  /** Почему createEngine вернул null. */
  @StringRes
  open fun unavailableMessage(): Int = R.string.map_unavailable

  /** Строки полноэкранного редактора настроек поставщика (редакторы из SettingsScreen.kt); null - настроек нет.
      Принадлежат типу, а не движку: настройки нужны и когда движок не создан, например без ключа.
  */
  open val settings: (@Composable ColumnScope.() -> Unit)? get() = null
}
