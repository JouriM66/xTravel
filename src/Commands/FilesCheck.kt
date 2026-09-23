// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import java.io.File

/** Уборка данных: лишний файл - тот, на который не указывают загруженные данные.
    Пустые каталоги удаляются при обходе, лишние файлы - только после согласия пользователя. */
object FilesCheck : IAppCommand {

  // Files of the application itself: they belong to no element.
  private val OWN_FILES = setOf(DATA_FILE, "$DATA_FILE.tmp")

  // Directories of the data directory that belong to Android, not to the application.
  private val FOREIGN_DIRS = setOf("files", "cache")

  private val main = Handler(Looper.getMainLooper())

  /**
  * Scans application data for unused files and offers confirmed cleanup with counts.
  * @return Unit; scanning runs asynchronously and completion is shown in a dialog.
  */
  override fun execute() {
    val set = DataSelectors.all()
    val recording = TrackRecorder.current?.name
    TrackStorage.io.execute {
      val used = usedFiles(set, recording)
      val files = mutableListOf<File>()
      scan(AppDirs.base, files, top = true)
      val unused = files.filter { it.absolutePath !in used }
      main.post { report(files.size, unused) }
    }
  }

  // Everything the application knows a name for: its own files, the pictures of the elements and the files of the tracks.
  private fun usedFiles(set: DataSet, recording: String?): Set<String> {
    val files = mutableListOf<File>()
    OWN_FILES.forEach { files += File(AppDirs.base, it) }
    PointStore.usedPictures(set).forEach { files += PointStore.pictureFile(it) }
    (set.tracks.map { it.name } + listOfNotNull(recording)).forEach { name ->
      files += TrackFiles.dataFile(name)
      files += TrackFiles.headerFile(name)
    }
    return files.map { it.absolutePath }.toSet()
  }

  // Collects the files and removes the directories left empty. Runs on the "io" thread.
  private fun scan(dir: File, files: MutableList<File>, top: Boolean) {
    dir.listFiles().orEmpty().forEach { entry ->
      when {
        entry.isDirectory && top && entry.name in FOREIGN_DIRS -> {}
        entry.isDirectory -> {
          scan(entry, files, top = false)
          if (entry.listFiles().orEmpty().isEmpty()) entry.delete()
        }
        else -> files += entry
      }
    }
  }

  private fun report(total: Int, unused: List<File>) {
    if (unused.isEmpty()) {
      AppDialog.show {
        AlertDialog(
          onDismissRequest = AppDialog::close,
          icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
          text = { Text(stringResource(R.string.files_clean, total)) },
          confirmButton = { DialogButton(Icons.Outlined.Close, R.string.close, primary = true, onClick = AppDialog::close) }
        )
      }
      return
    }
    AppDialog.confirm(
      R.string.files_unused,
      total,
      unused.size,
      icon = Icons.Outlined.CleaningServices,
      confirmIcon = Icons.Outlined.Delete,
      confirmLabel = R.string.delete
    ) {
      TrackStorage.io.execute {
        unused.forEach { it.delete() }
        scan(AppDirs.base, mutableListOf(), top = true)
      }
    }
  }
}
