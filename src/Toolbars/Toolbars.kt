// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

// FOR LOCAL USE: "Sunken" look: inner shadow on the map side, a lighter one on the opposite side.
private val SHADOW_COLOR = Color(0x2E000000)
private val SHADOW_LIGHT_COLOR = Color(0x1A000000)
private val SHADOW_DEPTH = 5.dp
private val SHADOW_LIGHT_DEPTH = 2.dp

// FOR LOCAL USE: toolbar background; a tap on its free part closes the sheet as well, except on the toolbar of a sheet editor.
private fun Modifier.toolbar(mapBelow: Boolean, closesSheet: Boolean = true) = this
  .fillMaxWidth()
  .height(TOOLBAR_HEIGHT)
  .background(TOOLBAR_COLOR)
  .drawBehind {
    val deep = SHADOW_DEPTH.toPx()
    val light = SHADOW_LIGHT_DEPTH.toPx()
    val towardsMap = Brush.verticalGradient(
      listOf(Color.Transparent, SHADOW_COLOR),
      startY = if (mapBelow) size.height - deep else deep,
      endY = if (mapBelow) size.height else 0f
    )
    drawRect(towardsMap, topLeft = Offset(0f, if (mapBelow) size.height - deep else 0f), size = Size(size.width, deep))
    val opposite = Brush.verticalGradient(
      listOf(Color.Transparent, SHADOW_LIGHT_COLOR),
      startY = if (mapBelow) light else size.height - light,
      endY = if (mapBelow) 0f else size.height
    )
    drawRect(opposite, topLeft = Offset(0f, if (mapBelow) 0f else size.height - light), size = Size(size.width, light))
  }
  .then(if (closesSheet) Modifier.pointerInput(Unit) { detectTapGestures { BottomSheet.close() } } else Modifier)
  .padding(horizontal = 0.dp)

/**
* Displays navigation controls or the active sheet editor's replacement toolbar.
* @return Unit; call once in the main screen layout.
*/
@Composable
fun TopToolbar() {
  val editor = BottomSheet.toolbarEditor
  if (editor != null) {
    // Keyed by the editor: without it a new editor of the same kind would inherit what the toolbar of the previous one remembered,
    // and the tool asked for at its opening, such as the editor of a new note, would never start.
    key(editor) { EditorToolbar(editor) }
    return
  }
  Row(Modifier.toolbar(mapBelow = true), verticalAlignment = Alignment.CenterVertically) {
    PositionButton()
    SpeedIndicator()
    PointsWarningsButton()
    if (RouteStore.active) RouteFollow(Modifier.weight(1f)) else Clock(Modifier.weight(1f))
    DirectionButton()
    MainMenu()
  }
}

// FOR LOCAL USE: standard toolbar of a sheet editor: "back", then the buttons of the editor, scrolled when they do not fit.
@Composable
private fun EditorToolbar(editor: SheetEditor) {
  Row(Modifier.toolbar(mapBelow = true, closesSheet = false), verticalAlignment = Alignment.CenterVertically) {
    ToolbarItem(Icons.AutoMirrored.Outlined.ArrowBack, R.string.back) { BottomSheet.back() }
    val scroll = rememberScrollState()
    // The row is at least as wide as the place it is given, so an editor may press its buttons to the right edge.
    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
      val width = maxWidth
      Row(
        Modifier.fillMaxHeight().horizontalScroll(scroll).widthIn(min = width),
        verticalAlignment = Alignment.CenterVertically
      ) {
        with(editor) { ToolbarButtons() }
      }
      if (scroll.value > 0) ScrollArrow(true, Modifier.align(Alignment.CenterStart))
      if (scroll.value < scroll.maxValue) ScrollArrow(false, Modifier.align(Alignment.CenterEnd))
    }
  }
}

/**
* Displays the application's lower navigation, photo and search controls.
* @return Unit; call once in the main screen layout.
*/
@Composable
fun BottomToolbar() {
  Row(Modifier.toolbar(mapBelow = false), verticalAlignment = Alignment.CenterVertically) {
    CommandItem(AppCommands.showPoints, R.drawable.ic_point, R.string.points)
    CommandItem(AppCommands.showTracks, R.drawable.ic_track, R.string.tracks)
    CommandItem(AppCommands.showRoute, R.drawable.ic_route, R.string.route)
    Spacer(Modifier.weight(1f))
    CommandItem(AppCommands.showNotes, R.drawable.ic_notes, R.string.notes)
    PhotoToolButton()
    CommandItem(AppCommands.search, R.drawable.ic_search, R.string.search)
  }
}

// FOR LOCAL USE: a sheet command is unavailable while its sheet is open, and its toolbar item is framed.
// While the sheet is open every toolbar item only closes it.
@Composable
private fun CommandItem(command: IAppCommand, icon: Int, label: Int) {
  val open = !command.available()
  ToolbarItem(painterResource(icon), label, selected = open, onClick = sheetFirst { if (!open) command.execute() })
}
