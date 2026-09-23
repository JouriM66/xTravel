// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Маршруты приложения: остановки и геометрии между ними. Остановка ссылается на точку и своих координат не хранит.

// A route as a file keeps it. The place carries the coordinates and the texts of the stop; the ways found for the leg
// leading to it are kept with it, because asking a provider for them again costs time and money.
/**
* Holds one stop of a route as a file keeps it: its place and the ways leading to it.
* @param place Point record carrying the coordinates and the texts of the stop.
* @param ways Ways found for the leg leading to the stop, the active one first.
* @property place Point record carrying the coordinates and the texts of the stop.
* @property ways Ways found for the leg leading to the stop, the active one first.
*/
class RouteStopRecord(val place: MapPoint, val ways: List<RouteGeometry>)

/**
* Holds a route as a file keeps it: a name and the stops with their ways.
*
* Public and subclass/module-facing members:
* - [places] - Point records of the stops, in travel order.
* @param name Route name.
* @param stops Stops of the route in travel order.
* @property name Route name.
* @property stops Stops of the route in travel order.
*/
class RouteRecord(val name: String, val stops: List<RouteStopRecord>) {
  /**
  * Point records of the stops, in travel order.
  * @return Point records of the stops, in travel order.
  */
  val places: List<MapPoint> get() = stops.map { it.place }
}

private const val ROUTE_NAME_PATTERN = "yyyy-MM-dd HH:mm"

// Part of the screen width the position must move before the stops are ordered again.
private const val SORT_SHIFT = 0.1

// Used while no map frame was drawn yet and the screen says nothing about the scale.
private const val SORT_SHIFT_METERS = 100.0

/** Участок, который нужно запросить: остановка и место начала пути к ней. FOR LOCAL USE */
private class LegJob(val entry: RouteEntry, val from: GeoPoint)

/**
* Owns the routes of the application, the selected route, the running navigation and the requests for route geometries.
*
* Usage: Every member is used on the main thread; requests answer there as well.
*
* Public and subclass/module-facing members:
* - [routes] - Observable routes of the application.
* - [selected] - Route shown and navigated, or null while none exists.
* - [active] - Whether navigation along the selected route is running.
* - [scrollTarget] - Stop the route sheet should scroll to, or null.
* - [requestedAt] - Момент отправки ожидаемых запросов геометрий.
* - [travelSpeed] - Усреднённая скорость навигации.
* - [tag] - XML section identifier of the routes.
* - [ensureSelected] - Returns the selected route, creating one named after the moment it was made.
* - [addPoint] - Appends a stop for a point the route does not hold yet.
* - [addPlace] - Appends a stop for a place, creating the point of that place when it has none.
* - [holds] - Whether the selected route already stops at the point.
* - [focus] - Scrolls the route sheet to a stop and marks its row.
* - [toggleHighlight] - Выделение строки остановки и его снятие.
* - [removeEntry] - Removes a stop from the selected route.
* - [removePoint] - Removes the stop of a point.
* - [removeAt] - Removes the routes standing at the given positions of the list.
* - [beginReorder] - Remembers the order of the stops before a drag of the list.
* - [moveEntry] - Moves a stop inside the route while it is dragged.
* - [endReorder] - Applies the order the drag left, asking about it when the navigation is touched.
* - [legStart] - Where the leg leading to a stop starts.
* - [calculate] - Requests the geometries of every leg, one leg after another.
* - [recalculate] - Requests the geometries of one leg.
* - [abort] - Прерывает ожидание запросов геометрий.
* - [choose] - Makes one geometry of a leg the active one.
* - [chooseOnly] - Makes one geometry of a leg the active one and drops the other answers of that leg.
* - [nextWay] - Makes the next answer of a leg the active one.
* - [clearStops] - Removes every stop of the selected route.
* - [sort] - Orders the stops by shortest next distance from the current position.
* - [start] - Starts navigation once every leg has a geometry.
* - [stop] - Stops navigation, cuts the travelled part of the current leg and draws every way whole again.
* - [onPosition] - Follows the position along the way being travelled, or orders the stops of a route not being travelled.
* - [travelSeconds] - Время пути по усреднённой скорости навигации.
* - [onPointChanged], [onPointsChanged], [onPointsRemoved] - Следят за точками маршрутов.
* - [activeTarget] - The stop being travelled to.
* - [distanceToTarget] - Distance left to the active target in meters.
* - [distanceToEnd] - Distance left to the end of the route in meters.
* - [activeGeometry] - The geometry currently being travelled.
* - [snapshot] - Builds the file record of a route in the main thread.
* - [stopPlace] - Запись места остановки чужого формата с именем по умолчанию.
* - [onLoaded] - Replaces the routes with the loaded ones.
* - [applyImport] - Adds imported routes that the application does not have yet.
* - [write] - Writes the routes as an XML section: a stop as its place and texts, with the ways found for its leg.
* - [read] - Reads the routes of an XML section into the import accumulator.
*/
object RouteStore : IDataOwner, IPointListener {

  /**
  * Observable routes of the application.
  * @return Observable routes of the application.
  */
  val routes = mutableStateListOf<Route>()

  private var current by mutableStateOf<Route?>(null)

  /**
  * Route shown and navigated, or null while none exists.
  * @return Route shown and navigated, or null while none exists.
  */
  var selected: Route?
    get() = current
    set(value) {
      if (current === value) return
      current = value
      ModuleHost.requestRedraw()
    }

  /**
  * Whether navigation along the selected route is running.
  * @return Whether navigation along the selected route is running.
  */
  var active by mutableStateOf(false)
    private set

  /**
  * Stop the route sheet should scroll to, or null.
  * @return Stop the route sheet should scroll to, or null.
  */
  var scrollTarget by mutableStateOf<RouteEntry?>(null)

  /** Момент отправки ожидаемых запросов геометрий по SystemClock.elapsedRealtime; null - ответов не ждём */
  var requestedAt by mutableStateOf<Long?>(null)
    private set

  /** Последняя ненулевая усреднённая скорость навигации, м/с; null - скорость ещё не измерялась */
  var travelSpeed by mutableStateOf<Double?>(null)
    private set

  private var nextId = 1L
  private var nextEntryId = 1L
  private var dragged: List<RouteEntry>? = null
  private var sortedAt: GeoPoint? = null // Position the stops were ordered from.
  private var series = 0 // Номер серии запросов: ответы прерванной или заменённой серии отбрасываются.
  private val speeds = ArrayDeque<Float>() // Окно последних скоростей навигации.

  /**
  * XML section identifier of the routes.
  * @return XML section identifier of the routes.
  */
  override val tag = "routes"

  /**
  * Returns the selected route, creating one named after the moment it was made.
  * @return The selected route; a new route is appended when none exists.
  */
  fun ensureSelected(): Route {
    selected?.let { if (it in routes) return it }
    val created = Route(nextId++, LocalDateTime.now().format(DateTimeFormatter.ofPattern(ROUTE_NAME_PATTERN, Locale.ROOT)))
    routes += created
    selected = created
    DataStore.scheduleSave()
    return created
  }

  // A point stands in a route at most once, so the menu of a point offers either adding it or excluding it.
  /**
  * Appends a stop for a point the route does not hold yet.
  * @param pointId Stable identifier of the point the stop is made of.
  * @return Unit; a point already in the route does nothing.
  */
  fun addPoint(pointId: Long) = PointStore.find(pointId)?.let { addStop(it) } ?: Unit

  // A place picked on the map becomes a stop through the point manager: it gives back the point standing there or creates one.
  /**
  * Appends a stop for a place, creating the point of that place when it has none.
  * @param place Geographic position in latitude/longitude degrees.
  * @return Unit; persistence is scheduled.
  */
  fun addPlace(place: GeoPoint) = addStop(PointStore.findOrCreate(MapPoint(id = 0L, lat = place.lat, lon = place.lon)))

  /** Статус точки маршрут не меняет, по настройке включается только автопосещение. FOR LOCAL USE */
  private fun addStop(point: MapPoint) {
    val route = ensureSelected()
    if (route.entries.any { it.pointId == point.id }) return
    // The new stop goes to the end of the travel, so the legs before it stay as they are.
    change(false) {
      route.entries += RouteEntry(nextEntryId++, point.id, point.geo)
      if (Settings.routeMarkPoints.value && !point.autoVisit) PointStore.update(point.id) { it.copy(autoVisit = true) }
    }
  }

  /**
  * Whether the selected route already stops at the point.
  * @param pointId Stable identifier of the referenced point.
  * @return Whether the selected route already stops at that point.
  */
  fun holds(pointId: Long) = entryOf(pointId) != null

  /**
  * Scrolls the route sheet to a stop and marks its row.
  * @param entry Stop of the selected route to show.
  * @return Unit; the mark lives in memory only.
  */
  fun focus(entry: RouteEntry) {
    selected?.entries?.forEach { it.highlighted = it === entry }
    scrollTarget = entry
    ModuleHost.requestRedraw()
  }

  /** Выделяет строку остановки и снимает выделение с остальных; повторное нажатие снимает его со всех,
      и маршрут снова рисуется целиком. Возвращает, выделена ли строка теперь.
  */
  fun toggleHighlight(entry: RouteEntry): Boolean {
    val on = !entry.highlighted
    selected?.entries?.forEach { it.highlighted = on && it === entry }
    ModuleHost.requestRedraw()
    return on
  }

  /**
  * Removes a stop from the selected route.
  * @param entry Stop of the selected route to remove.
  * @return Unit; an unknown stop does nothing.
  */
  fun removeEntry(entry: RouteEntry) {
    val route = selected ?: return
    val index = route.entries.indexOf(entry)
    if (index < 0) return
    // Taking away the last stop leaves the stops before it untouched; taking away any other one changes the way to the next.
    change(index < route.entries.size - 1) { route.entries.remove(entry) }
  }

  /**
  * Removes the stop of a point.
  * @param pointId Stable identifier of the referenced point.
  * @return Unit; a point the route does not hold does nothing.
  */
  fun removePoint(pointId: Long) = entryOf(pointId)?.let { removeEntry(it) } ?: Unit

  private fun entryOf(pointId: Long): RouteEntry? = selected?.entries?.firstOrNull { it.pointId == pointId }

  /**
  * Removes the routes standing at the given positions of the list.
  * @param indices Positions in the route list to remove.
  * @return Unit; persistence is scheduled when something was removed.
  */
  fun removeAt(indices: Collection<Int>) {
    val gone = indices.mapNotNull { routes.getOrNull(it) }
    if (gone.isEmpty()) return
    routes.removeAll(gone.toSet())
    if (selected !in routes) selected = routes.firstOrNull()
    DataStore.scheduleSave()
    ModuleHost.requestRedraw()
  }

  /**
  * Remembers the order of the stops before a drag of the list.
  * @return Unit; the order is kept until the drag ends.
  */
  fun beginReorder() {
    dragged = selected?.entries?.toList()
  }

  // The drag moves the row by row, so nothing is decided here: the order is judged once, when the drag ends.
  /**
  * Moves a stop inside the route while it is dragged.
  * @param from Existing stop index to take.
  * @param to Destination stop index.
  * @return Unit; equal or out-of-range indices do nothing.
  */
  fun moveEntry(from: Int, to: Int) {
    val route = selected ?: return
    if (from == to || from !in route.entries.indices || to !in route.entries.indices) return
    route.entries.add(to, route.entries.removeAt(from))
  }

  /**
  * Applies the order the drag left, asking about it when the navigation is touched.
  * @return Unit; an order that did not change does nothing.
  */
  fun endReorder() {
    val before = dragged ?: return
    dragged = null
    val route = selected ?: return
    val after = route.entries.toList()
    if (after.size == before.size && after.indices.all { after[it] === before[it] }) return
    // The order is put back first, so refusing the question leaves the route as it was.
    route.entries.clear()
    route.entries.addAll(before)
    change(true) {
      route.entries.clear()
      route.entries.addAll(after)
    }
  }

  /** Все изменения списка остановок идут через эту точку. Изменение, делающее навигацию другим маршрутом,
      согласуется с пользователем, если построение не автоматическое. FOR LOCAL USE
  */
  private fun change(affectsNavigation: Boolean, action: () -> Unit) {
    if (!active || !affectsNavigation || Settings.routeAutoCalculate.value) {
      action()
      return afterChange()
    }
    askRouteChange(onRecalculate = {
      action()
      afterChange(rebuild = true)
    }, onStop = {
      stop()
      action()
      afterChange()
    })
  }

  /** Последствия изменения списка или точек выбранного маршрута: сброс неверных геометрий, автосортировка и достройка.
      При навигации участок без геометрии требует решения: перестроить или идти по направлению. FOR LOCAL USE
  */
  private fun afterChange(rebuild: Boolean = false) {
    val route = selected
    if (route != null) {
      dropChangedLegs(route)
      if (active && route.entries.isEmpty()) stop()
      if (!active && Settings.routeAutoSort.value && order()) dropChangedLegs(route)
      if (missing(route)) {
        when {
          rebuild || Settings.routeAutoCalculate.value -> buildMissing { if (active && missing(route)) askMissing() }
          active -> askMissing()
        }
      }
    }
    sortedAt = null
    DataStore.scheduleSave()
    ModuleHost.requestRedraw()
  }

  private fun missing(route: Route) = route.entries.any { it.geometries.isEmpty() }

  /** Вопрос при навигации об участках без геометрии; отказ от ответа останавливает навигацию. FOR LOCAL USE */
  private fun askMissing() {
    val route = selected ?: return
    if (!active || !missing(route)) return
    askRebuild(onRebuild = { buildMissing { if (active && missing(route)) askMissing() } }, onDirections = {
      fillVirtual()
      ModuleHost.requestRedraw()
    }, onDismiss = ::stop)
  }

  // FOR LOCAL USE: a leg nobody answered for is travelled along the straight line, made here as a way of its own.
  private fun fillVirtual() {
    val route = selected ?: return
    val entries = route.entries.toList()
    entries.forEachIndexed { index, entry ->
      if (entry.geometries.isNotEmpty()) return@forEachIndexed
      val from = startOf(entries, index) ?: return@forEachIndexed
      val place = entry.place
      val meters = GeoMath.distance(from.lat, from.lon, place.lat, place.lon)
      val way = RouteGeometry(from, place, Settings.routeTransport.value, listOf(from, place), meters, 0.0, virtual = true)
      way.state = RouteGeometryState.ACTIVE
      entry.geometries += way
    }
  }

  /** Путь принадлежит паре мест, для которой запрошен, поэтому участок между другими местами его теряет. У первого
      участка начало - позиция в момент запроса, оно позади, поэтому судится только конец. Возвращает, сброшено ли что-то.
  */
  private fun dropChangedLegs(route: Route): Boolean {
    var dropped = false
    route.entries.forEachIndexed { index, entry ->
      val start = route.entries.getOrNull(index - 1)?.place
      val place = entry.place
      val gone = entry.geometries.filter { way ->
        !samePlace(way.to, place) || (start != null && !samePlace(way.from, start))
      }
      if (gone.isEmpty()) return@forEachIndexed
      entry.geometries.removeAll(gone.toSet())
      if (entry.chosen() == null) entry.geometries.firstOrNull()?.state = RouteGeometryState.ACTIVE
      dropped = true
    }
    return dropped
  }

  /** Запрашивает только участки без геометрии; onDone получает false при ошибке. FOR LOCAL USE */
  private fun buildMissing(onDone: ((Boolean) -> Unit)? = null) {
    val route = selected ?: return
    val entries = route.entries.toList()
    val jobs = entries.indices.mapNotNull { index ->
      if (entries[index].geometries.isNotEmpty()) return@mapNotNull null
      startOf(entries, index)?.let { LegJob(entries[index], it) }
    }
    runJobs(route, jobs, onDone)
  }

  /**
  * Requests the geometries of every leg, one leg after another.
  * @return Unit; while an answer is awaited the waiting is shown instead.
  */
  fun calculate() {
    val route = selected ?: return
    if (requestedAt != null) return showRouteWaiting()
    val entries = route.entries.toList()
    runJobs(route, entries.indices.mapNotNull { index -> startOf(entries, index)?.let { LegJob(entries[index], it) } })
  }

  /** Участки запрашиваются по одному, чтобы не заваливать движок и чтобы порядок был виден. FOR LOCAL USE */
  private fun runJobs(route: Route, jobs: List<LegJob>, onDone: ((Boolean) -> Unit)? = null) {
    val number = ++series
    if (jobs.isEmpty()) {
      requestedAt = null
      onDone?.invoke(true)
      return
    }
    requestedAt = SystemClock.elapsedRealtime()
    runJob(route, jobs, 0, number, onDone)
  }

  private fun runJob(route: Route, jobs: List<LegJob>, index: Int, number: Int, onDone: ((Boolean) -> Unit)?) {
    if (number != series) return
    if (selected !== route || index >= jobs.size) {
      requestedAt = null
      onDone?.invoke(selected === route)
      return
    }
    val job = jobs[index]
    request(job.from, job.entry.place, onReady = { geometries ->
      if (number == series) {
        replace(job.entry, geometries)
        DataStore.scheduleSave()
        runJob(route, jobs, index + 1, number, onDone)
      }
    }, onFailed = {
      if (number == series) {
        abort()
        onDone?.invoke(false)
      }
    })
  }

  /** Прерывает ожидание: ответы отправленных запросов, если придут, будут отброшены */
  fun abort() {
    series++
    requestedAt = null
  }

  private fun replace(entry: RouteEntry, geometries: List<RouteGeometry>) {
    entry.geometries.clear()
    entry.geometries.addAll(geometries)
    entry.geometries.forEachIndexed { index, geometry ->
      geometry.state = if (index == 0) RouteGeometryState.ACTIVE else RouteGeometryState.INACTIVE
    }
  }

  // FOR LOCAL USE: where the leg of a stop starts: at the stop before it, or at the current position for the first stop.
  private fun startOf(entries: List<RouteEntry>, index: Int): GeoPoint? =
    if (index > 0) entries[index - 1].place else GpsDataManager.lastFix?.let { GeoPoint(it.lat, it.lon) }

  /**
  * Where the leg leading to a stop starts.
  * @param entry Stop of the selected route.
  * @return The place before the stop, or null when the stop is unknown or no position is known yet.
  */
  fun legStart(entry: RouteEntry): GeoPoint? {
    val entries = selected?.entries ?: return null
    val index = entries.indexOf(entry)
    return if (index < 0) null else startOf(entries, index)
  }

  /**
  * Requests the geometries of one leg.
  * @param entry Stop the leg leads to.
  * @return Unit; a leg without a known start does nothing.
  */
  fun recalculate(entry: RouteEntry) {
    val route = selected ?: return
    if (requestedAt != null) return showRouteWaiting()
    val index = route.entries.indexOf(entry)
    if (index < 0) return
    val from = startOf(route.entries, index) ?: return
    runJobs(route, listOf(LegJob(entry, from)))
  }

  /** Ошибка сообщается здесь же, onFailed только передаёт её вызывающему. FOR LOCAL USE */
  private fun request(from: GeoPoint, to: GeoPoint, onReady: (List<RouteGeometry>) -> Unit, onFailed: () -> Unit) {
    val listener = object : IRouteListener {
      override fun onRouteReady(geometries: List<RouteGeometry>) {
        onReady(geometries)
        ModuleHost.requestRedraw()
      }

      override fun onRouteFailed(message: String) {
        Notify.error(R.string.route_request_failed, message)
        onFailed()
      }
    }
    if (!Maps.engine.requestRoute(from, to, Settings.routeTransport.value, listener)) {
      Notify.error(R.string.route_not_supported)
      onFailed()
    }
  }

  /**
  * Makes one geometry of a leg the active one.
  * @param entry Stop the leg leads to.
  * @param geometry Geometry of that leg to make active.
  * @return Unit; requests a redraw.
  */
  fun choose(entry: RouteEntry, geometry: RouteGeometry) {
    entry.geometries.forEach { it.state = if (it === geometry) RouteGeometryState.ACTIVE else RouteGeometryState.INACTIVE }
    geometry.rewind()
    ModuleHost.requestRedraw()
  }

  // While the navigation runs the leg is travelled along one way only, so choosing another one leaves nothing beside it.
  /**
  * Makes one geometry of a leg the active one and drops the other answers of that leg.
  * @param entry Stop the leg leads to.
  * @param geometry Geometry of that leg to keep.
  * @return Unit; persistence is scheduled.
  */
  fun chooseOnly(entry: RouteEntry, geometry: RouteGeometry) {
    entry.geometries.retainAll { it === geometry }
    choose(entry, geometry)
    DataStore.scheduleSave()
  }

  /**
  * Makes the next answer of a leg the active one.
  * @param entry Stop the leg leads to.
  * @return Unit; a leg with less than two answers does nothing.
  */
  fun nextWay(entry: RouteEntry) {
    val ways = entry.ways()
    if (ways.size < 2) return
    val index = ways.indexOfFirst { it.state == RouteGeometryState.ACTIVE }
    choose(entry, ways[(index + 1) % ways.size])
    DataStore.scheduleSave()
  }

  // The cleaning is asked for by the sheet, which stops the navigation first; nothing is built again after it.
  /**
  * Removes every stop of the selected route.
  * @return Unit; the route itself is kept, empty.
  */
  fun clearStops() {
    val route = selected ?: return
    route.entries.clear()
    DataStore.scheduleSave()
    ModuleHost.requestRedraw()
  }

  /**
  * Orders the stops by shortest next distance from the current position.
  * @return Unit; the ways of the legs that changed are dropped.
  */
  fun sort() = change(true) { order() }

  /** Жадная сортировка по ближайшему следующему месту; без позиции сортировать не от чего.
      Возвращает, изменился ли порядок. FOR LOCAL USE
  */
  private fun order(): Boolean {
    val route = selected ?: return false
    val rest = route.entries.toMutableList()
    if (rest.size < 2) return false
    val ordered = mutableListOf<RouteEntry>()
    var here = GpsDataManager.lastFix?.let { GeoPoint(it.lat, it.lon) } ?: return false
    while (rest.isNotEmpty()) {
      val from = here
      val index = rest.indices.minBy { i -> GeoMath.distance(from.lat, from.lon, rest[i].place.lat, rest[i].place.lon) }
      val taken = rest.removeAt(index)
      ordered += taken
      here = taken.place
    }
    if (ordered.indices.all { route.entries.getOrNull(it) === ordered[it] }) return false
    route.entries.clear()
    route.entries.addAll(ordered)
    return true
  }

  /** Запуск навигации. Без позиции GPS не запускается. Геометрия первого участка, начало которой дальше радиуса
      прибытия от текущей позиции, сбрасывается. Участки без геометрии рассчитываются (при авторасчёте сразу, иначе
      после вопроса) или получают направление; навигация начинается, только когда геометрия есть у всех.
  */
  fun start() {
    val route = selected ?: return
    if (route.entries.isEmpty() || active) return
    if (requestedAt != null) return showRouteWaiting()
    val fix = GpsDataManager.lastFix
    if (fix == null || GpsDataManager.status == GpsStatus.UNAVAILABLE) return AppDialog.message(AppSession.context.getString(R.string.route_no_gps))
    val first = route.entries.first()
    val begin = first.chosen()?.points?.firstOrNull()
    if (begin != null && GeoMath.distance(fix.lat, fix.lon, begin.lat, begin.lon) > Settings.routeArrivalRadius.value) {
      first.geometries.clear()
      DataStore.scheduleSave()
      ModuleHost.requestRedraw()
    }
    if (!missing(route)) return begin()
    val calculate = {
      buildMissing { ok ->
        if (ok && selected === route) {
          if (missing(route)) Notify.error(R.string.route_not_found) else begin()
        }
      }
    }
    if (Settings.routeAutoCalculate.value) calculate() else askStart(onCalculate = calculate, onDirections = ::begin)
  }

  /** Начало навигации: у каждого участка остаётся только выбранный путь, пустые получают направление. FOR LOCAL USE */
  private fun begin() {
    val route = selected ?: return
    if (route.entries.isEmpty() || active) return
    route.entries.forEach { entry ->
      val chosen = entry.chosen()
      entry.geometries.retainAll { it === chosen }
      entry.geometries.forEach { it.rewind() }
    }
    speeds.clear()
    travelSpeed = null
    active = true
    fillVirtual()
    GpsDataManager.lastFix?.let { onPosition(GeoPoint(it.lat, it.lon), null) }
    DataStore.scheduleSave()
    ModuleHost.requestRedraw()
  }

  /** Остановка навигации: прямые уходят вместе с ней, у текущего участка обрезается пройденная часть,
      остальные пути снова рисуются целиком. Статусы точек не меняются.
  */
  fun stop() {
    if (!active) return
    active = false
    abort()
    selected?.entries?.forEachIndexed { index, entry ->
      entry.geometries.removeAll { it.virtual }
      if (index == 0) entry.chosen()?.let { way -> entry.geometries[entry.geometries.indexOf(way)] = way.trimmed() }
      entry.geometries.forEach { it.rewind() }
      if (entry.chosen() == null) entry.geometries.firstOrNull()?.state = RouteGeometryState.ACTIVE
    }
    speeds.clear()
    travelSpeed = null
    DataStore.scheduleSave()
    ModuleHost.requestRedraw()
  }

  /** Ведёт позицию по пути текущего участка и снимает остановку, когда позиция вошла в радиус прибытия;
      без навигации только автосортировка. Меняет остановки и геометрии, поэтому только в главном потоке.
  */
  fun onPosition(here: GeoPoint, speed: Float?) {
    if (!active) {
      autoSort(here)
      return
    }
    speed?.let { addSpeed(it) }
    val entry = selected?.entries?.firstOrNull() ?: return
    val way = entry.chosen()
    way?.advance(here)
    // Концом участка считается и точка, и конец пути: путь может не доходить до недостижимой точки, например в море.
    val radius = arrivalRadius(entry)
    val ends = listOfNotNull(entry.place, way?.points?.lastOrNull())
    if (ends.any { GeoMath.distance(here.lat, here.lon, it.lat, it.lon) <= radius }) arrive(entry)
    ModuleHost.requestRedraw()
  }

  private fun addSpeed(speed: Float) {
    speeds.addLast(speed)
    while (speeds.size > ROUTE_SPEED_AVERAGING_POINTS) speeds.removeFirst()
    val average = speeds.average()
    if (average > 0.0) travelSpeed = average
  }

  /** Больший из радиуса прибытия маршрута и радиуса автопосещения точки, если оно у неё включено */
  private fun arrivalRadius(entry: RouteEntry): Double {
    val own = entry.point()?.takeIf { it.autoVisit }?.visitRadius ?: 0
    return maxOf(Settings.routeArrivalRadius.value, own).toDouble()
  }

  /** Время пути в секундах по усреднённой скорости навигации; null - скорость ещё не измерялась */
  fun travelSeconds(meters: Double): Double? = travelSpeed?.let { meters / it }

  // FOR LOCAL USE: automatic sorting follows the position of the traveller: it orders the stops again once he has moved
  // a tenth of the screen away from the place they were ordered from. A running navigation is never reordered.
  private fun autoSort(here: GeoPoint) {
    if (!Settings.routeAutoSort.value) return
    if ((selected?.entries?.size ?: 0) < 2) return
    val last = sortedAt
    if (last != null && GeoMath.distance(last.lat, last.lon, here.lat, here.lon) < sortShift()) return
    sortedAt = here
    sort()
  }

  // FOR LOCAL USE: the shift of the position that is worth a new ordering, taken from the scale of the map on the screen.
  private fun sortShift(): Double {
    val viewport = Maps.lastViewport ?: return SORT_SHIFT_METERS
    return viewport.width * SORT_SHIFT * viewport.metersPerPixel()
  }

  /** Точка могла сдвинуться: сбрасываются геометрии её участков */
  override fun onPointChanged(id: Long) = pointsChanged(false)

  override fun onPointsChanged() = pointsChanged(false)

  /** Остановки удалённых точек уходят из всех маршрутов */
  override fun onPointsRemoved(ids: Collection<Long>) {
    var removed = false
    routes.forEach { route -> if (route.entries.removeAll { it.pointId in ids }) removed = true }
    pointsChanged(removed)
  }

  /** У невыбранных маршрутов только сбрасываются неверные геометрии, у выбранного - все последствия изменения.
      FOR LOCAL USE
  */
  private fun pointsChanged(removed: Boolean) {
    var changed = removed
    routes.forEach { if (it !== selected && dropChangedLegs(it)) changed = true }
    val route = selected
    if (route != null && (removed || dropChangedLegs(route))) return afterChange()
    if (changed) DataStore.scheduleSave()
  }

  /** Остановка достигнута: она уходит из маршрута, целью становится следующая. FOR LOCAL USE */
  private fun arrive(entry: RouteEntry) {
    val route = selected ?: return
    route.entries.remove(entry)
    DataStore.scheduleSave()
    if (route.entries.isEmpty()) stop()
  }

  /**
  * The geometry currently being travelled.
  * @return The active geometry of the first stop, or null.
  */
  fun activeGeometry(): RouteGeometry? = selected?.entries?.firstOrNull()?.chosen()

  // The way of a stop leads to that stop, so the stop the travel goes to is the first one of the list.
  /**
  * The stop being travelled to.
  * @return The target stop, or null while the route has none.
  */
  fun activeTarget(): RouteEntry? = selected?.entries?.firstOrNull()

  /**
  * Distance left to the active target in meters.
  * @return The length left of the active way, the straight distance without one, or zero.
  */
  fun distanceToTarget(): Double {
    activeGeometry()?.let { return it.remaining() }
    val target = activeTarget()?.place ?: return 0.0
    val here = GpsDataManager.lastFix ?: return 0.0
    return GeoMath.distance(here.lat, here.lon, target.lat, target.lon)
  }

  /**
  * Distance left to the end of the route in meters.
  * @return The sum of the leg being travelled and every leg after it.
  */
  fun distanceToEnd(): Double {
    val route = selected ?: return 0.0
    if (route.entries.isEmpty()) return 0.0
    var total = distanceToTarget()
    for (index in 1 until route.entries.size) {
      val entry = route.entries[index]
      total += entry.chosen()?.meters ?: straight(route.entries[index - 1], entry)
    }
    return total
  }

  private fun straight(entry: RouteEntry, next: RouteEntry): Double =
    GeoMath.distance(entry.place.lat, entry.place.lon, next.place.lat, next.place.lon)

  /**
  * Builds the file record of a route in the main thread.
  * @param route Route to describe.
  * @return The record of the route; stops whose point is gone are left out.
  */
  fun snapshot(route: Route): RouteRecord = RouteRecord(
    route.name,
    route.entries.mapNotNull { entry ->
      val point = entry.point() ?: return@mapNotNull null
      val ways = entry.ways().sortedBy { way -> if (way.state == RouteGeometryState.ACTIVE) 0 else 1 }
      RouteStopRecord(point, ways)
    }
  )

  /** Место остановки чужого формата: без имени получает имя из типа формата и номера, например GPX1 */
  fun stopPlace(place: MapPoint, format: String, number: Int): MapPoint =
    if (place.captionOnly() != null) place else place.copy(info = MetaInfo(listOf(PointElement.Text("$format$number")) + place.elements))

  /**
  * Replaces the routes with the loaded ones.
  * @param loaded Routes read from the application data, or null when nothing was read.
  * @return Unit; run on the main thread after the points are loaded.
  */
  fun onLoaded(loaded: List<RouteRecord>?) {
    selected = null
    routes.clear()
    nextId = 1L
    active = false
    abort()
    loaded?.forEach { routes += build(it) }
    selected = routes.firstOrNull()
  }

  /**
  * Adds imported routes that the application does not have yet.
  * @param loaded Routes read from the imported data.
  * @return The number of routes added.
  */
  fun applyImport(loaded: List<RouteRecord>): Int {
    var added = 0
    loaded.forEach { source ->
      val route = build(source)
      val points = route.entries.map { it.pointId }
      val known = routes.any { own -> own.name == route.name && own.entries.map { it.pointId } == points }
      if (!known) {
        routes += route
        added++
      }
    }
    if (selected == null) selected = routes.firstOrNull()
    if (added > 0) DataStore.scheduleSave()
    return added
  }

  /** Остановка файла привязывается к точке в том же месте; если такой нет, точка создаётся из записи файла.
      FOR LOCAL USE
  */
  private fun build(source: RouteRecord): Route {
    val route = Route(nextId++, source.name)
    source.stops.forEach { stop ->
      val point = PointStore.findOrCreate(stop.place)
      if (route.entries.any { it.pointId == point.id }) return@forEach
      val entry = RouteEntry(nextEntryId++, point.id, point.geo)
      entry.geometries.addAll(stop.ways)
      entry.geometries.forEachIndexed { index, way ->
        way.state = if (index == 0) RouteGeometryState.ACTIVE else RouteGeometryState.INACTIVE
      }
      route.entries += entry
    }
    return route
  }

  private fun samePlace(one: GeoPoint, other: GeoPoint) = GeoMath.samePlace(one.lat, one.lon, other.lat, other.lon)

  /**
  * Writes the routes as an XML section: a stop as its place and texts, with the ways found for its leg.
  * @param set Snapshot of selected application data to serialize or export.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @param xml XML serializer already positioned inside the owning document or section.
  * @return Unit; run on the IO executor with the serializer inside the xTravel root.
  */
  override fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    xml.startTag(null, tag)
    set.routes.forEach { route ->
      xml.startTag(null, "route")
      if (route.name.isNotEmpty()) xml.attribute(null, "name", route.name)
      route.stops.forEach { stop ->
        xml.startTag(null, "point")
        xml.attribute(null, "id", stop.place.id.toString())
        xml.attribute(null, "lat", stop.place.lat.toString())
        xml.attribute(null, "lon", stop.place.lon.toString())
        val texts = stop.place.elements.filterIsInstance<PointElement.Text>()
        texts.getOrNull(0)?.let { if (it.text.isNotEmpty()) xml.attribute(null, "name", it.text) }
        texts.getOrNull(1)?.let { if (it.text.isNotEmpty()) xml.attribute(null, "text", it.text) }
        stop.ways.forEach { writeWay(xml, it) }
        xml.endTag(null, "point")
      }
      xml.endTag(null, "route")
    }
    xml.endTag(null, tag)
  }

  /**
  * Reads the routes of an XML section into the import accumulator.
  *
  * Usage: Start on the section opening tag; the places of the stops are taken as they are written.
  * @param parser XML pull parser positioned at the opening tag to consume.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @param result Mutable import accumulator receiving parsed records.
  * @return Unit; consumes the current XML section.
  */
  override fun read(parser: XmlPullParser, dir: File, result: LoadedData) {
    parser.forEachChild { name -> if (name == "route") result.routes += readRoute(parser) }
  }

  private fun readRoute(parser: XmlPullParser): RouteRecord {
    val name = parser.attr("name").orEmpty()
    val stops = mutableListOf<RouteStopRecord>()
    parser.forEachChild { child ->
      if (child != "point") return@forEachChild
      val lat = parser.attr("lat")?.toDoubleOrNull()
      val lon = parser.attr("lon")?.toDoubleOrNull()
      val id = parser.attr("id")?.toLongOrNull()
      val caption = parser.attr("name").orEmpty()
      val text = parser.attr("text").orEmpty()
      val ways = mutableListOf<RouteGeometry>()
      val place = if (lat != null && lon != null) GeoPoint(lat, lon) else null
      parser.forEachChild { inner -> if (inner == "way") readWay(parser, place)?.let { ways += it } }
      if (place == null) return@forEachChild
      val elements = mutableListOf<PointElement>()
      if (caption.isNotEmpty() || text.isNotEmpty()) elements += PointElement.Text(caption)
      if (text.isNotEmpty()) elements += PointElement.Text(text)
      val record = MapPoint(id = id ?: 0L, lat = place.lat, lon = place.lon, info = MetaInfo(elements))
      stops += RouteStopRecord(stopPlace(record, "xTravel", stops.size + 1), ways)
    }
    return RouteRecord(name, stops)
  }

  // A way is written with everything a request answered: its numbers, its line and its steps.
  private fun writeWay(xml: XmlSerializer, way: RouteGeometry) {
    xml.startTag(null, "way")
    xml.attribute(null, "transport", way.transport.name)
    xml.attribute(null, "meters", way.meters.toString())
    xml.attribute(null, "seconds", way.seconds.toString())
    xml.attribute(null, "from", pointText(way.from))
    xml.attribute(null, "to", pointText(way.to))
    xml.startTag(null, "line").text(way.points.joinToString(" ") { pointText(it) }).endTag(null, "line")
    way.steps.forEach { step ->
      xml.startTag(null, "step")
      xml.attribute(null, "at", step.index.toString())
      xml.attribute(null, "turn", step.maneuver.name)
      if (step.description.isNotEmpty()) xml.attribute(null, "text", step.description)
      xml.endTag(null, "step")
    }
    xml.endTag(null, "way")
  }

  private fun pointText(point: GeoPoint) = String.format(Locale.ROOT, "%.6f,%.6f", point.lat, point.lon)

  private fun readWay(parser: XmlPullParser, place: GeoPoint?): RouteGeometry? {
    val transport = TransportKind.entries.firstOrNull { it.name == parser.attr("transport") } ?: Settings.routeTransport.value
    val meters = parser.attr("meters")?.toDoubleOrNull() ?: 0.0
    val seconds = parser.attr("seconds")?.toDoubleOrNull() ?: 0.0
    val from = parsePoint(parser.attr("from"))
    val to = parsePoint(parser.attr("to"))
    var line = emptyList<GeoPoint>()
    val steps = mutableListOf<RouteStep>()
    parser.forEachChild { child ->
      when (child) {
        "line" -> line = parseLine(parser.nextText())
        "step" -> {
          val at = parser.attr("at")?.toIntOrNull() ?: 0
          val turn = RouteManeuver.entries.firstOrNull { it.name == parser.attr("turn") } ?: RouteManeuver.UNKNOWN
          steps += RouteStep(at, turn, parser.attr("text").orEmpty())
        }
      }
    }
    if (line.isEmpty()) return null
    return RouteGeometry(from ?: line.first(), to ?: place ?: line.last(), transport, line, meters, seconds, steps)
  }

  private fun parseLine(text: String): List<GeoPoint> = text.trim().split(' ').mapNotNull { parsePoint(it) }

  private fun parsePoint(pair: String?): GeoPoint? {
    val lat = pair?.substringBefore(',')?.toDoubleOrNull() ?: return null
    val lon = pair.substringAfter(',', "").toDoubleOrNull() ?: return null
    return GeoPoint(lat, lon)
  }
}
