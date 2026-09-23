// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Carries the latest raw and accepted GPS measurements with their freshness and provider-availability flags.
 * Provider availability does not guarantee that either measurement is fresh.
 */
class GpsUpdate(
  val raw: GpsMeasure?,
  val fix: GpsMeasure?,
  val rawIsNew: Boolean,
  val fixIsNew: Boolean,
  val available: Boolean
)

/**
 * Carries one GPS measurement with nullable unavailable sensor fields.
 * [time] uses Unix epoch milliseconds, while [receiveTime] uses monotonic uptime and must not be compared with it.
 */
class GpsMeasure(
  val time: Long,
  val lat: Double,
  val lon: Double,
  val elevation: Double? = null,
  val speed: Float? = null,
  val course: Float? = null,
  val hAccuracy: Float? = null,
  val vAccuracy: Float? = null,
  val speedAccuracy: Float? = null,
  val bearingAccuracy: Float? = null,
  val nSats: Int? = null,
  val receiveTime: Long = 0
) {
  /** Determines whether the sample has a course and is younger than the movement timeout. */
  fun isMoving(): Boolean = course != null && System.currentTimeMillis() - time < GPS_MOVE_STOP_DELAY_MS
}

/**
 * Converts an Android Location into a measurement with nullable absent fields and an uptime reception timestamp.
 * Mean-sea-level altitude is preferred when the device and Android version provide it.
 */
fun Location.toGpsMeasure(): GpsMeasure {
  val ele = when {
    Build.VERSION.SDK_INT >= 34 && hasMslAltitude() -> mslAltitudeMeters
    hasAltitude() -> altitude
    else -> null
  }
  return GpsMeasure(
    time = time,
    lat = latitude,
    lon = longitude,
    elevation = ele,
    speed = if (hasSpeed()) speed else null,
    course = if (hasBearing()) bearing else null,
    hAccuracy = if (hasAccuracy()) accuracy else null,
    vAccuracy = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    speedAccuracy = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond else null,
    bearingAccuracy = if (hasBearingAccuracy()) bearingAccuracyDegrees else null,
    nSats = extras?.getInt("satellites", -1)?.takeIf { it >= 0 },
    receiveTime = SystemClock.uptimeMillis()
  )
}

/**
 * Owns the GPS worker, registered filters, course smoothing, status publication and sample subscriptions.
 * Subscribers run on the GPS worker, while observable status is posted to the main thread.
 */
object GpsDataManager : IBlinkListener {

  private const val UPDATE_INTERVAL_MS = 1000L

  private val worker = HandlerThread("gps").also { it.start() }
  val handler = Handler(worker.looper)
  /** Handler for serialized GPS, filter and sample-processing work. */

  private val main = Handler(Looper.getMainLooper())

  private val subscribers = CopyOnWriteArrayList<(GpsUpdate) -> Unit>()
  var filters by mutableStateOf<List<IGpsFilter>>(emptyList())
    private set
  /** Observable list of registered filters; mutate through [register] and [unregister]. */

  private var filter: IGpsFilter = GpsFilter_None
  private var locationManager: LocationManager? = null
  private var appContext: Context? = null
  private var providerEnabled = false
  private var lastCall = 0L
  private val courseSamples = ArrayDeque<Float>()

  var emulated by mutableStateOf(false)
    private set
  /** Indicates whether device samples are replaced with periodic map-center measurements. */

  @Volatile
  private var emulating = false

  @Volatile
  var lastRaw: GpsMeasure? = null
    private set
  /** Latest raw measurement after course smoothing; published across threads. */

  @Volatile
  var lastFix: GpsMeasure? = null
    private set
  /** Last filter-accepted position, which may be older than [lastRaw]. */

  var status by mutableStateOf(GpsStatus.UNAVAILABLE)
    private set
  /** GPS availability and freshness status published on the main thread. */

  private var publishedStatus = GpsStatus.UNAVAILABLE

  private val listener = object : LocationListener {
    override fun onLocationChanged(location: Location) {
      Failures.guard(FailureSource.GPS) { onLocation(location) }
    }

    override fun onProviderEnabled(provider: String) = setProviderEnabled(true)

    override fun onProviderDisabled(provider: String) = setProviderEnabled(false)

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
  }

  /** Registers the GPS manager with the shared blink timer on its worker handler. */
  fun selfRegister() {
    TimerManager.subscribe(this, handler)
  }

  /**
   * Registers a uniquely named filter and rebuilds the selection when it is configured.
   * Distinct filter instances with duplicate names are rejected.
   */
  @Synchronized
  fun register(filter: IGpsFilter) {
    if (filters.any { it === filter }) return
    require(filters.none { it.name() == filter.name() }) { "GPS filter already registered: ${filter.name()}" }
    filters = filters + filter
    if (Settings.filterMode.value == filter.name()) rebuildFilter()
  }

  /** Removes a filter and rebuilds the active selection if necessary. */
  @Synchronized
  fun unregister(filter: IGpsFilter) {
    if (filters.none { it === filter }) return
    filters = filters.filterNot { it === filter }
    if (Settings.filterMode.value == filter.name()) rebuildFilter()
  }

  /**
   * Adds a callback for measurement and periodic availability updates.
   * Callbacks execute on the GPS worker, so UI changes must be posted to the main thread.
   */
  fun subscribe(subscriber: (GpsUpdate) -> Unit) {
    subscribers += subscriber
  }

  /** Removes a GPS update callback; already-dispatched work may still complete. */
  fun unsubscribe(subscriber: (GpsUpdate) -> Unit) {
    subscribers -= subscriber
  }

  /** Queues GPS work under a failure guard so an exception cannot stop the worker thread. */
  fun post(action: () -> Unit) {
    handler.post { Failures.guard(FailureSource.GPS, action) }
  }

  /**
   * Queues filter initialization and LocationManager subscription on the GPS worker.
   * [GpsPermissionInit] must grant location permission before this operation is called by [GpsService].
   */
  @SuppressLint("MissingPermission")
  fun startUpdates(context: Context) = post {
    courseSamples.clear()
    appContext = context.applicationContext
    filter = currentFilter()
    filter.reset()
    filter.start(context.applicationContext, handler)
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    locationManager = manager
    providerEnabled = manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_INTERVAL_MS, 0f, listener, worker.looper)
    publish(rawIsNew = false, fixIsNew = false)
  }

  /** Queues filter cleanup, removes location updates and publishes unavailable state. */
  fun stopUpdates() = post {
    courseSamples.clear()
    val manager = locationManager
    locationManager = null
    filter.stop()
    filter.reset()
    filter = GpsFilter_None
    manager?.removeUpdates(listener)
    publish(rawIsNew = false, fixIsNew = false)
  }

  /** Queues replacement of the selected filter and resets its history and course smoothing. */
  fun rebuildFilter() = post {
    val context = appContext ?: return@post
    if (locationManager == null) return@post
    filter.stop()
    filter = currentFilter()
    filter.reset()
    courseSamples.clear()
    filter.start(context, handler)
  }

  /** Measures whole seconds since the last raw GPS reception, or returns null before the first sample. */
  fun secondsSinceData(): Long? = lastRaw?.let { (SystemClock.uptimeMillis() - it.receiveTime) / 1000 }

  /**
   * Switches between device GPS and periodic map-center measurements.
   * The observable flag changes on the caller thread, while reset and sampling are queued on the GPS worker.
   */
  fun emulate(on: Boolean) {
    emulated = on
    emulating = on
    post {
      courseSamples.clear()
      filter.reset()
      if (on) take(Maps.camera.center)
    }
  }

  /** Produces an emulated sample or publishes missing-data state when the callback interval expires. */
  override fun onBlinkOn() {
    if (emulating) {
      take(Maps.camera.center)
      return
    }
    if (SystemClock.uptimeMillis() - lastCall >= GPS_DELAY_TO_SEND_DATA_IF_MISSING) {
      publish(rawIsNew = false, fixIsNew = false)
    }
  }

  /**
   * Builds an emulated sample from the map center and fills every field for track readers.
   * Computed speed prevents the filter from treating every emulated sample as a stationary phone.
   */
  private fun take(place: GeoPoint) {
    val time = System.currentTimeMillis()
    val previous = lastRaw
    val seconds = previous?.let { (time - it.time) / 1000.0 }?.takeIf { it > 0 }
    val distance = previous?.let { GeoMath.distance(it.lat, it.lon, place.lat, place.lon) }
    val speed = if (seconds != null && distance != null) (distance / seconds).toFloat() else 0f
    val course = if (previous != null && distance != null && distance > 0) {
      GeoMath.bearing(previous.lat, previous.lon, place.lat, place.lon)
    } else {
      0f
    }
    onNewMeasure(
      GpsMeasure(time, place.lat, place.lon, 0.0, speed, course, 0f, 0f, 0f, 0f, 0, SystemClock.uptimeMillis())
    )
  }

  private fun setProviderEnabled(enabled: Boolean) {
    if (!enabled) courseSamples.clear()
    providerEnabled = enabled
    publish(rawIsNew = false, fixIsNew = false)
  }

  /** Resolves the configured filter by its persisted name and falls back to [GpsFilter_None]. */
  fun currentFilter(): IGpsFilter = filters.firstOrNull { it.name() == Settings.filterMode.value } ?: GpsFilter_None

  private fun onLocation(location: Location) {
    if (emulating) return
    onNewMeasure(location.toGpsMeasure())
  }

  /**
   * Suppresses the current position before filtering so the track does not receive a duplicate point without movement.
   * All other acceptance decisions remain with the selected filter.
   */
  private fun onNewMeasure(measure: GpsMeasure) {
    val fix = smoothCourse(measure)
    lastRaw = fix
    val samePlace = lastFix?.let { GeoMath.samePlace(it.lat, it.lon, fix.lat, fix.lon) } == true
    val accepted = !samePlace && filter.acceptNewValue(fix)
    if (accepted) lastFix = fix
    publish(rawIsNew = true, fixIsNew = accepted)
  }

  /** Replaces the incoming course with the circular mean of recent measurements. */
  private fun smoothCourse(measure: GpsMeasure): GpsMeasure {
    val course = measure.course
    if (course == null) {
      courseSamples.clear()
      return measure
    }
    if (lastRaw?.let { measure.receiveTime - it.receiveTime > GPS_DATA_DELAY_TO_OF } == true) {
      courseSamples.clear()
    }
    courseSamples.addLast(course)
    while (courseSamples.size > GPS_COURSE_AVERAGING_POINTS.coerceAtLeast(1)) {
      courseSamples.removeFirst()
    }
    var sumSin = 0.0
    var sumCos = 0.0
    for (sample in courseSamples) {
      val radians = Math.toRadians(sample.toDouble())
      sumSin += sin(radians)
      sumCos += cos(radians)
    }
    /** Opposing directions have no defined mean, so the newest course is retained. */
    val averaged = if (hypot(sumSin, sumCos) < 1e-9) course else {
      ((Math.toDegrees(atan2(sumSin, sumCos)) + 360.0) % 360.0).toFloat() % 360f
    }
    return GpsMeasure(
      time = measure.time,
      lat = measure.lat,
      lon = measure.lon,
      elevation = measure.elevation,
      speed = measure.speed,
      course = averaged,
      hAccuracy = measure.hAccuracy,
      vAccuracy = measure.vAccuracy,
      speedAccuracy = measure.speedAccuracy,
      bearingAccuracy = measure.bearingAccuracy,
      nSats = measure.nSats,
      receiveTime = measure.receiveTime
    )
  }

  private fun publish(rawIsNew: Boolean, fixIsNew: Boolean) {
    lastCall = SystemClock.uptimeMillis()
    val available = emulating || (locationManager != null && providerEnabled)
    val update = GpsUpdate(lastRaw, lastFix, rawIsNew, fixIsNew, available)
    subscribers.forEach { it(update) }
    val raw = lastRaw
    val newStatus = when {
      !available -> GpsStatus.UNAVAILABLE
      raw == null || lastCall - raw.receiveTime > GPS_DATA_DELAY_TO_OF -> GpsStatus.WAITING
      else -> GpsStatus.OK
    }
    if (newStatus != publishedStatus) {
      if (newStatus != GpsStatus.OK) {
        filter.reset()
        courseSamples.clear()
      }
      publishedStatus = newStatus
      main.post {
        status = newStatus
        ModuleHost.requestRedraw()
        GpsService.refresh()
      }
    }
  }
}
