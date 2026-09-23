// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Time of the application: the clock of the device or, while a debug time is set, a clock started from that time and running
// by itself. Everything that needs the time — schedules, the toolbar clock — asks here, so the debug time moves them all.
/**
* Provides device time or a running debug clock shared by schedules and toolbar displays.
*
* Public and subclass/module-facing members:
* - [debugStart] - Selected debug start minute, or null while device time is used; observable and internally assigned.
* - [tick] - Observable counter advanced on clock ticks to invalidate time-dependent UI.
* - [start] - Subscribes the application clock to the shared timer.
* - [stop] - Unsubscribes the application clock from the timer.
* - [onBlinkOn] - Advances the observable clock tick for time displays.
* - [millis] - Reads application time including the optional debug offset.
* - [now] - Converts application time to the device-local calendar time.
* - [minuteOfDay] - Reads the device-local minute within the application day.
* - [dayOfWeek] - Reads the device-local application weekday.
* - [useTime] - Starts a running debug clock at a selected minute of today or restores device time.
* - [dateText] - Formats the application date and observes the clock tick for Compose invalidation.
* - [timeText] - Formats application time and observes the clock tick for Compose invalidation.
*/
object AppClock : IAppModule, IBlinkListener {

  private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

  // Difference between the shown time and the clock of the device; zero while the device clock is used.
  @Volatile
  private var shiftMs = 0L

  /** Selected debug start minute, or null while device time is used; observable and internally assigned. */
  var debugStart by mutableStateOf<Int?>(null)
    private set

  /** Observable counter advanced on clock ticks to invalidate time-dependent UI. */
  var tick by mutableIntStateOf(0)
    private set

  /** Subscribes the application clock to the shared timer. */
  override fun start() = TimerManager.subscribe(this)

  /**
  * Unsubscribes the application clock from the timer.
  * @return Unit; stops periodic UI ticks.
  */
  override fun stop() = TimerManager.unsubscribe(this)

  /**
  * Advances the observable clock tick for time displays.
  * @return Unit; called once per on phase.
  */
  override fun onBlinkOn() {
    tick++
  }

  /**
  * Reads application time including the optional debug offset.
  * @return Milliseconds since the Unix epoch.
  */
  fun millis(): Long = System.currentTimeMillis() + shiftMs

  /**
  * Converts application time to the device-local calendar time.
  * @return The current LocalDateTime.
  */
  fun now(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis()), ZoneId.systemDefault())

  /**
  * Reads the device-local minute within the application day.
  * @return Minutes since midnight in 0..1439.
  */
  fun minuteOfDay(): Int = now().let { it.hour * 60 + it.minute }

  // Monday = 0.
  /**
  * Reads the device-local application weekday.
  * @return Monday-based weekday index in 0..6.
  */
  fun dayOfWeek(): Int = now().dayOfWeek.value - 1

  // Debug time: the chosen moment of today becomes the current one and goes on by itself; without it the device clock returns.
  /**
  * Starts a running debug clock at a selected minute of today or restores device time.
  * @param minutes Local minute of today at which the debug clock starts, or null to return to device time.
  * @return Unit; also requests a map redraw.
  */
  fun useTime(minutes: Int?) {
    debugStart = minutes
    shiftMs = if (minutes == null) 0L else {
      val day = LocalDateTime.now().toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
      day + minutes * 60_000L - System.currentTimeMillis()
    }
    ModuleHost.requestRedraw()
  }

  // Both read the tick, so the interface showing them is redrawn every second.
  /**
  * Formats the application date and observes the clock tick for Compose invalidation.
  * @return A yyyy-MM-dd date string.
  */
  fun dateText(): String {
    tick
    return now().format(DATE)
  }

  /**
  * Formats application time and observes the clock tick for Compose invalidation.
  * @return An HH:mm:ss time string.
  */
  fun timeText(): String {
    tick
    return now().format(TIME)
  }
}
