// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Straight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import android.os.SystemClock
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex

// Sheet of the selected route: its stops in travel order, the ways between them and the actions over the whole route.

// FOR LOCAL USE: height of a row, measured in lines of the list text.
private const val ROUTE_ROW_LINES = 2f
private val ROW_PADDING = 2.dp
private val PIN_SIZE = 28.dp

/**
* Shows the stops of the selected route with their ways, and the toolbar acting on the whole route.
*
* Public and subclass/module-facing members:
* - [hasToolbar] - Always true: the route sheet brings its own toolbar.
* - [ToolbarButtons] - Emits the transport, run, calculate and sort buttons.
* - [Content] - Emits the list of the stops.
*/
class RouteSheet : SheetEditor() {

  override val caption: String get() = AppSession.context.getString(R.string.sheet_route)

  /**
  * Always true: the route sheet brings its own toolbar.
  * @return Always true: the route sheet brings its own toolbar.
  */
  override val hasToolbar get() = true

  /**
  * Number of stops of the selected route.
  * @return Number of stops of the selected route.
  */
  override fun getContentElementsCount() = RouteStore.selected?.entries?.size ?: 0

  /**
  * Emits the transport, run, calculate and sort buttons.
  * @receiver Toolbar row scope.
  * @return Unit; emits the toolbar buttons.
  */
  @Composable
  override fun RowScope.ToolbarButtons() = RouteToolbar()

  /**
  * Emits the list of the stops.
  * @return Unit; emits the stop list.
  */
  @Composable
  override fun Content() = RouteList()
}

// Building and sorting belong to the planning of the route, so while it is being travelled they are not offered. A
// button whose work is done by the application itself is framed: the setting says so, and pressing it still does the work.
@Composable
private fun RowScope.RouteToolbar() {
  TransportButton()
  if (RouteStore.active) {
    ToolbarItem(Icons.Outlined.Stop, R.string.route_stop, tint = DELETE_ICON_COLOR) { askRouteStop() }
  } else {
    ToolbarItem(Icons.Outlined.PlayArrow, R.string.route_start, tint = START_ICON_COLOR) { RouteStore.start() }
    ToolbarItem(Icons.AutoMirrored.Outlined.AltRoute, R.string.route_calculate, selected = Settings.routeAutoCalculate.value) {
      RouteStore.calculate()
    }
    ToolbarItem(Icons.AutoMirrored.Outlined.Sort, R.string.sort, selected = Settings.routeAutoSort.value) { RouteStore.sort() }
  }
  ShareToolbarItem { RouteStore.selected?.let { DataSelectors.routes(listOf(it)) } ?: DataSet() }
  Spacer(Modifier.weight(1f))
  ToolbarItem(Icons.Outlined.Delete, R.string.route_clear_stops) {
    askClear(R.string.route_clear_stops_question) { RouteStore.clearStops() }
  }
}

/** Единый вопрос об остановке навигации: его задают кнопка тулбара шторки и элемент навигации верхнего тулбара */
fun askRouteStop() = AppDialog.confirm(R.string.route_stop_question) { RouteStore.stop() }

// FOR LOCAL USE: a cleaning is agreed with the user; while the navigation runs it also takes the travel away.
private fun askClear(@StringRes question: Int, action: () -> Unit) {
  if (!RouteStore.active) return AppDialog.confirm(question) { action() }
  AppDialog.confirm(R.string.route_clear_navigation_question) {
    RouteStore.stop()
    action()
  }
}

/**
* Asks what to do with the navigation when a change makes the route another travel.
* @param onRecalculate Called when the change is accepted and the route is to be built again.
* @param onStop Called when the change is accepted and the navigation is to be stopped.
* @return Unit; refusing the question calls nothing.
*/
fun askRouteChange(onRecalculate: () -> Unit, onStop: () -> Unit) = AppDialog.show {
  AlertDialog(
    onDismissRequest = { AppDialog.close() },
    icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.route_change_question))
        DialogButton(Icons.Outlined.Refresh, R.string.route_change_recalculate, primary = true, fullWidth = true) {
          AppDialog.close()
          onRecalculate()
        }
        DialogButton(Icons.Outlined.Stop, R.string.route_change_stop, danger = true, fullWidth = true) {
          AppDialog.close()
          onStop()
        }
        DialogButton(Icons.Outlined.Close, R.string.cancel, fullWidth = true) { AppDialog.close() }
      }
    },
    confirmButton = {}
  )
}

/** Вопрос при запуске навигации, когда у части участков нет геометрии */
fun askStart(onCalculate: () -> Unit, onDirections: () -> Unit) = AppDialog.show {
  AlertDialog(
    onDismissRequest = { AppDialog.close() },
    icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.route_missing_question))
        DialogButton(Icons.AutoMirrored.Outlined.AltRoute, R.string.route_calculate, primary = true, fullWidth = true) {
          AppDialog.close()
          onCalculate()
        }
        DialogButton(Icons.Outlined.Straight, R.string.route_directions, fullWidth = true) {
          AppDialog.close()
          onDirections()
        }
        DialogButton(Icons.Outlined.Close, R.string.cancel, fullWidth = true) { AppDialog.close() }
      }
    },
    confirmButton = {}
  )
}

/** Вопрос при навигации, когда участок потерял геометрию: перестроить или идти по направлению.
    Закрытие без ответа вызывает onDismiss: без геометрии навигация продолжаться не может.
*/
fun askRebuild(onRebuild: () -> Unit, onDirections: () -> Unit, onDismiss: () -> Unit) = AppDialog.show {
  AlertDialog(
    onDismissRequest = {
      AppDialog.close()
      onDismiss()
    },
    icon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = null) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.route_rebuild_question))
        DialogButton(Icons.Outlined.Refresh, R.string.route_rebuild, primary = true, fullWidth = true) {
          AppDialog.close()
          onRebuild()
        }
        DialogButton(Icons.Outlined.Straight, R.string.route_directions, fullWidth = true) {
          AppDialog.close()
          onDirections()
        }
      }
    },
    confirmButton = {}
  )
}

/** Ожидание ответов на запросы геометрий с прошедшим временем; закрывается само, когда ответы пришли */
fun showRouteWaiting() = AppDialog.show {
  val since = RouteStore.requestedAt
  LaunchedEffect(since) { if (since == null) AppDialog.close() }
  AppClock.tick
  val seconds = since?.let { (SystemClock.elapsedRealtime() - it) / 1000 } ?: 0L
  AlertDialog(
    onDismissRequest = { AppDialog.close() },
    icon = { Icon(Icons.AutoMirrored.Outlined.AltRoute, contentDescription = null) },
    text = { Text(stringResource(R.string.route_waiting, seconds)) },
    confirmButton = {
      DialogButton(Icons.Outlined.Stop, R.string.route_abort, danger = true) {
        AppDialog.close()
        RouteStore.abort()
      }
    },
    dismissButton = { DialogButton(Icons.Outlined.Close, R.string.close) { AppDialog.close() } }
  )
}

// The transport button shows the setting and changes it; its label names the way of travelling that is chosen now.
@Composable
private fun TransportButton() {
  var menu by remember { mutableStateOf(false) }
  val current = Settings.routeTransport.value
  Box {
    ToolbarItem(current.icon, current.label) { menu = true }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      TransportKind.entries.forEach { kind ->
        MenuItem(kind.icon, stringResource(kind.label)) {
          menu = false
          Settings.routeTransport.value = kind
        }
      }
    }
  }
}

@Composable
private fun RouteList() {
  val route = RouteStore.selected
  val entries = route?.entries.orEmpty()
  val listState = rememberLazyListState()
  var draggingId by remember { mutableStateOf<Long?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val style = MaterialTheme.typography.bodyMedium
  val height = with(LocalDensity.current) { (style.lineHeight * ROUTE_ROW_LINES).toDp() } + ROW_PADDING * 2

  val target = RouteStore.scrollTarget
  LaunchedEffect(target, entries.size) {
    val index = entries.indexOfFirst { it === target }
    if (index >= 0) {
      listState.animateScrollToItem(index)
      RouteStore.scrollTarget = null
    }
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize().scrollIndicator(listState)) {
    items(entries, key = { it.id }) { entry ->
      val dragged = draggingId == entry.id
      Column(
        Modifier
          .zIndex(if (dragged) 1f else 0f)
          .graphicsLayer { translationY = if (dragged) dragOffset else 0f }
          .background(Color.White)
      ) {
        SwipeRevealRow(enabled = true, onDelete = { RouteStore.removeEntry(entry) }) {
          EntryRow(entry, height) {
            // The order of an automatically sorted route is not the business of the user, so it cannot be dragged.
            if (Settings.routeAutoSort.value) return@EntryRow
            DragHandle(entry.id, listState, onStart = {
              draggingId = entry.id
              dragOffset = 0f
              RouteStore.beginReorder()
            }, onEnd = {
              draggingId = null
              dragOffset = 0f
              RouteStore.endReorder()
            }) { dy ->
              dragOffset += dy
              dragOffset = reorder(entry.id, dragOffset, listState)
            }
          }
        }
        HorizontalDivider()
      }
    }
  }
}

// Swaps the dragged row with a neighbour once it passes half of the neighbour; returns the remaining offset.
private fun reorder(entryId: Long, offset: Float, listState: LazyListState): Float {
  val entries = RouteStore.selected?.entries ?: return offset
  val items = listState.layoutInfo.visibleItemsInfo
  val current = items.firstOrNull { it.key == entryId } ?: return offset
  val index = entries.indexOfFirst { it.id == entryId }
  if (offset > 0) {
    val next = items.firstOrNull { it.index == current.index + 1 } ?: return offset
    if (offset > next.size / 2f) {
      RouteStore.moveEntry(index, index + 1)
      return offset - next.size
    }
  } else if (offset < 0) {
    val previous = items.firstOrNull { it.index == current.index - 1 } ?: return offset
    if (-offset > previous.size / 2f) {
      RouteStore.moveEntry(index, index - 1)
      return offset + previous.size
    }
  }
  return offset
}

@Composable
private fun EntryRow(entry: RouteEntry, height: Dp, dragHandle: @Composable () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(height)
      .background(if (entry.highlighted) HIGHLIGHT_BACKGROUND else Color.White),
    verticalAlignment = Alignment.CenterVertically
  ) {
    PinCell(entry)
    WaysCell(entry)
    MainField(entry, Modifier.weight(1f).fillMaxHeight())
    dragHandle()
  }
}

// The pin of the stop opens its menu. A stop whose place holds no point is drawn as a plain planned pin, and the items
// that need a point are left out of its menu.
@Composable
private fun PinCell(entry: RouteEntry) {
  var menu by remember { mutableStateOf(false) }
  val point = entry.point()
  Box(
    modifier = Modifier.fillMaxHeight().width(PIN_SIZE + ROW_PADDING * 2).clickable { menu = true },
    contentAlignment = Alignment.Center
  ) {
    PointPin(point ?: MapPoint(id = 0L, lat = entry.place.lat, lon = entry.place.lon, status = PointStatus.PLANNED))
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      EntryMenu(entry, point) { menu = false }
    }
  }
}

/** Меню булавки остановки - единое меню точки; остановка, чья точка уже пропала, даёт только исключение из маршрута */
@Composable
private fun ColumnScope.EntryMenu(entry: RouteEntry, point: MapPoint?, close: () -> Unit) {
  if (point != null) return PointPopupMenu(point.id, PointMenuSource.ROUTE, close)
  MenuItem(Icons.Outlined.Delete, stringResource(R.string.route_exclude)) {
    close()
    RouteStore.removeEntry(entry)
  }
}

// The icon of the ways tells what is known about the leg leading to the stop and is the only way to change it: one way
// is simply there, several are switched round by round, and a leg without a way is asked for. During the travel the leg
// is not changed from the list at all.
@Composable
private fun WaysCell(entry: RouteEntry) {
  if (RouteStore.active) return
  val ways = entry.ways().size
  val tint = when (ways) {
    0 -> ROUTE_WAY_NONE_COLOR
    1 -> ROUTE_WAY_ONE_COLOR
    else -> ROUTE_WAY_MANY_COLOR
  }
  Box(
    modifier = Modifier.fillMaxHeight().width(PIN_SIZE + ROW_PADDING * 2).clickable { onWays(entry, ways) },
    contentAlignment = Alignment.Center
  ) {
    Icon(ROUTE_WAYS_ICON, contentDescription = null, tint = tint)
    if (ways > 1) WaysBadge(ways, tint)
  }
}

// FOR LOCAL USE: the number of the ways found for the leg, in the corner of its icon.
@Composable
private fun BoxScope.WaysBadge(ways: Int, tint: Color) {
  Text(
    ways.toString(),
    modifier = Modifier.align(Alignment.TopEnd).background(Color.White, CircleShape).padding(horizontal = 2.dp),
    style = MaterialTheme.typography.labelSmall,
    color = tint
  )
}

// FOR LOCAL USE: what the icon of the ways does, by the number of the ways the leg has.
private fun onWays(entry: RouteEntry, ways: Int) {
  if (ways > 1) return RouteStore.nextWay(entry)
  if (ways == 0) AppDialog.confirm(R.string.route_get_way) { RouteStore.recalculate(entry) }
}

/** Имя остановки и справа в той же строке длина участка и время: при планировании - оценка движка, при навигации -
    по усреднённой скорости (у первого участка по оставшейся длине). Нажатие выделяет строку и показывает участок,
    повторное снимает выделение.
*/
@Composable
private fun MainField(entry: RouteEntry, modifier: Modifier) {
  val chosen = entry.chosen()
  val start = RouteStore.legStart(entry)
  val straight = start?.let { GeoMath.distance(it.lat, it.lon, entry.place.lat, entry.place.lon) }
  Box(modifier.clickable { if (RouteStore.toggleHighlight(entry)) showLeg(entry) }.padding(horizontal = 6.dp, vertical = ROW_PADDING)) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        entry.caption(),
        modifier = Modifier.weight(1f),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
      if (chosen != null) {
        val meters = if (entry === RouteStore.activeTarget() && RouteStore.active) chosen.remaining() else chosen.meters
        Numbers(distanceText(meters))
        val seconds = if (RouteStore.active) RouteStore.travelSeconds(meters) else chosen.seconds.takeIf { !chosen.virtual }
        seconds?.let { Numbers(durationText(it), INACTIVE_COLOR) }
      } else {
        straight?.let { Numbers(distanceText(it)) }
      }
    }
  }
}

// FOR LOCAL USE: fits the leg leading to the stop into the map, or the stop itself when no way was found for it.
private fun showLeg(entry: RouteEntry) {
  val line = entry.geometries.flatMap { it.points }
  if (line.isEmpty()) Maps.centerOn(entry.place) else Maps.showArea(line)
}

@Composable
private fun Numbers(text: String, color: Color = Color.Unspecified) {
  Text(text, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1)
}
