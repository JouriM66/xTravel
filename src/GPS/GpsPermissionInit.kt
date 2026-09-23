// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.location.LocationManagerCompat

/**
 * Runs the activity permission and device-settings sequence needed to enable GPS collection.
 * It must be created during Activity.onCreate, and any refusal disables GPS.
 */
class GpsPermissionInit(private val activity: AppCompatActivity) {

  private enum class Step { LOCATION, NOTIFICATIONS, LOCATION_ON, BATTERY, MIUI, DONE }

  private var step = Step.LOCATION
  private var active = false

  private var requested: Array<String> = emptyArray() /** The first permission determines the request result. */

  private val permissionLauncher = activity.registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()
  ) { result ->
    val main = requested.first()
    when {
      result[main] == true || granted(main) -> next()
      main == Manifest.permission.ACCESS_FINE_LOCATION && granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> {
        AppSession.message(R.string.gps_precise_required)
        refuse()
      }
      else -> {
        /** A permanently refused request must be handled through application settings next time. */
        if (!activity.shouldShowRequestPermissionRationale(main)) {
          Settings.prefs.edit { putBoolean(blockedKey(main), true) }
        }
        refuse()
      }
    }
  }

  private val settingsLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
    val from = step
    if (from == Step.MIUI) showMiui() else next(returnedFrom = from)
  }

  /** Starts the asynchronous GPS permission and settings sequence unless it is already active. */
  fun run() {
    if (active) return
    active = true
    step = Step.LOCATION
    next()
  }

  private fun next(returnedFrom: Step? = null) {
    while (true) {
      val done = when (step) {
        Step.LOCATION -> granted(Manifest.permission.ACCESS_FINE_LOCATION)
        Step.NOTIFICATIONS -> Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        Step.LOCATION_ON -> locationEnabled()
        Step.BATTERY -> ignoringBatteryOptimizations()
        Step.MIUI -> !isMiui() || Settings.miuiConfirmed.value
        Step.DONE -> {
          finish()
          return
        }
      }
      if (done) {
        step = Step.entries[step.ordinal + 1]
        continue
      }
      if (returnedFrom == step) {
        refuse()
        return
      }
      ask()
      return
    }
  }

  private fun ask() {
    when (step) {
      /** Android 12+ ignores a precise-location request without the approximate permission. */
      Step.LOCATION -> askPermission(
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        R.string.gps_location_permission_settings
      )
      Step.NOTIFICATIONS -> askPermission(
        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
        R.string.gps_notification_permission_settings
      )
      Step.LOCATION_ON -> dialog(
        Icons.Outlined.LocationOff,
        R.string.gps_location_off,
        Icons.Outlined.LocationOn,
        R.string.turn_on
      ) {
        openSettings(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS))
      }
      Step.BATTERY -> dialog(
        Icons.Outlined.BatteryAlert,
        R.string.gps_battery,
        Icons.Outlined.Check,
        R.string.allow
      ) {
        openSettings(
          Intent(
            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}")
          )
        )
      }
      Step.MIUI -> showMiui()
      Step.DONE -> Unit
    }
  }

  /** Opens application settings when Android no longer displays a permanently refused permission request. */
  private fun askPermission(permissions: Array<String>, @StringRes settingsMessage: Int) {
    val main = permissions.first()
    if (Settings.prefs.getBoolean(blockedKey(main), false) && !activity.shouldShowRequestPermissionRationale(main)) {
      dialog(Icons.Outlined.Lock, settingsMessage, Icons.Outlined.Settings, R.string.open_settings) {
        openSettings(appSettingsIntent())
      }
      return
    }
    Settings.prefs.edit { remove(blockedKey(main)) }
    requested = permissions
    if (Failures.guard(FailureSource.GPS) { permissionLauncher.launch(permissions) } == null) {
      refuse()
    }
  }

  /** Treats a missing settings screen as a refusal because the required state cannot be enabled. */
  private fun openSettings(intent: Intent) {
    if (Failures.guard(FailureSource.GPS) { settingsLauncher.launch(intent) } == null) {
      refuse()
    }
  }

  private fun blockedKey(permission: String) = "blocked_$permission"

  private fun showMiui() {
    AppDialog.show {
      AlertDialog(
        onDismissRequest = { cancelDialog() },
        icon = { Icon(Icons.Outlined.BatteryAlert, contentDescription = null) },
        text = { Text(stringResource(R.string.gps_miui)) },
        confirmButton = {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DialogButton(Icons.Outlined.Check, R.string.ok, primary = true, fullWidth = true) {
              AppDialog.close()
              Settings.miuiConfirmed.value = true
              next()
            }
            DialogButton(Icons.Outlined.Settings, R.string.open_settings, fullWidth = true) {
              AppDialog.close()
              openSettings(appSettingsIntent())
            }
            CancelButton { cancelDialog() }
          }
        }
      )
    }
  }

  private fun dialog(
    icon: ImageVector,
    @StringRes message: Int,
    actionIcon: ImageVector,
    @StringRes action: Int,
    onAction: () -> Unit
  ) {
    AppDialog.show {
      AlertDialog(
        onDismissRequest = { cancelDialog() },
        icon = { Icon(icon, contentDescription = null) },
        text = { Text(stringResource(message)) },
        confirmButton = {
          DialogButton(actionIcon, action, primary = true) {
            AppDialog.close()
            onAction()
          }
        },
        dismissButton = { CancelButton { cancelDialog() } }
      )
    }
  }

  private fun cancelDialog() {
    AppDialog.close()
    refuse()
  }

  private fun refuse() {
    active = false
    Settings.useGps.value = false
    GpsService.stop(activity)
    Notify.info(R.string.gps_off)
  }

  private fun finish() {
    active = false
    GpsService.start(activity)
  }

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

  private fun locationEnabled(): Boolean {
    val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return LocationManagerCompat.isLocationEnabled(manager)
  }

  private fun ignoringBatteryOptimizations(): Boolean {
    val manager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
    return manager.isIgnoringBatteryOptimizations(activity.packageName)
  }

  private fun appSettingsIntent() =
    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))

  /** MIUI and HyperOS require manual autostart and unrestricted-battery settings for background work. */
  private fun isMiui(): Boolean = Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")
}
