// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource

// FOR LOCAL USE: Points in alarm: the button is there only while at least one is, the menu leads to the point in the list.
/**
* Displays the warning control only when points are in schedule alarm and offers navigation to each point.
* @return Unit; emits the toolbar control and popup.
*/
@Composable
internal fun PointsWarningsButton() {
  var open by remember { mutableStateOf(false) }
  val alarmed = PointSchedule.alarmed()
  if (alarmed.isEmpty()) return
  val texts = MapTexts(
    meters = stringResource(R.string.scale_m),
    kilometers = stringResource(R.string.scale_km),
    minutes = stringResource(R.string.time_m),
    hoursMinutes = stringResource(R.string.time_h_m),
    daysHours = stringResource(R.string.time_d_h)
  )
  Box {
    ToolbarItem(painterResource(R.drawable.ic_point), R.string.warnings, tint = Color(Settings.pointAlarmColor.value), onClick = sheetFirst { open = true })
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
      alarmed.forEach { point ->
        val state = PointSchedule.state(point)
        val time = state?.let { PointsLayer.formatMinutes(it.minutesLeft, texts) }.orEmpty()
        val label = if (state?.availability == Availability.OPEN) R.string.warning_closes else R.string.warning_opens
        MenuItem(Icons.Outlined.Alarm, stringResource(label, point.fullCaption(), time)) {
          open = false
          PointStore.focus(point.id)
          AppCommands.showPoints.execute()
        }
      }
    }
  }
}
