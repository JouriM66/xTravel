// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Встроенная карта: свои векторные тайлы PMTiles без сети (тип MapType.NONE, пункт "Встроенный").
    Проекция - Web Mercator, калибровки нет: экран считается MapViewport по формуле, как и слои приложения.
*/
class VectorMapEngine : IMapEngine {

  override val projection: IMapProjection get() = WebMercatorProjection

  private var shownCamera by mutableStateOf(CameraState(GeoPoint(0.0, 0.0), 1f, 0f))
  private var tiles: VectorTiles? = null

  override fun setCamera(state: CameraState) {
    shownCamera = state
  }

  /** Сменились файл карты или оформление. */
  override fun settingsChanged() {
    tiles?.reset()
  }

  override fun release() {
    tiles?.close()
    tiles = null
  }

  @Composable
  override fun Backdrop(modifier: Modifier) {
    val density = LocalDensity.current.density
    val current = remember(density) { VectorTiles(density).also { tiles = it } }
    DisposableEffect(current) {
      onDispose {
        current.close()
        if (tiles === current) tiles = null
      }
    }
    Canvas(modifier) {
      current.revision
      current.draw(drawContext.canvas.nativeCanvas, size.width, size.height, shownCamera)
    }
  }
}

/** Строки редактора настроек встроенной карты (MapType.NONE.settings). */
object VectorMapSettings {

  @Composable
  fun Rows() {
    FileRow()
    ChoiceRow(R.string.map_style, Settings.vectorMapStyle, MapStyleKind.entries.associateWith { it.label })
  }

  /** Выбранный файл карты; выбор - системным диалогом, файл читается на месте, приложение его не копирует. FOR LOCAL USE */
  @Composable
  private fun FileRow() {
    val file = Settings.vectorMapFile.value
    val name = remember(file) { if (file.isEmpty()) null else displayNameOf(Uri.parse(file)) ?: file }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) choose(uri) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.vector_map_file), modifier = Modifier.weight(1f))
        IconButton(onClick = { pick.launch(arrayOf("*/*")) }) {
          Icon(Icons.Outlined.FolderOpen, stringResource(R.string.vector_map_choose), tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { clear() }, enabled = file.isNotEmpty()) {
          Icon(Icons.Outlined.Close, stringResource(R.string.vector_map_builtin))
        }
      }
      Text(
        name ?: stringResource(R.string.vector_map_builtin),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  }

  // Негодный файл не принимается: прежний выбор остаётся.
  private fun choose(uri: Uri) {
    val resolver = AppSession.app.contentResolver
    val check = runCatching { PmTiles(ChannelBytes.uri(uri)).close() }
    if (check.isFailure) {
      Notify.error(R.string.vector_file_invalid, displayNameOf(uri) ?: uri.toString())
      return
    }
    runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    if (Settings.vectorMapFile.value != uri.toString()) releaseCurrent()
    Settings.vectorMapFile.value = uri.toString()
  }

  private fun clear() {
    releaseCurrent()
    Settings.vectorMapFile.value = ""
  }

  private fun releaseCurrent() {
    val current = Settings.vectorMapFile.value
    if (current.isEmpty()) return
    runCatching { AppSession.app.contentResolver.releasePersistableUriPermission(Uri.parse(current), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
  }
}

/** Сведения для окна "О программе": данные OpenStreetMap и оформление Protomaps. Встроенная карта есть всегда. */
object VectorMapLicense : ILicenseInfo {
  override fun licenseInfo(): Array<LicenseInfo> = arrayOf(
    LicenseInfo("OpenStreetMap", AppSession.context.getString(R.string.license_osm)),
    LicenseInfo("Protomaps Basemaps", AppSession.context.getString(R.string.license_protomaps))
  )
}
