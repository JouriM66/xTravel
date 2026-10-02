// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

// Selection of data for the whole application: the same tree serves sharing, importing and clearing. The tree holds the points,
// the routes, the notes and the tracks; a point and the notes are taken whole, with everything they hold.
// Leaving the sheet cancels the operation.

/**
* Selects sharing, importing or clearing in the data-selection sheet.
*/
enum class DataOperation { 
  /** Export selected application data. */
  SHARE, 
  /** Import selected prepared data. */
  IMPORT, 
  /** Delete selected application data. */
  CLEAR }

/** Что показывает дерево: данные приложения или прочитанные из файла импорта (source) */
class DataContent(
  val points: List<MapPoint>,
  val routes: List<RouteRecord>,
  val notes: MetaInfo?,
  val tracks: List<TrackHeader>,
  val source: ImportSource? = null
) {
  val imported get() = source != null
  val findDuplicates = source?.data?.fromGpx == false /** Треки из GPX добавляются всегда, дубли среди них не ищутся */

  /** Размер файла данных по пути формата обмена; null - узнать нельзя */
  fun fileSize(path: String): Long? = if (source != null) source.fileSize(path) else File(AppDirs.base, path).takeIf { it.isFile }?.length()
}

private const val KEY_POINTS = "points"
private const val KEY_NOTE_LIST = "note-list"
private const val KEY_TRACKS = "tracks"
private const val KEY_ROUTES = "routes"

// How much data a row stands for: the files it holds and their size on disk. Text lives inside data.xml, so it adds size but no file.
// A size that cannot be learned makes the whole sum unknown, then only the files are shown.
private data class DataAmount(val files: Int, val bytes: Long?) {

  operator fun plus(other: DataAmount) = DataAmount(files + other.files, if (bytes == null || other.bytes == null) null else bytes + other.bytes)

  fun text() = listOfNotNull(files.takeIf { it > 0 }?.let { formatCount(it) }, bytes?.takeIf { it > 0 }?.let { formatCount(it) }).joinToString(" · ")

  companion object {
    val NONE = DataAmount(0, 0)
  }
}

private sealed class DataNode {
  abstract val key: String
  open val children: List<DataNode> get() = emptyList()
  // Amount of the row itself; a branch has none of its own, it only sums what is below it.
  open val amount: DataAmount get() = DataAmount.NONE
}

private class GroupNode(override val key: String, @StringRes val label: Int, val icon: Int, override val children: List<DataNode>) : DataNode()

/** Точка целиком; amount - её картинки и тексты */
private class PointNode(val point: MapPoint, override val amount: DataAmount) : DataNode() {
  override val key = "p${point.id}"
}

/** Записки целиком */
private class NotesNode(val notes: MetaInfo, override val amount: DataAmount) : DataNode() {
  override val key = KEY_NOTE_LIST
}

private class RouteNode(val index: Int, val record: RouteRecord) : DataNode() {
  override val key = "r$index"
}

// A track the application already holds is marked and left unchecked; it is loaded only if the user checks it by hand.
private class TrackNode(val header: TrackHeader, val duplicate: Boolean, override val amount: DataAmount) : DataNode() {
  override val key = "t${header.id}"
}

private class TreeRow(val node: DataNode, val level: Int)

/** Дерево выбора данных на шторке для трёх операций */
object DataSelect {

  fun openShare() = open(DataOperation.SHARE, ownData()) {}

  fun openClear() = open(DataOperation.CLEAR, ownData()) {}

  /** Данные файла импорта; onDone - после импорта или его отмены */
  fun openImport(source: ImportSource, onDone: () -> Unit) {
    val data = source.data
    open(DataOperation.IMPORT, DataContent(data.points, data.routes, data.notes, data.tracks, source), onDone)
  }

  private fun ownData() =
    DataContent(PointStore.points.toList(), RouteStore.routes.map(RouteStore::snapshot), NotesStore.notes, TrackStorage.tracks.toList())

  private fun open(operation: DataOperation, content: DataContent, onDone: () -> Unit) {
    BottomSheet.open("data", DataSelectEditor(operation, content, onDone))
    BottomSheet.expand()
  }
}

private class DataSelectEditor(
  private val operation: DataOperation,
  private val content: DataContent,
  private val onDone: () -> Unit
) : SheetEditor() {

  private val nodes = buildNodes(content)
  private val checked = mutableStateMapOf<String, Boolean>()
  private val expanded = mutableStateMapOf<String, Boolean>()
  private var finished = false

  override val caption: String get() = AppSession.context.getString(when (operation) {
    DataOperation.SHARE -> R.string.sheet_data_share
    DataOperation.IMPORT -> R.string.sheet_data_import
    DataOperation.CLEAR -> R.string.sheet_data_clear
  })

  init {
    if (operation != DataOperation.CLEAR) {
      nodes.forEach { check(it, true) }
      nodes.flatMap { it.children }.filterIsInstance<TrackNode>().filter { it.duplicate }.forEach { check(it, false) }
    }
  }

  override val hasToolbar get() = true

  @Composable
  override fun RowScope.ToolbarButtons() {
    // Отправка идёт через общее меню "поделиться": провайдеры, которые принимают отмеченное.
    if (operation == DataOperation.SHARE) return ShareToolbarItem(onShared = {
      finished = true
      BottomSheet.close()
    }) { selectedSet() }
    val icon = when (operation) {
      DataOperation.SHARE -> Icons.Outlined.Share
      DataOperation.IMPORT -> Icons.Outlined.FileDownload
      DataOperation.CLEAR -> Icons.Outlined.Delete
    }
    val label = when (operation) {
      DataOperation.SHARE -> R.string.send
      DataOperation.IMPORT -> R.string.load
      DataOperation.CLEAR -> R.string.delete
    }
    val any = checked.values.any { it }
    ToolbarItem(icon, label, enabled = any) { if (any) execute() }
  }

  @Composable
  override fun Content() {
    DisposableEffect(Unit) {
      onDispose { if (!finished) cancel() }
    }
    val rows = mutableListOf<TreeRow>()
    nodes.forEach { collect(it, 0, rows) }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().scrollIndicator(listState)) {
      items(rows, key = { it.node.key }) { row ->
        NodeRow(row)
        HorizontalDivider()
      }
    }
  }

  private fun collect(node: DataNode, level: Int, rows: MutableList<TreeRow>) {
    rows += TreeRow(node, level)
    if (expanded[node.key] != true) return
    node.children.forEach { collect(it, level + 1, rows) }
  }

  @Composable
  private fun NodeRow(row: TreeRow) {
    val node = row.node
    val open = expanded[node.key] == true
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .height(ROW_HEIGHT)
        .background(Color.White)
        .padding(start = INDENT * row.level),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Box(Modifier.size(ROW_HEIGHT), contentAlignment = Alignment.Center) {
        if (node.children.isNotEmpty()) {
          Icon(
            if (open) Icons.Outlined.ExpandMore else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            stringResource(if (open) R.string.hide else R.string.show),
            modifier = Modifier.clickable { expanded[node.key] = !open }
          )
        }
      }
      Checkbox(checked = checked[node.key] == true, onCheckedChange = { check(node, it) })
      NodeIcon(node)
      Spacer(Modifier.width(6.dp))
      Text(
        nodeLabel(node),
        style = MaterialTheme.typography.bodyMedium,
        color = when {
          node is TrackNode && node.duplicate -> DUPLICATE_COLOR
          else -> Color.Unspecified
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).clickable { if (node.children.isNotEmpty()) expanded[node.key] = !open }
      )
      val amount = rowText(node)
      if (amount.isNotEmpty()) {
        Text(
          amount,
          style = MaterialTheme.typography.bodySmall,
          color = PASSIVE_COLOR,
          maxLines = 1,
          modifier = Modifier.padding(start = 6.dp, end = 8.dp)
        )
      }
    }
  }

  // A point and the notes show how many texts and pictures they hold, a track - its file; a branch shows what is checked below it.
  private fun rowText(node: DataNode): String = when (node) {
    is PointNode -> infoCounts(node.point.info)
    is NotesNode -> infoCounts(node.notes)
    is TrackNode -> node.amount.text()
    is RouteNode -> ""
    is GroupNode -> checkedAmount(node).text()
  }

  private fun checkedAmount(node: DataNode): DataAmount = when {
    node.children.isEmpty() -> if (isChecked(node.key)) node.amount else DataAmount.NONE
    else -> node.children.fold(DataAmount.NONE) { sum, child -> sum + checkedAmount(child) }
  }

  @Composable
  private fun NodeIcon(node: DataNode) {
    when (node) {
      is GroupNode -> Icon(painterResource(node.icon), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
      is PointNode -> PinIcon(node.point, content.imported)
      is NotesNode -> Icon(painterResource(R.drawable.ic_notes), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
      is RouteNode -> Icon(painterResource(R.drawable.ic_route), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
      is TrackNode -> Icon(painterResource(R.drawable.ic_track), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
    }
  }

  @Composable
  private fun nodeLabel(node: DataNode): String = when (node) {
    is GroupNode -> stringResource(node.label)
    is PointNode -> node.point.fullCaption()
    is NotesNode -> stringResource(R.string.notes)
    is RouteNode -> node.record.name.ifEmpty { stringResource(R.string.route) }
    is TrackNode -> if (node.duplicate) "${node.header.name} ${stringResource(R.string.duplicate)}" else node.header.name
  }

  // A check is passed down to everything the node holds.
  private fun check(node: DataNode, value: Boolean) {
    checked[node.key] = value
    node.children.forEach { check(it, value) }
  }

  private fun isChecked(key: String) = checked[key] == true

  private fun selectedPoints(): List<MapPoint> = content.points.filter { isChecked("p${it.id}") }

  private fun selectedNotes(): MetaInfo? = content.notes?.takeIf { isChecked(KEY_NOTE_LIST) && it.elements.isNotEmpty() }

  private fun selectedRoutes() = content.routes.filterIndexed { index, _ -> isChecked("r$index") }

  private fun selectedTracks() = content.tracks.filter { isChecked("t${it.id}") }

  private fun execute() {
    finished = true
    BottomSheet.close()
    when (operation) {
      DataOperation.SHARE -> {}
      DataOperation.IMPORT -> content.source?.let { DataImport.apply(it, selectedSet(), onDone) }
      DataOperation.CLEAR -> clear()
    }
  }

  private fun cancel() = onDone()

  private fun selectedSet() = DataSet(selectedPoints(), selectedRoutes(), selectedTracks(), selectedNotes())

  // Под модальным окном: пока идёт удаление, приложением пользоваться нельзя. Файлы треков удаляются в "io",
  // поэтому окно закрывается, когда очередь "io" дойдёт до конца.
  private fun clear() {
    AppDialog.busy(R.string.data_clearing, Icons.Outlined.Delete)
    afterFrames { clearSelected() }
  }

  private fun clearSelected() {
    val points = selectedPoints()
    var pictures = points.sumOf { it.info.pictures.size }
    if (points.isNotEmpty()) PointStore.remove(points.map { it.id })
    val notes = selectedNotes()
    if (notes != null) {
      pictures += notes.pictures.size
      NotesStore.update { MetaInfo(emptyList()) }
    }
    val tracks = selectedTracks()
    if (tracks.isNotEmpty()) TrackStorage.delete(tracks)
    val routes = content.routes.indices.filter { isChecked("r$it") }
    if (routes.isNotEmpty()) RouteStore.removeAt(routes)
    TrackStorage.io.execute {
      Handler(Looper.getMainLooper()).post {
        AppDialog.close()
        Notify.info(R.string.data_clear_done, points.size, notes?.elements?.size ?: 0, tracks.size, pictures, routes.size)
        onDone()
      }
    }
  }
}

private val DUPLICATE_COLOR = Color(0xFF0C447C)
private val ROW_HEIGHT = 40.dp
private val ICON_SIZE = 28.dp
private val INDENT = 16.dp

// The pin of the list; imported points are not in the application yet, so they get the plain one.
@Composable
private fun PinIcon(point: MapPoint, imported: Boolean) {
  Canvas(Modifier.size(ICON_SIZE)) {
    val pin = size.height * 0.78f
    val tip = Offset(size.width / 2, size.height / 2 + pin / 2 - pin * 0.04f)
    with(PointsLayer) {
      if (imported) drawPlainPin(tip, pin, point.visited) else drawPointPin(point, tip, pin)
    }
  }
}

/** Сколько текстов и картинок у точки или записок: "тексты/картинки"; пусто, если нет ни того, ни другого. FOR LOCAL USE */
private fun infoCounts(info: MetaInfo): String {
  val pictures = info.pictures.size
  val texts = info.elements.size - pictures
  return if (texts == 0 && pictures == 0) "" else "$texts/$pictures"
}

// Size of the items: a picture is its file, a text is what it takes inside data.xml.
private fun infoAmount(info: MetaInfo, content: DataContent): DataAmount = info.elements.fold(DataAmount.NONE) { sum, element ->
  sum + when (element) {
    is PointElement.Text -> DataAmount(0, element.text.toByteArray().size.toLong())
    is PointElement.Picture -> DataAmount(1, content.fileSize(DataIO.picturePath(element.file)))
  }
}

// A track counts as one item, its size is the size of its data file.
private fun trackAmount(header: TrackHeader, content: DataContent) = DataAmount(1, content.fileSize(DataIO.trackPath(header.id)))

private fun buildNodes(content: DataContent): List<DataNode> {
  val notes = content.notes ?: MetaInfo(emptyList())
  return listOf(
    GroupNode(KEY_POINTS, R.string.points, R.drawable.ic_point, content.points.map { PointNode(it, infoAmount(it.info, content)) }),
    GroupNode(KEY_ROUTES, R.string.routes, R.drawable.ic_route, content.routes.mapIndexed { index, record -> RouteNode(index, record) }),
    NotesNode(notes, infoAmount(notes, content)),
    GroupNode(KEY_TRACKS, R.string.tracks, R.drawable.ic_track, content.tracks.map {
      TrackNode(it, content.findDuplicates && TrackStorage.duplicateOf(it) != null, trackAmount(it, content))
    })
  )
}
