// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Файл импорта: данные, прочитанные из него для выбора, и доступ к файлам, на которые они ссылаются.
    Файлы берутся прямо из источника, без промежуточной распаковки.
*/
abstract class ImportSource(val uri: Uri, val name: String, val data: LoadedData) {
  /** Размер файла источника по пути формата обмена (DataIO.picturePath, trackPath); null - узнать нельзя */
  open fun fileSize(path: String): Long? = null

  /** Файлы wanted из источника: onFile по мере чтения, write пишет файл в указанное место. Ход - в progress,
      его отмена прерывает чтение (CancellationException). Поток импорта.
  */
  abstract fun readFiles(context: Context, wanted: Set<String>, progress: TaskProgress, onFile: (path: String, write: (File) -> Unit) -> Unit)

  /** Поток всего файла источника, ход - по прочитанному объёму; объём файла неизвестен - ход без шкалы */
  protected fun openStream(context: Context, progress: TaskProgress): InputStream {
    val length = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
    progress.start(length.coerceAtLeast(0L))
    val stream = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    return ProgressInput(stream, progress).buffered(IMPORT_BUFFER)
  }
}

/** Архив обмена; catalog - его оглавление, если источник позволяет произвольный доступ. FOR LOCAL USE */
private class ArchiveSource(uri: Uri, name: String, data: LoadedData, private val catalog: Map<String, ZipCatalogEntry>?) :
  ImportSource(uri, name, data) {

  override fun fileSize(path: String) = catalog?.get(path)?.size

  // With the catalog every chosen entry is read from its own place, the rest of the archive is not touched.
  override fun readFiles(context: Context, wanted: Set<String>, progress: TaskProgress, onFile: (path: String, write: (File) -> Unit) -> Unit) {
    val entries = catalog?.let { wanted.mapNotNull(it::get) } ?: return readInOrder(context, wanted, progress, onFile)
    progress.start(entries.sumOf { it.size })
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
      val buffer = ByteArray(IMPORT_BUFFER)
      var done = 0L
      entries.sortedBy { it.offset }.forEach { entry ->
        if (progress.cancelled) throw CancellationException()
        onFile(entry.name) { target ->
          openZipEntry(input.channel, entry).use { data ->
            target.outputStream().use { out ->
              while (true) {
                if (progress.cancelled) throw CancellationException()
                val count = data.read(buffer)
                if (count < 0) break
                out.write(buffer, 0, count)
                done += count
                progress.advance(done)
              }
            }
          }
        }
      }
    }
  }

  // Without random access the archive is read from the start; ZipInputStream feeds the inflater by 512 bytes, it is slow.
  private fun readInOrder(context: Context, wanted: Set<String>, progress: TaskProgress, onFile: (path: String, write: (File) -> Unit) -> Unit) {
    val left = wanted.toMutableSet()
    ZipInputStream(openStream(context, progress)).use { zip ->
      while (left.isNotEmpty()) {
        val entry = zip.nextEntry ?: break
        if (entry.isDirectory || !left.remove(entry.name)) continue
        onFile(entry.name) { target -> target.outputStream().use { zip.copyTo(it, IMPORT_BUFFER) } }
      }
    }
  }
}

/** GPX: файлы - это треки, второй проход пишет их точки. FOR LOCAL USE */
private class GpxSource(uri: Uri, name: String, data: LoadedData) : ImportSource(uri, name, data) {

  override fun readFiles(context: Context, wanted: Set<String>, progress: TaskProgress, onFile: (path: String, write: (File) -> Unit) -> Unit) =
    openStream(context, progress).use { GpxImport.readTracks(it, wanted, onFile) }
}

// Files opened in xTravel or shared to it, and files chosen by the user: an exchange file or GPX is read for the data selection sheet,
// other files are only reported. The files are taken one after another: the next one waits for the sheet and the import.
/** Импорт файлов: handle - из intent, open - выбранные пользователем; файлы обрабатываются по очереди.
    Архив обмена: читаются только version.txt и data.xml, по ним строится дерево выбора; не архив пробуется как GPX.
    apply разносит выбранное по местам прямо из исходного файла.
*/
object DataImport {

  private val main = Handler(Looper.getMainLooper())
  private val queue = ArrayDeque<Uri>()
  private var busy = false

  fun handle(context: Context, intent: Intent?) {
    if (intent != null) open(context, urisOf(intent))
  }

  /** Главный поток, нужно право чтения uris */
  fun open(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val app = context.applicationContext
    queue.addAll(uris)
    next(app)
  }

  /** Применение выбранного из источника. Точки, записки и маршруты сливаются сразу; затем картинки и треки
      распаковываются из файла на место под окном хода с отменой. Каждый файл включается в данные, как только
      готов, поэтому отмена оставляет уже распакованное; картинки, которые не успели распаковаться, убираются из точек.
      Главный поток; onDone - после завершения или отмены.
  */
  fun apply(source: ImportSource, set: DataSet, onDone: () -> Unit) {
    val context = AppSession.context.applicationContext
    val pictures = PointStore.applyImport(set.points, set.notes)
    val routes = RouteStore.applyImport(set.routes)
    val tracks = set.tracks.associateBy { DataIO.trackPath(it.id) }
    val wanted = pictures.map { it.path }.toSet() + tracks.keys
    val result = ImportResult(set.points.size, set.notes?.elements?.size ?: 0, routes)
    if (wanted.isEmpty()) return finish(source, result, null, onDone)
    val byPath = pictures.groupBy { it.path }
    val progress = TaskProgress()
    AppDialog.progress(R.string.import_progress, Icons.Outlined.FileDownload, progress, dangerCancel = true)
    thread(name = "import") {
      val filled = mutableSetOf<File>()
      val failure = runCatching {
        source.readFiles(context, wanted, progress) { path, write ->
          byPath[path]?.let { fillPictures(it, write, filled) }
          tracks[path]?.let { if (fillTrack(source, it, write)) result.tracks++ }
        }
      }.exceptionOrNull()
      main.post {
        pictures.forEach { picture ->
          if (picture.target in filled) result.files++ else PointStore.dropPicture(picture.target.name)
          PointStore.releasePicture(picture.target)
        }
        if (!progress.cancelled) AppDialog.close()
        finish(source, result, failure, onDone)
      }
    }
  }

  private fun finish(source: ImportSource, result: ImportResult, failure: Throwable?, onDone: () -> Unit) {
    if (failure != null && failure !is CancellationException) {
      Log.w("xTravel", "Import failed: ${source.uri}", failure)
      Notify.error(R.string.import_failed, source.name)
    } else {
      Notify.info(R.string.data_import_done, result.points, result.notes, result.tracks, result.files, result.routes)
    }
    onDone()
  }

  // A picture taken by several items is read once and copied; a duplicate of a picture the point had is removed at once.
  private fun fillPictures(items: List<PointStore.ImportPicture>, write: (File) -> Unit, filled: MutableSet<File>) {
    val first = items.first()
    writeFile(first.target, write)
    items.drop(1).forEach { first.target.copyTo(it.target, overwrite = true) }
    items.forEach { item ->
      if (PointStore.isDuplicate(item)) item.target.delete() else filled += item.target
    }
  }

  // The track file goes under a new id; a summary the source did not give is built from the file.
  private fun fillTrack(source: ImportSource, header: TrackHeader, write: (File) -> Unit): Boolean {
    val id = TrackFiles.newId()
    val file = TrackFiles.dataFile(id)
    writeFile(file, write)
    val imported = if (header.id in source.data.rescan) TrackFiles.scan(id, header.name, header.visible)
      else header.copy(id = id, highlighted = false)
    if (imported == null) {
      file.delete()
      return false
    }
    main.post { TrackStorage.onImported(listOf(imported)) }
    return true
  }

  /** Недописанный файл удаляется */
  private fun writeFile(target: File, write: (File) -> Unit) {
    try {
      write(target)
    } catch (error: Throwable) {
      target.delete()
      throw error
    }
  }

  private fun next(context: Context) {
    if (busy) return
    val uri = queue.removeFirstOrNull() ?: return
    busy = true
    // Своим потоком, не "io": там очередь подгрузки треков, файл ждал бы её десятки секунд.
    val progress = TaskProgress()
    AppDialog.progress(R.string.import_reading, Icons.Outlined.FileDownload, progress)
    thread(name = "import") { prepare(context, uri, progress) }
  }

  private fun done(context: Context) {
    busy = false
    next(context)
  }

  /** Чтение файла для дерева выбора под окном "Получение данных"; отмена окна прерывает чтение. Поток импорта */
  private fun prepare(context: Context, uri: Uri, progress: TaskProgress) {
    val name = displayName(context, uri) ?: uri.lastPathSegment ?: uri.toString()
    var unsupported: String? = null
    val source = runCatching {
      readArchive(context, uri, name, progress) ?: run {
        if (progress.cancelled) throw CancellationException()
        GpxImport.read(context, uri)?.let { GpxSource(uri, name, it) }
      }
    }.onFailure {
      if (it !is CancellationException) Log.w("xTravel", "Import failed: $uri", it)
      if (it is UnsupportedDataFormat) unsupported = it.version
    }.getOrNull()
    main.post {
      if (progress.cancelled) return@post done(context)
      AppDialog.close()
      if (source == null) {
        val version = unsupported
        if (version != null) AppDialog.message(context.getString(R.string.format_unsupported, version))
        else Notify.error(R.string.import_not_implemented, name)
        done(context)
      } else {
        DataSelect.openImport(source) { done(context) }
      }
    }
  }

  /** Архив обмена по его version.txt и data.xml; null - не zip или в нём нет data.xml.
      Версия формата не та - UnsupportedDataFormat: импортеров архивов прежних версий пока нет.
  */
  private fun readArchive(context: Context, uri: Uri, name: String, progress: TaskProgress): ImportSource? {
    val files = readByCatalog(context, uri) ?: readInOrder(context, uri, progress) ?: return null
    if (progress.cancelled) throw CancellationException()
    val xml = files.xml ?: return null
    val version = files.version ?: xml.inputStream().use(DataIO::xmlVersion)
    if (version != DATA_VERSION.toString()) throw UnsupportedDataFormat(version)
    val data = xml.inputStream().use { DataIO.parse(it, null) }
    return ArchiveSource(uri, name, data, files.catalog)
  }

  /** version.txt и data.xml по оглавлению, прямо с их места; null - источник без произвольного доступа или не zip */
  private fun readByCatalog(context: Context, uri: Uri): ArchiveFiles? = runCatching {
    val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
      val channel = input.channel
      val catalog = readZipCatalog(channel) ?: return null
      val version = catalog[VERSION_FILE]?.let { readZipEntry(channel, it).decodeToString().trim() }
      ArchiveFiles(version, catalog[DATA_FILE]?.let { readZipEntry(channel, it) }, catalog)
    }
  }.getOrNull()

  // Without random access the archive is read from the start until data.xml: an archive of an old version keeps it at the end.
  private fun readInOrder(context: Context, uri: Uri, progress: TaskProgress): ArchiveFiles? {
    var version: String? = null
    var xml: ByteArray? = null
    val stream = context.contentResolver.openInputStream(uri) ?: return null
    runCatching {
      ZipInputStream(ProgressInput(stream, progress).buffered(IMPORT_BUFFER)).use { zip ->
        while (xml == null) {
          val entry = zip.nextEntry ?: break
          when (entry.name) {
            VERSION_FILE -> version = zip.readBytes().decodeToString().trim()
            DATA_FILE -> xml = zip.readBytes()
          }
        }
      }
    }
    return ArchiveFiles(version, xml, null)
  }

  private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
      if (cursor.moveToFirst()) cursor.getString(0) else null
    }
  }.getOrNull()

  private fun urisOf(intent: Intent): List<Uri> = when (intent.action) {
    Intent.ACTION_VIEW -> listOfNotNull(intent.data)
    Intent.ACTION_SEND -> listOfNotNull(stream(intent))
    Intent.ACTION_SEND_MULTIPLE -> streams(intent)
    else -> emptyList()
  }

  private fun stream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

  private fun streams(intent: Intent): List<Uri> =
    if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}

private const val IMPORT_BUFFER = 256 * 1024 /** Блок чтения и записи файлов импорта. FOR LOCAL USE */

/** Прочитанное из архива для дерева выбора; catalog - оглавление, если источник позволяет произвольный доступ. FOR LOCAL USE */
private class ArchiveFiles(val version: String?, val xml: ByteArray?, val catalog: Map<String, ZipCatalogEntry>?)

/** Итог импорта для сообщения. FOR LOCAL USE */
private class ImportResult(val points: Int, val notes: Int, val routes: Int) {
  var tracks = 0
  var files = 0
}

/** FOR LOCAL USE
    Поток источника импорта: прочитанный объём идёт в ход операции, её отмена прерывает чтение (CancellationException).
*/
private class ProgressInput(input: InputStream, private val progress: IExportProgress) : FilterInputStream(input) {
  private var done = 0L

  override fun read(): Int {
    val value = super.read()
    if (value >= 0) count(1)
    return value
  }

  override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
    val read = super.read(buffer, offset, length)
    if (read > 0) count(read.toLong())
    return read
  }

  override fun skip(length: Long): Long {
    val skipped = super.skip(length)
    if (skipped > 0) count(skipped)
    return skipped
  }

  private fun count(size: Long) {
    if (progress.cancelled) throw CancellationException()
    done += size
    progress.advance(done)
  }
}
