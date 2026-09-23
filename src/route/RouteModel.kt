// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/** Маршрут - упорядоченный список точек, каждая не более одного раза. Остановка хранит геометрии участка, ведущего к ней:
    у первой - от текущей позиции, у остальных - от предыдущей остановки.
    Остановка ссылается только на точку: место, имя и описание всегда берутся из точки.
*/

/**
* Names the ways of travelling a route can be requested for.
*
* Public and subclass/module-facing members:
* - [label] - String resource naming this way of travelling.
* - [icon] - Icon shown for this way of travelling.
*/
enum class TransportKind(@StringRes val label: Int, val icon: ImageVector) {
  /** On foot. */
  WALK(R.string.transport_walk, Icons.AutoMirrored.Outlined.DirectionsWalk),
  /** By bicycle. */
  BICYCLE(R.string.transport_bicycle, Icons.AutoMirrored.Outlined.DirectionsBike),
  /** By public transport. */
  TRANSIT(R.string.transport_transit, Icons.Outlined.DirectionsBus),
  /** By car. */
  CAR(R.string.transport_car, Icons.Outlined.DirectionsCar),
  /** By truck. */
  TRUCK(R.string.transport_truck, Icons.Outlined.LocalShipping)
}

// Of the answers found for one leg exactly one is the active way; the others stay as they came and wait to be chosen.
/**
* Names how a route geometry is drawn on the map.
*/
enum class RouteGeometryState {
  /** The way chosen for its leg. */
  ACTIVE,
  /** A way of the same leg that was not chosen. */
  INACTIVE
}

/** Остановка маршрута: ссылка на точку и геометрии участка, ведущего к ней.
    id - ключ строки списка, живёт только в памяти.
*/
class RouteEntry(val id: Long, val pointId: Long, place: GeoPoint) {

  /** Последнее известное место точки: нужно, пока удалённая точка ещё не убрана из маршрута */
  private var known = place

  /** Место точки, после её удаления - последнее известное */
  val place: GeoPoint get() = point()?.geo?.also { known = it } ?: known

  val name: String get() = point()?.captionOnly().orEmpty()

  /** Первая строка второго текста точки: первый текст - её имя */
  val description: String get() {
    val texts = point()?.elements?.filterIsInstance<PointElement.Text>() ?: return ""
    return texts.getOrNull(1)?.text?.lineSequence()?.firstOrNull()?.trim().orEmpty()
  }

  /**
  * Observable geometries leading to this stop.
  * @return Observable geometries leading to this stop.
  */
  val geometries = mutableStateListOf<RouteGeometry>()

  // Runtime only, never saved: the row of this stop is marked until another stop is marked.
  /**
  * Runtime-only list highlight of the row of this stop.
  * @return Runtime-only list highlight of the row of this stop.
  */
  var highlighted by mutableStateOf(false)

  /** Имя точки, без него - координаты */
  fun caption(): String = name.ifEmpty { formatCoordinates(place) }

  fun point(): MapPoint? = PointStore.find(pointId)

  /**
  * The active geometry of this stop, or null.
  * @return The active geometry, or null when the stop has none.
  */
  fun chosen(): RouteGeometry? = geometries.firstOrNull { it.state == RouteGeometryState.ACTIVE }

  // The straight line made for a leg without an answer is not a variant of it: it is neither saved nor counted.
  /**
  * Geometries a provider answered for this stop, without the virtual one.
  * @return The answered geometries of the leg leading to this stop.
  */
  fun ways(): List<RouteGeometry> = geometries.filter { !it.virtual }
}

/**
* Holds the identity, name and ordered stops of one route.
*
* Public and subclass/module-facing members:
* - [id] - Stable route identifier.
* - [name] - Observable route name.
* - [entries] - Observable ordered stops of the route.
*/
class Route(val id: Long, name: String) {

  /**
  * Observable route name.
  * @return Observable route name.
  */
  var name by mutableStateOf(name)

  /**
  * Observable ordered stops of the route.
  * @return Observable ordered stops of the route.
  */
  val entries = mutableStateListOf<RouteEntry>()
}
