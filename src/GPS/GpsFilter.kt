// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Defines GPS sample acceptance and lifecycle/reset hooks executed on the GPS worker.
 * [reset] clears history independently of the resource management performed by [start] and [stop].
 */
interface IGpsFilter {
  /** Provides the stable filter identifier used in settings and registration. */
  fun name(): String

  /** Attaches resources needed to filter samples and delivers callbacks through [handler]. */
  fun start(context: Context, handler: Handler) {}

  /** Releases resources such as sensor subscriptions. */
  fun stop() {}

  /** Clears acceptance history without replacing the filter or its resource subscriptions. */
  fun reset() {}

  /** Determines whether a GPS sample becomes the accepted application position. */
  fun acceptNewValue(fix: GpsMeasure): Boolean
}

/** Accepts every sample; manager-level duplicate-position suppression still applies. */
object GpsFilter_None : IGpsFilter {
  /** Returns the persisted identifier of the pass-through filter. */
  override fun name() = "NONE"

  /** Accepts every sample delivered by the manager. */
  override fun acceptNewValue(fix: GpsMeasure) = true
}

/**
 * Rejects inaccurate, stationary, too-close or implausibly fast samples using GPS and accelerometer history.
 * A zero value in the corresponding setting disables each heuristic.
 */
object GpsFilter_Simple : IGpsFilter, SensorEventListener {

  private const val CALM_WINDOW_MS = 2000L
  private const val CALM_DEVIATION = 0.3f
  private const val MAX_OUTLIERS = 5 /** Consecutive outliers after which the reference sample is treated as unreliable. */

  /** Returns the persisted identifier of the heuristic filter. */
  override fun name() = "SIMPLE"

  private var sensorManager: SensorManager? = null
  private var hasAccelerometer = false
  private val magnitudes = ArrayDeque<Pair<Long, Float>>()
  private var last: GpsMeasure? = null
  private var outliers = 0

  /**
   * Registers an accelerometer listener on the supplied handler after releasing any prior registration.
   * Filtering remains available when the device has no accelerometer.
   */
  override fun start(context: Context, handler: Handler) {
    stop()
    val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
    sensorManager = manager
    hasAccelerometer = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
  }

  /** Unregisters the accelerometer without clearing acceptance history. */
  override fun stop() {
    sensorManager?.unregisterListener(this)
    sensorManager = null
    hasAccelerometer = false
  }

  /** Clears filter history while keeping the current sensor registration. */
  override fun reset() {
    magnitudes.clear()
    last = null
    outliers = 0
  }

  /** Accumulates accelerometer magnitudes in a rolling calm-detection window. */
  override fun onSensorChanged(event: SensorEvent) {
    val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
    val now = SystemClock.uptimeMillis()
    magnitudes.addLast(now to sqrt(x * x + y * y + z * z))
    while (magnitudes.isNotEmpty() && now - magnitudes.first().first > CALM_WINDOW_MS) {
      magnitudes.removeFirst()
    }
  }

  /** Ignores sensor-accuracy changes because the filter uses measured acceleration only. */
  override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

  /** Without an accelerometer, the speed threshold alone determines stationary state. */
  private fun phoneIsCalm(): Boolean {
    if (!hasAccelerometer) return true
    if (magnitudes.size < 2) return false
    val mean = magnitudes.sumOf { it.second.toDouble() } / magnitudes.size
    val variance = magnitudes.sumOf { (it.second - mean) * (it.second - mean) } / magnitudes.size
    return sqrt(variance) < CALM_DEVIATION
  }

  /**
   * Applies configured accuracy, implied-speed, stationary and minimum-distance checks.
   * After five consecutive speed outliers, speed rejection is bypassed once so a bad reference sample cannot block all future positions.
   */
  override fun acceptNewValue(fix: GpsMeasure): Boolean {
    // Первое значение после сброса принимается всегда: фильтр работает со второго, иначе позиции нет, пока не сдвинешься.
    val previous = last ?: run {
      last = fix
      return true
    }
    val maxAccuracy = Settings.filterAccuracy.value
    /** Zero accuracy means the device did not provide it, so only positive values are compared. */
    if (maxAccuracy > 0 && fix.hAccuracy != null && fix.hAccuracy > 0 && fix.hAccuracy > maxAccuracy) {
      return false
    }

    val distance = GeoMath.distance(previous.lat, previous.lon, fix.lat, fix.lon)

    val maxSpeed = Settings.filterMaxSpeed.value
    val seconds = (fix.time - previous.time) / 1000.0
    if (maxSpeed > 0 && seconds > 0 && distance / seconds * 3.6 > maxSpeed && outliers < MAX_OUTLIERS) {
      outliers++
      return false
    }
    outliers = 0

    val stationary = Settings.filterStationarySpeed.value
    if (stationary > 0 && fix.speed != null && fix.speed * 3.6 < stationary && phoneIsCalm()) {
      return false
    }

    val minDistance = Settings.filterMinDistance.value
    if (minDistance > 0 && distance < minDistance) return false
    last = fix
    return true
  }
}
