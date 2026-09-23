// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.runtime.Composable
import java.util.Locale

/** Провайдеры "поделиться" ссылками в приложения карт: принимают точки или маршрут и ничего, кроме координат и имени,
    не передают. Места набора - остановки первого маршрута, без маршрутов - точки.
*/

/** Пакеты приложений, чьи иконки берут провайдеры; они же перечислены в queries манифеста. FOR LOCAL USE */
private const val YANDEX_MAPS_PACKAGE = "ru.yandex.yandexmaps"
private const val DOUBLE_GIS_PACKAGE = "ru.dublgis.dgismobile"

private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.6f", value) /** Координата для ссылки. FOR LOCAL USE */

/** Ссылка на место в Яндекс Картах: метка и крупный масштаб */
fun yandexMapsLink(place: GeoPoint) = "https://yandex.ru/maps/?pt=${coordinate(place.lon)},${coordinate(place.lat)}&z=17&l=map"

/** Места набора по порядку. FOR LOCAL USE */
private fun places(set: DataSet): List<MapPoint> = set.routes.firstOrNull()?.places ?: set.points

/** Открытие ссылки в приложении, которое её перехватывает, иначе в браузере. FOR LOCAL USE */
private fun openLink(uri: String) {
  val context = AppSession.context
  val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
  if (context === AppSession.app) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  try {
    context.startActivity(intent)
  } catch (_: ActivityNotFoundException) {
    Notify.error(R.string.browser_unavailable)
  }
}

/** Общее у провайдеров ссылок: типы, проверка и счёт мест. */
internal abstract class MapLinkShare : IGeoDataShare {
  override val supportedTypes = setOf(GeoDataType.POINTS, GeoDataType.ROUTES)
  override fun checkData(set: DataSet) = places(set).isNotEmpty()
  override fun count(set: DataSet) = places(set).size

  /** Одно место открывается точкой, несколько - маршрутом */
  override fun share(set: DataSet) {
    val places = places(set).let { if (limit > 0) it.take(limit) else it }
    if (places.size == 1) openPoint(places[0]) else openRoute(places)
  }

  abstract fun openPoint(point: MapPoint)

  abstract fun openRoute(points: List<MapPoint>)
}

/** Сайт Яндекс Карт: одна точка меткой, несколько - маршрутом через точки по порядку. Число мест ограничено только длиной ссылки. */
internal object YandexMapsSiteShare : MapLinkShare() {
  override val label get() = R.string.share_yandex_site

  @Composable
  override fun icon() = rememberAppIcon(YANDEX_MAPS_PACKAGE, Icons.Outlined.Map)

  override fun openPoint(point: MapPoint) = openLink(yandexMapsLink(point.geo))

  override fun openRoute(points: List<MapPoint>) {
    val stops = points.joinToString("~") { "${coordinate(it.lat)},${coordinate(it.lon)}" }
    openLink("https://yandex.ru/maps/?rtext=$stops&rtt=${transport(Settings.routeTransport.value)}")
  }

  private fun transport(kind: TransportKind) = when (kind) {
    TransportKind.WALK -> "pd"
    TransportKind.BICYCLE -> "bc"
    TransportKind.TRANSIT -> "mt"
    TransportKind.CAR, TransportKind.TRUCK -> "auto"
  }
}

/** 2ГИС: ссылки открывает приложение, без него сайт. Маршрут на Android - до 10 промежуточных точек. */
internal object DoubleGisShare : MapLinkShare() {
  override val label get() = R.string.share_2gis
  override val limit get() = 12

  @Composable
  override fun icon() = rememberAppIcon(DOUBLE_GIS_PACKAGE, Icons.Outlined.Map)

  override fun openPoint(point: MapPoint) = openLink("https://2gis.ru/geo/${coordinate(point.lon)},${coordinate(point.lat)}")

  override fun openRoute(points: List<MapPoint>) {
    val stops = points.joinToString("|") { "${coordinate(it.lon)},${coordinate(it.lat)}" }
    openLink("https://2gis.ru/directions/tab/${transport(Settings.routeTransport.value)}/points/$stops")
  }

  private fun transport(kind: TransportKind) = when (kind) {
    TransportKind.WALK -> "pedestrian"
    TransportKind.BICYCLE -> "bicycle"
    TransportKind.TRANSIT -> "bus"
    TransportKind.CAR -> "car"
    TransportKind.TRUCK -> "truck"
  }
}

/** Любое приложение карт через схему geo: с системным выбором, только одна точка. Регистрируется последним из провайдеров.
    Видимость приложений geo на Android 11+ даёт запись в queries манифеста.
*/
object GeoAppShare : IGeoDataShare {
  override val label get() = R.string.share_geo_app
  override val supportedTypes = setOf(GeoDataType.POINTS)
  override val limit get() = 1

  override fun checkData(set: DataSet) = set.points.isNotEmpty() && appAvailable()

  override fun count(set: DataSet) = set.points.size

  override fun share(set: DataSet) {
    val point = set.points.firstOrNull() ?: return
    val lat = coordinate(point.lat)
    val lon = coordinate(point.lon)
    val name = point.captionOnly()?.let { "(${Uri.encode(it)})" }.orEmpty()
    openLink("geo:$lat,$lon?q=$lat,$lon$name")
  }

  private fun appAvailable() = AppSession.app.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")), 0).isNotEmpty()
}
