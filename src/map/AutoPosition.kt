// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper

private val main = Handler(Looper.getMainLooper())

// Returns the map to the position after the time from the settings without user actions; the time counts from the last action
// or from the switch to the manual mode. Checked when new data comes from the device.
/**
* Restores automatic GPS following after the configured period of user inactivity.
*
* Public and subclass/module-facing members:
* - [start] - Registers GPS and interaction subscriptions required by AutoPosition.
* - [stop] - Removes the subscriptions owned by AutoPosition.
*
* Inherited API: see [IdleWatcher] for members not overridden here.
*/
object AutoPosition : IAppModule, IdleWatcher() {

  private var lastMode: PositionMode? = null

  private val onUpdate: (GpsUpdate) -> Unit = { if (it.rawIsNew) main.post(::check) }

  /**
  * Registers GPS and interaction subscriptions required by AutoPosition.
  * @return Unit; called by the module lifecycle.
  */
  override fun start() {
    GpsDataManager.subscribe(onUpdate)
    UserActivity.subscribe(this)
  }

  /**
  * Removes the subscriptions owned by AutoPosition.
  * @return Unit; called by the module lifecycle.
  */
  override fun stop() {
    GpsDataManager.unsubscribe(onUpdate)
    UserActivity.unsubscribe(this)
  }

  private fun check() {
    val mode = Maps.positionMode
    if (mode != lastMode) {
      lastMode = mode
      if (mode == PositionMode.CUSTOM) restart()
    }
    val limit = Settings.autoPosition.value
    // While the position is emulated by the map it must not be moved back: the map is the source of the position.
    if (limit <= 0 || mode != PositionMode.CUSTOM || GpsDataManager.emulated) return
    if (idleMs() >= limit * 1000L) Maps.followGps()
  }
}

// Auto rotation of the map: while the position arrow is shown, the course goes up, but only when the user did nothing for IDLE_MS.
/**
* Rotates the map toward the current movement course after user inactivity.
*
* Public and subclass/module-facing members:
* - [start] - Registers GPS and interaction subscriptions required by MapRotation.
* - [stop] - Removes the subscriptions owned by MapRotation.
*
* Inherited API: see [IdleWatcher] for members not overridden here.
*/
object MapRotation : IAppModule, IdleWatcher() {

  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    val fix = update.fix
    val course = fix?.course
    if (update.fixIsNew && course != null && fix.isMoving()) main.post { rotate(course) }
  }

  /**
  * Registers GPS and interaction subscriptions required by MapRotation.
  * @return Unit; called by the module lifecycle.
  */
  override fun start() {
    GpsDataManager.subscribe(onUpdate)
    UserActivity.subscribe(this)
  }

  /**
  * Removes the subscriptions owned by MapRotation.
  * @return Unit; called by the module lifecycle.
  */
  override fun stop() {
    GpsDataManager.unsubscribe(onUpdate)
    UserActivity.unsubscribe(this)
  }

  private fun rotate(course: Float) {
    if (Settings.mapDirection.value != MapDirection.AUTO || idleMs() < MAP_ROTATE_AFTER_IDLE_MS || GpsDataManager.emulated) return
    Maps.rotateTo(course)
  }
}
