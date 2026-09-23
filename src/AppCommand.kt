// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import java.io.File

// Controls execute a command without knowing how it is implemented.
/**
* Defines an executable interface action with observable availability.
*
* Public and subclass/module-facing members:
* - [available] - Reports whether the command is currently available for its control.
* - [execute] - Performs the command action.
*/
interface IAppCommand {

  // Reads observable state, so bound controls update by themselves.
  /**
  * Reports whether the command is currently available for its control.
  * @return True by default; implementations may read observable UI state.
  */
  fun available(): Boolean = true

  /**
  * Performs the command action.
  * @return Unit; callers should respect available().
  */
  fun execute()
}

private class ShowSheetCommand(
  private val id: String,
  @StringRes private val caption: Int,
  private val elements: () -> Int = { 0 },
  private val content: @Composable () -> Unit
) : IAppCommand {
  override fun available() = BottomSheet.ownerId != id
  override fun execute() = BottomSheet.open(id, caption, elements, content)
}

private class ShowEditorCommand(private val id: String, private val editor: () -> SheetEditor) : IAppCommand {
  override fun available() = BottomSheet.ownerId != id
  override fun execute() = BottomSheet.open(id, editor())
}

private class OpenModalCommand(private val id: String, private val content: @Composable () -> Unit) : IAppCommand {
  override fun available() = ModalScreen.ownerId != id
  override fun execute() = ModalScreen.open(id, content)
}

private class SelectMapTypeCommand(private val type: MapType) : IAppCommand {
  override fun available() = Maps.shownType != type
  override fun execute() = Maps.select(type)
}

private class SelectLanguageCommand(private val language: AppLanguage) : IAppCommand {
  override fun available() = Languages.current != language
  override fun execute() = Languages.select(language)
}

private class SimpleCommand(private val action: () -> Unit) : IAppCommand {
  override fun execute() = action()
}

/**
* Provides shared commands for sheets, settings, map controls, data exchange and application exit.
*
* Public and subclass/module-facing members:
* - [showPoints] - Command opening the point list; unavailable when that sheet already owns the view.
* - [showTracks] - Command opening the track list; unavailable when that sheet already owns the view.
* - [showRoute] - Command opening the route sheet; unavailable when that sheet already owns the view.
* - [showNotes] - Command opening the synthetic notes point editor.
* - [search] - Command opening the search sheet.
* - [openSettings] - Command opening application settings in the modal screen.
* - [openAbout] - Command opening the application information and license screen.
* - [selectMapType] - Commands indexed by each supported map provider type.
* - [selectLanguage] - Commands indexed by each supported application language.
* - [nextMapDirection] - Command cycling through map-direction modes.
* - [followGps] - Command restoring automatic GPS following from manual positioning.
* - [shareData] - Command opening data selection for export.
* - [clearData] - Command opening data selection for deletion.
* - [checkFiles] - Command scanning stored files for cleanup candidates.
* - [saveSettings] - Command exporting nonsecret preferences to a temporary text file and opening sharing.
* - [exit] - Command ending the current application session through its attached activity.
*/
object AppCommands {
  /**
  * Command opening the point list; unavailable when that sheet already owns the view.
  * @return Command opening the point list; unavailable when that sheet already owns the view.
  */
  val showPoints: IAppCommand = ShowEditorCommand("points") { PointsListEditor() }
  /**
  * Command opening the track list; unavailable when that sheet already owns the view.
  * @return Command opening the track list; unavailable when that sheet already owns the view.
  */
  val showTracks: IAppCommand = ShowEditorCommand("tracks") { TracksListEditor() }
  /**
  * Command opening the route sheet; unavailable when that sheet already owns the view.
  * @return Command opening the route sheet; unavailable when that sheet already owns the view.
  */
  val showRoute: IAppCommand = ShowEditorCommand("route") { RouteSheet() }
  /**
  * Command opening the notes editor.
  * @return Command opening the notes editor.
  */
  val showNotes: IAppCommand = ShowEditorCommand("notes") { PointInfoEditor(InfoOwner.Notes) }
  /**
  * Command opening the search sheet.
  * @return Command opening the search sheet.
  */
  val search: IAppCommand = ShowSheetCommand("search", R.string.sheet_search, { searchResultsCount() }) { SearchSheet() }
  /**
  * Command opening application settings in the modal screen.
  * @return Command opening application settings in the modal screen.
  */
  val openSettings: IAppCommand = OpenModalCommand("settings") { SettingsScreen() }
  /**
  * Command opening the application information and license screen.
  * @return Command opening the application information and license screen.
  */
  val openAbout: IAppCommand = OpenModalCommand("about") { AboutScreen() }
  /**
  * Commands indexed by each supported map provider type.
  * @return Commands indexed by each supported map provider type.
  */
  val selectMapType: Map<MapType, IAppCommand> = MapType.entries.associateWith { SelectMapTypeCommand(it) }
  /**
  * Commands indexed by each supported application language.
  * @return Commands indexed by each supported application language.
  */
  val selectLanguage: Map<AppLanguage, IAppCommand> = Languages.all.associateWith { SelectLanguageCommand(it) }
  /**
  * Command cycling through map-direction modes.
  * @return Command cycling through map-direction modes.
  */
  val nextMapDirection: IAppCommand = SimpleCommand {
    val entries = MapDirection.entries
    Settings.mapDirection.value = entries[(Settings.mapDirection.value.ordinal + 1) % entries.size]
  }
  /**
  * Command restoring automatic GPS following from manual positioning.
  * @return Command restoring automatic GPS following from manual positioning.
  */
  val followGps: IAppCommand = SimpleCommand { Maps.followGps() }

  /**
  * Command opening data selection for export.
  * @return Command opening data selection for export.
  */
  val shareData: IAppCommand = SimpleCommand { DataSelect.openShare() }
  /**
  * Command opening data selection for deletion.
  * @return Command opening data selection for deletion.
  */
  val clearData: IAppCommand = SimpleCommand { DataSelect.openClear() }
  /**
  * Command scanning stored files for cleanup candidates.
  * @return Command scanning stored files for cleanup candidates.
  */
  val checkFiles: IAppCommand = FilesCheck

  /**
  * Command exporting nonsecret preferences to a temporary text file and opening sharing.
  * @return Command exporting nonsecret preferences to a temporary text file and opening sharing.
  */
  val saveSettings: IAppCommand = SimpleCommand {
    val file = File(AppDirs.share, "xtravel-settings.txt")
    file.writeText(Settings.export())
    Sharing.share(listOf(file), "text/plain")
  }

  /**
  * Command ending the current application session through its attached activity.
  * @return Command ending the current application session through its attached activity.
  */
  val exit: IAppCommand = SimpleCommand { AppSession.activity?.let(AppSession::exit) }
}

/**
* Displays a menu item whose enabled state follows the command and closes the menu before executing it.
* @param icon Icon displayed for this model or interface action.
* @param text Text to display, search, parse or share as specified by this operation.
* @param command Shared action supplying availability and execution behavior.
* @param onClick Action invoked when the user activates the control.
* @return Unit; emits the command item.
*/
@Composable
fun CommandMenuItem(icon: ImageVector?, text: String, command: IAppCommand, onClick: () -> Unit) {
  MenuItem(icon, text, enabled = command.available()) {
    onClick()
    command.execute()
  }
}
