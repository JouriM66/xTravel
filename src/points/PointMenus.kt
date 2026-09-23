// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

// Adding points: at a place, or with the data the map provider knows about it.
/**
* Creates points, requests geocoder data and validates schedule-control changes.
*
* Public and subclass/module-facing members:
* - [menuPlace] - Chooses the map center in manual mode or the latest accepted position while raw GPS status is OK.
* - [add] - Creates a point at the supplied location.
* - [addAndEdit] - Creates a point at the supplied location and opens its metadata editor.
* - [requestMapData] - Appends geocoder name and descriptions to a point without exact text duplicates.
* - [setControl] - Enables schedule control only for a point with a schedule, or asks before disabling it.
* - [toggleControl] - Requests the inverse of the point's current schedule-control flag.
*/
object PointActions {

  // Where a point from the main menu goes: the map center while it is moved by hand, otherwise the fresh GPS position.
  /**
  * Chooses the map center in manual mode or the latest accepted position while raw GPS status is OK.
  * @return The selected position, or null without usable GPS outside manual mode.
  */
  fun menuPlace(): GeoPoint? =
    if (Maps.positionMode == PositionMode.CUSTOM) Maps.camera.center
    else GpsDataManager.lastFix?.takeIf { GpsDataManager.status == GpsStatus.OK }?.let { GeoPoint(it.lat, it.lon) }

  /**
  * Creates a point at the supplied location.
  * @param place Geographic location for point creation or movement.
  * @return The created point; PointStore handles persistence.
  */
  fun add(place: GeoPoint): MapPoint = PointStore.add(place)

  // A point made on the map is opened at once: its metadata editor, with the tool the menu item asked for.
  /**
  * Creates a point at the supplied location and opens its metadata editor.
  * @param place Geographic location for point creation or movement.
  * @param start Tool the metadata editor starts with. Default: none.
  * @return Unit; the editor is opened above the current sheet content.
  */
  fun addAndEdit(place: GeoPoint, start: PointInfoStart = PointInfoStart.NONE) {
    BottomSheet.push(PointInfoEditor(InfoOwner.Point(add(place).id), start = start))
  }

  /**
  * Appends geocoder name and descriptions to a point without exact text duplicates.
  * @param id Stable point identifier.
  * @return Unit; the callback updates an existing point on the main thread.
  */
  fun requestMapData(id: Long) {
    val point = PointStore.find(id) ?: return
    GeoSearchManager.byPosition(point.geo) { answers ->
      if (answers.isEmpty()) return@byPosition
      PointStore.update(id) { current ->
        val elements = current.elements.toMutableList()
        val texts = elements.filterIsInstance<PointElement.Text>().map { it.text }.toMutableSet()
        answers.first().name?.takeIf { it.isNotBlank() }?.let { name ->
          val position = if (texts.isEmpty()) 0 else elements.size
          if (texts.add(name)) elements.add(position, PointElement.Text(name))
        }
        answers.mapNotNull { it.description?.takeIf(String::isNotBlank) }.forEach { text ->
          if (texts.add(text)) elements += PointElement.Text(text)
        }
        current.copy(info = MetaInfo(elements))
      }
    }
  }

  // The only place the schedule control is switched at: here the point is checked and told why the control cannot work.
  // Fields set directly, for example in the properties editor, are not checked.
  /**
  * Enables schedule control only for a point with a schedule, or asks before disabling it.
  * @param point Point snapshot whose attachments, schedule or metadata are processed; null represents absent loaded notes where accepted.
  * @param on True to enable control after schedule validation; false to ask before disabling it.
  * @return Unit; a rejected request is reported through Notify.
  */
  fun setControl(point: MapPoint, on: Boolean) {
    if (!on) {
      AppDialog.confirm(R.string.control_off_confirm, point.fullCaption()) {
        PointStore.update(point.id) { it.copy(scheduleControl = false) }
      }
      return
    }
    if (point.hasSchedule) PointStore.update(point.id) { it.copy(scheduleControl = true) } else Notify.error(R.string.control_needs_schedule)
  }

  /**
  * Requests the inverse of the point's current schedule-control flag.
  * @param point Point snapshot whose attachments, schedule or metadata are processed; null represents absent loaded notes where accepted.
  * @return Unit; applies the same validation and confirmation as setControl.
  */
  fun toggleControl(point: MapPoint) = setControl(point, !point.scheduleControl)

}

// Menu of a place without own objects.
/**
* Displays coordinate and point-creation actions for a place without a selected application object.
* @param info Geographic information for the empty place selected on the map.
* @receiver Menu column scope in which rows are emitted.
* @return Unit; emits menu rows in the receiver ColumnScope.
*/
@Composable
fun ColumnScope.EmptyPlaceMenu(info: MapPointInfo) {
  MenuItem(Icons.Outlined.Place, formatCoordinates(info.point)) {
    MapPopup.close()
    PointDialogs.coordinates(info.point, MapTap::showPlace)
  }
  HorizontalDivider()
  MenuItem(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(R.string.add_point_and_note)) {
    MapPopup.close()
    PointActions.addAndEdit(info.point, PointInfoStart.TEXT)
  }
  MenuItem(Icons.Outlined.AddAPhoto, stringResource(R.string.add_point_and_photo)) {
    MapPopup.close()
    PointActions.addAndEdit(info.point, PointInfoStart.CAMERA)
  }
  MenuItem(Icons.AutoMirrored.Outlined.PlaylistAdd, stringResource(R.string.add_point_to_route)) {
    MapPopup.close()
    RouteStore.addPlace(info.point)
  }
}

enum class PointMenuSource { MAP, LIST, ROUTE } /** Откуда вызвано меню точки: карта, список точек, шторка маршрута */

/** Единое контекстное меню точки для всех мест интерфейса. Пункты, у которых в источнике есть способ проще или которые
    там не имеют смысла, не показываются: "Переместить" - только на карте, "Показать на карте" - не на карте,
    "Показать в списке" и "Заметки" - не в списке. close закрывает меню перед действием.
*/
@Composable
fun ColumnScope.PointPopupMenu(id: Long, source: PointMenuSource, close: () -> Unit) {
  val point = PointStore.find(id) ?: return
  var share by remember { mutableStateOf(false) }
  val set = DataSet(points = listOf(point))
  if (share) {
    PopupShareMenu(set, close)
    return
  }
  fun act(action: () -> Unit): () -> Unit = {
    close()
    action()
  }
  if (source == PointMenuSource.MAP) MenuItem(Icons.Outlined.OpenWith, stringResource(R.string.move), onClick = act { PointMoveMode.start(id) })
  else MenuItem(Icons.Outlined.Map, stringResource(R.string.show_on_map), onClick = act { Maps.centerOn(point.geo) })
  HorizontalDivider()
  MenuItem(Icons.AutoMirrored.Outlined.NoteAdd, stringResource(R.string.add_note), onClick = act {
    BottomSheet.push(PointInfoEditor(InfoOwner.Point(id), start = PointInfoStart.TEXT))
  })
  MenuItem(Icons.Outlined.AddAPhoto, stringResource(R.string.add_photo), onClick = act {
    BottomSheet.push(PointInfoEditor(InfoOwner.Point(id), start = PointInfoStart.PICTURE))
  })
  HorizontalDivider()
  if (source != PointMenuSource.LIST) {
    MenuItem(Icons.AutoMirrored.Outlined.List, stringResource(R.string.show_in_list), onClick = act {
      PointStore.focus(id)
      AppCommands.showPoints.execute()
    })
    // Число заметок без первого текста - имени точки, и число картинок.
    val texts = (point.elements.count { it is PointElement.Text } - 1).coerceAtLeast(0)
    val pictures = point.info.pictures.size
    MenuItem(Icons.Outlined.Description, stringResource(R.string.point_data, texts, pictures), onClick = act {
      BottomSheet.push(PointInfoEditor(InfoOwner.Point(id)))
    })
  }
  MenuItem(Icons.Outlined.Settings, stringResource(R.string.properties), onClick = act { openPointProperties(id) })
  ShareMenuItem(set) { share = true }
  RouteItem(id, close)
  HorizontalDivider()
  if (point.alarm()) {
    MenuItem(Icons.Outlined.AlarmOff, stringResource(R.string.clear_alarm), onClick = act {
      PointStore.update(id) { it.copy(scheduleControl = false) }
    })
  }
  MenuItem(Icons.Outlined.Flag, stringResource(R.string.plan), trailing = if (point.status == PointStatus.PLANNED) Icons.Outlined.Check else null, onClick = act {
    PointStore.update(id) { it.copy(status = if (it.status == PointStatus.PLANNED) PointStatus.INDEPENDENT else PointStatus.PLANNED) }
  })
  MenuItem(
    Icons.Outlined.CheckCircle,
    stringResource(if (point.visited) R.string.visit_clear else R.string.visit_set),
    trailing = if (point.visited) Icons.Outlined.Check else null,
    onClick = act { PointStore.update(id) { it.copy(visited = !it.visited) } }
  )
  HorizontalDivider()
  MenuItem(Icons.Outlined.Delete, stringResource(R.string.delete_point), onClick = act { PointDialogs.delete(listOf(id)) })
}

// A point stands in the route of the navigation at most once, so one item both adds it and excludes it.
/**
* Displays the item adding the point to the route or excluding it from the route.
* @param id Stable point identifier.
* @param close Called before the action, to close the menu the item stands in.
* @return Unit; emits one menu row.
*/
@Composable
fun RouteItem(id: Long, close: () -> Unit) {
  val holds = RouteStore.holds(id)
  val icon = if (holds) Icons.Outlined.Delete else Icons.AutoMirrored.Outlined.PlaylistAdd
  MenuItem(icon, stringResource(if (holds) R.string.route_exclude else R.string.add_point_to_route)) {
    close()
    if (holds) RouteStore.removePoint(id) else RouteStore.addPoint(id)
  }
}

// Moving a point: the point follows the finger while held, the map moves when touched elsewhere. Ends with Done or Back.
/**
* Owns the temporary mode that enables dragging an existing point on the map.
*
* Public and subclass/module-facing members:
* - [activeId] - ID of the point currently in move mode, or null.
* - [start] - Activates drag-based point movement and installs its floating toolbar.
* - [done] - Ends point-moving mode and closes its floating toolbar.
*/
object PointMoveMode {

  /**
  * ID of the point currently in move mode, or null.
  * @return ID of the point currently in move mode, or null.
  */
  var activeId by mutableStateOf<Long?>(null)
    private set

  /**
  * Activates drag-based point movement and installs its floating toolbar.
  * @param id Stable point identifier.
  * @return Unit; cancelling the toolbar leaves already-applied moves intact.
  */
  fun start(id: Long) {
    activeId = id
    FloatingBar.show(onCancel = { activeId = null }) { MoveBar() }
  }

  /**
  * Ends point-moving mode and closes its floating toolbar.
  * @return Unit; coordinates already applied remain saved.
  */
  fun done() {
    FloatingBar.close()
    activeId = null
  }
}

@Composable
private fun RowScope.MoveBar() {
  Icon(Icons.Outlined.OpenWith, contentDescription = null, modifier = Modifier.padding(start = 12.dp, end = 8.dp))
  Text(stringResource(R.string.moving_point), style = MaterialTheme.typography.titleMedium)
  Spacer(Modifier.weight(1f))
  ToolbarItem(Icons.Outlined.Check, R.string.done, onClick = PointMoveMode::done)
}

/**
* Presents point deletion and coordinate-entry dialogs.
*
* Public and subclass/module-facing members:
* - [delete] - Asks to delete points, with an additional confirmation when attachments exist.
* - [moveTo] - Opens a coordinate editor and applies valid coordinates to an existing point.
* - [coordinates] - Displays latitude/longitude input and accepts valid geographic bounds.
*/
object PointDialogs {

  // Second question only when the points have data elements; it tells apart the notes and the pictures that will go with them.
  /**
  * Asks to delete points, with an additional confirmation when attachments exist.
  * @param ids Identifiers of points or routes targeted by the operation.
  * @return Unit; deletion occurs only after required confirmations.
  */
  fun delete(ids: List<Long>) {
    val elements = ids.mapNotNull(PointStore::find).flatMap { it.elements }
    val pictures = elements.count { it is PointElement.Picture }
    val texts = elements.size - pictures
    val first = if (ids.size == 1) R.string.delete_point_confirm else R.string.delete_points_confirm
    AppDialog.confirm(first, ids.size, icon = Icons.Outlined.Delete, confirmIcon = Icons.Outlined.Delete, confirmLabel = R.string.delete) {
      if (elements.isNotEmpty()) {
        val second = if (ids.size == 1) R.string.point_items_confirm else R.string.points_items_confirm
        AppDialog.confirm(second, texts, pictures, icon = Icons.Outlined.Delete, confirmIcon = Icons.Outlined.Delete, confirmLabel = R.string.delete) {
          PointStore.remove(ids)
        }
      } else {
        PointStore.remove(ids)
      }
    }
  }

  /**
  * Opens a coordinate editor and applies valid coordinates to an existing point.
  * @param id Stable point identifier.
  * @return Unit; an unknown point ID does nothing.
  */
  fun moveTo(id: Long) {
    val point = PointStore.find(id) ?: return
    coordinates(point.geo) { place -> PointStore.update(id) { it.copy(lat = place.lat, lon = place.lon) } }
  }

  // Coordinates typed by hand; the caller decides what to do with the place.
  /**
  * Displays latitude/longitude input and accepts valid geographic bounds.
  * @param initial Initial geographic position, formatted with five decimal places.
  * @param onOk Receives accepted latitude and longitude after the dialog closes.
  * @return Unit; accepted coordinates are delivered through onOk.
  */
  fun coordinates(initial: GeoPoint, onOk: (GeoPoint) -> Unit) {
    AppDialog.show {
      var lat by remember { mutableStateOf(String.format(Locale.ROOT, "%.5f", initial.lat)) }
      var lon by remember { mutableStateOf(String.format(Locale.ROOT, "%.5f", initial.lon)) }
      val latValue = lat.replace(',', '.').toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
      val lonValue = lon.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
      AlertDialog(
        onDismissRequest = AppDialog::close,
        icon = { Icon(Icons.Outlined.EditLocation, contentDescription = null) },
        title = { Text(stringResource(R.string.move_to)) },
        text = {
          Column {
            CoordinateField(R.string.latitude, lat, latValue == null) { lat = it }
            CoordinateField(R.string.longitude, lon, lonValue == null) { lon = it }
          }
        },
        confirmButton = {
          DialogButton(Icons.Outlined.Check, R.string.ok, primary = true, enabled = latValue != null && lonValue != null) {
            AppDialog.close()
            if (latValue != null && lonValue != null) onOk(GeoPoint(latValue, lonValue))
          }
        },
        dismissButton = { CancelButton(AppDialog::close) }
      )
    }
  }

  @Composable
  private fun CoordinateField(@StringRes label: Int, value: String, error: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
      value = value,
      onValueChange = onChange,
      label = { Text(stringResource(label)) },
      singleLine = true,
      isError = error,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
  }
}
