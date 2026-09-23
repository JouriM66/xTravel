// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import java.util.Locale

// Data model of data-structure.md: points and schedules.

// Values a point starts with and old data is brought to: a zero radius or warning time means the value was never set.
/**
* Default automatic-visit radius in meters, also used for missing or invalid legacy values.
* @return Default automatic-visit radius in meters, also used for missing or invalid legacy values.
*/
const val DEFAULT_VISIT_RADIUS = 30
/**
* Default schedule-warning lead time in minutes.
* @return Default schedule-warning lead time in minutes.
*/
const val DEFAULT_WARN_BEFORE = 30

/**
* Represents a text or image attachment in a point, route or notes collection.
*
* Public and subclass/module-facing members:
* - [Text] - Stores a text attachment; the first point text also supplies its display name.
* - [Picture] - Stores an image filename relative to the owning data directory's images folder.
*/
sealed class PointElement {
  /**
  * Stores a text attachment; the first point text also supplies its display name.
  * @param text Full attachment text, including line breaks.
  * @property text Full attachment text, including line breaks.
  */
  data class Text(val text: String) : PointElement()
  /**
  * Stores an image filename relative to the owning data directory's images folder.
  * @param file Image filename relative to images/ in the owning data directory; not an absolute path.
  * @property file Image filename relative to images/ in the owning data directory; not an absolute path.
  */
  data class Picture(val file: String) : PointElement()
}

// One line of a schedule: the days it applies to and the time the point is open, always in the local time of the device.
// Days are Monday first; an interval never crosses midnight, such a day is written as two lines.
/**
* Defines an opening interval in device-local minutes for Monday-first weekday flags.
*
* Usage: days must contain seven Monday-first flags. Times are local minutes; split midnight-crossing intervals into separate rules.
*
* Public and subclass/module-facing members:
* - [everyDay] - Whether all stored weekday flags are enabled.
* - [valid] - Whether at least one day is enabled and the closing minute follows the opening minute.
* @param days Seven weekday flags in Monday-to-Sunday order. Default: List(7) { true }.
* @param from Opening minute since local midnight, normally in 0..1439.
* @param to Closing minute since local midnight, after from; 1440 can represent the end of the day.
* @property days Seven weekday flags in Monday-to-Sunday order. Default: List(7) { true }.
* @property from Opening minute since local midnight, normally in 0..1439.
* @property to Closing minute since local midnight, after from; 1440 can represent the end of the day.
*/
data class ScheduleRule(val days: List<Boolean> = List(7) { true }, val from: Int = 0, val to: Int = 0) {
  /**
  * Whether all stored weekday flags are enabled.
  * @return Whether all stored weekday flags are enabled.
  */
  val everyDay: Boolean get() = days.all { it }
  /**
  * Whether at least one day is enabled and the closing minute follows the opening minute.
  * @return Whether at least one day is enabled and the closing minute follows the opening minute.
  */
  val valid: Boolean get() = days.any { it } && to > from
}

/**
* Describes a point's independent, planned or active role.
*/
enum class PointStatus { 
  /** Point is not currently part of an active visit plan. */
  INDEPENDENT, 
  /** Point is planned for a future visit. */
  PLANNED, 
  /** Point is the active visit target. */
  ACTIVE }

/**
* Describes missing schedule information or an open/closed schedule state.
*/
enum class Availability { 
  /** No valid schedule state is available. */
  NONE, 
  /** The point is currently within an opening interval. */
  OPEN, 
  /** The point is outside its opening intervals. */
  CLOSED }

// What is drawn for a point with a schedule: nothing, the time until it opens, until it closes, or both.
/**
* Selects which upcoming schedule transition is displayed on the map.
*/
enum class ScheduleDisplay { 
  /** Hide schedule-transition text. */
  NONE, 
  /** Show time until opening while closed. */
  TO_OPEN, 
  /** Show time until closing while open. */
  TO_CLOSE, 
  /** Show the next opening or closing transition. */
  ALWAYS }

/**
* Immutable point snapshot containing coordinates, attachments, visibility and visit/schedule settings.
*
* Usage: Treat snapshots as immutable and apply edits with PointStore.update. Compare coordinates through GeoMath.samePlace, not exact equality.
*
* Public and subclass/module-facing members:
* - [geo] - Geographic coordinate view of this point's latitude and longitude.
* - [hasSchedule] - Whether at least one valid schedule rule exists.
* - [controlPossible] - Whether the point has a valid schedule and can support schedule control.
* - [captionOnly] - Extracts the trimmed first line of the first text attachment.
* - [fullCaption] - Chooses the point name or formatted coordinates for lists and dialogs.
* @param id Stable point identifier.
* @param lat Latitude in geographic degrees.
* @param lon Longitude in geographic degrees.
* @param alt Optional point altitude in meters. Default: null.
* @param icon Persisted icon name from PointIcons.names, or an empty string for no inner icon.
* @param info Метаинформация точки; её первый текст - имя точки.
* @param schedule Device-local weekday opening intervals; split intervals that cross midnight. Default: emptyList().
* @param visible Whether the point, route or track should be displayed. Default: true.
* @param visited Whether the point has been visited. Default: false.
* @param status Point's independent, planned or active role. Default: PointStatus.INDEPENDENT.
* @param autoVisit Whether entering the configured visit radius may mark the point visited. Default: false.
* @param visitRadius Automatic-visit radius in meters; retained while autoVisit is disabled. Default: DEFAULT_VISIT_RADIUS.
* @param scheduleDisplay Which upcoming schedule transition is displayed. Default: ScheduleDisplay.NONE.
* @param scheduleControl Whether schedule warnings are enabled for this point. Default: false.
* @param warnBefore Warning lead time in minutes; retained while scheduleControl is disabled. Default: DEFAULT_WARN_BEFORE.
* @param highlighted Runtime-only list highlight; not persisted to data files. Default: false.
* @property id Stable point identifier.
* @property lat Latitude in geographic degrees.
* @property lon Longitude in geographic degrees.
* @property alt Optional point altitude in meters. Default: null.
* @property icon Persisted icon name from PointIcons.names, or an empty string for no inner icon.
* @property info Метаинформация точки; её первый текст - имя точки.
* @property schedule Device-local weekday opening intervals; split intervals that cross midnight. Default: emptyList().
* @property visible Whether the point, route or track should be displayed. Default: true.
* @property visited Whether the point has been visited. Default: false.
* @property status Point's independent, planned or active role. Default: PointStatus.INDEPENDENT.
* @property autoVisit Whether entering the configured visit radius may mark the point visited. Default: false.
* @property visitRadius Automatic-visit radius in meters; retained while autoVisit is disabled. Default: DEFAULT_VISIT_RADIUS.
* @property scheduleDisplay Which upcoming schedule transition is displayed. Default: ScheduleDisplay.NONE.
* @property scheduleControl Whether schedule warnings are enabled for this point. Default: false.
* @property warnBefore Warning lead time in minutes; retained while scheduleControl is disabled. Default: DEFAULT_WARN_BEFORE.
* @property highlighted Runtime-only list highlight; not persisted to data files. Default: false.
*/
data class MapPoint(
  val id: Long,
  val lat: Double,
  val lon: Double,
  val alt: Double? = null,
  val icon: String = "",
  val info: MetaInfo = MetaInfo(),
  val schedule: List<ScheduleRule> = emptyList(),
  val visible: Boolean = true,
  val visited: Boolean = false,
  val status: PointStatus = PointStatus.INDEPENDENT,
  // The point marks itself visited when the position comes closer than visitRadius meters. The radius keeps its value
  // while the switch is off, so turning it on again gives back the same distance.
  val autoVisit: Boolean = false,
  val visitRadius: Int = DEFAULT_VISIT_RADIUS,
  val scheduleDisplay: ScheduleDisplay = ScheduleDisplay.NONE,
  val scheduleControl: Boolean = false,
  // Minutes before the event the point goes to alarm at; kept while the control is off, as the radius is.
  val warnBefore: Int = DEFAULT_WARN_BEFORE,
  // Runtime only, never saved: the row of this point is marked in the list until another point is marked.
  val highlighted: Boolean = false
) {
  /**
  * Geographic coordinate view of this point's latitude and longitude.
  * @return Geographic coordinate view of this point's latitude and longitude.
  */
  val geo: GeoPoint get() = GeoPoint(lat, lon)

  val elements: List<PointElement> get() = info.elements /** Сокращение для чтения; меняется через copy(info = ...) */

  /**
  * Whether at least one valid schedule rule exists.
  * @return Whether at least one valid schedule rule exists.
  */
  val hasSchedule: Boolean get() = schedule.any { it.valid }

  // The control works only with a schedule; the warning time is always set.
  /**
  * Whether the point has a valid schedule and can support schedule control.
  * @return Whether the point has a valid schedule and can support schedule control.
  */
  val controlPossible: Boolean get() = hasSchedule

  // First line of the first text element; used for the map label and the list.
  /**
  * Extracts the trimmed first line of the first text attachment.
  * @return The display name, or null when absent or blank.
  */
  fun captionOnly(): String? = info.caption()

  /**
  * Chooses the point name or formatted coordinates for lists and dialogs.
  * @return A nonnull user-facing label.
  */
  fun fullCaption(): String = captionOnly() ?: formatCoordinates(geo)
}

// Minutes of the day from "HH:mm"; null when the text is not a time.
/**
* Parses a trimmed HH:mm value into minutes since midnight.
* @param text Local time written as HH:mm; surrounding whitespace is ignored.
* @return Minutes in 0..1439, or null for an invalid value.
*/
fun parseTime(text: String): Int? {
  val parts = text.trim().split(':')
  if (parts.size != 2) return null
  val hours = parts[0].toIntOrNull() ?: return null
  val minutes = parts[1].toIntOrNull() ?: return null
  if (hours !in 0..23 || minutes !in 0..59) return null
  return hours * 60 + minutes
}

/**
* Formats a minute count into zero-padded hours and minutes.
*
* Usage: Supply a nonnegative minute count; hours wrap modulo 24.
* @param minutes Nonnegative minutes to format; hours wrap modulo 24.
* @return An HH:mm string using Locale.ROOT.
*/
fun formatTime(minutes: Int): String = String.format(Locale.ROOT, "%02d:%02d", minutes / 60 % 24, minutes % 60)
