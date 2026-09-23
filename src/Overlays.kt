// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val DISABLED_ALPHA = 0.4f
private val DISABLED_FILTER = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

// The only dialog shown at a time; content is an AlertDialog. Messages wait while another dialog is shown.
// An open dialog holds the idle time of the user (the dialog window does not pass its touches to the activity).
/**
* Hosts one application dialog and queues informational messages while another dialog is open.
*
* Public and subclass/module-facing members:
* - [show] - Replaces the active dialog content and holds user-idle processing while a dialog is open.
* - [close] - Closes the active dialog and schedules the next queued informational message.
* - [message] - Shows an informational dialog with OK, queuing it behind an existing dialog.
* - [confirm] - Shows a confirmation dialog with optional checkbox and invokes callbacks after accepting.
* - [confirmPlural] - Shows a confirmation whose localized message is selected and formatted with a count.
* - [input] - Edits a single-line value with optional voice input and validates it through onOk.
* - [Host] - Emits the current application dialog.
*/
object AppDialog {

  private var content by mutableStateOf<(@Composable () -> Unit)?>(null)
  private val pending = ArrayDeque<@Composable () -> Unit>()
  private val main = Handler(Looper.getMainLooper())

  private fun replaceContent(new: (@Composable () -> Unit)?) {
    val wasOpen = content != null
    content = new
    if (!wasOpen && new != null) UserActivity.hold() else if (wasOpen && new == null) UserActivity.resume()
  }

  /**
  * Replaces the active dialog content and holds user-idle processing while a dialog is open.
  * @param content Composable content emitted by this host in its declared scope.
  * @return Unit; call on the main thread.
  */
  fun show(content: @Composable () -> Unit) = replaceContent(content)

  // A waiting message is shown after the tasks already posted, so a dialog opened right after this one goes first.
  /**
  * Closes the active dialog and schedules the next queued informational message.
  * @return Unit; releases the dialog's idle hold.
  */
  fun close() {
    replaceContent(null)
    if (pending.isNotEmpty()) main.post { if (content == null) replaceContent(pending.removeFirstOrNull()) }
  }

  // Information or an error, with OK.
  /**
  * Shows an informational dialog with OK, queuing it behind an existing dialog.
  * @param text Text to display, search, parse or share as specified by this operation.
  * @return Unit; calls from other threads are posted to the main thread.
  */
  fun message(text: String) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post { message(text) }
      return
    }
    val dialog: @Composable () -> Unit = {
      AlertDialog(
        onDismissRequest = ::close,
        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
        text = { Text(text) },
        confirmButton = { DialogButton(Icons.Outlined.Check, R.string.ok, primary = true, onClick = ::close) }
      )
    }
    if (content == null) replaceContent(dialog) else pending.addLast(dialog)
  }

  /**
  * Shows a confirmation dialog with optional checkbox and invokes callbacks after accepting.
  *
  * Usage: onCheckedConfirm runs before onConfirm; callbacks execute after closing.
  * @param message String resource for the question, formatted with args.
  * @param args Formatting arguments for the localized message resource.
  * @param icon Icon displayed for this model or interface action. Default: Icons.AutoMirrored.Outlined.HelpOutline.
  * @param confirmIcon Icon displayed on the confirmation action. Default: Icons.Outlined.Check.
  * @param confirmLabel String resource identifying the confirmation button label. Default: R.string.ok.
  * @param checkboxLabel Optional localized checkbox label resource; null hides the checkbox. Default: null.
  * @param onCheckedConfirm Receives the checkbox value after closing and before onConfirm, even when the default hidden checkbox remains false.
  * @param onConfirm Action invoked after the dialog is accepted and closed.
  * @return Unit; cancellation only closes the dialog.
  */
  fun confirm(
    @StringRes message: Int,
    vararg args: Any,
    icon: ImageVector = Icons.AutoMirrored.Outlined.HelpOutline,
    confirmIcon: ImageVector = Icons.Outlined.Check,
    @StringRes confirmLabel: Int = R.string.ok,
    @StringRes checkboxLabel: Int? = null,
    onCheckedConfirm: (Boolean) -> Unit = {},
    onConfirm: () -> Unit
  ) = show {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
      onDismissRequest = ::close,
      icon = { Icon(icon, contentDescription = null) },
      text = {
        Column {
          Text(stringResource(message, *args))
          checkboxLabel?.let { label ->
            Row(Modifier.clickable { checked = !checked }, verticalAlignment = Alignment.CenterVertically) {
              Checkbox(checked = checked, onCheckedChange = { checked = it })
              Text(stringResource(label))
            }
          }
        }
      },
      confirmButton = {
        DialogButton(confirmIcon, confirmLabel, primary = true) {
          close()
          onCheckedConfirm(checked)
          onConfirm()
        }
      },
      dismissButton = { CancelButton(::close) }
    )
  }

  // The message is a plural resource taking the count.
  /**
  * Shows a confirmation whose localized message is selected and formatted with a count.
  * @param message Plurals resource selected by count and formatted with that same count.
  * @param count Number of items used for counting, formatting or notification display.
  * @param icon Icon displayed for this model or interface action. Default: Icons.AutoMirrored.Outlined.HelpOutline.
  * @param confirmIcon Icon displayed on the confirmation action. Default: Icons.Outlined.Check.
  * @param confirmLabel String resource identifying the confirmation button label. Default: R.string.ok.
  * @param onConfirm Action invoked after the dialog is accepted and closed.
  * @return Unit; acceptance closes the dialog before invoking onConfirm.
  */
  fun confirmPlural(
    @PluralsRes message: Int,
    count: Int,
    icon: ImageVector = Icons.AutoMirrored.Outlined.HelpOutline,
    confirmIcon: ImageVector = Icons.Outlined.Check,
    @StringRes confirmLabel: Int = R.string.ok,
    onConfirm: () -> Unit
  ) = show {
    AlertDialog(
      onDismissRequest = ::close,
      icon = { Icon(icon, contentDescription = null) },
      text = { Text(pluralStringResource(message, count, count)) },
      confirmButton = {
        DialogButton(confirmIcon, confirmLabel, primary = true) {
          close()
          onConfirm()
        }
      },
      dismissButton = { CancelButton(::close) }
    )
  }

  // onOk returns an error message resource or null when the value is accepted.
  /**
  * Edits a single-line value with optional voice input and validates it through onOk.
  *
  * Usage: The value passed to onOk is trimmed; return null to accept it.
  * @param title String resource used as the input dialog's title.
  * @param initial Initial untrimmed text displayed in the input field.
  * @param icon Icon displayed for this model or interface action. Default: Icons.Outlined.Edit.
  * @param onOk Receives trimmed input; return a string resource ID to display an error, or null to accept and close.
  * @return Unit; a nonnull error resource keeps the dialog open.
  */
  fun input(@StringRes title: Int, initial: String, icon: ImageVector = Icons.Outlined.Edit, onOk: (String) -> Int?) = show {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
      onDismissRequest = ::close,
      icon = { Icon(icon, contentDescription = null) },
      title = { Text(stringResource(title)) },
      text = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = text,
            onValueChange = {
              text = it
              error = null
            },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(stringResource(it)) } },
            modifier = Modifier.weight(1f)
          )
          VoiceButton {
            text = it
            error = null
          }
        }
      },
      confirmButton = {
        DialogButton(Icons.Outlined.Check, R.string.ok, primary = true) {
          error = onOk(text.trim())
          if (error == null) close()
        }
      },
      dismissButton = { CancelButton(::close) }
    )
  }

  /**
  * Emits the current application dialog.
  * @return Unit; install one host above the screen's other content.
  */
  @Composable
  fun Host() {
    content?.invoke()
  }
}

// Popup menu at a point of the map.
/**
* Hosts a popup at a screen location and optionally exposes its geographic marker to the map layer.
*
* Public and subclass/module-facing members:
* - [marker] - Geographic place associated with the open popup, or null; used by TapMarkerModule.
* - [show] - Opens a popup at screen coordinates with an optional geographic marker.
* - [close] - Closes the popup and clears its anchor and geographic marker.
* - [Host] - Displays the current anchored popup and closes it on dismissal.
*/
object MapPopup {

  private var anchor by mutableStateOf<Offset?>(null)
  private var content by mutableStateOf<(@Composable ColumnScope.() -> Unit)?>(null)

  // Place the menu is about; drawn on the map by TapMarkerModule while the menu is open.
  /**
  * Geographic place associated with the open popup, or null; used by TapMarkerModule.
  * @return Geographic place associated with the open popup, or null; used by TapMarkerModule.
  */
  var marker by mutableStateOf<GeoPoint?>(null)
    private set

  /**
  * Opens a popup at screen coordinates with an optional geographic marker.
  * @param at Popup anchor in the map host's screen coordinates, in pixels.
  * @param marker Optional geographic position displayed while the popup is open. Default: null.
  * @param content Composable content emitted by this host in its declared scope.
  * @return Unit; replaces any existing popup content.
  */
  fun show(at: Offset, marker: GeoPoint? = null, content: @Composable ColumnScope.() -> Unit) {
    anchor = at
    this.marker = marker
    this.content = content
  }

  /**
  * Closes the popup and clears its anchor and geographic marker.
  * @return Unit; updates observable popup state.
  */
  fun close() {
    anchor = null
    marker = null
    content = null
  }

  /**
  * Displays the current anchored popup and closes it on dismissal.
  * @return Unit; install in the same coordinate space as the supplied anchor.
  */
  @Composable
  fun Host() {
    val at = anchor ?: return
    val current = content ?: return
    key(at) {
      Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
        DropdownMenu(expanded = true, onDismissRequest = ::close) { current() }
      }
    }
  }
}

// Floating toolbar over the top toolbar: multiple selection, point moving.
/**
* Hosts temporary toolbar actions and an explicit cancellation callback.
*
* Public and subclass/module-facing members:
* - [isOpen] - Whether temporary floating-toolbar content is installed.
* - [show] - Displays temporary toolbar content with an owner cancellation callback.
* - [close] - Closes the floating toolbar without notifying its owner of cancellation.
* - [cancel] - Closes the floating toolbar and then invokes the saved cancellation callback.
* - [Host] - Displays the temporary toolbar and binds Back to cancel.
*/
object FloatingBar {

  private var content by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
  private var onCancel: (() -> Unit)? = null

  /**
  * Whether temporary floating-toolbar content is installed.
  * @return Whether temporary floating-toolbar content is installed.
  */
  val isOpen: Boolean get() = content != null

  /**
  * Displays temporary toolbar content with an owner cancellation callback.
  * @param onCancel Callback invoked when the owning action or alert is explicitly cancelled.
  * @param content Composable content emitted by this host in its declared scope.
  * @return Unit; replaces the previous floating toolbar.
  */
  fun show(onCancel: () -> Unit, content: @Composable RowScope.() -> Unit) {
    this.onCancel = onCancel
    this.content = content
  }

  /**
  * Closes the floating toolbar without notifying its owner of cancellation.
  * @return Unit; clears content and callback.
  */
  fun close() {
    content = null
    onCancel = null
  }

  // Closes and tells the owner that the mode is cancelled.
  /**
  * Closes the floating toolbar and then invokes the saved cancellation callback.
  * @return Unit; safe when no callback exists.
  */
  fun cancel() {
    val callback = onCancel
    close()
    callback?.invoke()
  }

  /**
  * Displays the temporary toolbar and binds Back to cancel.
  * @param modifier Compose layout and drawing modifier applied to the emitted host. Default: Modifier.
  * @return Unit; the modifier controls placement above the main toolbar.
  */
  @Composable
  fun Host(modifier: Modifier = Modifier) {
    BackHandler(enabled = content != null) { cancel() }
    val current = content ?: return
    Surface(
      modifier = modifier.fillMaxWidth().height(TOOLBAR_HEIGHT),
      color = MaterialTheme.colorScheme.primaryContainer,
      shadowElevation = 6.dp
    ) {
      Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) { current() }
    }
  }
}

/**
* Displays a clickable drawable icon with an accessibility label and optional color tint.
* @param icon Drawable resource identifying the action or search-result icon.
* @param label String resource identifying the displayed label.
* @param enabled Whether the control accepts interaction; disabled controls are also visually dimmed. Default: true.
* @param tint Optional override for the displayed icon's color; an unspecified or absent value preserves its natural colors. Default: null.
* @param size Drawable size in dp; the clickable container adds 16 dp.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the icon control.
*/
@Composable
fun IconButtonImage(
  @DrawableRes icon: Int,
  @StringRes label: Int,
  enabled: Boolean = true,
  tint: Color? = null,
  size: Int = 28,
  onClick: () -> Unit
) {
  Box(
    modifier = Modifier.size((size + 16).dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
    contentAlignment = Alignment.Center
  ) {
    Image(
      painter = painterResource(icon),
      contentDescription = stringResource(label),
      modifier = Modifier.size(size.dp),
      alpha = if (enabled) 1f else DISABLED_ALPHA,
      colorFilter = when {
        !enabled -> DISABLED_FILTER
        tint != null -> ColorFilter.tint(tint)
        else -> null
      }
    )
  }
}

/**
* Displays an eye icon representing current visibility and invokes the supplied toggle action.
* @param visible Whether the point, route or track should be displayed.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the visibility button.
*/
@Composable
fun EyeButton(visible: Boolean, onClick: () -> Unit) {
  IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
    Icon(
      if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
      contentDescription = stringResource(R.string.visibility),
      tint = if (visible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    )
  }
}

// Row that shows a delete button on the right when swiped to the left.
/**
* Reveals a delete action by dragging row content to the left.
*
* Usage: The callback owns deletion and any confirmation; disabling gestures does not itself delete or reset data.
* @param enabled Whether the control accepts interaction; disabled controls are also visually dimmed.
* @param onDelete Action invoked when the revealed delete control is activated; owns any confirmation.
* @param content Composable content emitted by this host in its declared scope.
* @return Unit; emits the swipeable row.
*/
@Composable
fun SwipeRevealRow(enabled: Boolean, onDelete: () -> Unit, content: @Composable () -> Unit) {
  val revealPx = with(LocalDensity.current) { 72.dp.toPx() }
  val offset = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()
  Box(Modifier.fillMaxWidth()) {
    if (offset.value < 0f) {
      Box(Modifier.matchParentSize().background(DELETE_BACKGROUND), contentAlignment = Alignment.CenterEnd) {
        Box(
          modifier = Modifier.width(72.dp).fillMaxHeight().clickable {
            scope.launch { offset.animateTo(0f) }
            onDelete()
          },
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = Color.White)
        }
      }
    }
    Box(
      Modifier
        .offset { IntOffset(offset.value.roundToInt(), 0) }
        .background(Color.White)
        .draggable(
          state = rememberDraggableState { delta -> scope.launch { offset.snapTo((offset.value + delta).coerceIn(-revealPx, 0f)) } },
          orientation = Orientation.Horizontal,
          enabled = enabled,
          onDragStopped = { offset.animateTo(if (offset.value < -revealPx / 2) -revealPx else 0f) }
        )
    ) { content() }
  }
}

/**
* Opens Android sharing choosers using FileProvider URIs with read permission.
*
* Public and subclass/module-facing members:
* - [shareText] - Opens an Android chooser for plain text with a subject.
* - [uriFor] - Creates a FileProvider content URI for a shareable application file.
* - [share] - Opens an Android chooser for one or more files with temporary read permission.
*/
object Sharing {

  /**
  * Opens an Android chooser for plain text with a subject.
  * @param text Text to display, search, parse or share as specified by this operation.
  * @param subject Subject included in the plain-text sharing intent.
  * @return Unit; reports when no sharing activity is available.
  */
  fun shareText(text: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    start(Intent.createChooser(intent, null))
  }

  /**
  * Creates a FileProvider content URI for a shareable application file.
  *
  * Usage: The file must lie within the configured provider paths; unsupported paths raise an exception.
  * @param file File to read, copy, share, decode or release as described by the operation.
  * @return The content URI.
  */
  fun uriFor(file: File): Uri = FileProvider.getUriForFile(AppSession.app, "${AppSession.app.packageName}.files", file)

  /**
  * Opens an Android chooser for one or more files with temporary read permission.
  * @param files Files to share or copy; no ownership transfer is implied unless documented.
  * @param mimeType MIME type advertised to the receiving Android application.
  * @param text Текст вместе с файлами; без файлов отправляется один текст.
  * @return Unit; an empty list without text does nothing.
  */
  fun share(files: List<File>, mimeType: String, text: String = "") {
    if (files.isEmpty()) {
      if (text.isNotEmpty()) start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
      return
    }
    val uris = ArrayList(files.map(::uriFor))
    val intent = if (uris.size == 1) {
      Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
      Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }
    intent.type = mimeType
    if (text.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, text) // мессенджеры ставят текст подписью
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    start(Intent.createChooser(intent, null))
  }

  private fun start(chooser: Intent) {
    val context = AppSession.context
    if (context === AppSession.app) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
      Notify.error(R.string.share_unavailable)
    }
  }
}
