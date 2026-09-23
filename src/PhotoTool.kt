// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// FOR LOCAL USE: stages of the toolbar photo transaction.
private enum class PhotoStage { IDLE, CAMERA, REVIEW, DESTINATION, DISCARD }

// FOR LOCAL USE: the photo tool owns unattached files until the whole series is accepted or discarded.
/**
* Displays the photo toolbar control and owns camera-series review, destination and discard dialogs.
*
* Usage: Coordinates are captured before the first camera launch. Files stay unattached until a destination is chosen; discard deletes the series.
* @return Unit; emits the control and the active dialog.
*/
@Composable
fun PhotoToolButton() {
  var stage by rememberSaveable { mutableStateOf(PhotoStage.IDLE) }
  var files by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
  var pending by rememberSaveable { mutableStateOf<String?>(null) }
  var latitude by rememberSaveable { mutableStateOf(0.0) }
  var longitude by rememberSaveable { mutableStateOf(0.0) }
  val shoot = remember { arrayOfNulls<() -> Unit>(1) }

  fun discard() {
    (files + listOfNotNull(pending)).forEach { name ->
      val file = PointStore.pictureFile(name)
      file.delete()
      PointStore.releasePicture(file)
      Pictures.forget(name)
    }
    files = arrayListOf()
    pending = null
    stage = PhotoStage.IDLE
  }

  fun attach(owner: InfoOwner) {
    if (owner.info == null) return
    owner.update { MetaInfo(it.elements + files.map { name -> PointElement.Picture(name) }) }
    files.forEach { PointStore.releasePicture(PointStore.pictureFile(it)) }
    files = arrayListOf()
    stage = PhotoStage.IDLE
  }

  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
    val name = pending ?: return@rememberLauncherForActivityResult
    pending = null
    val file = PointStore.pictureFile(name)
    if (ok && file.length() > 0) {
      TrackStorage.io.execute { recompressPicture(file) }
      files = ArrayList(files + name)
      shoot[0]?.invoke()
    } else {
      file.delete()
      PointStore.releasePicture(file)
      stage = if (files.isEmpty()) PhotoStage.IDLE else PhotoStage.REVIEW
    }
  }
  shoot[0] = {
    val file = PointStore.newPictureFile("jpg")
    pending = file.name
    stage = PhotoStage.CAMERA
    try {
      launcher.launch(Sharing.uriFor(file))
    } catch (_: ActivityNotFoundException) {
      file.delete()
      PointStore.releasePicture(file)
      pending = null
      stage = if (files.isEmpty()) PhotoStage.IDLE else PhotoStage.REVIEW
      Notify.error(R.string.camera_unavailable)
    }
  }
  val active = stage != PhotoStage.IDLE
  DisposableEffect(active) {
    if (active) UserActivity.hold()
    onDispose { if (active) UserActivity.resume() }
  }
  ToolbarItem(Icons.Outlined.PhotoCamera, R.string.photo_tool, onClick = sheetFirst {
    if (stage == PhotoStage.IDLE) {
      val position = GpsDataManager.lastFix?.takeIf { GpsDataManager.status == GpsStatus.OK }
        ?.let { GeoPoint(it.lat, it.lon) } ?: Maps.camera.center
      latitude = position.lat
      longitude = position.lon
      shoot[0]?.invoke()
    }
  })

  when (stage) {
    PhotoStage.REVIEW -> AlertDialog(
      onDismissRequest = ::discard,
      icon = { Icon(Icons.Outlined.PhotoCamera, null) },
      title = { Text(stringResource(R.string.photo_series_count, files.size)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(files, key = { it }) { SquareThumb(it, Modifier.size(80.dp)) }
          }
          DialogButton(Icons.Outlined.PhotoCamera, R.string.photo_continue, fullWidth = true) { shoot[0]?.invoke() }
          DialogButton(Icons.Outlined.Save, R.string.photo_save, primary = true, fullWidth = true) { stage = PhotoStage.DESTINATION }
          DeleteDialogButton(fullWidth = true, onClick = ::discard)
        }
      },
      confirmButton = {}
    )
    PhotoStage.DESTINATION -> AlertDialog(
      onDismissRequest = { stage = PhotoStage.DISCARD },
      title = { Text(stringResource(R.string.photo_series_count, files.size)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          DialogButton(Icons.Outlined.NearMe, R.string.photo_nearest, fullWidth = true, enabled = PointStore.points.isNotEmpty()) {
            PointStore.points.minByOrNull { GeoMath.distance(latitude, longitude, it.lat, it.lon) }?.let { attach(InfoOwner.Point(it.id)) }
          }
          DialogButton(Icons.AutoMirrored.Outlined.Notes, R.string.photo_notes, fullWidth = true) { attach(InfoOwner.Notes) }
          DialogButton(Icons.Outlined.AddLocationAlt, R.string.photo_here, fullWidth = true) {
            val point = PointStore.points.firstOrNull { GeoMath.samePlace(latitude, longitude, it.lat, it.lon) }
              ?: PointStore.add(GeoPoint(latitude, longitude))
            attach(InfoOwner.Point(point.id))
          }
          DeleteDialogButton(fullWidth = true, onClick = ::discard)
        }
      },
      confirmButton = {}
    )
    PhotoStage.DISCARD -> AlertDialog(
      onDismissRequest = ::discard,
      icon = { Icon(Icons.Outlined.WarningAmber, null) },
      text = { Text(stringResource(R.string.photo_unsaved)) },
      confirmButton = { DialogButton(Icons.AutoMirrored.Outlined.List, R.string.photo_choose_again, primary = true) { stage = PhotoStage.DESTINATION } },
      dismissButton = { DeleteDialogButton(onClick = ::discard) }
    )
    else -> Unit
  }
}
