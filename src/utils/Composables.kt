// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Displays a filled red delete button with an icon and localized label; deletion is delegated to the callback.
 * @param fullWidth Whether the button fills its parent's available width. Default: false.
 * @param onClick Action invoked when the user activates the control.
 * @return Unit; emits the button.
 */
@Composable
fun DeleteDialogButton(fullWidth: Boolean = false, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier,
    colors = ButtonDefaults.buttonColors(
      containerColor = MaterialTheme.colorScheme.error,
      contentColor = MaterialTheme.colorScheme.onError
    )
  ) {
    Icon(
      Icons.Outlined.Delete,
      contentDescription = null,
      modifier = Modifier.size(ButtonDefaults.IconSize)
    )
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(stringResource(R.string.delete))
  }
}

/**
 * Displays a localized label above its value.
 * @param label String resource identifying the displayed label.
 * @param value Displayed property value placed below the localized label.
 * @return Unit; emits the property display.
 */
@Composable
fun Property(@StringRes label: Int, value: String) {
  Column {
    Text(
      stringResource(label),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(value, style = MaterialTheme.typography.bodyLarge)
  }
}

/**
 * Displays a label and value in aligned columns, growing a shared label-width state to fit the widest label.
 *
 * Usage: Before rendering the group, declare val maxLabelWidth = remember { mutableStateOf(0.dp) } and pass that
 * same state to every row. Do not create it separately for each row; widths only grow until the state is recreated.
 * @param label String resource identifying the displayed label.
 * @param value Displayed property value placed to the right of the shared-width label.
 * @param maxLabelWidth One state shared by the aligned group: declare val maxLabelWidth =
 * remember { mutableStateOf(0.dp) } before the rows and pass it to each call.
 * @return Unit; emits a property row.
 */
@Composable
fun AlignedProperty(
  @StringRes label: Int,
  value: String,
  maxLabelWidth: MutableState<Dp>
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
  ) {
    val density = LocalDensity.current

    Text(
      text = stringResource(label),
      modifier = Modifier
        .widthIn(min = maxLabelWidth.value)
        .onGloballyPositioned { layoutCoordinates ->
          val widthDp = with(density) { layoutCoordinates.size.width.toDp() }
          if (widthDp > maxLabelWidth.value) {
            maxLabelWidth.value = widthDp
          }
        }
        .padding(end = 16.dp),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Text(
      text = value,
      modifier = Modifier.weight(1f),
      style = MaterialTheme.typography.bodyLarge
    )
  }
}

/**
 * Shows a narrow directional marker for an edge with more scrollable content.
 * @param toLeft True for the left scroll edge, false for the right edge.
 * @param modifier Compose layout and drawing modifier applied to the emitted host.
 * @return Unit; emits the scroll indicator.
 */
@Composable
fun ScrollArrow(toLeft: Boolean, modifier: Modifier) {
  val icon = if (toLeft) {
    Icons.AutoMirrored.Outlined.KeyboardArrowLeft
  } else {
    Icons.AutoMirrored.Outlined.KeyboardArrowRight
  }
  Icon(
    icon,
    contentDescription = null,
    tint = INACTIVE_COLOR,
    modifier = modifier
      .fillMaxHeight()
      .width(12.dp)
      .background(TOOLBAR_COLOR.copy(alpha = 0.85f))
  )
}

/**
 * Reports vertical row-drag movements without scrolling or reordering the list itself.
 *
 * Usage: The caller must apply reordering and maintain drag state. Cancellation invokes onEnd as well as normal completion.
 * @param key Stable identifier used to persist or retain the associated state.
 * @param listState Associated lazy-list state used as a gesture key; this function does not scroll the list itself.
 * @param onStart Called once when the user starts dragging the handle.
 * @param onEnd Called after normal drag completion or cancellation.
 * @param onDrag Receives each consumed vertical drag delta in screen pixels.
 * @return Unit; emits the drag handle.
 */
@Composable
fun DragHandle(
  key: Any,
  listState: LazyListState,
  onStart: () -> Unit,
  onEnd: () -> Unit,
  onDrag: (Float) -> Unit
) {
  Box(
    modifier = Modifier
      .fillMaxHeight()
      .width(ROW_BUTTON_WIDTH)
      .pointerInput(key, listState) {
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
    Icon(Icons.Outlined.DragHandle, stringResource(R.string.reorder))
  }
}

/**
 * Formats a distance as meters or kilometers for the interface.
 * @param meters Distance in meters.
 * @return A localized distance label.
 */
@Composable
fun distanceText(meters: Double): String =
  if (meters < 1000) {
    stringResource(R.string.scale_m, meters.roundToInt())
  } else {
    stringResource(
      R.string.distance_km,
      String.format(Locale.ROOT, "%.1f", meters / 1000).removeSuffix(".0")
    )
  }

/**
 * Formats a duration as minutes, hours and minutes, or days and hours.
 * @param seconds Duration in seconds.
 * @return A localized duration label.
 */
@Composable
fun durationText(seconds: Double): String {
  val minutes = (seconds / 60).roundToInt()
  return when {
    minutes < 60 -> stringResource(R.string.time_m, minutes)
    minutes < 24 * 60 -> stringResource(R.string.time_h_m, minutes / 60, minutes % 60)
    else -> stringResource(R.string.time_d_h, minutes / (24 * 60), minutes % (24 * 60) / 60)
  }
}

/** Неподвижный заголовок полноэкранного окна: название слева во всю свободную ширину, кнопки actions прижаты вправо.
    Ставится над прокручиваемым содержимым, а не внутри него.
*/
@Composable
fun ModalHeader(title: String, actions: @Composable RowScope.() -> Unit = {}) {
  Row(
    Modifier.fillMaxWidth().height(TOOLBAR_HEIGHT).background(TOOLBAR_COLOR).padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      title,
      style = MaterialTheme.typography.titleLarge,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
    )
    actions()
  }
}

/** Иконка установленного приложения; если его нет или пакет не виден (queries манифеста), то fallback.
    Проверяется один раз за композицию.
*/
@Composable
fun rememberAppIcon(packageName: String, fallback: ImageVector): Painter {
  val bitmap = remember(packageName) {
    runCatching { AppSession.app.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
  }
  val vector = rememberVectorPainter(fallback)
  return remember(bitmap) { bitmap?.let { BitmapPainter(it) } } ?: vector
}
