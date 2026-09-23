// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

// Current track first, then the saved ones. A tap gives the menu of the track, a long press opens the selection editor.
/**
* Displays the track list with current recording, saved tracks and selection entry points.
* @return Unit; call from composition within the bottom sheet.
*/
@Composable
fun TracksSheet() = TrackList(selection = null, rememberLazyListState())

/** Корневой редактор шторки треков: список и тулбар с "поделиться" всеми сохранёнными треками */
class TracksListEditor : SheetEditor() {

  override val caption: String get() = AppSession.context.getString(R.string.sheet_tracks)

  override val hasToolbar get() = true

  override fun getContentElementsCount() = TrackStorage.tracks.size

  @Composable
  override fun RowScope.ToolbarButtons() = ShareToolbarItem { DataSelectors.tracks(TrackStorage.tracks.toList()) }

  @Composable
  override fun Content() = TracksSheet()
}

// Multiple selection: the same list with check boxes, opened by a long press on a row. The current track has no check box:
// a long press on it opens the editor with nothing selected.
private class TrackSelectionEditor(first: String?, private val firstIndex: Int, private val firstOffset: Int) : SheetEditor() {

  val selection = mutableStateListOf<String>().apply { first?.let(::add) }

  val jump = mutableStateOf<String?>(null) /** Трек, к которому список прокручивается после касания на карте */

  override val caption: String get() = AppSession.context.getString(R.string.sheet_tracks_select)

  override val hasToolbar get() = true

  override fun getContentElementsCount() = TrackStorage.tracks.size

  @Composable
  override fun RowScope.ToolbarButtons() = TrackSelectionButtons(selection)

  @Composable
  override fun Content() = TrackList(selection, rememberLazyListState(firstIndex, firstOffset), jump)
}

/** Касание трека на карте в режиме выбора: список прокручивается к нему, его выбор переключается; касание текущего
    трека (null) в этом режиме ничего не делает. Возвращает false, если режим выбора не открыт.
*/
fun toggleSelectedTrack(name: String?): Boolean {
  val editor = BottomSheet.topEditor as? TrackSelectionEditor ?: return false
  if (name == null) return true
  if (name in editor.selection) editor.selection.remove(name) else editor.selection.add(name)
  editor.jump.value = name
  return true
}

@Composable
private fun TrackList(selection: SnapshotStateList<String>?, listState: LazyListState, jump: MutableState<String?>? = null) {
  val tracks = TrackStorage.tracks

  val target = TrackStorage.scrollTarget
  val currentTarget = TrackStorage.scrollToCurrent
  LaunchedEffect(currentTarget) {
    if (currentTarget && selection == null) {
      listState.scrollToItem(0)
      TrackStorage.scrollToCurrent = false
    }
  }
  LaunchedEffect(target, tracks.size) {
    if (target == null || selection != null) return@LaunchedEffect
    val index = tracks.indexOfFirst { it.name == target }
    if (index >= 0) {
      listState.animateScrollToItem(index + 1)
      TrackStorage.scrollTarget = null
    }
  }
  val jumpTarget = jump?.value
  LaunchedEffect(jumpTarget) {
    val index = tracks.indexOfFirst { it.name == jumpTarget }
    if (index >= 0) listState.animateScrollToItem(index + 1)
    jump?.value = null
  }
  LaunchedEffect(tracks.size) {
    if (selection == null) return@LaunchedEffect
    val names = tracks.map { it.name }.toSet()
    selection.retainAll { it in names }
  }

  LazyColumn(state = listState, modifier = Modifier.fillMaxSize().scrollIndicator(listState)) {
    item(key = ":current") {
      CurrentTrackRow(selection) {
        BottomSheet.push(TrackSelectionEditor(null, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
      }
      HorizontalDivider()
    }
    items(tracks, key = { it.name }) { header ->
      TrackRow(header, selection) {
        BottomSheet.push(TrackSelectionEditor(header.name, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset))
      }
      HorizontalDivider()
    }
  }
}

// In the selection mode a tap on the body of the row switches its selection; the visibility button works as usual.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackRow(header: TrackHeader, selection: SnapshotStateList<String>?, onLongClick: () -> Unit) {
  val selected = selection != null && header.name in selection
  val highlighted = selection == null && header.highlighted
  var menu by remember { mutableStateOf(false) }
  val toggle: () -> Unit = {
    if (selection != null) {
      if (header.name in selection) selection.remove(header.name) else selection.add(header.name)
    }
  }
  SwipeRevealRow(enabled = selection == null, onDelete = { TrackActions.delete(listOf(header)) {} }) {
    Box {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 36.dp)
          .background(
            when {
              selected -> SELECTED_BACKGROUND
              highlighted -> HIGHLIGHT_BACKGROUND
              else -> Color.White
            }
          )
          .combinedClickable(
            onClick = { if (selection != null) toggle() else menu = true },
            onLongClick = if (selection == null) onLongClick else null
          )
          .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        if (selection != null) Checkbox(checked = selected, onCheckedChange = { toggle() })
        EyeButton(header.visible) { TrackStorage.setVisible(header, !header.visible) }
        Text(header.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pointsText(header), color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        TrackMenu(header) { menu = false }
      }
    }
  }
}

// Menu of a track tapped on the map: to the list and the places of the track, sharing as a submenu. A name of null is the
// track being recorded now, it has no row of its own to mark.
/**
* Displays actions for a saved track or the current recording, including export-format selection.
* @param name Saved-track filename stem, or null to show actions for the active recording.
* @param close Callback closing the owning popup before a selected action proceeds.
* @receiver Menu column scope in which rows are emitted.
* @return Unit; emits menu content in the receiver ColumnScope.
*/
@Composable
fun ColumnScope.TrackMapMenu(name: String?, close: () -> Unit) {
  var share by remember { mutableStateOf(false) }
  val header = if (name == null) TrackRecorder.current else TrackStorage.find(name)
  val set = trackSet(header, name == null)
  if (share) return PopupShareMenu(set, close)
  MenuItem(Icons.AutoMirrored.Outlined.List, stringResource(R.string.to_list)) {
    close()
    name?.let(TrackStorage::focus)
    BottomSheet.open("tracks", TracksListEditor())
  }
  MenuItem(Icons.Outlined.Info, stringResource(R.string.properties), enabled = header != null) {
    close()
    BottomSheet.push(TrackPropertiesEditor(name))
  }
  MenuItem(Icons.Outlined.Flag, stringResource(R.string.go_to_start), enabled = header != null) {
    close()
    header?.let(TrackActions::goToStart)
  }
  MenuItem(Icons.Outlined.SportsScore, stringResource(R.string.go_to_end), enabled = header != null && header.complete) {
    close()
    header?.let(TrackActions::goToEnd)
  }
  ShareMenuItem(set) { share = true }
}

/** Набор трека для "поделиться"; у текущего трека файл есть не сразу, без файла набор пуст. FOR LOCAL USE */
private fun trackSet(header: TrackHeader?, current: Boolean) =
  if (header == null || (current && !TrackRecorder.hasFile)) DataSet() else DataSelectors.tracks(listOf(header))

// Actions on one track; the share providers open in place of the list.
@Composable
private fun ColumnScope.TrackMenu(header: TrackHeader, close: () -> Unit) {
  var share by remember { mutableStateOf(false) }
  val set = trackSet(header, current = false)
  if (share) return PopupShareMenu(set, close)
  MenuItem(Icons.Outlined.Info, stringResource(R.string.properties)) {
    close()
    BottomSheet.push(TrackPropertiesEditor(header.name))
  }
  MenuItem(Icons.Outlined.Edit, stringResource(R.string.rename)) {
    close()
    TrackActions.rename(header) {}
  }
  MenuItem(Icons.Outlined.Flag, stringResource(R.string.go_to_start)) {
    close()
    TrackActions.goToStart(header)
  }
  MenuItem(Icons.Outlined.SportsScore, stringResource(R.string.go_to_end), enabled = header.complete) {
    close()
    TrackActions.goToEnd(header)
  }
  ShareMenuItem(set) { share = true }
  MenuItem(Icons.Outlined.Delete, stringResource(R.string.delete)) {
    close()
    TrackActions.delete(listOf(header)) {}
  }
}

// The same actions for the current track: renaming and properties always, the rest when there are samples (sharing: a file).
@Composable
private fun ColumnScope.CurrentTrackMenu(close: () -> Unit) {
  var share by remember { mutableStateOf(false) }
  val header = TrackRecorder.current
  val set = trackSet(header, current = true)
  if (share) return PopupShareMenu(set, close)
  MenuItem(Icons.Outlined.Info, stringResource(R.string.properties)) {
    close()
    BottomSheet.push(TrackPropertiesEditor(null))
  }
  MenuItem(Icons.Outlined.Edit, stringResource(R.string.rename)) {
    close()
    TrackActions.renameCurrent()
  }
  MenuItem(Icons.Outlined.Flag, stringResource(R.string.go_to_start), enabled = header != null) {
    close()
    header?.let(TrackActions::goToStart)
  }
  MenuItem(Icons.Outlined.SportsScore, stringResource(R.string.go_to_end), enabled = header != null) {
    close()
    header?.let(TrackActions::goToEnd)
  }
  ShareMenuItem(set) { share = true }
  HorizontalDivider()
  MenuItem(Icons.Outlined.Delete, stringResource(R.string.delete), enabled = header != null) {
    close()
    TrackActions.deleteCurrent {}
  }
}

// Number of points; unknown for an unfinished track whose data is not loaded.
private fun pointsText(header: TrackHeader) = if (header.complete) formatCount(header.points) else "—"

/**
* Implements user-facing track navigation, renaming, deletion and export actions.
*
* Public and subclass/module-facing members:
* - [goToStart] - Centers the map on a track's first position in manual camera mode.
* - [goToEnd] - Centers the map on a track's final recorded position in manual mode.
* - [rename] - Opens a name editor and renames a saved track after validation.
* - [renameCurrent] - Opens a name editor for the active recording and displays validation errors.
* - [delete] - Asks for confirmation with selected track and sample counts, then queues deletion.
* - [deleteCurrent] - Asks before deleting the active recording and starting an empty one.
*/
object TrackActions {

  /**
  * Centers the map on a track's first position in manual camera mode.
  * @param header Track metadata used by the requested operation.
  * @return Unit; updates Maps.
  */
  fun goToStart(header: TrackHeader) = Maps.centerOn(GeoPoint(header.startLat, header.startLon))

  /**
  * Centers the map on a track's final recorded position in manual mode.
  * @param header Track metadata used by the requested operation.
  * @return Unit; callers should offer this only for a usable endpoint.
  */
  fun goToEnd(header: TrackHeader) = Maps.centerOn(GeoPoint(header.endLat, header.endLon))

  /**
  * Opens a name editor and renames a saved track after validation.
  * @param header Track metadata used by the requested operation.
  * @param onRenamed Receives the accepted new track filename stem.
  * @return Unit; successful renaming invokes onRenamed.
  */
  fun rename(header: TrackHeader, onRenamed: (String) -> Unit) {
    AppDialog.input(R.string.rename, header.name) { name ->
      when (TrackStorage.rename(header, name)) {
        RenameResult.OK -> {
          onRenamed(name)
          null
        }
        RenameResult.EXISTS -> R.string.name_exists
        RenameResult.INVALID -> R.string.name_invalid
      }
    }
  }

  /**
  * Opens a name editor for the active recording and displays validation errors.
  * @return Unit; physical rename work is delegated to TrackRecorder.
  */
  fun renameCurrent() {
    AppDialog.input(R.string.rename, TrackRecorder.name.orEmpty()) { name ->
      when (TrackRecorder.rename(name)) {
        RenameResult.OK -> null
        RenameResult.EXISTS -> R.string.name_exists
        RenameResult.INVALID -> R.string.name_invalid
      }
    }
  }

  // Only the known numbers of points are counted.
  /**
  * Asks for confirmation with selected track and sample counts, then queues deletion.
  * @param headers Track metadata records selected for the operation.
  * @param onDeleted Continuation after deletion has been requested, not necessarily after all file IO completes.
  * @return Unit; onDeleted runs after deletion is requested, not after every file operation finishes.
  */
  fun delete(headers: List<TrackHeader>, onDeleted: () -> Unit) {
    if (headers.isEmpty()) return
    val points = headers.filter { it.complete }.sumOf { it.points }
    confirmDelete(headers.singleOrNull()?.name, headers.size, points) {
      TrackStorage.delete(headers)
      onDeleted()
    }
  }

  // The data of the current track is deleted and the recording starts anew; without samples nothing happens.
  /**
  * Asks before deleting the active recording and starting an empty one.
  * @param onDeleted Continuation after deletion has been requested, not necessarily after all file IO completes.
  * @return Unit; an empty recording does nothing.
  */
  fun deleteCurrent(onDeleted: () -> Unit) {
    val points = TrackRecorder.pointCount
    if (points == 0) return
    confirmDelete(TrackRecorder.name.orEmpty(), 1, points) {
      TrackRecorder.discard()
      onDeleted()
    }
  }

  // name: the only track, null for several.
  private fun confirmDelete(name: String?, count: Int, points: Int, onConfirm: () -> Unit) = AppDialog.show {
    val single = name != null
    val pointsText = when {
      points == 0 -> stringResource(if (single) R.string.track_points_unknown else R.string.tracks_points_unknown)
      single -> pluralStringResource(R.plurals.track_points_saved, points, points)
      else -> pluralStringResource(R.plurals.tracks_points_saved, points, points)
    }
    val title = if (name != null) stringResource(R.string.delete_track_title, name) else pluralStringResource(R.plurals.delete_tracks_title, count, count)
    AlertDialog(
      onDismissRequest = AppDialog::close,
      icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
      title = { Text(title) },
      text = { Text(pointsText + "\n" + stringResource(if (single) R.string.delete_track_question else R.string.delete_tracks_question)) },
      confirmButton = {
        DialogButton(Icons.Outlined.Delete, R.string.delete, primary = true) {
          AppDialog.close()
          onConfirm()
        }
      },
      dismissButton = { CancelButton(AppDialog::close) }
    )
  }
}

// The current track behaves like the saved ones, but it is never selected; Rec finishes it and starts a new one.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CurrentTrackRow(selection: SnapshotStateList<String>?, onLongClick: () -> Unit) {
  var menu by remember { mutableStateOf(false) }
  val selecting = selection != null
  SwipeRevealRow(enabled = !selecting, onDelete = { TrackActions.deleteCurrent {} }) {
    Box {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 36.dp)
          .background(if (!selecting && TrackStorage.currentHighlighted) HIGHLIGHT_BACKGROUND else Color.White)
          .combinedClickable(onClick = { if (!selecting) menu = true }, onLongClick = if (!selecting) onLongClick else null)
          .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        EyeButton(TrackRecorder.visible) {
          TrackRecorder.visible = !TrackRecorder.visible
          DataStore.scheduleSave()
          ModuleHost.requestRedraw()
        }
        Text(TrackRecorder.name ?: "—", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatCount(TrackRecorder.pointCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButtonImage(R.drawable.ic_rec, R.string.new_track, size = 24, enabled = !selecting) {
          AppDialog.confirm(R.string.rec_confirm, icon = Icons.Outlined.FiberManualRecord, confirmIcon = Icons.Outlined.Refresh, confirmLabel = R.string.new_track) {
            TrackRecorder.restart()
          }
        }
      }
      DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
        CurrentTrackMenu { menu = false }
      }
    }
  }
}

// Actions keep the editor open and clear the selection.
@Composable
private fun TrackSelectionButtons(selection: SnapshotStateList<String>) {
  val selected = selection.mapNotNull(TrackStorage::find)
  val clear = { selection.clear() }
  Text(selection.size.toString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
  ShareToolbarItem(onShared = clear) { DataSelectors.tracks(selected) }
  ToolbarItem(Icons.AutoMirrored.Outlined.CallMerge, R.string.merge, enabled = selected.size >= 2) {
    if (selected.size >= 2) showMergeDialog(selected, clear)
  }
  ToolbarItem(Icons.Outlined.Delete, R.string.delete, enabled = selected.isNotEmpty()) { TrackActions.delete(selected, clear) }
}

private fun showMergeDialog(selected: List<TrackHeader>, onDone: () -> Unit) {
  AppDialog.show {
    AlertDialog(
      onDismissRequest = AppDialog::close,
      icon = { Icon(Icons.AutoMirrored.Outlined.CallMerge, contentDescription = null) },
      title = { Text(stringResource(R.string.merge_title, selected.size)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          DialogButton(Icons.Outlined.DeleteSweep, R.string.merge_delete, primary = true, fullWidth = true) {
            AppDialog.close()
            TrackStorage.merge(selected, deleteSources = true)
            onDone()
          }
          DialogButton(Icons.Outlined.ContentCopy, R.string.merge_keep, fullWidth = true) {
            AppDialog.close()
            TrackStorage.merge(selected, deleteSources = false)
            onDone()
          }
        }
      },
      confirmButton = {},
      dismissButton = { CancelButton(AppDialog::close) }
    )
  }
}

// Name and everything kept about the track; the toolbar has the actions of the track menu. Deleting the track closes the editor.
// name null: the current track, its values come from memory.
private class TrackPropertiesEditor(name: String?) : SheetEditor() {

  private var name by mutableStateOf(name)
  private val isCurrent = name == null

  override val caption: String get() = AppSession.context.getString(R.string.sheet_track_properties)

  override val hasToolbar get() = true

  @Composable
  override fun RowScope.ToolbarButtons() {
    if (isCurrent) {
      val header = TrackRecorder.current
      ToolbarItem(Icons.Outlined.Edit, R.string.rename) { TrackActions.renameCurrent() }
      ToolbarItem(Icons.Outlined.Flag, R.string.go_to_start, enabled = header != null) { header?.let(TrackActions::goToStart) }
      ToolbarItem(Icons.Outlined.SportsScore, R.string.go_to_end, enabled = header != null) { header?.let(TrackActions::goToEnd) }
      ShareToolbarItem { trackSet(header, current = true) }
      ToolbarItem(Icons.Outlined.Delete, R.string.delete, enabled = header != null) { TrackActions.deleteCurrent { BottomSheet.back() } }
      return
    }
    val header = name?.let(TrackStorage::find) ?: return
    ToolbarItem(Icons.Outlined.Edit, R.string.rename) { TrackActions.rename(header) { name = it } }
    ToolbarItem(Icons.Outlined.Flag, R.string.go_to_start) { TrackActions.goToStart(header) }
    ToolbarItem(Icons.Outlined.SportsScore, R.string.go_to_end, enabled = header.complete) { if (header.complete) TrackActions.goToEnd(header) }
    ShareToolbarItem { trackSet(header, current = false) }
    ToolbarItem(Icons.Outlined.Delete, R.string.delete) { TrackActions.delete(listOf(header)) { BottomSheet.back() } }
  }

  @Composable
  override fun Content() {
    val header = if (isCurrent) TrackRecorder.current else name?.let(TrackStorage::find) ?: return
    val tableWidth = remember { mutableStateOf(0.dp) }

    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().scrollIndicator(scroll).verticalScroll(scroll).padding(5.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {

      Text(if (isCurrent) TrackRecorder.name ?: "—" else header?.name.orEmpty(), style = MaterialTheme.typography.headlineSmall)

      if (header == null) {
        AlignedProperty(R.string.track_points, "0", tableWidth)
        return@Column
      }
      val known = header.complete || isCurrent

      if ( header.points > 0 )
       AlignedProperty( R.string.track_points, header.points.toString(), tableWidth )

      if ( known )
        AlignedProperty(
          R.string.track_duration,
          formatDuration(header.end - header.start),
          tableWidth )

      AlignedProperty(
        R.string.track_start,
        "${formatTime(header.start)}\n${formatCoordinates(GeoPoint(header.startLat, header.startLon))}",
        tableWidth )
      if ( known ) {
        AlignedProperty(
          R.string.track_end,
          "${formatTime(header.end)}\n${formatCoordinates(GeoPoint(header.endLat, header.endLon))}",
          tableWidth )
        AlignedProperty(
          R.string.track_area,
          "${formatCoordinates(GeoPoint(header.minLat, header.minLon))}\n${formatCoordinates(GeoPoint(header.maxLat, header.maxLon))}",
          tableWidth )
      }
    }
  }

  private fun formatTime(ms: Long): String = DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

  private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds % 3600 / 60, seconds % 60)
  }
}
