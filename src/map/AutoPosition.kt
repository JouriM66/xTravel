// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper

private val main = Handler(Looper.getMainLooper())

/** Возврат карты к позиции после Settings.autoPosition секунд без действий пользователя; отсчёт - от последнего
    действия или от перехода в ручной режим. Проверяется при новых данных от устройства.
*/
object AutoPosition : IAppModule, IdleWatcher() {

  private var lastMode: PositionMode? = null

  private val onUpdate: (GpsUpdate) -> Unit = { if (it.rawIsNew) main.post(::check) }

  override fun start() {
    GpsDataManager.subscribe(onUpdate)
    UserActivity.subscribe(this)
  }

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
    // При эмуляции позиция берётся с карты: возвращать карту к ней нельзя.
    if (limit <= 0 || mode != PositionMode.CUSTOM || GpsDataManager.emulated) return
    if (idleMs() >= limit * 1000L) Maps.followGps()
  }
}

/** Автоповорот карты курсом вверх, пока видна стрелка позиции и пользователь ничего не делал MAP_ROTATE_AFTER_IDLE_MS. */
object MapRotation : IAppModule, IdleWatcher() {

  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    val fix = update.fix
    val course = fix?.course
    if (update.fixIsNew && course != null && fix.isMoving()) main.post { rotate(course) }
  }

  override fun start() {
    GpsDataManager.subscribe(onUpdate)
    UserActivity.subscribe(this)
  }

  override fun stop() {
    GpsDataManager.unsubscribe(onUpdate)
    UserActivity.unsubscribe(this)
  }

  private fun rotate(course: Float) {
    if (Settings.mapDirection.value != MapDirection.AUTO || idleMs() < MAP_ROTATE_AFTER_IDLE_MS || GpsDataManager.emulated) return
    Maps.rotateTo(course)
  }
}
