// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.Notes
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// Selection of data for the whole application: the same tree serves sharing, importing and clearing. The tree holds the points
// with their items, the routes, the notes and the tracks; the virtual rows "images" and "notes" filter the items of all points at once.
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

// What the tree shows: the data of the application, or the data read from an unpacked exchange file.
/**
* Provides points, notes, track headers and their source directory to the data-selection tree.
*
* Public and subclass/module-facing members:
* - [images] - Images subdirectory of the content's source directory; resolving the path does not create it.
* @param points Selected point records, or a sample count when the parameter is numeric.
* @param routes Route records forming the routes branch.
* @param notes Notes whose elements form the notes branch, or null.
* @param tracks Selected track headers; sample files are resolved by their names.
* @param dir Application or temporary import data root; images are resolved in its images subdirectory.
* @param imported True when the selection is backed by temporary imported data.
* @property points Selected point records, or a sample count when the parameter is numeric.
* @property routes Route records forming the routes branch.
* @property notes Notes whose elements form the notes branch, or null.
* @property tracks Selected track headers; sample files are resolved by their names.
* @property dir Application or temporary import data root; images are resolved in its images subdirectory.
* @property imported True when the selection is backed by temporary imported data.
*/
class DataContent(
  val points: List<MapPoint>,
  val routes: List<RouteRecord>,
  val notes: MetaInfo?, // элементы записок - строки ветки записок
  val tracks: List<TrackHeader>,
  // Data directory the files are read from; for an import it is also the directory to remove when the operation ends.
  val dir: File,
  val imported: Boolean
) {
  /**
  * Images subdirectory of the content's source directory; resolving the path does not create it.
  * @return Images subdirectory of the content's source directory; resolving the path does not create it.
  */
  val images: File get() = DataIO.imagesDir(dir)
}

private const val KEY_POINTS = "points"
private const val KEY_IMAGES = "images"
private const val KEY_NOTES = "notes"
private const val KEY_NOTE_LIST = "note-list"
private const val KEY_TRACKS = "tracks"
private const val KEY_ROUTES = "routes"

// How much data a row stands for: the files it holds and their size on disk. Text lives inside data.xml, so it adds size but no file.
private data class DataAmount(val files: Int, val bytes: Long) {

  val empty get() = files == 0 && bytes == 0L

  operator fun plus(other: DataAmount) = DataAmount(files + other.files, bytes + other.bytes)

  fun text() = listOf(files.takeIf { it > 0 }?.let { formatCount(it) }, bytes.takeIf { it > 0 }?.let { formatCount(it) }).filterNotNull().joinToString(" · ")

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

// Row that carries no data of its own: it only tells whether the items of that kind are processed.
private class FilterNode(override val key: String, @StringRes val label: Int, val icon: ImageVector) : DataNode()

private class PointNode(val point: MapPoint, override val children: List<DataNode>) : DataNode() {
  override val key = "p${point.id}"
}

/** Элемент метаинформации: точки по её id или записок (pointId = null) */
private class ElementNode(val pointId: Long?, val index: Int, val element: PointElement, override val amount: DataAmount) : DataNode() {
  override val key = if (pointId == null) noteKey(index) else "p$pointId#$index"
}

private fun noteKey(index: Int) = "n#$index" /** Ключ строки элемента записок. FOR LOCAL USE */

private class RouteNode(val index: Int, val record: RouteRecord) : DataNode() {
  override val key = "r$index"
}

// A track the application already holds is marked and left unchecked; it is loaded only if the user checks it by hand.
private class TrackNode(val header: TrackHeader, val duplicate: Boolean, override val amount: DataAmount) : DataNode() {
  override val key = "t${header.name}"
}

private class TreeRow(val node: DataNode, val level: Int)

/**
* Opens data-selection editors and coordinates exporting, importing and their completion callbacks.
*
* Public and subclass/module-facing members:
* - [openShare] - Opens a fully expanded selection sheet for exporting current application data.
* - [openClear] - Opens a fully expanded selection sheet for deleting current application data.
* - [openImport] - Opens an import-selection sheet over prepared data and invokes completion when the sheet finishes.
* - [import] - Prepares and applies selected imported data, removes its temporary directory and calls completion.
*/
object DataSelect {

  private val main = Handler(Looper.getMainLooper())

  /**
  * Opens a fully expanded selection sheet for exporting current application data.
  * @return Unit; replaces the current sheet subject to its close guard.
  */
  fun openShare() = open(DataOperation.SHARE, ownData()) {}

  /**
  * Opens a fully expanded selection sheet for deleting current application data.
  * @return Unit; the sheet handles selection and confirmation.
  */
  fun openClear() = open(DataOperation.CLEAR, ownData()) {}

  // Data of an unpacked exchange file; onDone is called after the import or its cancelling, with the directory already removed.
  /**
  * Opens an import-selection sheet over prepared data and invokes completion when the sheet finishes.
  * @param data Loaded source data to publish or prepare for import.
  * @param onDone Completion callback invoked when the asynchronous operation or selection flow finishes.
  * @return Unit; ownership of the temporary import directory is transferred to the sheet.
  */
  fun openImport(data: LoadedData, onDone: () -> Unit) =
    open(DataOperation.IMPORT, DataContent(data.points, data.routes, data.notes, data.tracks, data.dir, imported = true), onDone)

  private fun ownData() =
    DataContent(PointStore.points.toList(), RouteStore.routes.map(RouteStore::snapshot), NotesStore.notes, TrackStorage.tracks.toList(), AppDirs.base, imported = false)

  private fun open(operation: DataOperation, content: DataContent, onDone: () -> Unit) {
    BottomSheet.open("data", DataSelectEditor(operation, content, onDone))
    BottomSheet.expand()
  }

  /**
  * Prepares and applies selected imported data, removes its temporary directory and calls completion.
  * @param data Loaded source data to publish or prepare for import.
  * @param onDone Completion callback invoked when the asynchronous operation or selection flow finishes.
  * @return Unit; file work runs on IO and model updates run on the main thread.
  */
  internal fun import(data: LoadedData, onDone: () -> Unit) {
    TrackStorage.io.execute {
      val plan = runCatching { PointStore.prepareImport(data) }.getOrNull()
      val tracks = runCatching { TrackStorage.importFiles(data.tracks, data.dir) }.getOrDefault(emptyList())
      main.post {
        val points = if (plan == null) 0 else PointStore.applyImport(plan)
        val routes = RouteStore.applyImport(data.routes)
        if (tracks.isNotEmpty()) TrackStorage.onImported(tracks)
        val files = plan?.pictures?.size ?: 0
        Notify.info(R.string.data_import_done, points, data.notes?.elements?.size ?: 0, tracks.size, files, routes)
        data.dir.deleteRecursively()
        onDone()
      }
    }
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
          node is FilterNode -> INACTIVE_COLOR
          else -> Color.Unspecified
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f).clickable { if (node.children.isNotEmpty()) expanded[node.key] = !open }
      )
      val amount = rowAmount(node)
      if (!amount.empty) {
        Text(
          amount.text(),
          style = MaterialTheme.typography.bodySmall,
          color = PASSIVE_COLOR,
          maxLines = 1,
          modifier = Modifier.padding(start = 6.dp, end = 8.dp)
        )
      }
    }
  }

  // A data row always shows what it holds itself; a branch shows what is checked below it, a filter row shows nothing.
  private fun rowAmount(node: DataNode): DataAmount = when (node) {
    is FilterNode -> DataAmount.NONE
    is ElementNode, is TrackNode -> node.amount
    else -> checkedAmount(node)
  }

  private fun checkedAmount(node: DataNode): DataAmount = when (node) {
    is ElementNode -> if (isChecked(node.key) && allowed(node)) node.amount else DataAmount.NONE
    is TrackNode -> if (isChecked(node.key)) node.amount else DataAmount.NONE
    else -> node.children.fold(DataAmount.NONE) { sum, child -> sum + checkedAmount(child) }
  }

  @Composable
  private fun NodeIcon(node: DataNode) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    when (node) {
      is GroupNode -> Icon(painterResource(node.icon), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
      is FilterNode -> Icon(node.icon, null, Modifier.size(ICON_SIZE), tint = color)
      is PointNode -> PinIcon(node.point, content.imported)
      is RouteNode -> Icon(painterResource(R.drawable.ic_route), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
      is TrackNode -> Icon(painterResource(R.drawable.ic_track), null, Modifier.size(ICON_SIZE), tint = Color.Unspecified)
      is ElementNode -> when (val element = node.element) {
        is PointElement.Text -> Icon(Icons.AutoMirrored.Outlined.Notes, null, Modifier.size(ICON_SIZE), tint = color)
        is PointElement.Picture -> SquareThumb(
          element.file,
          Modifier
            .size(ICON_SIZE)
            .clip(RoundedCornerShape(4.dp))
            .clickable { ModalScreen.open("picture") { PictureViewer(element.file, content.images) } },
          content.images
        )
      }
    }
  }

  @Composable
  private fun nodeLabel(node: DataNode): String = when (node) {
    is GroupNode -> stringResource(node.label)
    is FilterNode -> stringResource(node.label)
    is PointNode -> node.point.fullCaption()
    is RouteNode -> node.record.name.ifEmpty { stringResource(R.string.route) }
    is TrackNode -> if (node.duplicate) "${node.header.name} ${stringResource(R.string.duplicate)}" else node.header.name
    is ElementNode -> when (val element = node.element) {
      is PointElement.Text -> element.text.lineSequence().firstOrNull()?.trim().orEmpty()
      is PointElement.Picture -> element.file
    }
  }

  // A check is passed down to everything the node holds.
  private fun check(node: DataNode, value: Boolean) {
    checked[node.key] = value
    node.children.forEach { check(it, value) }
  }

  private fun isChecked(key: String) = checked[key] == true

  // Items of the kind the virtual rows switched off are not processed at all. The rows belong to the points branch:
  // the notes are other data, they only share the format of the point, so their items are ruled by their own checks alone.
  private fun allowed(element: PointElement) = when (element) {
    is PointElement.Text -> isChecked(KEY_NOTES)
    is PointElement.Picture -> isChecked(KEY_IMAGES)
  }

  private fun allowed(node: ElementNode) = node.pointId == null || allowed(node.element)

  // A point goes into the operation with its own check; without it only the items checked inside it are taken.
  private fun selectedPoints(): List<MapPoint> = content.points.mapNotNull { point ->
    val elements = point.elements.filterIndexed { index, element -> isChecked("p${point.id}#$index") && allowed(element) }
    if (isChecked("p${point.id}") || elements.isNotEmpty()) point.copy(info = MetaInfo(elements)) else null
  }

  // Notes go with the items that are checked; nothing checked means no notes in the operation.
  private fun selectedNotes(): MetaInfo? {
    val notes = content.notes ?: return null
    val elements = notes.elements.filterIndexed { index, _ -> isChecked(noteKey(index)) }
    return if (elements.isEmpty()) null else MetaInfo(elements)
  }

  private fun selectedRoutes() = content.routes.filterIndexed { index, _ -> isChecked("r$index") }

  private fun selectedTracks() = content.tracks.filter { isChecked("t${it.name}") }

  private fun execute() {
    finished = true
    BottomSheet.close()
    when (operation) {
      DataOperation.SHARE -> {}
      DataOperation.IMPORT -> DataSelect.import(loaded(), onDone)
      DataOperation.CLEAR -> clear()
    }
  }

  private fun cancel() {
    if (content.imported) content.dir.deleteRecursively()
    onDone()
  }

  private fun selectedSet() = DataSet(selectedPoints(), selectedRoutes(), selectedTracks(), selectedNotes())

  private fun loaded(): LoadedData = LoadedData(content.dir).also { data ->
    data.points += selectedPoints()
    data.routes += selectedRoutes()
    data.notes = selectedNotes()
    data.tracks += selectedTracks()
  }

  private fun clear() {
    val removed = mutableListOf<Long>()
    var pictures = 0
    content.points.forEach { point ->
      if (isChecked("p${point.id}")) {
        removed += point.id
        pictures += point.elements.count { it is PointElement.Picture }
        return@forEach
      }
      val drop = point.elements.indices.filter { index -> isChecked("p${point.id}#$index") && allowed(point.elements[index]) }
      if (drop.isEmpty()) return@forEach
      pictures += drop.count { point.elements[it] is PointElement.Picture }
      PointStore.update(point.id) { current -> current.copy(info = MetaInfo(current.elements.filterIndexed { index, _ -> index !in drop })) }
    }
    if (removed.isNotEmpty()) PointStore.remove(removed)
    val noteDrop = content.notes?.elements.orEmpty().indices.filter { index -> isChecked(noteKey(index)) }
    if (noteDrop.isNotEmpty()) {
      pictures += noteDrop.count { content.notes!!.elements[it] is PointElement.Picture }
      NotesStore.update { current -> MetaInfo(current.elements.filterIndexed { index, _ -> index !in noteDrop }) }
    }
    val tracks = selectedTracks()
    if (tracks.isNotEmpty()) TrackStorage.delete(tracks)
    val routes = content.routes.indices.filter { isChecked("r$it") }
    if (routes.isNotEmpty()) RouteStore.removeAt(routes)
    Notify.info(R.string.data_clear_done, removed.size, noteDrop.size, tracks.size, pictures, routes.size)
    onDone()
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

// Size of an item: a picture is its file, a text is what it takes inside data.xml.
private fun elementAmount(element: PointElement, images: File): DataAmount = when (element) {
  is PointElement.Text -> DataAmount(0, element.text.toByteArray().size.toLong())
  is PointElement.Picture -> DataAmount(1, File(images, element.file).length())
}

// A track counts as one item and takes both of its files.
private fun trackAmount(header: TrackHeader, dir: File): DataAmount {
  val tracks = DataIO.tracksDir(dir)
  return DataAmount(1, TrackFiles.dataFile(header.name, tracks).length() + TrackFiles.headerFile(header.name, tracks).length())
}

private fun buildNodes(content: DataContent): List<DataNode> {
  val images = content.images
  val points = content.points.map { point ->
    PointNode(point, point.elements.mapIndexed { index, element -> ElementNode(point.id, index, element, elementAmount(element, images)) })
  }
  val pointChildren = listOf(
    FilterNode(KEY_IMAGES, R.string.images, Icons.Outlined.Image),
    FilterNode(KEY_NOTES, R.string.notes, Icons.AutoMirrored.Outlined.Notes)
  ) + points
  return listOf(
    GroupNode(KEY_POINTS, R.string.points, R.drawable.ic_point, pointChildren),
    GroupNode(KEY_ROUTES, R.string.routes, R.drawable.ic_route, content.routes.mapIndexed { index, record -> RouteNode(index, record) }),
    GroupNode(
      KEY_NOTE_LIST,
      R.string.notes,
      R.drawable.ic_notes,
      content.notes?.elements.orEmpty().mapIndexed { index, element ->
        ElementNode(null, index, element, elementAmount(element, images))
      }
    ),
    GroupNode(KEY_TRACKS, R.string.tracks, R.drawable.ic_track, content.tracks.map { TrackNode(it, content.imported && TrackStorage.duplicateOf(it) != null, trackAmount(it, content.dir)) })
  )
}
