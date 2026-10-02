// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import java.io.File
import kotlin.concurrent.thread

/** Каталог данных несовместимого формата при запуске (DataStore.init): окно с выбором - удалить данные, завершить
    приложение без изменения каталога или сохранить данные архивом в Download и затем очистить каталог.
    Служебные каталоги Android (AppDirs.ANDROID_DIRS) не сохраняются и не удаляются.
    Пока решения нет, данные не загружаются и каталог не меняется. Главный поток.
*/
object IncompatibleData {

  private val main = Handler(Looper.getMainLooper())

  /** Окно выбора; error - текст неудачи прошлого сохранения */
  fun ask(version: String, error: String? = null) = AppDialog.show {
    AlertDialog(
      onDismissRequest = {},
      properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
      icon = { Icon(Icons.Outlined.Warning, contentDescription = null) },
      title = { Text(stringResource(R.string.data_incompatible_title)) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(stringResource(R.string.data_incompatible, version.ifEmpty { "?" }))
          error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          DialogButton(Icons.Outlined.DeleteForever, R.string.data_incompatible_delete, danger = true, fullWidth = true) {
            AppDialog.close()
            clear()
          }
          DialogButton(Icons.AutoMirrored.Outlined.ExitToApp, R.string.data_incompatible_exit, fullWidth = true) {
            AppDialog.close()
            AppSession.activity?.let(AppSession::exit)
          }
          DialogButton(Icons.Outlined.Save, R.string.data_incompatible_save, primary = true, fullWidth = true) { askName(version) }
        }
      },
      confirmButton = {}
    )
  }

  private fun askName(version: String) {
    AppDialog.input(R.string.data_incompatible_name, defaultName(), Icons.Outlined.Save, onCancel = { ask(version) }) { name ->
      if (name.isBlank()) return@input R.string.name_invalid
      // Окно ввода закрывается после ответа, поэтому сохранение начинается следующим шагом.
      main.post { save(version, name) }
      null
    }
  }

  /** Имя по версии из version.txt, если её первая строка - целое число, иначе unknown */
  private fun defaultName(): String {
    val file = File(AppDirs.base, VERSION_FILE)
    val number = runCatching { file.takeIf { it.isFile }?.useLines { it.firstOrNull() }?.trim() }.getOrNull()
    return if (number != null && number.matches(Regex("\\d+"))) "xTravel-data-v$number-${fileTimeStamp()}" else "xTravel-unknown-data-${fileTimeStamp()}"
  }

  private fun save(version: String, name: String) {
    val file = if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
    val progress = TaskProgress()
    AppDialog.progress(R.string.data_incompatible_saving, Icons.Outlined.Save, progress)
    thread(name = "backup") {
      val result = runCatching { saveToDownloads(file, "application/zip") { DataIO.zipDirectory(AppDirs.base, AppDirs.dataContent(), it, progress) } }
      main.post {
        // Отмена уже закрыла окно хода, недописанный файл удалён: снова выбор.
        if (progress.cancelled) return@post ask(version)
        AppDialog.close()
        result.onSuccess { saved ->
          Notify.info(R.string.data_incompatible_saved, saved)
          clear()
        }.onFailure { ask(version, it.message ?: it.javaClass.simpleName) }
      }
    }
  }

  // Очистка и загрузка идут одна за другой в потоке "io".
  private fun clear() {
    TrackStorage.io.execute { AppDirs.dataContent().forEach { it.deleteRecursively() } }
    DataStore.init()
  }
}
