// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

// FOR LOCAL USE: No GPS (the tap runs the GPS checks) / waiting for data, blinking (the tap tells how long ago the data came) /
// auto, green (the map follows the position) / manual, blue (the tap switches back to auto).
/**
* Displays GPS/following state, blinking while waiting, and handles position-control taps.
* @return Unit; owns a timer subscription while waiting.
*/
@Composable
internal fun PositionButton() {
  val indicator = PositionIndicator.current()
  var dim by remember { mutableStateOf(false) }
  val waiting = indicator == PositionIndicator.WAITING
  DisposableEffect(waiting) {
    dim = false
    val blinker = object : IBlinkListener {
      override fun onBlinkOn() {
        dim = false
      }

      override fun onBlinkOff() {
        dim = true
      }
    }
    if (waiting) TimerManager.subscribe(blinker)
    onDispose { TimerManager.unsubscribe(blinker) }
  }
  val icon = when {
    indicator == PositionIndicator.NONE -> Icons.Outlined.LocationDisabled
    indicator.manualShape -> Icons.Outlined.LocationSearching
    else -> Icons.Outlined.MyLocation
  }
  Box(Modifier.alpha(if (waiting && dim) WAIT_DIM_ALPHA else 1f)) {
    ToolbarItem(icon, indicator.label, tint = indicator.color, onClick = sheetFirst { onPositionTap(indicator) })
  }
}

// FOR LOCAL USE: handles taps on the position indicator.
private fun onPositionTap(indicator: PositionIndicator) {
  when (indicator) {
    PositionIndicator.NONE -> if (Settings.useGps.value) AppSession.activity?.gpsSetup?.run() else Settings.useGps.value = true
    PositionIndicator.WAITING -> {
      val seconds = GpsDataManager.secondsSinceData()
      if (seconds == null) AppSession.toast(R.string.gps_waiting_never) else AppSession.toast(R.string.gps_waiting, seconds)
    }
    PositionIndicator.AUTO -> Unit
    PositionIndicator.MANUAL -> AppCommands.followGps.execute()
  }
}
