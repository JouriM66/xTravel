// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource

// FOR LOCAL USE: North up (blue north arrow) / auto rotation / manual (grey north arrow); the tap switches them in turn.
/**
* Displays and cycles north-up, automatic and manual map-direction modes.
* @return Unit; emits the direction control.
*/
@Composable
internal fun DirectionButton() {
  val direction = Settings.mapDirection.value
  val onClick = sheetFirst { AppCommands.nextMapDirection.execute() }
  when (direction) {
    MapDirection.AUTO -> ToolbarItem(painterResource(R.drawable.ic_auto_rotate), direction.shortLabel, onClick = onClick)
    else -> {
      val color = if (direction == MapDirection.NORTH) NORTH_ON_COLOR else INACTIVE_COLOR
      ToolbarItem(painterResource(R.drawable.ic_north), direction.shortLabel, tint = color, onClick = onClick)
    }
  }
}
