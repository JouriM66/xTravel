// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.directions.DirectionsFactory
import com.yandex.mapkit.directions.driving.Action
import com.yandex.mapkit.directions.driving.DrivingOptions
import com.yandex.mapkit.directions.driving.DrivingRoute
import com.yandex.mapkit.directions.driving.DrivingRouter
import com.yandex.mapkit.directions.driving.DrivingRouterType
import com.yandex.mapkit.directions.driving.DrivingSession
import com.yandex.mapkit.directions.driving.VehicleOptions
import com.yandex.mapkit.directions.driving.VehicleType
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.transport.TransportFactory
import com.yandex.mapkit.transport.masstransit.BicycleRouterV2
import com.yandex.mapkit.transport.masstransit.FilterVehicleTypes
import com.yandex.mapkit.transport.masstransit.FitnessOptions
import com.yandex.mapkit.transport.masstransit.MasstransitRouter
import com.yandex.mapkit.transport.masstransit.PedestrianRouter
import com.yandex.mapkit.transport.masstransit.Route as MasstransitYandexRoute
import com.yandex.mapkit.transport.masstransit.RouteOptions
import com.yandex.mapkit.transport.masstransit.Session as MasstransitSession
import com.yandex.mapkit.transport.masstransit.TimeOptions
import com.yandex.mapkit.transport.masstransit.TransitOptions
import com.yandex.runtime.Error

// Routing of the Yandex provider. Pedestrian, bicycle and public-transport answers carry no manoeuvres; only the
// driving routers describe the turns, so a route of the other kinds gets its steps from nobody.

/**
* Requests routes from the Yandex routers and turns their answers into application geometries.
*
* Usage: Call on the main thread after MapKit is ready; the listener answers on the main thread as well.
*
* Public and subclass/module-facing members:
* - [request] - Submits a route request for the given way of travelling.
*/
object YandexRoutes {

  private var drivingRouter: DrivingRouter? = null
  private var pedestrianRouter: PedestrianRouter? = null
  private var bicycleRouter: BicycleRouterV2? = null
  private var masstransitRouter: MasstransitRouter? = null

  // MapKit does not hold the session itself; a session that is collected stops answering.
  private val sessions = mutableListOf<Any>()

  /**
  * Submits a route request for the given way of travelling.
  * @param from Coordinate the way starts at.
  * @param to Coordinate the way ends at.
  * @param transport Way of travelling to request.
  * @param listener Receiver of the answer or of the failure reason.
  * @return True when the request was submitted.
  */
  fun request(from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener): Boolean {
    if (!YandexMapEngine.ensureReady()) return false
    val points = listOf(waypoint(from), waypoint(to))
    val submitted = Failures.guard(FailureSource.MAP) {
      when (transport) {
        TransportKind.WALK -> walk(points, from, to, transport, listener)
        TransportKind.BICYCLE -> bicycle(points, from, to, transport, listener)
        TransportKind.TRANSIT -> transit(points, from, to, transport, listener)
        TransportKind.CAR -> drive(points, from, to, transport, VehicleOptions(), listener)
        TransportKind.TRUCK -> drive(points, from, to, transport, VehicleOptions().setVehicleType(VehicleType.TRUCK), listener)
      }
      true
    }
    return submitted == true
  }

  private fun waypoint(point: GeoPoint) = RequestPoint(Point(point.lat, point.lon), RequestPointType.WAYPOINT, null, null, null)

  private fun keep(session: Any) {
    sessions += session
  }

  private fun done(session: Any?) {
    sessions.remove(session)
  }

  // FOR LOCAL USE: an answer of a router. MapKit calls back from its own native code: a failure that leaves the handler
  // is not caught by anybody and kills the process without a word, so the handling goes through the guard.
  private fun answer(session: Any?, block: () -> Unit) {
    done(session)
    Failures.guard(FailureSource.MAP, block)
  }

  // FOR LOCAL USE: the answer handler of the pedestrian, bicycle and public-transport routers; all three answer alike.
  private fun masstransitCallback(
    from: GeoPoint,
    to: GeoPoint,
    transport: TransportKind,
    listener: IRouteListener,
    session: () -> MasstransitSession?
  ) = object : MasstransitSession.RouteListener {
    override fun onMasstransitRoutes(routes: MutableList<MasstransitYandexRoute>) {
      answer(session()) { listener.onRouteReady(routes.map { fromMasstransit(it, from, to, transport) }) }
    }

    override fun onMasstransitRoutesError(error: Error) {
      answer(session()) { listener.onRouteFailed(error.javaClass.simpleName) }
    }
  }

  private fun walk(points: List<RequestPoint>, from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener) {
    val router = pedestrianRouter ?: TransportFactory.getInstance().createPedestrianRouter().also { pedestrianRouter = it }
    var session: MasstransitSession? = null
    val callback = masstransitCallback(from, to, transport, listener) { session }
    session = router.requestRoutes(points, TimeOptions(), RouteOptions(FitnessOptions()), callback)
    keep(session)
  }

  private fun transit(points: List<RequestPoint>, from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener) {
    val router = masstransitRouter ?: TransportFactory.getInstance().createMasstransitRouter().also { masstransitRouter = it }
    var session: MasstransitSession? = null
    val callback = masstransitCallback(from, to, transport, listener) { session }
    // The options must be filled in: the constructor without arguments leaves their fields empty and the native binding dies on them.
    val options = TransitOptions(FilterVehicleTypes.NONE.value, TimeOptions())
    session = router.requestRoutes(points, options, RouteOptions(FitnessOptions()), callback)
    keep(session)
  }

  private fun bicycle(points: List<RequestPoint>, from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener) {
    val router = bicycleRouter ?: TransportFactory.getInstance().createBicycleRouterV2().also { bicycleRouter = it }
    var session: MasstransitSession? = null
    val callback = masstransitCallback(from, to, transport, listener) { session }
    session = router.requestRoutes(points, TimeOptions(), RouteOptions(FitnessOptions()), callback)
    keep(session)
  }

  private fun drive(points: List<RequestPoint>, from: GeoPoint, to: GeoPoint, transport: TransportKind, vehicle: VehicleOptions, listener: IRouteListener) {
    val router = drivingRouter
      ?: DirectionsFactory.getInstance().createDrivingRouter(DrivingRouterType.COMBINED).also { drivingRouter = it }
    var session: DrivingSession? = null
    val callback = object : DrivingSession.DrivingRouteListener {
      override fun onDrivingRoutes(routes: MutableList<DrivingRoute>) {
        answer(session) { listener.onRouteReady(routes.map { fromDriving(it, from, to, transport) }) }
      }

      override fun onDrivingRoutesError(error: Error) {
        answer(session) { listener.onRouteFailed(error.javaClass.simpleName) }
      }
    }
    session = router.requestRoutes(points, DrivingOptions(), vehicle, callback)
    keep(session)
  }

  private fun line(geometry: Polyline): List<GeoPoint> = geometry.points.map { GeoPoint(it.latitude, it.longitude) }

  // FOR LOCAL USE: the length of a line the provider gave no distance for.
  private fun lineLength(line: List<GeoPoint>): Double {
    var total = 0.0
    for (i in 1 until line.size) total += GeoMath.distance(line[i - 1].lat, line[i - 1].lon, line[i].lat, line[i].lon)
    return total
  }

  private fun fromDriving(route: DrivingRoute, from: GeoPoint, to: GeoPoint, transport: TransportKind): RouteGeometry {
    val line = line(route.geometry)
    val weight = route.metadata.weight
    val steps = route.sections.map { section ->
      val annotation = section.metadata.annotation
      RouteStep(section.geometry.begin.segmentIndex, maneuver(annotation.action), annotation.descriptionText.orEmpty())
    }
    return RouteGeometry(from, to, transport, line, weight.distance.value, weight.timeWithTraffic.value, steps)
  }

  private fun fromMasstransit(route: MasstransitYandexRoute, from: GeoPoint, to: GeoPoint, transport: TransportKind): RouteGeometry {
    val line = line(route.geometry)
    return RouteGeometry(from, to, transport, line, lineLength(line), route.metadata.weight.time.value)
  }

  private fun maneuver(action: Action?): RouteManeuver = when (action) {
    Action.STRAIGHT -> RouteManeuver.STRAIGHT
    Action.SLIGHT_LEFT -> RouteManeuver.SLIGHT_LEFT
    Action.SLIGHT_RIGHT -> RouteManeuver.SLIGHT_RIGHT
    Action.LEFT -> RouteManeuver.LEFT
    Action.RIGHT -> RouteManeuver.RIGHT
    Action.HARD_LEFT -> RouteManeuver.HARD_LEFT
    Action.HARD_RIGHT -> RouteManeuver.HARD_RIGHT
    Action.FORK_LEFT -> RouteManeuver.FORK_LEFT
    Action.FORK_RIGHT -> RouteManeuver.FORK_RIGHT
    Action.UTURN_LEFT -> RouteManeuver.UTURN_LEFT
    Action.UTURN_RIGHT -> RouteManeuver.UTURN_RIGHT
    Action.ENTER_ROUNDABOUT -> RouteManeuver.ENTER_ROUNDABOUT
    Action.LEAVE_ROUNDABOUT -> RouteManeuver.LEAVE_ROUNDABOUT
    Action.BOARD_FERRY -> RouteManeuver.BOARD_FERRY
    Action.LEAVE_FERRY -> RouteManeuver.LEAVE_FERRY
    Action.EXIT_LEFT -> RouteManeuver.EXIT_LEFT
    Action.EXIT_RIGHT -> RouteManeuver.EXIT_RIGHT
    Action.FINISH -> RouteManeuver.FINISH
    Action.WAYPOINT -> RouteManeuver.WAYPOINT
    else -> RouteManeuver.UNKNOWN
  }
}
