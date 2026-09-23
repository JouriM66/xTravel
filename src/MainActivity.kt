package com.jm.xtravel

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView

/**
* Hosts the Compose interface, activity lifecycle, incoming import intents and GPS setup.
*
* Public and subclass/module-facing members:
* - [gpsSetup] - Activity-owned permission/settings coordinator initialized during onCreate.
* - [onCreate] - Initializes activity services, checks GPS setup, processes an initial import and installs the Compose screen.
* - [onNewIntent] - Processes another import intent delivered to the singleTop activity.
* - [onStart] - Starts visible map activity after the superclass callback.
* - [onResume] - Marks renewed activity interaction after resuming.
* - [dispatchTouchEvent] - Tracks touch holds and releases while delegating event handling to the activity.
* - [dispatchKeyEvent] - Marks key-down interaction before delegating key handling.
* - [onStop] - Stops map activity and queues a data save before the superclass callback.
* - [onDestroy] - Detaches this activity from AppSession before superclass destruction.
*/
class MainActivity : AppCompatActivity() {

  /**
  * Activity-owned permission/settings coordinator initialized during onCreate.
  * @return Activity-owned permission/settings coordinator initialized during onCreate.
  */
  lateinit var gpsSetup: GpsPermissionInit
    private set

  /**
  * Initializes activity services, checks GPS setup, processes an initial import and installs the Compose screen.
  * @param savedInstanceState Android activity restoration state, or null on a fresh creation.
  * @return Unit; Android lifecycle entry point.
  */
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
      navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
    )
    super.onCreate(savedInstanceState)
    Languages.init()
    AppSession.attach(this)
    gpsSetup = GpsPermissionInit(this)
    AppSession.ensureRunning()
    if (savedInstanceState == null) {
      Failures.showLastCrash(this)
      if (Settings.useGps.value) gpsSetup.run()
      DataImport.handle(this, intent)
    }
    setContent {
      XTravelTheme {
        MainScreen()
      }
    }
  }

  /**
  * Processes another import intent delivered to the singleTop activity.
  * @param intent Android intent carrying startup, import or notification information.
  * @return Unit; delegates import extraction to DataImport.
  */
  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    DataImport.handle(this, intent)
  }

  /**
  * Starts visible map activity after the superclass callback.
  * @return Unit; Android lifecycle entry point.
  */
  override fun onStart() {
    super.onStart()
    Maps.start()
  }

  /**
  * Marks renewed activity interaction after resuming.
  * @return Unit; restarts idle timing through UserActivity.
  */
  override fun onResume() {
    super.onResume()
    UserActivity.touch()
  }

  // A finger on the screen holds the idle time of the user, lifting it starts the time anew.
  /**
  * Tracks touch holds and releases while delegating event handling to the activity.
  * @param event Platform event supplied to this callback.
  * @return The superclass event-consumption result.
  */
  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> UserActivity.hold()
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> UserActivity.resume()
    }
    return super.dispatchTouchEvent(event)
  }

  /**
  * Marks key-down interaction before delegating key handling.
  * @param event Platform event supplied to this callback.
  * @return The superclass event-consumption result.
  */
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) UserActivity.touch()
    return super.dispatchKeyEvent(event)
  }

  /**
  * Stops map activity and queues a data save before the superclass callback.
  * @return Unit; disk persistence is asynchronous.
  */
  override fun onStop() {
    Maps.stop()
    DataStore.saveNow()
    super.onStop()
  }

  /**
  * Detaches this activity from AppSession before superclass destruction.
  * @return Unit; does not end the application session by itself.
  */
  override fun onDestroy() {
    AppSession.detach(this)
    super.onDestroy()
  }
}

// Back closes the dialog, the floating bar, the modal window, the popup and the sheet in turn (their own handlers),
// then asks whether to exit like the Exit menu item.
@Composable
private fun MainScreen() {
  BackHandler {
    AppDialog.confirm(R.string.exit_confirm, confirmIcon = Icons.AutoMirrored.Outlined.ExitToApp) {
      AppCommands.exit.execute()
    }
  }
  val view = LocalView.current
  val keepScreenOn = Settings.keepScreenOn.value
  DisposableEffect(keepScreenOn) {
    if (keepScreenOn) view.keepScreenOn = true
    onDispose { if (keepScreenOn) view.keepScreenOn = false }
  }
  Box(Modifier.fillMaxSize().background(TOOLBAR_COLOR)) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
      TopToolbar()
      Box(Modifier.weight(1f).fillMaxWidth()) {
        MapArea(Modifier.fillMaxSize())
        BottomSheet.Host(Modifier.fillMaxSize())
      }
      BottomToolbar()
    }
    FloatingBar.Host(Modifier.statusBarsPadding())
    ModalScreen.Host()
    AppDialog.Host()
  }
}
