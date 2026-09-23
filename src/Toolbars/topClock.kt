// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// FOR LOCAL USE: Date and time of the application clock, a second apart; while the debug time is set they show it, not the device.
/**
* Displays application date and seconds-resolution time in the supplied layout bounds.
* @param modifier Compose layout and drawing modifier applied to the emitted host.
* @return Unit; debug time changes the time text color.
*/
@Composable
internal fun Clock(modifier: Modifier) {
  Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
    Text(
      AppClock.dateText(),
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      color = MaterialTheme.colorScheme.onSecondaryContainer
    )
    Text(
      AppClock.timeText(),
      style = MaterialTheme.typography.headlineSmall,
      maxLines = 1,
      color = if (AppClock.debugStart == null) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.primary
    )
  }
}
