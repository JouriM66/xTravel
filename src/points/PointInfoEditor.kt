// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// FOR LOCAL USE: picture row dimensions in the point metadata editor.
private const val PICTURE_HEIGHT_EM = 6f
private val PICTURE_GAP = 2.dp
private val ROW_START = 12.dp

// What the metadata editor starts with when it is opened for a new note or picture.
/**
* Tool the point metadata editor opens with.
*/
enum class PointInfoStart {
  /** Nothing is started; the editor just shows the items. */
  NONE,
  /** The text editor for a new item is opened at once. */
  TEXT,
  /** The camera/gallery menu is opened at once. */
  PICTURE,
  /** The camera is started at once, without asking where the picture comes from. */
  CAMERA
}

// Index of the text editor for a new element; it becomes an element only when confirmed.
/**
* Sentinel index requesting creation of a new point text element.
* @return Sentinel index requesting creation of a new point text element.
*/
const val NEW_ELEMENT = -1

// Data elements of a point as rows: a text, or pictures standing one after another, as many as fit into a line. A row is moved
// by the handle on the right (pictures of a line together). A text gives a menu, a picture opens all pictures of the point.
/**
* Displays and edits point attachments or notes, optionally focusing a matching element.
*
* Usage: The owner is a point or the notes. Attachment changes are applied immediately; focusElement is an element index, not a display-row index.
*
* Public and subclass/module-facing members:
* - [hasToolbar] - True: the attachment editor supplies its own toolbar actions.
* - [ToolbarButtons] - Displays text and picture insertion actions, plus coordinate/geocoder actions for real points.
* - [Content] - Displays editable attachment rows and focuses the requested element when present.
*
* Inherited API: see [SheetEditor] for members not overridden here.
* @param owner Владелец метаинформации: точка или записки.
* @param focusElement Attachment index to scroll to and highlight; a negative index disables initial focus. Default: -1.
*/
class PointInfoEditor(private val owner: InfoOwner, private val focusElement: Int = -1, start: PointInfoStart = PointInfoStart.NONE) : SheetEditor() {

  // The tool asked for at the opening; it is used once, so returning from it does not start it again.
  private var pending = start

  override val caption: String get() = owner.caption

  /**
  * True: the attachment editor supplies its own toolbar actions.
  * @return True: the attachment editor supplies its own toolbar actions.
  */
  override val hasToolbar get() = true

  /**
  * Number of items of the edited point.
  * @return Number of items of the edited point.
  */
  override fun getContentElementsCount() = owner.info?.elements?.size ?: 0

  /**
  * Displays text and picture insertion actions, plus coordinate/geocoder actions for real points.
  * @receiver Toolbar row scope supplying row layout behavior.
  * @return Unit; invoke from composition with a RowScope receiver.
  */
  @Composable
  override fun RowScope.ToolbarButtons() {
    val started = remember { take() }
    val pictures = rememberPictureSources()
    ToolbarItem(Icons.Outlined.TextFields, R.string.text) { editPointText(owner, NEW_ELEMENT) }
    PictureMenu(
      Icons.Outlined.AddPhotoAlternate, R.string.picture, owner,
      replacing = null, pictures = pictures, opened = started == PointInfoStart.PICTURE
    )
    if (owner is InfoOwner.Point) AddMenu(owner.id)
    ShareToolbarItem { owner.shareSet() }
    LaunchedEffect(started) {
      when (started) {
        PointInfoStart.TEXT -> editPointText(owner, NEW_ELEMENT)
        PointInfoStart.CAMERA -> pictures.camera(owner, null)
        else -> {}
      }
    }
  }

  // The requested tool, once.
  private fun take(): PointInfoStart = pending.also { pending = PointInfoStart.NONE }

  /**
  * Displays editable attachment rows and focuses the requested element when present.
  * @return Unit; invoke from composition through the sheet host.
  */
  @Composable
  override fun Content() = PointElements(owner, focusElement)
}

/** Режим выбора строк редактора данных точки и заметок, открывается долгим нажатием на строку: чекбоксы,
    перетаскивание, выбор и удаление выбранного. Выбор хранится ключами элементов, строка картинок выбирается целиком.
*/
private class PointElementsSelectionEditor(private val owner: InfoOwner, first: List<String>) : SheetEditor() {

  val selection = mutableStateListOf<String>().apply { addAll(first) }

  override val caption: String get() = owner.caption

  override val hasToolbar get() = true

  override fun getContentElementsCount() = owner.info?.elements?.size ?: 0

  @Composable
  override fun RowScope.ToolbarButtons() {
    SelectMenu()
    val any = selection.isNotEmpty()
    ShareToolbarItem { if (any) owner.shareSet(selectedElements()) else DataSet() }
    ToolbarItem(Icons.Outlined.Delete, R.string.delete, enabled = any) { if (any) deleteSelected() }
  }

  /** Выбранные элементы по порядку */
  private fun selectedElements(): List<PointElement> {
    val elements = owner.info?.elements.orEmpty()
    val keys = elementKeys(elements)
    return elements.filterIndexed { i, _ -> keys[i] in selection }
  }

  @Composable
  private fun SelectMenu() {
    var open by remember { mutableStateOf(false) }
    Box {
      ToolbarItem(Icons.Outlined.DoneAll, R.string.select) { open = true }
      DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        MenuItem(Icons.Outlined.SelectAll, stringResource(R.string.select_all)) {
          open = false
          selection.clear()
          selection.addAll(keys())
        }
        MenuItem(Icons.Outlined.Deselect, stringResource(R.string.select_none)) {
          open = false
          selection.clear()
        }
        MenuItem(Icons.Outlined.SwapHoriz, stringResource(R.string.select_invert)) {
          open = false
          val inverted = keys().filter { it !in selection }
          selection.clear()
          selection.addAll(inverted)
        }
      }
    }
  }

  private fun keys(): List<String> = elementKeys(owner.info?.elements.orEmpty())

  private fun deleteSelected() {
    val elements = owner.info?.elements ?: return
    val keys = elementKeys(elements)
    val count = keys.count { it in selection }
    if (count == 0) return
    val icon = Icons.Outlined.Delete
    AppDialog.confirmPlural(R.plurals.delete_elements_confirm, count, icon = icon, confirmIcon = icon, confirmLabel = R.string.delete) {
      owner.update { info ->
        val current = elementKeys(info.elements)
        info.elements.forEachIndexed { i, element -> if (current[i] in selection && element is PointElement.Picture) Pictures.forget(element.file) }
        MetaInfo(info.elements.filterIndexed { i, _ -> current[i] !in selection })
      }
      selection.clear()
    }
  }

  @Composable
  override fun Content() = PointElements(owner, -1, selection)
}

// FOR LOCAL USE: menu of metadata obtained from coordinates and geocoding.
@Composable
private fun AddMenu(id: Long) {
  var open by remember { mutableStateOf(false) }
  Box {
    ToolbarItem(Icons.Outlined.Add, R.string.add) { open = true }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      MenuItem(Icons.Outlined.MyLocation, stringResource(R.string.coordinates)) {
        open = false
        PointStore.update(id) { it.copy(info = MetaInfo(it.elements + PointElement.Text(formatCoordinates(it.geo)))) }
      }
      MenuItem(Icons.Outlined.TravelExplore, stringResource(R.string.map_data)) {
        open = false
        PointActions.requestMapData(id)
      }
    }
  }
}

// FOR LOCAL USE: elements from first to first + count - 1; pictures of a line, or one text.
private class ElementRow(val key: String, val first: Int, val count: Int, val pictures: List<String>, val text: String?, val keys: List<String>)

/** Ключи элементов, не зависящие от раскладки строк: у картинки имя файла, у текста номер повтора и сам текст. FOR LOCAL USE */
private fun elementKeys(elements: List<PointElement>): List<String> {
  val texts = HashMap<String, Int>()
  return elements.map { element ->
    when (element) {
      is PointElement.Text -> "t${texts.merge(element.text, 1, Int::plus)}:${element.text}"
      is PointElement.Picture -> "p:${element.file}"
    }
  }
}

// FOR LOCAL USE: metadata rows with content keys preserved during dragging.
private fun buildRows(elements: List<PointElement>, aspects: Map<String, Float>, width: Float, height: Float, gap: Float): List<ElementRow> {
  val rows = mutableListOf<ElementRow>()
  val keys = elementKeys(elements)
  var i = 0
  while (i < elements.size) {
    when (val element = elements[i]) {
      is PointElement.Text -> {
        rows += ElementRow(keys[i], i, 1, emptyList(), element.text, listOf(keys[i]))
        i++
      }
      is PointElement.Picture -> {
        val start = i
        val names = mutableListOf<String>()
        var used = 0f
        while (i < elements.size) {
          val picture = elements[i] as? PointElement.Picture ?: break
          val next = height * (aspects[picture.file] ?: 1f) + if (names.isEmpty()) 0f else gap
          if (names.isNotEmpty() && used + next > width) break
          used += next
          names += picture.file
          i++
        }
        rows += ElementRow(keys[start], start, names.size, names, null, keys.subList(start, i))
      }
    }
  }
  return rows
}

/** Строки элементов. С selection - режим выбора: касание строки переключает её выбор, меню и просмотр не открываются,
    смахивание выключено; без него долгое нажатие открывает режим выбора.
*/
@Composable
private fun PointElements(owner: InfoOwner, focusElement: Int, selection: SnapshotStateList<String>? = null) {
  val info = owner.info ?: return
  val listState = rememberLazyListState()
  var draggingKey by remember { mutableStateOf<String?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val pictures = info.pictures
  val aspects by produceState(emptyMap(), pictures) {
    value = withContext(Dispatchers.IO) { pictures.associateWith { Pictures.aspect(PointStore.pictureFile(it)) } }
  }
  val density = LocalDensity.current
  val pictureHeight = with(density) { (MaterialTheme.typography.bodyLarge.fontSize * PICTURE_HEIGHT_EM).toDp() }
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val width = maxWidth - ROW_START - ROW_BUTTON_WIDTH
    val rows = with(density) { buildRows(info.elements, aspects, width.toPx(), pictureHeight.toPx(), PICTURE_GAP.toPx()) }
    val highlightedKey = remember(owner, focusElement) { rows.firstOrNull { focusElement in it.first until it.first + it.count }?.key }
    val targetIndex = rows.indexOfFirst { it.key == highlightedKey }
    LaunchedEffect(highlightedKey, targetIndex) {
      if (targetIndex >= 0) listState.scrollToItem(targetIndex)
    }
    val currentRows by rememberUpdatedState(rows)
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().scrollIndicator(listState)) {
      items(rows, key = { it.key }) { row ->
        val dragged = draggingKey == row.key
        Column(
          Modifier
            .zIndex(if (dragged) 1f else 0f)
            .graphicsLayer { translationY = if (dragged) dragOffset else 0f }
            .background(Color.White)
        ) {
          val selected = selection != null && row.keys.all { it in selection }
          val toggle: (() -> Unit)? = if (selection == null) null else ({
            if (selected) selection.removeAll(row.keys.toSet()) else selection.addAll(row.keys.filter { it !in selection })
          })
          val longClick: (() -> Unit)? = if (selection == null) ({ BottomSheet.push(PointElementsSelectionEditor(owner, row.keys)) }) else null
          val background = when {
            selected -> SELECTED_BACKGROUND
            selection == null && row.key == highlightedKey -> HIGHLIGHT_BACKGROUND
            else -> Color.White
          }
          SwipeRevealRow(enabled = draggingKey == null && selection == null, onDelete = { deleteRow(owner, row) }) {
            Row(
              Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(background),
              verticalAlignment = Alignment.CenterVertically
            ) {
              if (toggle != null) Checkbox(checked = selected, onCheckedChange = { toggle() })
              Box(Modifier.weight(1f).padding(start = if (toggle != null) 0.dp else ROW_START, top = 4.dp, bottom = 4.dp)) {
                if (row.text != null) {
                  TextElement(owner, row.first, row.text, toggle, longClick)
                } else {
                  PictureLine(owner, row.pictures, aspects, pictureHeight, toggle, longClick)
                }
              }
              RowHandle(row.key, onStart = {
                draggingKey = row.key
                dragOffset = 0f
              }, onEnd = {
                draggingKey = null
                dragOffset = 0f
              }) { dy ->
                dragOffset += dy
                dragOffset = reorderRows(owner, row.key, dragOffset, listState, currentRows)
              }
            }
          }
          HorizontalDivider()
        }
      }
    }
  }
}

// A text, or all pictures of the line, after a question.
private fun deleteRow(owner: InfoOwner, row: ElementRow) {
  val remove = {
    owner.update { info -> MetaInfo(info.elements.filterIndexed { i, _ -> i !in row.first until row.first + row.count }) }
    row.pictures.forEach(Pictures::forget)
  }
  val icon = Icons.Outlined.Delete
  if (row.text != null) {
    AppDialog.confirm(R.string.delete_element_confirm, icon = icon, confirmIcon = icon, confirmLabel = R.string.delete, onConfirm = remove)
  } else {
    AppDialog.confirmPlural(R.plurals.delete_pictures_confirm, row.count, icon = icon, confirmIcon = icon, confirmLabel = R.string.delete, onConfirm = remove)
  }
}

// Swaps the dragged row with a neighbour once it passes half of the neighbour; returns the remaining offset.
private fun reorderRows(owner: InfoOwner, key: String, offset: Float, listState: LazyListState, rows: List<ElementRow>): Float {
  val items = listState.layoutInfo.visibleItemsInfo
  val current = items.firstOrNull { it.key == key } ?: return offset
  val index = rows.indexOfFirst { it.key == key }
  if (index < 0) return offset
  if (offset > 0 && index + 1 < rows.size) {
    val next = items.firstOrNull { it.index == current.index + 1 } ?: return offset
    if (offset > next.size / 2f) {
      swapRows(owner, rows[index], rows[index + 1])
      return offset - next.size
    }
  } else if (offset < 0 && index > 0) {
    val previous = items.firstOrNull { it.index == current.index - 1 } ?: return offset
    if (-offset > previous.size / 2f) {
      swapRows(owner, rows[index - 1], rows[index])
      return offset + previous.size
    }
  }
  return offset
}

// Neighbour rows: the upper one starts first.
private fun swapRows(owner: InfoOwner, upper: ElementRow, lower: ElementRow) {
  owner.update { info ->
    val e = info.elements
    val end = lower.first + lower.count
    MetaInfo(e.subList(0, upper.first) + e.subList(lower.first, end) + e.subList(upper.first, lower.first) + e.subList(end, e.size))
  }
}

@Composable
private fun RowHandle(key: String, onStart: () -> Unit, onEnd: () -> Unit, onDrag: (Float) -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxHeight()
      .width(ROW_BUTTON_WIDTH)
      .pointerInput(key) {
        detectDragGestures(
          onDragStart = { onStart() },
          onDragEnd = onEnd,
          onDragCancel = onEnd,
          onDrag = { change, amount ->
            change.consume()
            onDrag(amount.y)
          }
        )
      },
    contentAlignment = Alignment.Center
  ) {
    Icon(Icons.Outlined.DragHandle, stringResource(R.string.reorder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}

/** toggle задан в режиме выбора и заменяет меню текста. Ссылки в тексте видны всегда, а открываются касанием только вне
    режима выбора; касание мимо ссылки открывает меню строки.
*/
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextElement(owner: InfoOwner, index: Int, text: String, toggle: (() -> Unit)?, longClick: (() -> Unit)?) {
  var menu by remember { mutableStateOf(false) }
  var share by remember { mutableStateOf(false) }
  val close = {
    menu = false
    share = false
  }
  Box {
    Text(
      linkedText(text.ifEmpty { " " }, clickable = toggle == null),
      modifier = Modifier.fillMaxWidth().combinedClickable(onClick = toggle ?: { menu = true }, onLongClick = longClick).padding(vertical = 4.dp)
    )
    DropdownMenu(expanded = menu, onDismissRequest = close) {
      val set = owner.shareSet(listOf(PointElement.Text(text)))
      if (share) {
        PopupShareMenu(set, close)
      } else {
        MenuItem(Icons.Outlined.Edit, stringResource(R.string.edit)) {
          close()
          editPointText(owner, index)
        }
        MenuItem(Icons.Outlined.Delete, stringResource(R.string.delete)) {
          close()
          AppDialog.confirm(R.string.delete_element_confirm, icon = Icons.Outlined.Delete, confirmIcon = Icons.Outlined.Delete, confirmLabel = R.string.delete) {
            owner.update { info -> MetaInfo(info.elements.filterIndexed { i, _ -> i != index }) }
          }
        }
        ShareMenuItem(set) { share = true }
      }
    }
  }
}

/** toggle задан в режиме выбора и заменяет просмотр картинок. Нажатия ловит вся ширина строки, а не только картинки:
    свободное место справа тоже переключает выбор и открывает режим выбора долгим нажатием.
*/
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PictureLine(owner: InfoOwner, names: List<String>, aspects: Map<String, Float>, height: Dp, toggle: (() -> Unit)?, longClick: (() -> Unit)?) {
  Row(
    Modifier.fillMaxWidth().combinedClickable(onClick = toggle ?: {}, onLongClick = longClick),
    horizontalArrangement = Arrangement.spacedBy(PICTURE_GAP)
  ) {
    names.forEach { name ->
      val click = toggle ?: { openPictureViewer(owner, name) }
      FitPicture(name, Modifier.height(height).width(height * (aspects[name] ?: 1f)).combinedClickable(onClick = click, onLongClick = longClick))
    }
  }
}

// Multi-line text for a new or an existing element: a dialog over everything, so the sheet below it keeps its place and its size.
// Enter makes a new line; right of the field paste, voice input, OK applies, Cancel drops the edit. An emptied text is removed.
/**
* Opens the dialog editing one text element of a point, or creating a new one.
*
* Usage: Use NEW_ELEMENT for insertion. OK applies the current text; a blank replacement removes the old text. Cancellation discards the edit.
* @param owner Владелец метаинформации: точка или записки.
* @param index Index of the edited text element, or NEW_ELEMENT to append a new one.
* @return Unit; the dialog waits for its own OK or Cancel.
*/
fun editPointText(owner: InfoOwner, index: Int) = AppDialog.show { PointTextDialog(owner, index) }

@Composable
private fun PointTextDialog(owner: InfoOwner, index: Int) {
  var value by remember {
    mutableStateOf((owner.info?.elements?.getOrNull(index) as? PointElement.Text)?.text.orEmpty().let { TextFieldValue(it, TextRange(it.length)) })
  }
  // Replaces the selection, or inserts at the cursor.
  val insert: (String) -> Unit = { pasted ->
    if (pasted.isNotEmpty()) {
      val at = value.selection.min
      value = TextFieldValue(value.text.replaceRange(at, value.selection.max, pasted), TextRange(at + pasted.length))
    }
  }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  AlertDialog(
    onDismissRequest = AppDialog::close,
    text = {
      Row(Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = value,
          onValueChange = { value = it },
          modifier = Modifier.weight(1f).focusRequester(focus)
        )
        Column {
          IconButton(onClick = { insert(clipboardText()) }) {
            Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.paste))
          }
          VoiceButton(insert)
          IconButton(onClick = {
            applyPointText(owner, index, value.text)
            AppDialog.close()
          }) { Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.ok)) }
          IconButton(onClick = AppDialog::close) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel)) }
        }
      }
    },
    confirmButton = {}
  )
}

// FOR LOCAL USE: текст из буфера обмена системы.
private fun clipboardText(): String {
  val context = AppSession.context
  val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return ""
  return clip.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

// FOR LOCAL USE: результат правки текста: новый элемент, замена или удаление опустевшего.
private fun applyPointText(owner: InfoOwner, index: Int, text: String) {
  owner.update { info ->
    MetaInfo(when {
      index == NEW_ELEMENT -> if (text.isBlank()) info.elements else info.elements + PointElement.Text(text)
      text.isBlank() -> info.elements.filterIndexed { i, _ -> i != index }
      else -> info.elements.replaced(index, PointElement.Text(text))
    })
  }
}

// All pictures of the point on the whole screen, starting with the one touched: swiped through, zoomed with two fingers.
// Replacing and deleting apply at once; the view closes when no picture is left.
private fun openPictureViewer(owner: InfoOwner, start: String) = ModalScreen.open("pictures") { PictureViewerScreen(owner, start) }

@Composable
private fun PictureViewerScreen(owner: InfoOwner, start: String) {
  val pictures = owner.info?.pictures.orEmpty()
  LaunchedEffect(pictures.isEmpty()) { if (pictures.isEmpty()) ModalScreen.close() }
  if (pictures.isEmpty()) return
  val pager = rememberPagerState(initialPage = pictures.indexOf(start).coerceAtLeast(0)) { pictures.size }
  val current = pictures[pager.currentPage.coerceIn(pictures.indices)]
  val sources = rememberPictureSources()
  Column(Modifier.fillMaxSize().background(Color.Black)) {
    Row(
      Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT).background(TOOLBAR_COLOR).padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      ToolbarItem(Icons.AutoMirrored.Outlined.ArrowBack, R.string.back) { ModalScreen.close() }
      Text("${pager.currentPage.coerceIn(pictures.indices) + 1} / ${pictures.size}", style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.weight(1f))
      PictureMenu(Icons.Outlined.FindReplace, R.string.replace, owner, replacing = current, sources)
      ToolbarItem(Icons.Outlined.Delete, R.string.delete) {
        AppDialog.confirm(R.string.delete_element_confirm, icon = Icons.Outlined.Delete, confirmIcon = Icons.Outlined.Delete, confirmLabel = R.string.delete) {
          owner.update { info -> MetaInfo(info.elements.filterNot { it is PointElement.Picture && it.file == current }) }
          Pictures.forget(current)
        }
      }
      ShareToolbarItem { owner.shareSet(listOf(PointElement.Picture(current))) }
    }
    HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { pictures.getOrElse(it) { "" } }) { page ->
      pictures.getOrNull(page)?.let { PictureViewer(it) }
    }
  }
}

// Camera or gallery; the picture file is created before the point refers to it. A replaced picture gives its place to the new one.
// Adding goes on in series: after a shot the camera opens again until it is cancelled; the gallery gives several pictures at once.
private class PictureSources(val camera: (InfoOwner, String?) -> Unit, val gallery: (InfoOwner, String?) -> Unit)

// Where the next picture goes; kept outside the screen state, so a recreated activity still knows it.
private object PictureTarget {
  var camera: Triple<InfoOwner, File, String?>? = null
  var gallery: Pair<InfoOwner, String?>? = null
}

private fun attachPicture(owner: InfoOwner, file: File, replacing: String?) {
  owner.update { info ->
    val picture = PointElement.Picture(file.name)
    val index = replacing?.let { name -> info.elements.indexOfFirst { it is PointElement.Picture && it.file == name } } ?: -1
    MetaInfo(if (index >= 0) info.elements.replaced(index, picture) else info.elements + picture)
  }
  replacing?.let(Pictures::forget)
}

// Copies the chosen pictures in order on the "io" thread and attaches them on the main one. With the quality of the settings
// below full the picture is written again as JPEG; a source that cannot be decoded is copied as it is.
private fun copyPictures(owner: InfoOwner, uris: List<Uri>, replacing: String?) {
  val main = Handler(Looper.getMainLooper())
  val resolver = AppSession.app.contentResolver
  TrackStorage.io.execute {
    uris.forEach { uri ->
      val extension = resolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
      val compress = imageQuality() < 100
      var file = PointStore.newPictureFile(if (compress) "jpg" else extension)
      var copied = compress && runCatching { resolver.openInputStream(uri)?.use { writeCompressedPicture(it, file) } }.getOrDefault(false) == true
      if (!copied) {
        if (compress) {
          file.delete()
          PointStore.releasePicture(file)
          file = PointStore.newPictureFile(extension)
        }
        copied = runCatching { resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } != null }
          .getOrDefault(false)
      }
      val attached = file
      main.post {
        if (copied) attachPicture(owner, attached, replacing) else attached.delete()
        PointStore.releasePicture(attached)
      }
    }
  }
}

@Composable
private fun rememberPictureSources(): PictureSources {
  val shoot = remember { arrayOfNulls<(InfoOwner, String?) -> Unit>(1) }
  val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val (owner, file, replacing) = PictureTarget.camera ?: return@rememberLauncherForActivityResult
    PictureTarget.camera = null
    val taken = ok && file.length() > 0
    if (taken) {
      TrackStorage.io.execute {
        recompressPicture(file)
        Handler(Looper.getMainLooper()).post { Pictures.forget(file.name) }
      }
      attachPicture(owner, file, replacing)
    } else {
      file.delete()
    }
    PointStore.releasePicture(file)
    if (taken && replacing == null) shoot[0]?.invoke(owner, null)
  }
  val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
    val (owner, replacing) = PictureTarget.gallery ?: return@rememberLauncherForActivityResult
    PictureTarget.gallery = null
    if (uri != null) copyPictures(owner, listOf(uri), replacing)
  }
  val seriesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris: List<Uri> ->
    val (owner, _) = PictureTarget.gallery ?: return@rememberLauncherForActivityResult
    PictureTarget.gallery = null
    copyPictures(owner, uris, null)
  }
  return remember {
    val camera: (InfoOwner, String?) -> Unit = { owner, replacing ->
      val file = PointStore.newPictureFile("jpg")
      PictureTarget.camera = Triple(owner, file, replacing)
      try {
        cameraLauncher.launch(Sharing.uriFor(file))
      } catch (_: ActivityNotFoundException) {
        PictureTarget.camera = null
        PointStore.releasePicture(file)
        Notify.error(R.string.camera_unavailable)
      }
    }
    shoot[0] = camera
    PictureSources(
      camera = camera,
      gallery = { owner, replacing ->
        PictureTarget.gallery = owner to replacing
        val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        if (replacing == null) seriesLauncher.launch(request) else galleryLauncher.launch(request)
      }
    )
  }
}

@Composable
private fun PictureMenu(
  icon: ImageVector,
  @StringRes label: Int,
  owner: InfoOwner,
  replacing: String?,
  pictures: PictureSources,
  opened: Boolean = false
) {
  var menu by remember { mutableStateOf(opened) }
  Box {
    ToolbarItem(icon, label) { menu = true }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      MenuItem(Icons.Outlined.PhotoCamera, stringResource(R.string.camera)) {
        menu = false
        pictures.camera(owner, replacing)
      }
      MenuItem(Icons.Outlined.PhotoLibrary, stringResource(R.string.gallery)) {
        menu = false
        pictures.gallery(owner, replacing)
      }
    }
  }
}
