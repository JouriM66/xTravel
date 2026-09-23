// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import kotlin.math.max
import kotlin.math.roundToInt

private val HEADER_HEIGHT = 20.dp // FOR LOCAL USE: height of the header line with the handle, the caption and the counter
private val HANDLE_WIDTH = 36.dp // FOR LOCAL USE: width of the dragging handle in the middle of the header
private val HEADER_GAP = 6.dp // FOR LOCAL USE: free space between the handle and the texts beside it
private val SHEET_CORNER = 16.dp // FOR LOCAL USE: rounding of the upper corners; the texts of the header keep away from it

// FOR LOCAL USE: text of the header, drawn as tall as the header line allows and without any padding of its own.
@Composable
private fun HeaderText(text: String, modifier: Modifier) {
  val size = with(LocalDensity.current) { HEADER_HEIGHT.toSp() }
  Text(
    text,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    style = TextStyle(
      fontSize = size,
      lineHeight = size,
      platformStyle = PlatformTextStyle(includeFontPadding = false),
      lineHeightStyle = LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.Both)
    ),
    modifier = modifier
  )
}

// Content of the sheet. Editors are stacked, the top one is shown; an editor with a toolbar replaces the top toolbar of the application
// by the standard one: "back" and the buttons of the editor.
/**
* Defines the content and optional toolbar of one editor in the bottom-sheet stack.
*
* Usage: Open through BottomSheet. Editors that hold unsaved changes must install and release the host's close guard in their composition lifecycle.
*
* Public and subclass/module-facing members:
* - [hasToolbar] - Whether this editor replaces the application top toolbar with its own actions.
* - [ToolbarButtons] - Defines actions emitted in a RowScope when hasToolbar is true; the default emits no controls.
* - [getContentElementsCount] - Number of elements shown by this editor, for the counter of the sheet.
* - [Content] - Defines the composable editor body emitted by the sheet host.
*/
abstract class SheetEditor {
  /**
  * Whether this editor replaces the application top toolbar with its own actions.
  * @return Whether this editor replaces the application top toolbar with its own actions.
  */
  open val hasToolbar: Boolean get() = false

  /** Заголовок слева в шапке шторки; пустая строка - без заголовка */
  open val caption: String get() = ""

  /**
  * Defines actions emitted in a RowScope when hasToolbar is true; the default emits no controls.
  * @receiver Toolbar row scope supplying row layout behavior.
  * @return Unit; invoke from composition with a RowScope receiver.
  */
  @Composable
  open fun RowScope.ToolbarButtons() {}

  /**
  * Number of elements shown by this editor, for the counter of the sheet; lists report their length, plain editors nothing.
  * @return Number of elements, zero when the editor shows no list.
  */
  open fun getContentElementsCount(): Int = 0

  /**
  * Defines the composable editor body emitted by the sheet host.
  * @return Unit; invoke from composition through the sheet host.
  */
  @Composable
  abstract fun Content()
}

private class ComposableEditor(private val content: @Composable () -> Unit, private val elements: () -> Int, @StringRes private val title: Int) : SheetEditor() {
  override val caption: String get() = if (title == 0) "" else AppSession.context.getString(title)

  override fun getContentElementsCount() = elements()

  @Composable
  override fun Content() = content()
}

// The only bottom sheet of the application. Its height is internal; closing drops the whole stack of editors.
/**
* Owns the editor stack, remembered height and unsaved-change guard of the application's bottom sheet.
*
* Usage: Use on the main thread and install one Host over the map. close and back may be deferred by the active guard.
*
* Public and subclass/module-facing members:
* - [ownerId] - Observable sheet owner identifier, or null when the sheet is closed; changed only by the host.
* - [isOpen] - Whether the bottom sheet currently has an owner.
* - [coveredPx] - Computes the bottom portion of the map obscured by the sheet and keyboard lift.
* - [toolbarEditor] - Top editor when it requests a toolbar, otherwise null.
* - [open] - Opens a new editor stack or wraps composable content as its first editor.
* - [push] - Adds an editor above the current one, opening a new sheet if necessary.
* - [expand] - Expands the sheet to all available height without changing the persisted preferred height.
* - [toggleExpanded] - Switches between full height and the saved preferred height.
* - [back] - Returns to the previous editor or closes the sheet when no previous editor exists.
* - [close] - Closes the sheet and clears its entire stack before invoking the continuation.
* - [hold] - Installs or removes the current editor's unsaved-change guard.
* - [Host] - Displays the top editor with keyboard lift, draggable height, saved child state and guarded back handling.
*/
object BottomSheet {

  // Released below this share of the available height, the sheet closes.
  private const val CLOSE_FRACTION = 0.1f

  /**
  * Observable sheet owner identifier, or null when the sheet is closed; changed only by the host.
  * @return Observable sheet owner identifier, or null when the sheet is closed; changed only by the host.
  */
  var ownerId by mutableStateOf<String?>(null)
    private set

  /**
  * Whether the bottom sheet currently has an owner.
  * @return Whether the bottom sheet currently has an owner.
  */
  val isOpen: Boolean get() = ownerId != null

  // Lift above the keyboard, px.
  private var liftPx by mutableIntStateOf(0)

  private const val KEY_FRACTION = "sheet_fraction"
  private const val EDITOR_OWNER = "editor"

  private class Entry(val key: Int, val editor: SheetEditor)

  private val stack = mutableStateListOf<Entry>()
  // Editors whose saved state the holder may keep.
  private val heldKeys = mutableSetOf<Int>()
  private var nextKey = 0
  private var fraction by mutableFloatStateOf(1f)

  // Height of the map part the sheet covers from below, px, computed from the sheet state: the sheet and the map fill the same area
  // of heightPx. Reading it in drawing redraws on every change of the sheet.
  /**
  * Computes the bottom portion of the map obscured by the sheet and keyboard lift.
  * @param heightPx Height of the map and sheet host in screen pixels.
  * @return Covered height in pixels, or zero when closed.
  */
  fun coveredPx(heightPx: Float): Float = if (isOpen) ((heightPx - liftPx).coerceAtLeast(0f) * fraction + liftPx) else 0f

  // The editor whose toolbar is shown instead of the top toolbar of the application.
  /**
  * Top editor when it requests a toolbar, otherwise null.
  * @return Top editor when it requests a toolbar, otherwise null.
  */
  val toolbarEditor: SheetEditor? get() = stack.lastOrNull()?.editor?.takeIf { it.hasToolbar }

  val topEditor: SheetEditor? get() = stack.lastOrNull()?.editor /** Верхний редактор стека, null - шторка закрыта */

  // Height the sheet opens with: the last one the user left, kept between openings and restarts.
  private val savedFraction: Float get() = Settings.prefs.getFloat(KEY_FRACTION, 1f)

  /**
  * Opens a new editor stack or wraps composable content as its first editor.
  * @param ownerId Identifier used to distinguish the active sheet or modal owner.
  * @param caption String resource of the sheet header caption; zero leaves the header without a caption. Default: none.
  * @param elements Number of elements the content shows, for the counter of the sheet. Default: none.
  * @param content Composable content emitted by this host in its declared scope.
  * @return Unit; opening may be deferred by the current editor's unsaved-change guard.
  */
  fun open(ownerId: String, @StringRes caption: Int = 0, elements: () -> Int = { 0 }, content: @Composable () -> Unit) =
    open(ownerId, ComposableEditor(content, elements, caption))

  // Starts a new stack with the editor.
  /**
  * Opens a new editor stack or wraps composable content as its first editor.
  * @param ownerId Identifier used to distinguish the active sheet or modal owner.
  * @param editor Editor placed in the bottom-sheet stack.
  * @return Unit; opening may be deferred by the current editor's unsaved-change guard.
  */
  fun open(ownerId: String, editor: SheetEditor) {
    if (holder != null) {
      close { open(ownerId, editor) }
      return
    }
    if (this.ownerId == null) fraction = savedFraction
    this.ownerId = ownerId
    stack.clear()
    stack += Entry(nextKey++, editor)
  }

  // Over the current editor; with the sheet closed the editor opens alone.
  /**
  * Adds an editor above the current one, opening a new sheet if necessary.
  * @param editor Editor placed in the bottom-sheet stack.
  * @return Unit; lower editor state remains in the stack.
  */
  fun push(editor: SheetEditor) {
    if (ownerId == null) open(EDITOR_OWNER, editor) else stack += Entry(nextKey++, editor)
  }

  // Full height for the current content; the height the user keeps for the next openings is not changed.
  /**
  * Expands the sheet to all available height without changing the persisted preferred height.
  * @return Unit; updates the current height fraction.
  */
  fun expand() {
    fraction = 1f
  }

  // Tap on the handle: to the full height, and from it back to the height the user keeps, when that one is lower.
  /**
  * Switches between full height and the saved preferred height.
  * @return Unit; updates the current height fraction.
  */
  fun toggleExpanded() {
    fraction = if (fraction < 1f) 1f else savedFraction.coerceAtLeast(CLOSE_FRACTION)
  }

  // To the previous editor; the sheet closes when there is none.
  /**
  * Returns to the previous editor or closes the sheet when no previous editor exists.
  * @return Unit; the active unsaved-change guard may defer the action.
  */
  fun back() {
    if (held(::back)) return
    if (stack.size > 1) stack.removeAt(stack.lastIndex) else close()
  }

  // The action after the closing runs only when the sheet really closed: the top editor may hold it and ask the user first.
  /**
  * Closes the sheet and clears its entire stack before invoking the continuation.
  * @param after Continuation invoked only after the sheet has actually closed. Default: {}.
  * @return Unit; the continuation runs only after closing is accepted.
  */
  fun close(after: () -> Unit = {}) {
    if (held { close(after) }) return
    ownerId = null
    stack.clear()
    after()
  }

  // The top editor may hold the sheet, for example an editor with changes that are not applied yet: it is asked before
  // the sheet goes back or closes and either lets it happen or takes over, asking the user and repeating the action itself.
  private var holder: ((() -> Unit) -> Boolean)? = null

  /**
  * Installs or removes the current editor's unsaved-change guard.
  *
  * Usage: A guard returning true takes responsibility for completing or cancelling the supplied action; pass null when releasing ownership.
  * @param ask Optional close guard; return true to defer the action, and invoke its continuation when approved; null removes the guard.
  * @return Unit; replaces the guard.
  */
  fun hold(ask: ((() -> Unit) -> Boolean)?) {
    holder = ask
  }

  private fun held(action: () -> Unit): Boolean {
    val ask = holder ?: return false
    val taken = ask {
      holder = null
      action()
    }
    // The guard let the action through: its editor leaves with the sheet, so the guard must not meet the next one.
    if (!taken) holder = null
    return taken
  }

  // Placed over the map; rises above the keyboard.
  /**
  * Displays the top editor with keyboard lift, draggable height, saved child state and guarded back handling.
  *
  * Usage: The modifier should cover the same map area passed to coveredPx. The host retains state for stacked editors and installs guarded Back handling.
  * @param modifier Compose layout and drawing modifier applied to the emitted host. Default: Modifier.
  * @return Unit; install once above the map with the same available height used by coveredPx.
  */
  @Composable
  fun Host(modifier: Modifier = Modifier) {
    BackHandler(enabled = ownerId != null) { back() }
    var bottomGap by remember { mutableIntStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val lift = max(0, imeBottom - bottomGap)
    SideEffect { liftPx = lift }
    val current = stack.lastOrNull()
    // Keeps the state of the editors below the top one, for example the scroll position of a list.
    val states = rememberSaveableStateHolder()
    val keys = stack.map { it.key }
    LaunchedEffect(keys) { heldKeys.filter { it !in keys }.forEach(states::removeState); heldKeys.retainAll(keys) }
    heldKeys.addAll(keys)
    BoxWithConstraints(
      modifier.onGloballyPositioned { coordinates ->
        val root = coordinates.findRootCoordinates()
        bottomGap = (root.size.height - coordinates.boundsInRoot().bottom).roundToInt()
      }
    ) {
      if (current == null) return@BoxWithConstraints
      val liftDp = with(LocalDensity.current) { lift.toDp() }
      val available = (maxHeight - liftDp).coerceAtLeast(0.dp)
      val availablePx = with(LocalDensity.current) { available.toPx() }.coerceAtLeast(1f)
      val dragState = rememberDraggableState { delta ->
        fraction = (fraction - delta / availablePx).coerceIn(0f, 1f)
      }
      Surface(
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .padding(bottom = liftDp)
          .fillMaxWidth()
          .height(available * fraction),
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER),
        color = Color.White,
        shadowElevation = 8.dp
      ) {
        Column {
          BoxWithConstraints(
            modifier = Modifier
              .fillMaxWidth()
              .height(HEADER_HEIGHT)
              .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStopped = {
                  if (fraction < CLOSE_FRACTION) close() else Settings.prefs.edit { putFloat(KEY_FRACTION, fraction) }
                }
              )
              .clickable { toggleExpanded() },
            contentAlignment = Alignment.Center
          ) {
            // Caption and counter stand on the sides of the handle and never reach under it.
            Box(Modifier.size(width = HANDLE_WIDTH, height = 4.dp).background(PASSIVE_COLOR, RoundedCornerShape(2.dp)))

            val side = (maxWidth - HEADER_GAP*2 - SHEET_CORNER*2 - 20.dp).coerceAtLeast(0.dp)
            val caption = current.editor.caption
            if (caption.isNotEmpty()) {
              Text(caption, Modifier.align(Alignment.CenterStart).padding(start = SHEET_CORNER+1.dp).widthIn(max = side), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val elements = current.editor.getContentElementsCount()
            if (elements > 0) {
              Text(elements.toString(), Modifier.align(Alignment.CenterEnd).padding(end = SHEET_CORNER+1.dp).widthIn(max = side))
            }
          }
          // Lists in the sheet are compact: controls are not stretched to the 48dp Material minimum.
          CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
              key(current.key) { states.SaveableStateProvider(current.key) { current.editor.Content() } }
            }
          }
        }
      }
    }
  }
}
