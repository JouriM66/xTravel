// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.edit

/**
* Stores an observable, persisted setting with a typed default and change listeners.
*
* Usage: Initialize Settings first. Assigning value persists and notifies listeners immediately; load changes state without notifying listeners.
*
* Public and subclass/module-facing members:
* - [value] - Observable typed preference; assignment persists a changed value and invokes registered listeners synchronously.
* - [onChange] - Registers a listener invoked synchronously after a changed value is persisted.
* - [load] - Loads a stored preference into observable state if present and readable.
* - [read] - Reads the typed value from a preference store.
* - [write] - Adds the typed value to the supplied preference editor.
* - [export] - Formats the current value for text export.
* @param T Value type represented by this setting.
* @param key Unique SharedPreferences key for this setting.
* @param default Typed fallback and initial observable value.
* @property key Unique SharedPreferences key for this setting.
* @property default Typed fallback and initial observable value.
*/
abstract class Setting<T>(val key: String, val default: T) {

  private val state = mutableStateOf(default)
  private val listeners = mutableListOf<(T) -> Unit>()

  /**
  * Observable typed preference; assignment persists a changed value and invokes registered listeners synchronously.
  * @return Observable typed preference; assignment persists a changed value and invokes registered listeners synchronously.
  */
  var value: T
    get() = state.value
    set(newValue) {
      if (newValue == state.value) return
      state.value = newValue
      Settings.prefs.edit { write(this, newValue) }
      listeners.forEach { it(newValue) }
    }

  /**
  * Registers a listener invoked synchronously after a changed value is persisted.
  * @param listener Callback invoked synchronously with the new value after a changed setting is persisted.
  * @return Unit; no immediate callback or unsubscribe handle is provided.
  */
  fun onChange(listener: (T) -> Unit) {
    listeners += listener
  }

  /**
  * Loads a stored preference into observable state if present and readable.
  * @param prefs SharedPreferences store to read.
  * @return Unit; loading does not notify change listeners.
  */
  fun load(prefs: SharedPreferences) {
    if (prefs.contains(key)) runCatching { state.value = read(prefs) }
  }

  /**
  * Reads the typed value from a preference store.
  * @param prefs SharedPreferences store to read.
  * @return The stored value or the implementation's fallback.
  */
  protected abstract fun read(prefs: SharedPreferences): T
  /**
  * Adds the typed value to the supplied preference editor.
  * @param editor Caller-owned preference editor receiving the typed value; the caller applies or commits it.
  * @param value Value to display, persist or substitute as described by the operation.
  * @return Unit; the caller owns applying the editor.
  */
  protected abstract fun write(editor: SharedPreferences.Editor, value: T)
  /**
  * Formats the current value for text export.
  * @return A type-specific string representation.
  */
  abstract fun export(): String
}

/**
* Persists integer settings using SharedPreferences.
*
* Public and subclass/module-facing members:
* - [read] - Reads the typed value from a preference store. Uses the IntSetting representation.
* - [write] - Adds the typed value to the supplied preference editor. Uses the IntSetting representation.
* - [export] - Formats the current value for text export. Uses the IntSetting representation.
*
* Inherited API: see [Setting] for members not overridden here.
* @param key Stable identifier used to persist or retain the associated state.
* @param default Fallback value used when the preference has no usable stored value.
*/
class IntSetting(key: String, default: Int) : Setting<Int>(key, default) {
  /**
  * Reads the typed value from a preference store. Uses the IntSetting representation.
  * @param prefs SharedPreferences store to read.
  * @return The stored value or the implementation's fallback.
  */
  override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)
  /**
  * Adds the typed value to the supplied preference editor. Uses the IntSetting representation.
  * @param editor Caller-owned preference editor receiving the typed value; the caller applies or commits it.
  * @param value Value to display, persist or substitute as described by the operation.
  * @return Unit; the caller owns applying the editor.
  */
  override fun write(editor: SharedPreferences.Editor, value: Int) {
    editor.putInt(key, value)
  }
  /**
  * Formats the current value for text export. Uses the IntSetting representation.
  * @return A type-specific string representation.
  */
  override fun export() = value.toString()
}

/**
* Persists Boolean settings using SharedPreferences.
*
* Public and subclass/module-facing members:
* - [read] - Reads the typed value from a preference store. Uses the BoolSetting representation.
* - [write] - Adds the typed value to the supplied preference editor. Uses the BoolSetting representation.
* - [export] - Formats the current value for text export. Uses the BoolSetting representation.
*
* Inherited API: see [Setting] for members not overridden here.
* @param key Stable identifier used to persist or retain the associated state.
* @param default Fallback value used when the preference has no usable stored value.
*/
class BoolSetting(key: String, default: Boolean) : Setting<Boolean>(key, default) {
  /**
  * Reads the typed value from a preference store. Uses the BoolSetting representation.
  * @param prefs SharedPreferences store to read.
  * @return The stored value or the implementation's fallback.
  */
  override fun read(prefs: SharedPreferences) = prefs.getBoolean(key, default)
  /**
  * Adds the typed value to the supplied preference editor. Uses the BoolSetting representation.
  * @param editor Caller-owned preference editor receiving the typed value; the caller applies or commits it.
  * @param value Value to display, persist or substitute as described by the operation.
  * @return Unit; the caller owns applying the editor.
  */
  override fun write(editor: SharedPreferences.Editor, value: Boolean) {
    editor.putBoolean(key, value)
  }
  /**
  * Formats the current value for text export. Uses the BoolSetting representation.
  * @return A type-specific string representation.
  */
  override fun export() = value.toString()
}

/**
* Persists string settings using SharedPreferences.
*
* Public and subclass/module-facing members:
* - [read] - Reads the typed value from a preference store. Uses the StringSetting representation.
* - [write] - Adds the typed value to the supplied preference editor. Uses the StringSetting representation.
* - [export] - Formats the current value for text export. Uses the StringSetting representation.
*
* Inherited API: see [Setting] for members not overridden here.
* @param key Stable identifier used to persist or retain the associated state.
* @param default Fallback value used when the preference has no usable stored value.
*/
class StringSetting(key: String, default: String) : Setting<String>(key, default) {
  /**
  * Reads the typed value from a preference store. Uses the StringSetting representation.
  * @param prefs SharedPreferences store to read.
  * @return The stored value or the implementation's fallback.
  */
  override fun read(prefs: SharedPreferences) = prefs.getString(key, default) ?: default
  /**
  * Adds the typed value to the supplied preference editor. Uses the StringSetting representation.
  * @param editor Caller-owned preference editor receiving the typed value; the caller applies or commits it.
  * @param value Value to display, persist or substitute as described by the operation.
  * @return Unit; the caller owns applying the editor.
  */
  override fun write(editor: SharedPreferences.Editor, value: String) {
    editor.putString(key, value)
  }
  /**
  * Formats the current value for text export. Uses the StringSetting representation.
  * @return A type-specific string representation.
  */
  override fun export() = value
}

// ARGB color.
/**
* Persists packed ARGB colors and exports them as eight-digit hexadecimal strings.
*
* Public and subclass/module-facing members:
* - [read] - Reads the typed value from a preference store. Uses the ColorSetting representation.
* - [write] - Adds the typed value to the supplied preference editor. Uses the ColorSetting representation.
* - [export] - Formats the current value for text export. Uses the ColorSetting representation.
*
* Inherited API: see [Setting] for members not overridden here.
* @param key Stable identifier used to persist or retain the associated state.
* @param default Fallback value used when the preference has no usable stored value.
*/
class ColorSetting(key: String, default: Long) : Setting<Int>(key, default.toInt()) {
  /**
  * Reads the typed value from a preference store. Uses the ColorSetting representation.
  * @param prefs SharedPreferences store to read.
  * @return The stored value or the implementation's fallback.
  */
  override fun read(prefs: SharedPreferences) = prefs.getInt(key, default)
  /**
  * Adds the typed value to the supplied preference editor. Uses the ColorSetting representation.
  * @param editor Caller-owned preference editor receiving the typed value; the caller applies or commits it.
  * @param value Value to display, persist or substitute as described by the operation.
  * @return Unit; the caller owns applying the editor.
  */
  override fun write(editor: SharedPreferences.Editor, value: Int) {
    editor.putInt(key, value)
  }
  /**
  * Formats the current value for text export. Uses the ColorSetting representation.
  * @return A type-specific string representation.
  */
  override fun export() = "#%08X".format(value)
}

/**
* Persists an enum constant by name and falls back to its default for an unknown stored name.
*
* Public and subclass/module-facing members:
* - [read] - Reads the typed value from a preference store. Uses the EnumSetting representation.
* - [write] - Adds the typed value to the supplied preference editor. Uses the EnumSetting representation.
* - [export] - Formats the current value for text export. Uses the EnumSetting representation.
*
* Inherited API: see [Setting] for members not overridden here.
* @param E Enum type used by this setting.
* @param key Stable identifier used to persist or retain the associated state.
* @param default Fallback value used when the preference has no usable stored value.
* @param entries All supported enum constants used to resolve persisted names.
*/
class EnumSetting<E : Enum<E>>(key: String, default: E, private val entries: List<E>) : Setting<E>(key, default) {
  /**
  * Reads the typed value from a preference store. Uses the EnumSetting representation.
  * @param prefs SharedPreferences store to read.
  * @return The stored value or the implementation's fallback.
  */
  override fun read(prefs: SharedPreferences) = entries.firstOrNull { it.name == prefs.getString(key, null) } ?: default
  /**
  * Adds the typed value to the supplied preference editor. Uses the EnumSetting representation.
  * @param editor Caller-owned preference editor receiving the typed value; the caller applies or commits it.
  * @param value Value to display, persist or substitute as described by the operation.
  * @return Unit; the caller owns applying the editor.
  */
  override fun write(editor: SharedPreferences.Editor, value: E) {
    editor.putString(key, value.name)
  }
  /**
  * Formats the current value for text export. Uses the EnumSetting representation.
  * @return A type-specific string representation.
  */
  override fun export() = value.name
}

// Global settings. Value 0 of a numeric setting switches the parameter off.
/**
* Registers application preferences and initializes their typed observable values.
*
* Usage: Numeric zero disables the corresponding optional threshold where documented. Do not include map API keys in exported preferences.
*
* Public and subclass/module-facing members:
* - [prefs] - SharedPreferences backing store; initialized by Settings.init and externally read-only.
* - [useGps] - Persisted switch controlling GPS collection.
* - [showSpeed] - Persisted switch controlling the toolbar speed display.
* - [notifyMode] - Persisted delivery mode for grouped ordinary messages.
* - [askExternalSearch] - Persisted flag requiring confirmation before external geocoding.
* - [mapDirection] - Persisted north-up, automatic or manual map-direction choice.
* - [map3d] - Persisted switch enabling the provider's 3D map presentation.
* - [mapType] - Persisted map-background provider selection.
* - [keepScreenOn] - Persisted flag keeping the activity display awake.
* - [imageQuality] - JPEG quality pictures from the camera and the gallery are written with; 100 keeps the original file.
* - [mapkitKey] - User-supplied MapKit API key; excluded from settings export.
* - [geocoderKey] - User-supplied HTTP geocoder API key; excluded from settings export.
* - [geocoderResults] - Requested number of HTTP geocoder results.
* - [rings] - Number of distance rings around the GPS position.
* - [ringStepValue] - Spacing between position rings in meters.
* - [autoPosition] - Idle delay in seconds before restoring GPS following; zero disables it.
* - [filterMode] - Persisted stable identifier of the selected GPS filter.
* - [filterAccuracy] - Maximum accepted horizontal error in meters; zero disables this check.
* - [filterStationarySpeed] - Stationary-speed threshold in kilometers per hour; zero disables this check.
* - [filterMinDistance] - Minimum separation of accepted GPS positions in meters; zero disables this check.
* - [filterMaxSpeed] - Maximum implied displacement speed in kilometers per hour; zero disables this check.
* - [minPoints] - Minimum recording sample count before creating paired track files.
* - [tailPoints] - Maximum visible current-track tail length in samples; zero retains the full line.
* - [currentTrackWidth] - Current-track line width in dp.
* - [currentTrackColor] - Packed ARGB color of the current-track line.
* - [savedTrackWidth] - Saved-track line width in dp.
* - [savedTrackColor] - Packed ARGB color of saved-track lines.
* - [pointIndependentColor] - Packed ARGB pin color for independent points.
* - [pointPlannedColor] - Packed ARGB pin color for planned points.
* - [pointActiveColor] - Packed ARGB pin color for active points.
* - [pointVisitedColor] - Packed ARGB pin color for visited points.
* - [pointAlarmColor] - Packed ARGB pin color for points in schedule alarm.
* - [availabilityOpenColor] - Packed ARGB availability halo color for open points.
* - [availabilityClosedColor] - Packed ARGB availability halo color for closed points.
* - [timeToOpenColor] - Packed ARGB text color for time until opening.
* - [timeToCloseColor] - Packed ARGB text color for time until closing.
* - [pointNameSize] - Point-name font size in sp; nonpositive values suppress outlined text drawing.
* - [pointTimeSize] - Schedule-time font size in sp; nonpositive values suppress outlined text drawing.
* - [routeTransport] - Way of travelling the route requests are made for.
* - [routeMarkPoints] - Whether a point added to a route gets automatic visiting switched on.
* - [routeActiveColor] - Packed ARGB color of the ways chosen for their legs.
* - [routeInactiveColor] - Packed ARGB color of the ways that were not chosen.
* - [routeAutoCalculate] - Whether a changed list of stops is built again without asking.
* - [routeAutoSort] - Whether a changed list of stops is sorted by itself.
* - [routeArrivalRadius] - Радиус прибытия навигации к остановке, м.
* - [routeShowManeuver] - Показывать направление следующего манёвра при навигации.
* - [miuiConfirmed] - Persisted acknowledgement of MIUI background settings, reset when GPS is enabled again.
* - [init] - Opens preferences, loads registered settings and migrates the former north-up option.
* - [export] - Exports registered settings as key=value lines, excluding personal map API keys.
*/
object Settings {

  /**
  * SharedPreferences backing store; initialized by Settings.init and externally read-only.
  * @return SharedPreferences backing store; initialized by Settings.init and externally read-only.
  */
  lateinit var prefs: SharedPreferences
    private set

  private val all = mutableListOf<Setting<*>>()

  private fun <S : Setting<*>> add(setting: S): S = setting.also { all += it }

  /**
  * Persisted switch controlling GPS collection.
  * @return Persisted switch controlling GPS collection.
  */
  val useGps = add(BoolSetting("use_gps", true))
  /**
  * Persisted switch controlling the toolbar speed display.
  * @return Persisted switch controlling the toolbar speed display.
  */
  val showSpeed = add(BoolSetting("show_speed", true))
  /**
  * Persisted delivery mode for grouped ordinary messages.
  * @return Persisted delivery mode for grouped ordinary messages.
  */
  val notifyMode = add(EnumSetting("notify_mode", NotifyMode.TOAST, NotifyMode.entries))
  /**
  * Persisted flag requiring confirmation before external geocoding.
  * @return Persisted flag requiring confirmation before external geocoding.
  */
  val askExternalSearch = add(BoolSetting("ask_external_search", true))
  /**
  * Persisted north-up, automatic or manual map-direction choice.
  * @return Persisted north-up, automatic or manual map-direction choice.
  */
  val mapDirection = add(EnumSetting("map_direction", MapDirection.MANUAL, MapDirection.entries))
  /**
  * Persisted switch enabling the provider's 3D map presentation.
  * @return Persisted switch enabling the provider's 3D map presentation.
  */
  val map3d = add(BoolSetting("map_3d", false))
  /**
  * Persisted map-background provider selection.
  * @return Persisted map-background provider selection.
  */
  val mapType = add(EnumSetting("map_type", MapType.YANDEX, MapType.entries))
  /**
  * Persisted flag keeping the activity display awake.
  * @return Persisted flag keeping the activity display awake.
  */
  val keepScreenOn = add(BoolSetting("keep_screen_on", true))
  /**
  * JPEG quality pictures from the camera and the gallery are written with; 100 keeps the original file.
  * @return JPEG quality pictures from the camera and the gallery are written with; 100 keeps the original file.
  */
  val imageQuality = add(IntSetting("image_quality", 80))

  /**
  * User-supplied MapKit API key; excluded from settings export.
  * @return User-supplied MapKit API key; excluded from settings export.
  */
  val mapkitKey = add(StringSetting("mapkit_key", ""))
  /**
  * User-supplied HTTP geocoder API key; excluded from settings export.
  * @return User-supplied HTTP geocoder API key; excluded from settings export.
  */
  val geocoderKey = add(StringSetting("geocoder_key", ""))
  /**
  * Requested number of HTTP geocoder results.
  * @return Requested number of HTTP geocoder results.
  */
  val geocoderResults = add(IntSetting("geocoder_results", 5))

  /**
  * Number of distance rings around the GPS position.
  * @return Number of distance rings around the GPS position.
  */
  val rings = add(IntSetting("position_rings", 3))
  /**
  * Spacing between position rings in meters.
  * @return Spacing between position rings in meters.
  */
  val ringStepValue = add(IntSetting("position_ring_step_m", 10))
  /**
  * Idle delay in seconds before restoring GPS following; zero disables it.
  * @return Idle delay in seconds before restoring GPS following; zero disables it.
  */
  val autoPosition = add(IntSetting("auto_position_s", 0))

  /**
  * Persisted stable identifier of the selected GPS filter.
  * @return Persisted stable identifier of the selected GPS filter.
  */
  val filterMode = add(StringSetting("gps_filter", GpsFilter_Simple.name()))
  /**
  * Maximum accepted horizontal error in meters; zero disables this check.
  * @return Maximum accepted horizontal error in meters; zero disables this check.
  */
  val filterAccuracy = add(IntSetting("gps_filter_accuracy_m", 5))
  /**
  * Stationary-speed threshold in kilometers per hour; zero disables this check.
  * @return Stationary-speed threshold in kilometers per hour; zero disables this check.
  */
  val filterStationarySpeed = add(IntSetting("gps_filter_stationary_kmh", 1))
  /**
  * Minimum separation of accepted GPS positions in meters; zero disables this check.
  * @return Minimum separation of accepted GPS positions in meters; zero disables this check.
  */
  val filterMinDistance = add(IntSetting("gps_filter_min_distance_m", 1))
  /**
  * Maximum implied displacement speed in kilometers per hour; zero disables this check.
  * @return Maximum implied displacement speed in kilometers per hour; zero disables this check.
  */
  val filterMaxSpeed = add(IntSetting("gps_filter_max_speed_kmh", 300))

  /**
  * Minimum recording sample count before creating paired track files.
  * @return Minimum recording sample count before creating paired track files.
  */
  val minPoints = add(IntSetting("track_min_points", 3))
  /**
  * Maximum visible current-track tail length in samples; zero retains the full line.
  * @return Maximum visible current-track tail length in samples; zero retains the full line.
  */
  val tailPoints = add(IntSetting("track_tail_points", 0))
  /**
  * Current-track line width in dp.
  * @return Current-track line width in dp.
  */
  val currentTrackWidth = add(IntSetting("track_current_width_dp", 3))
  /**
  * Packed ARGB color of the current-track line.
  * @return Packed ARGB color of the current-track line.
  */
  val currentTrackColor = add(ColorSetting("track_current_color", 0xFF53A600))
  /**
  * Saved-track line width in dp.
  * @return Saved-track line width in dp.
  */
  val savedTrackWidth = add(IntSetting("track_saved_width_dp", 2))
  /**
  * Packed ARGB color of saved-track lines.
  * @return Packed ARGB color of saved-track lines.
  */
  val savedTrackColor = add(ColorSetting("track_saved_color", 0xFFFF0080))

  /**
  * Packed ARGB pin color for independent points.
  * @return Packed ARGB pin color for independent points.
  */
  val pointIndependentColor = add(ColorSetting("point_color_independent", 0xFF59ACFF))
  /**
  * Packed ARGB pin color for planned points.
  * @return Packed ARGB pin color for planned points.
  */
  val pointPlannedColor = add(ColorSetting("point_color_planned", 0xFF00A600))
  /**
  * Packed ARGB pin color for active points.
  * @return Packed ARGB pin color for active points.
  */
  val pointActiveColor = add(ColorSetting("point_color_active", 0xB100FF00))
  /**
  * Packed ARGB pin color for visited points.
  * @return Packed ARGB pin color for visited points.
  */
  val pointVisitedColor = add(ColorSetting("point_color_visited", 0xFF8C8C8C))
  /**
  * Packed ARGB pin color for points in schedule alarm.
  * @return Packed ARGB pin color for points in schedule alarm.
  */
  val pointAlarmColor = add(ColorSetting("point_color_alarm", 0xFFE24B4A))
  /**
  * Packed ARGB availability halo color for open points.
  * @return Packed ARGB availability halo color for open points.
  */
  val availabilityOpenColor = add(ColorSetting("point_color_open", 0xFFD2FFA6))
  /**
  * Packed ARGB availability halo color for closed points.
  * @return Packed ARGB availability halo color for closed points.
  */
  val availabilityClosedColor = add(ColorSetting("point_color_closed", 0xFFFFA6A6))
  /**
  * Packed ARGB text color for time until opening.
  * @return Packed ARGB text color for time until opening.
  */
  val timeToOpenColor = add(ColorSetting("point_color_time_to_open", 0xFFA60000))
  /**
  * Packed ARGB text color for time until closing.
  * @return Packed ARGB text color for time until closing.
  */
  val timeToCloseColor = add(ColorSetting("point_color_time_to_close", 0xFF53A600))
  /**
  * Point-name font size in sp; nonpositive values suppress outlined text drawing.
  * @return Point-name font size in sp; nonpositive values suppress outlined text drawing.
  */
  val pointNameSize = add(IntSetting("point_name_size_sp", 14))
  /**
  * Schedule-time font size in sp; nonpositive values suppress outlined text drawing.
  * @return Schedule-time font size in sp; nonpositive values suppress outlined text drawing.
  */
  val pointTimeSize = add(IntSetting("point_time_size_sp", 20))

  /**
  * Way of travelling the route requests are made for.
  * @return Way of travelling the route requests are made for.
  */
  val routeTransport = add(EnumSetting("route_transport", TransportKind.WALK, TransportKind.entries))
  /**
  * Whether a point added to a route gets automatic visiting switched on.
  * @return Whether a point added to a route gets automatic visiting switched on.
  */
  val routeMarkPoints = add(BoolSetting("route_mark_points", true))
  /**
  * Packed ARGB color of the ways chosen for their legs.
  * @return Packed ARGB color of the ways chosen for their legs.
  */
  val routeActiveColor = add(ColorSetting("route_color_active", 0xAF32E632))
  /**
  * Packed ARGB color of the ways that were not chosen.
  * @return Packed ARGB color of the ways that were not chosen.
  */
  val routeInactiveColor = add(ColorSetting("route_color_inactive", 0x9B0080FF))
  /**
  * Whether a changed list of stops is built again without asking.
  * @return Whether a changed list of stops is built again without asking.
  */
  val routeAutoCalculate = add(BoolSetting("route_auto_calculate", true))
  /**
  * Whether a changed list of stops is sorted by itself.
  * @return Whether a changed list of stops is sorted by itself.
  */
  val routeAutoSort = add(BoolSetting("route_auto_sort", true))
  /** Радиус прибытия к остановке, м: действует больший из него и радиуса автопосещения точки */
  val routeArrivalRadius = add(IntSetting("route_arrival_radius", 30))
  val routeShowManeuver = add(BoolSetting("route_show_maneuver", true)) /** Направление следующего манёвра в тулбаре навигации */

  // Set once the user confirmed the MIUI background settings; reset when GPS is switched on again.
  /**
  * Persisted acknowledgement of MIUI background settings, reset when GPS is enabled again.
  * @return Persisted acknowledgement of MIUI background settings, reset when GPS is enabled again.
  */
  val miuiConfirmed = add(BoolSetting("miui_confirmed", true))

  /**
  * Opens preferences, loads registered settings and migrates the former north-up option.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @return Unit; initialize before accessing prefs or changing values.
  */
  fun init(context: Context) {
    prefs = context.getSharedPreferences("xtravel", Context.MODE_PRIVATE)
    all.forEach { it.load(prefs) }
    // The former switch "North up": off meant the manual direction.
    if (!prefs.contains(mapDirection.key) && !prefs.getBoolean("north_up", true)) mapDirection.value = MapDirection.MANUAL
  }

  // Lines "key=value" for moving the values into the defaults; the personal keys stay out.
  /**
  * Exports registered settings as key=value lines, excluding personal map API keys.
  * @return A newline-terminated settings string.
  */
  fun export(): String = (all - setOf(mapkitKey, geocoderKey)).joinToString("\n", postfix = "\n") { "${it.key}=${it.export()}" }
}
