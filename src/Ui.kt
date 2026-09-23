// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Material 3 look of the application: own color scheme and the common controls.

/**
* Primary application accent color.
* @return Primary application accent color.
*/
val BRAND_BLUE = Color(0xFF378ADD)

private val colors = lightColorScheme(
  primary = BRAND_BLUE,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE6F1FB),
  onPrimaryContainer = Color(0xFF0C447C),
  secondary = Color(0xFF5F5E5A),
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFE4E2DA),
  onSecondaryContainer = Color(0xFF2C2C2A),
  tertiary = Color(0xFF639922),
  background = Color.White,
  onBackground = Color(0xFF2C2C2A),
  surface = Color.White,
  onSurface = Color(0xFF2C2C2A),
  surfaceVariant = Color(0xFFF1EFE8),
  onSurfaceVariant = Color(0xFF5F5E5A),
  surfaceContainer = Color(0xFFF7F6F2),
  surfaceContainerHigh = Color(0xFFF1EFE8),
  surfaceContainerHighest = Color(0xFFE4E2DA),
  outline = Color(0xFFB4B2A9),
  outlineVariant = Color(0xFFD3D1C7),
  error = Color(0xFFE24B4A)
)

/**
* Applies the application's light Material 3 color scheme to composed content.
* @param content Composable content emitted by this host in its declared scope.
* @return Unit; emits the themed content.
*/
@Composable
fun XTravelTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = colors, content = content)
}

private const val DISABLED_ALPHA = 0.38f
private val DISABLED_FILTER = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

// Toolbar item: icon with a small label below; the whole item is the button.
/**
* Displays an icon above a compact toolbar label, with disabled interaction and optional selection framing.
*
* Usage: Disabled controls are visually dimmed and do not receive clicks; use the painter overload to retain multicolor drawable icons.
* @param icon Icon displayed for this model or interface action.
* @param label String resource identifying the displayed label.
* @param enabled Whether the control accepts interaction; disabled controls are also visually dimmed. Default: true.
* @param tint Optional override for the displayed icon's color; an unspecified or absent value preserves its natural colors. Default: Color.Unspecified.
* @param selected Whether the item represents the current selection. Default: false.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits a toolbar item.
*/
@Composable
fun ToolbarItem(
  icon: Painter,
  @StringRes label: Int,
  enabled: Boolean = true,
  // Unspecified keeps the colors of own multicolor icons.
  tint: Color = Color.Unspecified,
  // The element this item shows is open now: framed and tinted.
  selected: Boolean = false,
  onClick: () -> Unit
) {
  val shape = RoundedCornerShape(12.dp)
  val frame = if (selected) {
    Modifier.background(MaterialTheme.colorScheme.primaryContainer, shape).border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
  } else {
    Modifier
  }
  Column(
    modifier = Modifier
      .fillMaxHeight()
      .widthIn(min = 48.dp)
      .padding(vertical = 4.dp)
      .then(frame)
      .clip(shape)
      .clickable(enabled = enabled, onClick = onClick)
      .padding(horizontal = 3.dp)
      .alpha(if (enabled) 1f else DISABLED_ALPHA),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    // Unavailable: grey and faded, so it reads even on icons that are grey already.
    Image(
      icon,
      contentDescription = null,
      modifier = Modifier.size(26.dp),
      colorFilter = if (!enabled) DISABLED_FILTER else if (tint != Color.Unspecified) ColorFilter.tint(tint) else null
    )
    Spacer(Modifier.size(1.dp))
    Text(
      stringResource(label),
      fontSize = 11.sp,
      lineHeight = 12.sp,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSecondaryContainer
    )
  }
}

/**
* Displays an icon above a compact toolbar label, with disabled interaction and optional selection framing.
*
* Usage: Disabled controls are visually dimmed and do not receive clicks; use the painter overload to retain multicolor drawable icons.
* @param icon Icon displayed for this model or interface action.
* @param label String resource identifying the displayed label.
* @param enabled Whether the control accepts interaction; disabled controls are also visually dimmed. Default: true.
* @param tint Optional override for the displayed icon's color; an unspecified or absent value preserves its natural colors. Default: MaterialTheme.colorScheme.onSecondaryContainer.
* @param selected Whether the item represents the current selection. Default: false.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits a toolbar item.
*/
@Composable
fun ToolbarItem(
  icon: ImageVector,
  @StringRes label: Int,
  enabled: Boolean = true,
  tint: Color = MaterialTheme.colorScheme.onSecondaryContainer,
  selected: Boolean = false,
  onClick: () -> Unit
) = ToolbarItem(rememberVectorPainter(icon), label, enabled, iconTint(icon, tint), selected, onClick)

/**
* Width in dp for compact list-row controls and drag handles.
* @return Width in dp for compact list-row controls and drag handles.
*/
val ROW_BUTTON_WIDTH = 36.dp

// Button of a list row: the whole row height, as wide as its content.
/**
* Provides a compact clickable row control with an optional long-press action.
* @param onClick Action invoked when the user activates the control.
* @param onLongClick Optional action invoked on a long press; null leaves that gesture unassigned. Default: null.
* @param content Composable content emitted by this host in its declared scope.
* @return Unit; emits the control.
*/
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RowButton(onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
  Box(
    modifier = Modifier
      .fillMaxHeight()
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    contentAlignment = Alignment.Center
  ) {
    content()
  }
}

// Compact menu row: 40dp high, body text, icon on the left; trailing icon marks a submenu or a checked option.
/**
* Displays an icon-labelled menu action with optional trailing indicator and true disabled interaction.
* @param icon Icon displayed for this model or interface action.
* @param text Text to display, search, parse or share as specified by this operation.
* @param enabled Whether the control accepts interaction; disabled controls are also visually dimmed. Default: true.
* @param trailing Optional icon displayed at the trailing edge of the menu row. Default: null.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the menu item.
*/
@Composable
fun MenuItem(icon: ImageVector?, text: String, enabled: Boolean = true, trailing: ImageVector? = null, onClick: () -> Unit) {
  DropdownMenuItem(
    text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
    leadingIcon = {
      if (icon != null) Icon(icon, contentDescription = null, tint = iconTint(icon, LocalContentColor.current)) else Spacer(Modifier.width(24.dp))
    },
    trailingIcon = trailing?.let { { Icon(it, contentDescription = null) } },
    enabled = enabled,
    onClick = onClick,
    modifier = Modifier.height(40.dp)
  )
}

/** Пункт меню с картинкой вместо векторной иконки, без перекраски: например иконка чужого приложения */
@Composable
fun MenuItem(icon: Painter, text: String, enabled: Boolean = true, onClick: () -> Unit) {
  DropdownMenuItem(
    text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
    leadingIcon = { Image(icon, contentDescription = null, modifier = Modifier.size(24.dp)) },
    enabled = enabled,
    onClick = onClick,
    modifier = Modifier.height(40.dp)
  )
}

// Choice in a menu: check mark instead of the icon for the current value.
/**
* Displays a menu choice with a check mark when selected.
* @param text Text to display, search, parse or share as specified by this operation.
* @param selected Whether the item represents the current selection.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the choice.
*/
@Composable
fun ChoiceMenuItem(text: String, selected: Boolean, onClick: () -> Unit) =
  MenuItem(if (selected) Icons.Outlined.Check else null, text, onClick = onClick)

// Dialog button with an icon and a label; the main action is filled, an action that takes something away is red.
/**
* Displays an outlined dialog action or a filled primary or dangerous action with an icon and localized text.
* @param icon Icon displayed for this model or interface action.
* @param label String resource identifying the displayed label.
* @param primary Whether to use a filled primary action rather than an outlined button. Default: false.
* @param danger Whether the action takes something away and is filled with the error color. Default: false.
* @param fullWidth Whether the button fills its parent's available width. Default: false.
* @param enabled Whether the control accepts interaction; disabled controls are also visually dimmed. Default: true.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the action.
*/
@Composable
fun DialogButton(
  icon: ImageVector,
  @StringRes label: Int,
  primary: Boolean = false,
  danger: Boolean = false,
  fullWidth: Boolean = false,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  val modifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier
  val content: @Composable () -> Unit = {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize), tint = iconTint(icon, LocalContentColor.current))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(stringResource(label))
  }
  when {
    danger -> Button(
      onClick = onClick,
      modifier = modifier,
      enabled = enabled,
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
      )
    ) { content() }
    primary -> Button(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    else -> OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
  }
}

/**
* Displays a localized cancel text button with a close icon.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the button.
*/
@Composable
fun CancelButton(onClick: () -> Unit) {
  TextButton(onClick = onClick) {
    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
    Text(stringResource(R.string.cancel))
  }
}

// A control outside the sheet closes it and does its own action at once; an editor holding the sheet may stop both.
/**
* Wraps an action so the bottom sheet closes before the action executes.
*
* Usage: An editor with unsaved changes may defer or reject closing, in which case the action does not run until closing is accepted.
* @param action Action executed by this operation at the documented completion point.
* @return A callback to assign to an interface action.
*/
fun sheetFirst(action: () -> Unit): () -> Unit = {
  BottomSheet.close(action)
}
