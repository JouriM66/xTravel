// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

private val FIELD_WIDTH = 96.dp
private val ROW_SPACING = 8.dp

/** Свойства точки полноэкранным модальным окном. Правится копия, она применяется по "OK";
    отмена и "назад" с правками спрашивают. Удаление точки закрывает окно.
*/
fun openPointProperties(id: Long) {
  val draft = PointDraft(PointStore.find(id) ?: return)
  ModalScreen.open(PROPERTIES_OWNER) { PointPropertiesScreen(id, draft) }
}

private const val PROPERTIES_OWNER = "point_properties" /** Владелец модального окна свойств. FOR LOCAL USE */

/** Окно свойств: неподвижный заголовок с именем точки и кнопками, под ним прокручиваемые свойства. FOR LOCAL USE */
@Composable
private fun PointPropertiesScreen(id: Long, draft: PointDraft) {
  val point = PointStore.find(id)
  LaunchedEffect(point == null) { if (point == null) ModalScreen.close() }
  if (point == null) return
  val cancel = {
    if (draft.changed(PointStore.find(id))) AppDialog.confirm(R.string.discard_changes_confirm) { ModalScreen.close() } else ModalScreen.close()
  }
  BackHandler(onBack = cancel)
  Column(Modifier.fillMaxSize()) {
    ModalHeader(point.fullCaption()) {
      ToolbarItem(Icons.Outlined.Check, R.string.ok) {
        draft.applyTo(id)
        ModalScreen.close()
      }
      ToolbarItem(Icons.Outlined.Close, R.string.cancel, tint = DELETE_ICON_COLOR, onClick = cancel)
    }
    PointProperties(draft, Modifier.weight(1f))
  }
}

// Editable copy of the point: text fields keep what is typed, empty means the setting is not used.
private class PointDraft(point: MapPoint) {

  var lat by mutableStateOf(point.lat)
  var lon by mutableStateOf(point.lon)
  var icon by mutableStateOf(point.icon)
  var visited by mutableStateOf(point.visited)
  var status by mutableStateOf(point.status)
  var autoVisit by mutableStateOf(point.autoVisit)
  var radius by mutableStateOf(point.visitRadius.toString())
  val rules = mutableStateListOf<ScheduleRule>().apply { addAll(point.schedule) }
  var display by mutableStateOf(point.scheduleDisplay)
  var control by mutableStateOf(point.scheduleControl)
  var warn by mutableStateOf<Int?>(point.warnBefore)

  val hasSchedule: Boolean get() = rules.any { it.valid }

  // Nothing is checked here: the editor writes what is set, the checks live where the control is switched.
  fun applied(point: MapPoint) = point.copy(
    lat = lat,
    lon = lon,
    icon = icon,
    visited = visited,
    status = status,
    autoVisit = autoVisit,
    visitRadius = radius.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_VISIT_RADIUS,
    schedule = rules.toList(),
    scheduleDisplay = display,
    scheduleControl = control,
    warnBefore = warn ?: DEFAULT_WARN_BEFORE
  )

  fun changed(point: MapPoint?) = point != null && applied(point) != point

  fun applyTo(id: Long) = PointStore.update(id) { applied(it) }
}

@Composable
private fun PointProperties(draft: PointDraft, modifier: Modifier) {
  val scroll = rememberScrollState()
  Column(modifier.fillMaxWidth().scrollIndicator(scroll).verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
    CoordinatesRow(draft)
    IconRow(draft)
    CheckRow(R.string.visited, draft.visited) { draft.visited = it }
    StatusRow(draft)
    CheckRow(R.string.auto_visit, draft.autoVisit) { draft.autoVisit = it }
    if (draft.autoVisit) {
      NumberRow(R.string.auto_visit_radius, draft.radius) { draft.radius = it.filter(Char::isDigit) }
    }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    ScheduleSection(draft)
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    DisplayRow(draft)
    CheckRow(R.string.schedule_control, draft.control) { draft.control = it }
    if (draft.control) {
      TimeRow(R.string.warn_before, draft.warn) { draft.warn = it }
    }
  }
}

// Place of the point: the tap opens the coordinate dialog, the point moves when the properties are applied.
@Composable
private fun CoordinatesRow(draft: PointDraft) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable {
        PointDialogs.coordinates(GeoPoint(draft.lat, draft.lon)) { place ->
          draft.lat = place.lat
          draft.lon = place.lon
        }
      }
      .padding(vertical = ROW_SPACING),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(stringResource(R.string.coordinates), modifier = Modifier.weight(1f))
    Text(formatCoordinates(GeoPoint(draft.lat, draft.lon)), style = MaterialTheme.typography.titleMedium)
  }
}

// Current icon in a frame; the chooser is a square grid where the first cell means "no icon".
@Composable
private fun IconRow(draft: PointDraft) {
  var open by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().padding(vertical = ROW_SPACING), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.icon), modifier = Modifier.weight(1f))
    Box {
      IconCell(draft.icon, framed = true) { open = true }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Column(Modifier.padding(4.dp)) {
          val cells = listOf("") + PointIcons.names
          cells.chunked(PointIcons.GRID).forEach { row ->
            Row {
              row.forEach { name ->
                IconCell(name, framed = name == draft.icon) {
                  draft.icon = name
                  open = false
                }
              }
            }
          }
        }
      }
    }
  }
}

// Icon of the set on the color of a pin, so it reads as it will on the map; "none" is an empty cell.
@Composable
private fun IconCell(name: String, framed: Boolean, onClick: () -> Unit) {
  val shape = RoundedCornerShape(6.dp)
  val vector = PointIcons.vector(name)
  Box(
    modifier = Modifier
      .padding(2.dp)
      .size(40.dp)
      .background(if (vector == null) MaterialTheme.colorScheme.surfaceContainerHighest else Color(Settings.pointIndependentColor.value), shape)
      .border(if (framed) 2.dp else 1.dp, if (framed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      vector ?: Icons.Outlined.Block,
      contentDescription = null,
      tint = if (vector == null) MaterialTheme.colorScheme.onSurfaceVariant else PointIcons.ICON_COLOR,
      modifier = Modifier.size(24.dp)
    )
  }
}

// Status of the point: the pin of the status with its name, the tap takes the next one.
@Composable
private fun StatusRow(draft: PointDraft) {
  val point = MapPoint(0, 0.0, 0.0, status = draft.status, visited = draft.visited, icon = draft.icon)
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { draft.status = PointStatus.entries[(draft.status.ordinal + 1) % PointStatus.entries.size] }
      .padding(vertical = ROW_SPACING),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(stringResource(R.string.status), modifier = Modifier.weight(1f))
    PointPin(point)
    Spacer(Modifier.width(8.dp))
    Text(stringResource(statusLabel(draft.status)), style = MaterialTheme.typography.titleMedium)
  }
}

@StringRes
private fun statusLabel(status: PointStatus) = when (status) {
  PointStatus.INDEPENDENT -> R.string.status_independent
  PointStatus.PLANNED -> R.string.status_planned
  PointStatus.ACTIVE -> R.string.status_active
}

// Opening hours: lines of "days, from, to", collapsed at first; the header carries the alarm of the point.
@Composable
private fun ScheduleSection(draft: PointDraft) {
  var open by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = ROW_SPACING),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    Spacer(Modifier.width(8.dp))
    Text(stringResource(R.string.opening_hours), modifier = Modifier.weight(1f))
    AlarmIcon(draft.hasSchedule, draft.control && draft.warn != null)
  }
  if (!open) return
  draft.rules.forEachIndexed { index, rule ->
    ScheduleRow(rule, onChange = { draft.rules[index] = it }, onDelete = { draft.rules.removeAt(index) })
  }
  Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
    Box(Modifier.clickable { draft.rules.add(ScheduleRule(from = 9 * 60, to = 18 * 60)) }.padding(8.dp)) {
      Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.add))
    }
  }
}

@Composable
private fun ScheduleRow(rule: ScheduleRule, onChange: (ScheduleRule) -> Unit, onDelete: () -> Unit) {
  var days by remember { mutableStateOf(false) }
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.weight(1f)) {
      Box(Modifier.fillMaxWidth().clickable { days = true }.padding(vertical = 8.dp)) { Text(daysText(rule)) }
      DropdownMenu(expanded = days, onDismissRequest = { days = false }) {
        val names = dayNames()
        names.forEachIndexed { index, name ->
          MenuItem(if (rule.days[index]) Icons.Outlined.Check else null, name) {
            onChange(rule.copy(days = rule.days.mapIndexed { i, on -> if (i == index) !on else on }))
          }
        }
      }
    }
    TimeField(rule.from) { onChange(rule.copy(from = it)) }
    Text("—", modifier = Modifier.padding(horizontal = 4.dp))
    TimeField(rule.to) { onChange(rule.copy(to = it)) }
    Box(Modifier.clickable(onClick = onDelete).padding(8.dp)) {
      Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.delete))
    }
  }
}

@Composable
private fun daysText(rule: ScheduleRule): String {
  if (rule.everyDay) return stringResource(R.string.days_every)
  val names = dayNames()
  return rule.days.mapIndexedNotNull { index, on -> names[index].takeIf { on } }.joinToString(", ").ifEmpty { stringResource(R.string.days_none) }
}

@Composable
private fun dayNames() = listOf(
  stringResource(R.string.day_mon),
  stringResource(R.string.day_tue),
  stringResource(R.string.day_wed),
  stringResource(R.string.day_thu),
  stringResource(R.string.day_fri),
  stringResource(R.string.day_sat),
  stringResource(R.string.day_sun)
)

@Composable
private fun TimeField(minutes: Int, onPick: (Int) -> Unit) {
  val shape = RoundedCornerShape(6.dp)
  Box(
    modifier = Modifier
      .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
      .clickable { pickTime(minutes, onPick) }
      .padding(horizontal = 10.dp, vertical = 8.dp)
  ) {
    Text(formatTime(minutes))
  }
}

// Hours and minutes typed from the keyboard, the standard Material control.
@OptIn(ExperimentalMaterial3Api::class)
private fun pickTime(minutes: Int, onPick: (Int) -> Unit) = AppDialog.show {
  val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60, is24Hour = true)
  AlertDialog(
    onDismissRequest = AppDialog::close,
    icon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
    text = { TimeInput(state) },
    confirmButton = {
      DialogButton(Icons.Outlined.Check, R.string.ok, primary = true) {
        AppDialog.close()
        onPick(state.hour * 60 + state.minute)
      }
    },
    dismissButton = { CancelButton(AppDialog::close) }
  )
}

// Choice of what is drawn near the pin of the point.
@Composable
private fun DisplayRow(draft: PointDraft) {
  var open by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(8.dp)
  Row(Modifier.fillMaxWidth().padding(vertical = ROW_SPACING), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(R.string.schedule_display), modifier = Modifier.weight(1f).padding(end = 8.dp))
    Box {
      Row(
        modifier = Modifier
          .border(1.dp, MaterialTheme.colorScheme.outline, shape)
          .clickable { open = true }
          .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(stringResource(displayLabel(draft.display)))
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
      }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        ScheduleDisplay.entries.forEach { value ->
          ChoiceMenuItem(stringResource(displayLabel(value)), value == draft.display) {
            draft.display = value
            open = false
          }
        }
      }
    }
  }
}

@StringRes
private fun displayLabel(display: ScheduleDisplay) = when (display) {
  ScheduleDisplay.NONE -> R.string.display_none
  ScheduleDisplay.TO_OPEN -> R.string.display_to_open
  ScheduleDisplay.TO_CLOSE -> R.string.display_to_close
  ScheduleDisplay.ALWAYS -> R.string.display_always
}

@Composable
private fun CheckRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Checkbox(checked = checked, onCheckedChange = onChange)
    Text(stringResource(label), modifier = Modifier.padding(start = 4.dp))
  }
}

@Composable
private fun NumberRow(@StringRes label: Int, value: String, onChange: (String) -> Unit) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), modifier = Modifier.weight(1f).padding(end = 8.dp))
    OutlinedTextField(
      value = value,
      onValueChange = onChange,
      singleLine = true,
      modifier = Modifier.width(FIELD_WIDTH),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
  }
}

// Time value that may be unset: an empty field until it is picked.
@Composable
private fun TimeRow(@StringRes label: Int, minutes: Int?, onPick: (Int?) -> Unit) {
  val shape = RoundedCornerShape(6.dp)
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), modifier = Modifier.weight(1f).padding(end = 8.dp))
    Box(
      modifier = Modifier
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable { pickTime(minutes ?: 0) { onPick(it.takeIf { value -> value > 0 }) } }
        .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
      Text(minutes?.let(::formatTime) ?: stringResource(R.string.time_hint), color = if (minutes == null) MaterialTheme.colorScheme.outline else Color.Unspecified)
    }
  }
}
