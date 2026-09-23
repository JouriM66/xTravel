// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// FOR LOCAL USE: the square of the speed is lighter than the toolbar, so its area is visible.
private val SPEED_BACKGROUND = Color(0xFFF5F3EC)
// FOR LOCAL USE: the size the text is measured at; the shown size is this one scaled to the square.
private const val SPEED_BASE_SP = 40f
private val SPEED_MIN = 10.sp
private val SPEED_MAX = 44.sp
// FOR LOCAL USE: from this size the text is white with a black outline.
private val SPEED_OUTLINED_FROM = 28.sp
private val SPEED_PADDING = 4.dp

/** TOP_TOOLBAR: Displays rounded GPS speed while is moving or "-" while stationary.
    Displayed only if GPS available
*/
@Composable
internal fun SpeedIndicator() {
  val status = GpsDataManager.status
  if (!Settings.showSpeed.value || status == GpsStatus.UNAVAILABLE) return

  var speed by remember { mutableStateOf(GpsDataManager.lastRaw?.speed) }
  var moving by remember { mutableStateOf(GpsDataManager.lastFix?.isMoving() == true) }
  DisposableEffect(Unit) {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val subscriber: (GpsUpdate) -> Unit = { update ->
      main.post {
        if (update.rawIsNew) speed = update.raw?.speed
        moving = update.available && update.fix?.isMoving() == true
      }
    }
    GpsDataManager.subscribe(subscriber)
    speed = GpsDataManager.lastRaw?.speed
    moving = GpsDataManager.lastFix?.isMoving() == true
    onDispose {
      GpsDataManager.unsubscribe(subscriber)
      main.removeCallbacksAndMessages(null)
    }
  }
  val rawSpeed = speed
  val text = if (status == GpsStatus.OK && moving && rawSpeed != null && rawSpeed.isFinite()) {
    (rawSpeed * 3.6f).roundToInt().coerceAtLeast(0).toString()
  } else "-"

  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current

  BoxWithConstraints(Modifier.size(TOOLBAR_HEIGHT), contentAlignment = Alignment.Center) {
    val base = MaterialTheme.typography.headlineSmall.copy(fontSize = SPEED_BASE_SP.sp, fontWeight = FontWeight.Bold)
    val limit = with(density) { ((minOf(maxWidth, maxHeight)) - SPEED_PADDING * 2).toPx() }

    val measured = measurer.measure(text, base, maxLines = 1).size
    if ( measured.width == 0 || measured.height == 0 ) return@BoxWithConstraints

    val fitted = (SPEED_BASE_SP * minOf(limit / measured.width, limit / measured.height)).sp.coerceIn(SPEED_MIN, SPEED_MAX)
    val style = base.copy(fontSize = fitted)
    if (fitted >= SPEED_OUTLINED_FROM) {
      val outline = with(density) { 1.dp.toPx() }
      Text(text, style = style.copy(color = Color.White, drawStyle = Stroke(width = outline, join = StrokeJoin.Round)), maxLines = 1)
      Text(text, style = style.copy(color = Color.Black), maxLines = 1)
    } else {
      Text(text, style = style.copy(color = MaterialTheme.colorScheme.onSecondaryContainer), maxLines = 1)
    }
  }
}
