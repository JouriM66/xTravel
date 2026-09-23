// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

// FOR LOCAL USE: submenu selection in the main toolbar menu.
private enum class MenuState { CLOSED, DATA, MAIN, MAP, DEBUG, DEBUG_TIME, DEBUG_POSITION }

// FOR LOCAL USE: application actions and settings in the main toolbar.
/**
* Displays application, data, map-provider and debug menu actions.
* @return Unit; closes the sheet before opening menu controls.
*/
@Composable
internal fun MainMenu() {
  var menu by remember { mutableStateOf(MenuState.CLOSED) }
  val context = LocalContext.current
  val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
    if (uri != null) DataImport.open(context, listOf(uri))
  }
  Box {
    ToolbarItem(Icons.Outlined.Menu, R.string.menu, onClick = sheetFirst { menu = MenuState.MAIN })
    DropdownMenu(expanded = menu == MenuState.MAIN, onDismissRequest = { menu = MenuState.CLOSED }) {
      //val place = PointActions.menuPlace()
      CommandMenuItem(Icons.Outlined.Settings, stringResource(R.string.settings), AppCommands.openSettings) { menu = MenuState.CLOSED }
      MenuItem(Icons.Outlined.Layers, stringResource(R.string.map), trailing = SUBMENU_ICON) { menu = MenuState.MAP }
      MenuItem(Icons.Outlined.Description, stringResource(R.string.menu_data), trailing = SUBMENU_ICON) { menu = MenuState.DATA }
      HorizontalDivider()
      CommandMenuItem(Icons.Outlined.Info, stringResource(R.string.about), AppCommands.openAbout) { menu = MenuState.CLOSED }
      CommandMenuItem(Icons.AutoMirrored.Outlined.ExitToApp, stringResource(R.string.exit), AppCommands.exit) { menu = MenuState.CLOSED }
      if ( BuildConfig.DEBUG ) {
       HorizontalDivider()
       MenuItem(Icons.Outlined.BugReport, stringResource(R.string.debug), trailing = SUBMENU_ICON) { menu = MenuState.DEBUG }
      }
    }
    // Debug values of the whole application: the time every schedule is computed for and the source of the position.
    DropdownMenu(expanded = menu == MenuState.DEBUG, onDismissRequest = { menu = MenuState.CLOSED }) {
      MenuItem(Icons.Outlined.Timer, stringResource(R.string.debug_time), trailing = SUBMENU_ICON) { menu = MenuState.DEBUG_TIME }
      MenuItem(Icons.Outlined.MyLocation, stringResource(R.string.debug_position), trailing = SUBMENU_ICON) { menu = MenuState.DEBUG_POSITION }
      CommandMenuItem(Icons.Outlined.Save, stringResource(R.string.save_settings), AppCommands.saveSettings) { menu = MenuState.CLOSED }
    }
    DropdownMenu(expanded = menu == MenuState.DATA, onDismissRequest = { menu = MenuState.CLOSED }) {
     CommandMenuItem(Icons.Outlined.Share, stringResource(R.string.share), AppCommands.shareData) { menu = MenuState.CLOSED }
     MenuItem(Icons.Outlined.FileDownload, stringResource(R.string.import_data)) {
       menu = MenuState.CLOSED
       importFile.launch(arrayOf("*/*"))
     }
     CommandMenuItem(Icons.Outlined.DeleteSweep, stringResource(R.string.clear), AppCommands.clearData) { menu = MenuState.CLOSED }
     CommandMenuItem(Icons.Outlined.CleaningServices, stringResource(R.string.compact_data), AppCommands.checkFiles) { menu = MenuState.CLOSED }
    }
    DropdownMenu(expanded = menu == MenuState.DEBUG_TIME, onDismissRequest = { menu = MenuState.CLOSED }) {
      ChoiceMenuItem(stringResource(R.string.debug_none), AppClock.debugStart == null) {
        menu = MenuState.CLOSED
        AppClock.useTime(null)
      }
      DEBUG_TIMES.forEach { minutes ->
        ChoiceMenuItem(formatTime(minutes), AppClock.debugStart == minutes) {
          menu = MenuState.CLOSED
          AppClock.useTime(minutes)
        }
      }
    }
    DropdownMenu(expanded = menu == MenuState.DEBUG_POSITION, onDismissRequest = { menu = MenuState.CLOSED }) {
      ChoiceMenuItem(stringResource(R.string.debug_none), !GpsDataManager.emulated) {
        menu = MenuState.CLOSED
        GpsDataManager.emulate(false)
      }
      ChoiceMenuItem(stringResource(R.string.debug_position_manual), GpsDataManager.emulated) {
        menu = MenuState.CLOSED
        GpsDataManager.emulate(true)
      }
    }
    DropdownMenu(expanded = menu == MenuState.MAP, onDismissRequest = { menu = MenuState.CLOSED }) {
      MapType.entries.forEach { type ->
        val icon = if (type == MapType.NONE) Icons.Outlined.GridOn else Icons.Outlined.Map
        CommandMenuItem(icon, stringResource(type.label), AppCommands.selectMapType.getValue(type)) { menu = MenuState.CLOSED }
      }
    }
  }
}
