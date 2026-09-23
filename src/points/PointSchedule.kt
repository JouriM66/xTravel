// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

private const val DAY = 24 * 60
private const val WEEK = 7 * DAY

// State of a point at the current moment: whether it is open and how long it stays so.
/**
* Describes schedule availability and minutes remaining until the next opening or closing event.
* @param availability Current schedule state indicating open, closed or unavailable information.
* @param minutesLeft Minutes remaining until the next relevant opening or closing transition.
* @property availability Current schedule state indicating open, closed or unavailable information.
* @property minutesLeft Minutes remaining until the next relevant opening or closing transition.
*/
class ScheduleState(val availability: Availability, val minutesLeft: Int)

// Schedule of the points: what is open now, how long until the next event and which points are in alarm. The time of the day
// comes from AppClock, so the debug time moves it too. Also marks points visited when the position gets close enough.
/**
* Evaluates point schedules, warnings and GPS-triggered visits using the application clock.
*
* Public and subclass/module-facing members:
* - [tick] - Observable counter updated when the application minute changes.
* - [start] - Subscribes to clock phases and accepted GPS measurements for schedule updates and automatic visits.
* - [stop] - Removes schedule timer and GPS subscriptions.
* - [onBlinkOn] - Detects a changed application minute and schedules observable schedule updates and redraw.
* - [state] - Evaluates the next opening or closing transition at the application clock time.
* - [shownTime] - Filters the current schedule state according to the point's display mode.
* - [availability] - Reads whether a point is open, closed or has no usable schedule.
* - [alarm] - Checks enabled schedule control against the displayed transition's warning interval.
* - [alarmed] - Collects points currently in schedule alarm and observes the schedule tick.
*/
object PointSchedule : IAppModule, IBlinkListener {

  private val main = Handler(Looper.getMainLooper())
  private var lastMinute = -1

  // Read by the interface: changes every minute, so the shown times follow the clock.
  /**
  * Observable counter updated when the application minute changes.
  * @return Observable counter updated when the application minute changes.
  */
  var tick by mutableIntStateOf(0)
    private set

  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    val fix = update.fix
    if (update.fixIsNew && fix != null) main.post { autoVisit(fix) }
  }

  /**
  * Subscribes to clock phases and accepted GPS measurements for schedule updates and automatic visits.
  * @return Unit; callbacks post model changes to the main thread.
  */
  override fun start() {
    TimerManager.subscribe(this)
    GpsDataManager.subscribe(onUpdate)
  }

  /**
  * Removes schedule timer and GPS subscriptions.
  * @return Unit; stops automatic schedule updates and visits.
  */
  override fun stop() {
    TimerManager.unsubscribe(this)
    GpsDataManager.unsubscribe(onUpdate)
  }

  /**
  * Detects a changed application minute and schedules observable schedule updates and redraw.
  * @return Unit; unchanged minutes do nothing.
  */
  override fun onBlinkOn() {
    val minute = minuteOfDay()
    if (minute == lastMinute) return
    lastMinute = minute
    main.post { tick++ }
    ModuleHost.requestRedraw()
  }

  // Minutes since midnight of the clock of the application: the debug time moves it as well.
  private fun minuteOfDay(): Int = AppClock.minuteOfDay()

  // Monday = 0.
  private fun dayOfWeek(): Int = AppClock.dayOfWeek()

  // Minutes since the midnight of Monday.
  private fun weekMinute(): Int = dayOfWeek() * DAY + minuteOfDay()

  // Open with the time until closing, or closed with the time until opening; null when the point has no schedule.
  /**
  * Evaluates the next opening or closing transition at the application clock time.
  *
  * Usage: Rules use device-local weekdays and minutes and must not cross midnight.
  * @param point Point whose valid weekly schedule is evaluated against AppClock.
  * @return Schedule state, or null when no valid schedule exists.
  */
  fun state(point: MapPoint): ScheduleState? {
    if (!point.hasSchedule) return null
    val now = weekMinute()
    val rules = point.schedule.filter { it.valid }
    var closes = Int.MAX_VALUE
    var opens = Int.MAX_VALUE
    for (day in 0..7) {
      rules.forEach { rule ->
        val index = (dayOfWeek() + day) % 7
        if (!rule.days[index]) return@forEach
        val start = now - minuteOfDay() + day * DAY + rule.from
        val end = start + (rule.to - rule.from)
        if (now in start until end) closes = minOf(closes, end - now)
        if (start > now) opens = minOf(opens, start - now)
      }
    }
    if (closes != Int.MAX_VALUE) return ScheduleState(Availability.OPEN, closes)
    return ScheduleState(Availability.CLOSED, if (opens == Int.MAX_VALUE) WEEK else opens)
  }

  // Time shown near the pin follows the chosen display; without it only the availability halo is left.
  /**
  * Filters the current schedule state according to the point's display mode.
  * @param point Point whose schedule and scheduleDisplay determine the label.
  * @return The state to display, or null when the selected transition is hidden.
  */
  fun shownTime(point: MapPoint): ScheduleState? {
    val state = state(point) ?: return null
    val shown = when (point.scheduleDisplay) {
      ScheduleDisplay.NONE -> false
      ScheduleDisplay.ALWAYS -> true
      ScheduleDisplay.TO_OPEN -> state.availability == Availability.CLOSED
      ScheduleDisplay.TO_CLOSE -> state.availability == Availability.OPEN
    }
    return state.takeIf { shown }
  }

  /**
  * Reads whether a point is open, closed or has no usable schedule.
  * @param point Point snapshot whose attachments, schedule or metadata are processed; null represents absent loaded notes where accepted.
  * @return The calculated Availability.
  */
  fun availability(point: MapPoint): Availability = state(point)?.availability ?: Availability.NONE

  // Alarm: the event the display is about comes closer than the warning time. Without the control there is no alarm.
  /**
  * Checks enabled schedule control against the displayed transition's warning interval.
  * @param point Point whose scheduleControl and warnBefore determine warning state.
  * @return True when a displayed event is within warnBefore minutes.
  */
  fun alarm(point: MapPoint): Boolean {
    if (!point.scheduleControl || !point.controlPossible) return false
    val state = shownTime(point) ?: return false
    return state.minutesLeft <= point.warnBefore
  }

  // Reads the tick, so the interface asking for the alarms follows the clock by itself.
  /**
  * Collects points currently in schedule alarm and observes the schedule tick.
  * @return The current alarmed points.
  */
  fun alarmed(): List<MapPoint> {
    tick
    return PointStore.points.filter { alarm(it) }
  }

  // An independent point is not waited for, so coming to it means nothing; a planned or an active one is marked visited
  // and goes back to independent, its plan is done.
  private fun autoVisit(fix: GpsMeasure) {
    PointStore.points.filter { it.autoVisit && it.status != PointStatus.INDEPENDENT }.forEach { point ->
      if (GeoMath.distance(fix.lat, fix.lon, point.lat, point.lon) <= point.visitRadius) {
        PointStore.update(point.id) { it.copy(visited = true, status = PointStatus.INDEPENDENT) }
      }
    }
  }
}

/**
* Queries the receiver point's current schedule warning.
* @receiver Point snapshot whose schedule is queried.
* @return True when the receiver is in alarm.
*/
fun MapPoint.alarm(): Boolean = PointSchedule.alarm(this)

/**
* Queries the receiver point's current schedule availability.
* @receiver Point snapshot whose schedule is queried.
* @return NONE when it has no usable schedule.
*/
fun MapPoint.availability(): Availability = PointSchedule.availability(this)
