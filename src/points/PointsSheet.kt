// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.io.File
import kotlin.math.min

// Lines of the row text; the row height follows them.
private const val LIST_LINES = 3
// Pictures composed for the collage; more never fit into a row.
private const val MAX_THUMBS = 12
private val ROW_PADDING = 4.dp
// Alarm states of a point: no schedule, a schedule without the control, the control at work.
private val ALARM_OFF_COLOR = Color(0xFF2C2C2A)
private val ALARM_ON_COLOR = Color(0xFF2E9B33)

// Points in the manual order: the root editor of the points sheet. It opens at the point asked for (the point menu on the map)
// or at the point nearest to the map center; the position is taken once, when the sheet opens, so moving the map from the list
// does not scroll it, and coming back from an editor keeps the place.
/**
* Displays the point list initially scrolled to the requested point or the point nearest the map center.
* @return Unit; call within the bottom sheet.
*/
/** Корневой редактор шторки точек: список и тулбар с "поделиться" всеми точками */
class PointsListEditor : SheetEditor() {

  override val caption: String get() = AppSession.context.getString(R.string.sheet_points)

  override val hasToolbar get() = true

  override fun getContentElementsCount() = PointStore.points.size

  @Composable
  override fun RowScope.ToolbarButtons() = ShareToolbarItem { DataSelectors.points(PointStore.points.toList()) }

  @Composable
  override fun Content() = PointsSheet()
}

@Composable
fun PointsSheet() = PointList(selection = null, rememberLazyListState(initialFirstVisibleItemIndex = remember { openingIndex() }))

private fun openingIndex(): Int {
  val points = PointStore.points
  PointStore.scrollTarget?.let { target -> points.indexOfFirst { it.id == target }.takeIf { it >= 0 }?.let { return it } }
  val center = Maps.camera.center
  return points.indices.minByOrNull { GeoMath.distance(center.lat, center.lon, points[it].lat, points[it].lon) } ?: 0
}

// Manual sorting and selection: the same list with check boxes and drag handles, opened by a long press on a row.
private class PointSelectionEditor(first: Long, private val firstIndex: Int, private val firstOffset: Int) : SheetEditor() {

  val selection = mutableStateListOf(first)

  val jump = mutableStateOf<Long?>(null) /** Точка, к которой список прокручивается после касания на карте */

  override val caption: String get() = AppSession.context.getString(R.string.sheet_points_select)

  override val hasToolbar get() = true

  override fun getContentElementsCount() = PointStore.points.size

  @Composable
  override fun RowScope.ToolbarButtons() = PointSelectionButtons(selection)

  @Composable
  override fun Content() = PointList(selection, rememberLazyListState(firstIndex, firstOffset), jump)
}

/** Касание точки на карте в режиме выбора: список прокручивается к ней, её выбор переключается.
    Возвращает false, если режим выбора не открыт, тогда касание обрабатывается как обычно.
*/
fun toggleSelectedPoint(id: Long): Boolean {
  val editor = BottomSheet.topEditor as? PointSelectionEditor ?: return false
  if (id in editor.selection) editor.selection.remove(id) else editor.selection.add(id)
  editor.jump.value = id
  return true
}

@Composable
private fun PointList(selection: SnapshotStateList<Long>?, listState: LazyListState, jump: MutableState<Long?>? = null) {
  val points = PointStore.points
  var draggingId by remember { mutableStateOf<Long?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val textStyle = MaterialTheme.typography.bodyMedium
  val textHeight = with(LocalDensity.current) { (textStyle.lineHeight * LIST_LINES).toDp() }

  val target = PointStore.scrollTarget
  LaunchedEffect(target, points.size) {
    if (target == null || selection != null) return@LaunchedEffect
    val index = points.indexOfFirst { it.id == target }
    if (index >= 0) {
      listState.animateScrollToItem(index)
      PointStore.scrollTarget = null
    }
  }
  val jumpTarget = jump?.value
  LaunchedEffect(jumpTarget) {
    val index = points.indexOfFirst { it.id == jumpTarget }
    if (index >= 0) listState.animateScrollToItem(index)
    jump?.value = null
  }
  LaunchedEffect(points.size) {
    if (selection == null) return@LaunchedEffect
    val ids = points.map { it.id }.toSet()
    selection.retainAll { it in ids }
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize().scrollIndicator(listState)) {
    items(points, key = { it.id }) { point ->
      val dragged = draggingId == point.id
      Column(
        Modifier
          .zIndex(if (dragged) 1f else 0f)
          .graphicsLayer { translationY = if (dragged) dragOffset else 0f }
          .background(Color.White)
      ) {
        SwipeRevealRow(enabled = selection == null, onDelete = { PointDialogs.delete(listOf(point.id)) }) {
          PointRow(
            point, selection, textHeight,
            onLongClick = {
              BottomSheet.push(PointSelectionEditor(point.id, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
            }
          ) {
            DragHandle(point.id, listState, onStart = {
              draggingId = point.id
              dragOffset = 0f
            }, onEnd = {
              draggingId = null
              dragOffset = 0f
            }) { dy ->
              dragOffset += dy
              dragOffset = reorder(point.id, dragOffset, listState)
            }
          }
        }
        HorizontalDivider()
      }
    }
  }
}

// Four buttons in a square of the row height: visibility and the pin above, the alarm and the properties below.
// In the selection mode a tap on the body of the row switches its selection, the buttons work as usual;
// a drag handle is added on the right.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PointRow(point: MapPoint, selection: SnapshotStateList<Long>?, textHeight: Dp, onLongClick: () -> Unit, dragHandle: @Composable () -> Unit) {
  val selected = selection != null && point.id in selection
  val highlighted = selection == null && point.highlighted
  val toggle: () -> Unit = {
    if (selection != null) {
      if (point.id in selection) selection.remove(point.id) else selection.add(point.id)
    }
  }
  fun action(normal: () -> Unit): () -> Unit = if (selection == null) normal else toggle
  val longClick = if (selection == null) onLongClick else null
  val iconColor = MaterialTheme.colorScheme.onSurfaceVariant
  val height = textHeight + ROW_PADDING * 2
  val background = when {
    selected -> SELECTED_BACKGROUND
    highlighted -> HIGHLIGHT_BACKGROUND
    else -> Color.White
  }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(height)
      .background(background)
      .combinedClickable(onClick = action {}, onLongClick = longClick),
    verticalAlignment = Alignment.CenterVertically
  ) {
    if (selection != null) Checkbox(checked = selected, onCheckedChange = { toggle() })
    Column(Modifier.size(height)) {
      Row(Modifier.fillMaxWidth().weight(1f)) {
        Cell(longClick, { PointStore.update(point.id) { it.copy(visible = !it.visible) } }) {
          Icon(
            if (point.visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
            stringResource(if (point.visible) R.string.hide else R.string.show),
            tint = iconColor
          )
        }
        PinCell(point, longClick)
      }
      Row(Modifier.fillMaxWidth().weight(1f)) {
        Cell(longClick, { PointActions.toggleControl(point) }) { AlarmIcon(point.hasSchedule, point.scheduleControl && point.controlPossible) }
        Cell(longClick, { openPointProperties(point.id) }) {
          Icon(Icons.Outlined.Settings, ""/*stringResource(R.string.properties)*/, tint = iconColor)
        }
      }
    }
    PointSummary(
      point, textHeight,
      Modifier
        .weight(1f)
        .fillMaxHeight()
        .combinedClickable(onClick = action { BottomSheet.push(PointInfoEditor(InfoOwner.Point(point.id))) }, onLongClick = longClick)
        .padding(horizontal = 6.dp, vertical = ROW_PADDING)
    )
    if (selection != null) dragHandle()
  }
}

// Square cell of the button block; the long press of the row works over it as well.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.Cell(onLongClick: (() -> Unit)?, onClick: () -> Unit, content: @Composable () -> Unit) {
  Box(
    modifier = Modifier.fillMaxHeight().weight(1f).combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center
  ) {
    content()
  }
}

// The pin opens the menu of the point: the map, the states and the properties.
@Composable
private fun RowScope.PinCell(point: MapPoint, onLongClick: (() -> Unit)?) {
  var menu by remember { mutableStateOf(false) }
  Cell(onLongClick, { menu = true }) {
    Box {
      PointPin(point)
      DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        PointPopupMenu(point.id, PointMenuSource.LIST) { menu = false }
      }
    }
  }
}

// Alarm of the point: grey without a schedule, black with the control off, green while it works.
/**
* Displays schedule-control state, distinguishing missing schedules from enabled or disabled control.
* @param hasSchedule Whether a usable schedule exists for the displayed point.
* @param on Requested enabled state.
* @return Unit; emits the alarm icon.
*/
@Composable
fun AlarmIcon(hasSchedule: Boolean, on: Boolean) {
  val color = when {
    !hasSchedule -> PASSIVE_COLOR
    on -> ALARM_ON_COLOR
    else -> ALARM_OFF_COLOR
  }
  Icon(
    if (hasSchedule) Icons.Outlined.Alarm else Icons.Outlined.AlarmOff,
    stringResource(R.string.schedule_control),
    tint = color
  )
}

// The same pin as on the map (status, visit and availability colors).
/**
* Displays the point's map-style pin inside a list control.
* @param point Point snapshot whose attachments, schedule or metadata are processed; null represents absent loaded notes where accepted.
* @return Unit; emits the pin preview.
*/
@Composable
fun PointPin(point: MapPoint) {
  Canvas(Modifier.size(28.dp)) {
    // Room for the halo around the pin.
    val pin = size.height * 0.78f
    with(PointsLayer) { drawPointPin(point, Offset(size.width / 2, size.height / 2 + pin / 2 - pin * 0.04f), pin) }
  }
}

// First text, then the collage of pictures, then "+N" for the elements not shown. With pictures the collage keeps at least one
// square; the text takes the rest and leaves what it does not need to the collage. The place of "+N" is always kept, measured
// for all elements, and stays empty when everything is shown. Without elements: coordinates.
@Composable
private fun PointSummary(point: MapPoint, square: Dp, modifier: Modifier) {
  val firstText = point.elements.firstOrNull { it is PointElement.Text } as? PointElement.Text
  val pictures = point.elements.filterIsInstance<PointElement.Picture>().take(MAX_THUMBS)
  val total = point.elements.size
  val style = MaterialTheme.typography.bodyMedium
  val plusColor = MaterialTheme.colorScheme.onSurfaceVariant
  SubcomposeLayout(modifier) { constraints ->
    val gap = 4.dp.roundToPx()
    val side = square.roundToPx()
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val plusWidth = if (total > 0) subcompose("probe") { Text("+$total", style = style) }.first().measure(Constraints()).width + gap else 0
    val reserved = if (pictures.isNotEmpty()) side + gap else 0
    val text = subcompose("text") {
      when {
        firstText != null -> Text(firstText.text, style = style, maxLines = LIST_LINES, overflow = TextOverflow.Ellipsis)
        total == 0 -> Text(formatCoordinates(point.geo), style = style, maxLines = LIST_LINES, color = INACTIVE_COLOR)
      }
    }.firstOrNull()?.measure(Constraints(maxWidth = (width - plusWidth - reserved).coerceAtLeast(0), maxHeight = height))
    val textWidth = if (text != null && text.width > 0) text.width + gap else 0
    val count = min(pictures.size, ((width - textWidth - plusWidth + gap) / (side + gap)).coerceAtLeast(0))
    val thumbs = pictures.take(count).mapIndexed { index, picture ->
      subcompose("thumb$index") { SquareThumb(picture.file, Modifier.clip(RoundedCornerShape(4.dp))) }.first().measure(Constraints.fixed(side, side))
    }
    val hidden = total - (if (firstText != null) 1 else 0) - count
    val plus = if (hidden > 0) subcompose("plus") { Text("+$hidden", style = style, color = plusColor) }.first().measure(Constraints()) else null
    layout(width, height) {
      text?.place(0, (height - text.height) / 2)
      var x = textWidth
      thumbs.forEach {
        it.place(x, (height - side) / 2)
        x += side + gap
      }
      plus?.place(width - plus.width, (height - plus.height) / 2)
    }
  }
}

// Swaps the dragged row with a neighbour once it passes half of the neighbour; returns the remaining offset.
private fun reorder(id: Long, offset: Float, listState: LazyListState): Float {
  val items = listState.layoutInfo.visibleItemsInfo
  val current = items.firstOrNull { it.key == id } ?: return offset
  val index = PointStore.points.indexOfFirst { it.id == id }
  if (offset > 0) {
    val next = items.firstOrNull { it.index == current.index + 1 } ?: return offset
    if (offset > next.size / 2f) {
      PointStore.move(index, index + 1)
      return offset - next.size
    }
  } else if (offset < 0) {
    val previous = items.firstOrNull { it.index == current.index - 1 } ?: return offset
    if (-offset > previous.size / 2f) {
      PointStore.move(index, index - 1)
      return offset + previous.size
    }
  }
  return offset
}

// Sets of points a selection can be filled from or cleared by.
private enum class PointFilter(val icon: ImageVector, @StringRes val label: Int, val matches: (MapPoint) -> Boolean) {
  ALL(Icons.Outlined.DoneAll, R.string.filter_all, { true }),
  VISIBLE(Icons.Outlined.Visibility, R.string.filter_visible, { it.visible }),
  HIDDEN(Icons.Outlined.VisibilityOff, R.string.filter_hidden, { !it.visible }),
  PLANNED(Icons.Outlined.Flag, R.string.filter_planned, { it.status == PointStatus.PLANNED }),
  VISITED(Icons.Outlined.CheckCircle, R.string.filter_visited, { it.visited }),
  NOT_VISITED(Icons.Outlined.RadioButtonUnchecked, R.string.filter_not_visited, { !it.visited }),
  ALARM(Icons.Outlined.NotificationsActive, R.string.filter_alarm, { it.alarm() })
}

// Actions on the selected points; everything that changes them is done from one menu.
private enum class PointAction(val icon: ImageVector, @StringRes val label: Int) {
  SHOW(Icons.Outlined.Visibility, R.string.show),
  HIDE(Icons.Outlined.VisibilityOff, R.string.hide),
  PLAN(Icons.Outlined.Flag, R.string.plan),
  VISIT(Icons.Outlined.CheckCircle, R.string.visited),
  AUTO_VISIT(Icons.Outlined.MyLocation, R.string.auto_visit),
  WARNING(Icons.Outlined.NotificationsActive, R.string.schedule_control),
  DELETE(Icons.Outlined.Delete, R.string.delete)
}

@Composable
private fun PointSelectionButtons(selection: SnapshotStateList<Long>) {
  Text(selection.size.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
  FilterMenu(Icons.Outlined.DoneAll, R.string.select) { filter ->
    PointStore.points.filter { filter.matches(it) && it.id !in selection }.forEach { selection.add(it.id) }
  }
  FilterMenu(Icons.Outlined.RemoveDone, R.string.deselect) { filter ->
    val ids = PointStore.points.filter(filter.matches).map { it.id }.toSet()
    selection.removeAll { it in ids }
  }
  ShareToolbarItem { DataSelectors.points(PointStore.points.filter { it.id in selection }) }
  ActionMenu(selection)
}

@Composable
private fun FilterMenu(icon: ImageVector, @StringRes label: Int, onFilter: (PointFilter) -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    ToolbarItem(icon, label) { open = true }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      PointFilter.entries.forEach { filter ->
        MenuItem(filter.icon, stringResource(filter.label)) {
          open = false
          onFilter(filter)
        }
      }
    }
  }
}

@Composable
private fun ActionMenu(selection: SnapshotStateList<Long>) {
  var open by remember { mutableStateOf(false) }
  val any = selection.isNotEmpty()
  Box {
    ToolbarItem(Icons.Outlined.Bolt, R.string.action, enabled = any) { if (any) open = true }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      PointAction.entries.forEach { action ->
        MenuItem(action.icon, stringResource(action.label)) {
          open = false
          runAction(action, selection)
        }
      }
    }
  }
}

// Show and hide are done at once, the switches and the deletion ask first.
private fun runAction(action: PointAction, selection: SnapshotStateList<Long>) {
  val ids = selection.toList()
  when (action) {
    PointAction.SHOW -> ids.forEach { id -> PointStore.update(id) { it.copy(visible = true) } }
    PointAction.HIDE -> ids.forEach { id -> PointStore.update(id) { it.copy(visible = false) } }
    PointAction.PLAN -> AppDialog.confirm(R.string.visit_confirm, ids.size, icon = Icons.Outlined.Flag, confirmIcon = Icons.Outlined.Flag) {
      ids.forEach { id ->
        PointStore.update(id) { point ->
          point.copy(status = if (point.status == PointStatus.PLANNED) PointStatus.INDEPENDENT else PointStatus.PLANNED)
        }
      }
      ModuleHost.requestRedraw()
    }
    PointAction.VISIT -> AppDialog.confirm(R.string.visited_confirm, ids.size, icon = Icons.Outlined.CheckCircle, confirmIcon = Icons.Outlined.CheckCircle) {
      ids.forEach { id -> PointStore.update(id) { it.copy(visited = !it.visited) } }
      ModuleHost.requestRedraw()
    }
    PointAction.AUTO_VISIT -> AppDialog.confirm(R.string.auto_visit_confirm, ids.size, icon = Icons.Outlined.MyLocation, confirmIcon = Icons.Outlined.MyLocation) {
      ids.forEach { id -> PointStore.update(id) { it.copy(autoVisit = !it.autoVisit) } }
    }
    // The control needs a schedule: a point without one keeps it off, as the point menu does.
    PointAction.WARNING -> AppDialog.confirm(R.string.control_confirm, ids.size, icon = Icons.Outlined.NotificationsActive, confirmIcon = Icons.Outlined.NotificationsActive) {
      ids.forEach { id ->
        PointStore.update(id) { point -> point.copy(scheduleControl = !point.scheduleControl && point.controlPossible) }
      }
      ModuleHost.requestRedraw()
    }
    PointAction.DELETE -> PointDialogs.delete(ids)
  }
  if (action == PointAction.SHOW || action == PointAction.HIDE) ModuleHost.requestRedraw()
}
