// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.TextButton
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Bright palette: 12 hues in 4 shades (pale, light, pure, dark) and a row of greys from white to black.
private val PALETTE: List<Int> = buildList {
  listOf(0.35f to 1f, 0.65f to 1f, 1f to 1f, 1f to 0.65f).forEach { (saturation, value) ->
    for (i in 0 until 12) add(android.graphics.Color.HSVToColor(floatArrayOf(i * 30f, saturation, value)) and 0xFFFFFF)
  }
  for (i in 0 until 12) {
    val grey = 255 - i * 255 / 11
    add((grey shl 16) or (grey shl 8) or grey)
  }
}

/**
* Displays grouped application settings and their editors.
* @return Unit; changes are applied through persisted Setting values.
*/
@Composable
fun SettingsScreen() {
  // Rows are compact: switches are not stretched to the 48dp Material minimum.
  CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) { SettingsContent() }
}

// A touch anywhere takes the focus from a text field; the field touched gets it back.
@Composable
private fun SettingsContent() {
  val focus = LocalFocusManager.current
  Column(
    Modifier
      .fillMaxSize()
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
          focus.clearFocus()
        }
      }
      .imePadding()
  ) {
    ModalHeader(stringResource(R.string.settings))
    SettingsRows()
  }
}

/** Прокручиваемые группы настроек под неподвижным заголовком. FOR LOCAL USE */
@Composable
private fun ColumnScope.SettingsRows() {
  Column(
    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp)
  ) {
    Section(R.string.settings_general)
    LanguageSetting()
    SwitchRow(R.string.keep_screen_on, Settings.keepScreenOn)
    NumberRow(R.string.image_quality, Settings.imageQuality, min = MIN_IMAGE_QUALITY, max = 100)

    Section(R.string.settings_gps)
    SwitchRow(R.string.use_gps, Settings.useGps)
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(stringResource(R.string.show_speed), modifier = Modifier.weight(1f))
      androidx.compose.material3.Checkbox(checked = Settings.showSpeed.value, onCheckedChange = { Settings.showSpeed.value = it })
    }

    Section(R.string.settings_map)
    ChoiceRow(R.string.map_direction, Settings.mapDirection, MapDirection.entries.associateWith { it.label })
    SwitchRow(R.string.map_3d, Settings.map3d)

    Section(R.string.settings_yandex)
    KeyRow(R.string.mapkit_key, R.string.mapkit_key_help, Settings.mapkitKey)
    KeyRow(R.string.geocoder_key, R.string.geocoder_key_help, Settings.geocoderKey)
    NumberRow(R.string.geocoder_results, Settings.geocoderResults, min = 1)

    Section(R.string.settings_position)
    NumberRow(R.string.rings, Settings.rings)
    NumberRow(R.string.ring_step_value, Settings.ringStepValue)
    NumberRow(R.string.auto_position, Settings.autoPosition)

    Section(R.string.settings_gps_filter)
    val filterLabels = GpsDataManager.filters.associate { filter ->
      filter.name() to when (filter) {
        GpsFilter_None -> stringResource(R.string.filter_none)
        GpsFilter_Simple -> stringResource(R.string.filter_simple)
        else -> filter.name()
      }
    }
    val currentFilter = GpsDataManager.currentFilter()
    ComboRow(R.string.filter_mode, filterLabels[currentFilter.name()] ?: currentFilter.name()) { close ->
      filterLabels.forEach { (name, label) ->
        ChoiceMenuItem(label, currentFilter.name() == name) {
          close()
          Settings.filterMode.value = name
        }
      }
    }
    if (currentFilter === GpsFilter_Simple) {
      NumberRow(R.string.filter_accuracy, Settings.filterAccuracy)
      NumberRow(R.string.filter_stationary, Settings.filterStationarySpeed)
      NumberRow(R.string.filter_min_distance, Settings.filterMinDistance)
      NumberRow(R.string.filter_max_speed, Settings.filterMaxSpeed)
    }

    Section(R.string.settings_tracks)
    NumberRow(R.string.min_points, Settings.minPoints)
    NumberRow(R.string.tail_points, Settings.tailPoints)
    NumberRow(R.string.current_track_width, Settings.currentTrackWidth)
    ColorRow(R.string.current_track_color, Settings.currentTrackColor)
    NumberRow(R.string.saved_track_width, Settings.savedTrackWidth)
    ColorRow(R.string.saved_track_color, Settings.savedTrackColor)

    Section(R.string.settings_points)
    ColorRow(R.string.color_independent, Settings.pointIndependentColor)
    ColorRow(R.string.color_planned, Settings.pointPlannedColor)
    ColorRow(R.string.color_active, Settings.pointActiveColor)
    ColorRow(R.string.color_visited, Settings.pointVisitedColor)
    ColorRow(R.string.color_alarm, Settings.pointAlarmColor)
    ColorRow(R.string.color_open, Settings.availabilityOpenColor)
    ColorRow(R.string.color_closed, Settings.availabilityClosedColor)
    ColorRow(R.string.color_time_to_open, Settings.timeToOpenColor)
    ColorRow(R.string.color_time_to_close, Settings.timeToCloseColor)
    NumberRow(R.string.point_name_size, Settings.pointNameSize)
    NumberRow(R.string.point_time_size, Settings.pointTimeSize)

    Section(R.string.settings_route)
    ChoiceRow(R.string.transport, Settings.routeTransport, TransportKind.entries.associateWith { it.label })
    SwitchRow(R.string.route_mark_points, Settings.routeMarkPoints)
    ModeRow(R.string.route_building, Settings.routeAutoCalculate)
    ModeRow(R.string.route_sorting, Settings.routeAutoSort)
    NumberRow(R.string.route_arrival_radius, Settings.routeArrivalRadius, min = 10, max = 500)
    SwitchRow(R.string.route_show_maneuver, Settings.routeShowManeuver)
    ColorRow(R.string.route_color_active, Settings.routeActiveColor)
    ColorRow(R.string.route_color_inactive, Settings.routeInactiveColor)
  }
}

@Composable
private fun Section(@StringRes title: Int) {
  Text(
    stringResource(title),
    style = MaterialTheme.typography.titleMedium.let { it.copy(fontSize = it.fontSize * 1.5f) },
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 16.dp)
  )
  HorizontalDivider()
}

@Composable
private fun LanguageSetting() {
  ComboRow(R.string.language, Languages.current.name) { close ->
    Languages.all.forEach { language ->
      CommandMenuItem(Icons.Outlined.Translate, language.name, AppCommands.selectLanguage.getValue(language)) { close() }
    }
  }
}

// Choice from a list: the value in a frame with the drop-down arrow, the list opens under it.
@Composable
private fun ComboRow(@StringRes label: Int, value: String, items: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val shape = RoundedCornerShape(8.dp)
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), modifier = Modifier.weight(1f).padding(end = 8.dp))
    Box {
      Row(
        Modifier
          .height(36.dp)
          .widthIn(min = 104.dp)
          .border(1.dp, MaterialTheme.colorScheme.outline, shape)
          .clip(shape)
          .clickable { expanded = true }
          .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(value, modifier = Modifier.weight(1f, fill = false), maxLines = 1)
        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
      }
      DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { items { expanded = false } }
    }
  }
}

@Composable
private fun SwitchRow(@StringRes label: Int, setting: BoolSetting) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), modifier = Modifier.weight(1f))
    Switch(checked = setting.value, onCheckedChange = { setting.value = it })
  }
}

// A switch that says what it does better as a choice of two: the operation is done by hand or by the application.
@Composable
private fun ModeRow(@StringRes label: Int, setting: BoolSetting) {
  val modes = listOf(false to R.string.mode_manual, true to R.string.mode_auto)
  ComboRow(label, stringResource(modes.first { it.first == setting.value }.second)) { close ->
    modes.forEach { (value, text) ->
      ChoiceMenuItem(stringResource(text), setting.value == value) {
        close()
        setting.value = value
      }
    }
  }
}

@Composable
private fun <E : Enum<E>> ChoiceRow(@StringRes label: Int, setting: EnumSetting<E>, labels: Map<E, Int>) {
  ComboRow(label, stringResource(labels.getValue(setting.value))) { close ->
    labels.forEach { (value, text) ->
      ChoiceMenuItem(stringResource(text), setting.value == value) {
        close()
        setting.value = value
      }
    }
  }
}

// Key of a service: "?" shows what it is for, the field hides the value and saves it when it loses the focus.
@Composable
private fun KeyRow(@StringRes label: Int, @StringRes help: Int, setting: StringSetting) {
  var text by remember(setting.value) { mutableStateOf(setting.value) }
  var shown by remember { mutableStateOf(false) }
  val focus = LocalFocusManager.current
  val shape = RoundedCornerShape(8.dp)
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label))
    Box(
      Modifier.padding(horizontal = 4.dp).size(28.dp).clip(RoundedCornerShape(14.dp)).clickable { showKeyHelp(label, help) },
      contentAlignment = Alignment.Center
    ) {
      Icon(Icons.AutoMirrored.Outlined.HelpOutline, stringResource(R.string.help), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
    Row(
      Modifier.weight(1f).height(36.dp).border(1.dp, MaterialTheme.colorScheme.outline, shape).clip(shape),
      verticalAlignment = Alignment.CenterVertically
    ) {
      BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 8.dp)
          .onFocusChanged { if (!it.isFocused && text != setting.value) setting.value = text.trim() }
      )
      Box(Modifier.fillMaxHeight().width(36.dp).clickable { shown = !shown }, contentAlignment = Alignment.Center) {
        Icon(
          if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
          stringResource(if (shown) R.string.hide else R.string.show),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp)
        )
      }
    }
  }
}

private fun showKeyHelp(@StringRes title: Int, @StringRes help: Int) = AppDialog.show {
  val links = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
  val html = stringResource(help)
  val heading = stringResource(title)
  AlertDialog(
    onDismissRequest = AppDialog::close,
    icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
    title = { Text(heading) },
    text = { Text(AnnotatedString.fromHtml(html, linkStyles = links), style = MaterialTheme.typography.bodyMedium) },
    confirmButton = {
      DialogButton(Icons.Outlined.Share, R.string.share, primary = true) {
        AppDialog.close()
        Sharing.shareText(heading + "\n\n" + AnnotatedString.fromHtml(html).text, heading)
      }
    },
    dismissButton = { TextButton(onClick = AppDialog::close) { Text(stringResource(R.string.close)) } }
  )
}

// The unit is part of the label, for example "Ring step, m".
@Composable
private fun NumberRow(@StringRes label: Int, setting: IntSetting, min: Int = 0, max: Int = Int.MAX_VALUE) {
  Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(stringResource(label), modifier = Modifier.weight(1f).padding(end = 8.dp))
    NumberField(setting, min, max)
  }
}

// Integer with a lower bound and up/down buttons; a wrong typed value is shown but not saved.
@Composable
private fun NumberField(setting: IntSetting, min: Int = 0, max: Int = Int.MAX_VALUE) {
  var text by remember(setting.value) { mutableStateOf(setting.value.toString()) }
  val focus = LocalFocusManager.current
  val valid = text.toIntOrNull()?.takeIf { it in min..max } != null
  val shape = RoundedCornerShape(8.dp)
  val border = if (valid) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
  Row(
    Modifier.height(36.dp).width(104.dp).border(1.dp, border, shape).clip(shape),
    verticalAlignment = Alignment.CenterVertically
  ) {
    BasicTextField(
      value = text,
      onValueChange = { value ->
        text = value
        value.toIntOrNull()?.takeIf { it in min..max }?.let { setting.value = it }
      },
      singleLine = true,
      textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface),
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
      modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
    )
    VerticalDivider(color = border)
    Column(Modifier.fillMaxHeight().width(28.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
      StepButton(Icons.Outlined.KeyboardArrowUp) { setting.value = (setting.value + 1).coerceAtMost(max) }
      HorizontalDivider(color = border)
      StepButton(Icons.Outlined.KeyboardArrowDown) { setting.value = (setting.value - 1).coerceAtLeast(min) }
    }
  }
}

@Composable
private fun ColumnScope.StepButton(icon: ImageVector, onClick: () -> Unit) {
  Box(Modifier.weight(1f).fillMaxWidth().clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
  }
}

@Composable
private fun ColorRow(@StringRes label: Int, setting: ColorSetting) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable { showColorDialog(label, setting) }.padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(stringResource(label), modifier = Modifier.weight(1f))
    Box(Modifier.size(28.dp).background(Color(setting.value), RoundedCornerShape(6.dp)).border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp)))
  }
}

// Palette of 60 colors in rows of 12, thin opacity slider with a small preview in one line.
private fun showColorDialog(@StringRes label: Int, setting: ColorSetting) = AppDialog.show {
  var rgb by remember { mutableIntStateOf(setting.value and 0xFFFFFF) }
  var alpha by remember { mutableFloatStateOf(((setting.value ushr 24) and 0xFF) / 255f) }
  val result = ((alpha * 255).roundToInt() shl 24) or rgb
  AlertDialog(
    onDismissRequest = AppDialog::close,
    icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
    title = { Text(stringResource(label)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        PALETTE.chunked(12).forEach { row ->
          Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            row.forEach { color ->
              val selected = color == rgb
              Box(
                Modifier
                  .weight(1f)
                  .aspectRatio(1f)
                  .clip(RoundedCornerShape(4.dp))
                  .background(Color(0xFF000000 or color.toLong()))
                  .border(if (selected) 2.dp else 0.5.dp, if (selected) Color.Black else Color(0x33000000), RoundedCornerShape(4.dp))
                  .clickable { rgb = color }
              )
            }
          }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
          Text(stringResource(R.string.color_opacity, (alpha * 100).roundToInt()), style = MaterialTheme.typography.bodySmall)
          ThinSlider(alpha, 0.1f..1f, { alpha = it }, Modifier.weight(1f).padding(horizontal = 8.dp))
          Box(Modifier.size(24.dp).clip(RoundedCornerShape(4.dp)).background(Color(result)).border(0.5.dp, Color(0x33000000), RoundedCornerShape(4.dp)))
        }
      }
    },
    confirmButton = {
      DialogButton(Icons.Outlined.Check, R.string.ok, primary = true) {
        AppDialog.close()
        setting.value = result
      }
    },
    dismissButton = { CancelButton(AppDialog::close) }
  )
}

@Composable
private fun ThinSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, modifier: Modifier) {
  val active = MaterialTheme.colorScheme.primary
  val track = MaterialTheme.colorScheme.outlineVariant
  fun valueAt(x: Float, width: Int) = (range.start + (x / width).coerceIn(0f, 1f) * (range.endInclusive - range.start))
  Canvas(
    modifier
      .height(24.dp)
      .pointerInput(Unit) { detectTapGestures { onChange(valueAt(it.x, size.width)) } }
      .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> onChange(valueAt(change.position.x, size.width)) } }
  ) {
    val y = size.height / 2
    val x = size.width * (value - range.start) / (range.endInclusive - range.start)
    drawLine(track, Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
    drawLine(active, Offset(0f, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
    drawCircle(active, 7.dp.toPx(), Offset(x, y))
  }
}
