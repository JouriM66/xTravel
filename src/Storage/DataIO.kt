// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// Data directory: data.xml with a section per data type, next to it the files of the types (images/, tracks/).
// The application keeps its data in such a directory; the exchange file is the same directory packed into a zip.

const val DATA_VERSION = 2 /** Актуальная версия формата каталога данных; пишется в data.xml и задаёт устройство всего каталога */

/** Файл версии в корне каталога данных и архива: одна строка с номером DATA_VERSION. Его прочтёт загрузчик любой версии,
    даже если остальной каталог ему непонятен; та же версия пишется атрибутом в data.xml.
*/
const val VERSION_FILE = "version.txt"

/** Импортер каталога данных другой версии формата: переводит каталог на месте в актуальный формат.
    false - перевести не смог. Регистрируется в DataFormats (XTravelApp).
*/
interface IDataFormatImporter {
  val version: Int /** Версия формата, которую импортер понимает */

  fun upgrade(dir: File): Boolean
}

/** Версия 1: записки точкой внутри notes и старая запись автопосещения. Не поддерживается. */
object DataFormat1Importer : IDataFormatImporter {
  override val version = 1

  override fun upgrade(dir: File) = false
}

class UnsupportedDataFormat(val version: String) : Exception("Data format $version is not supported") /** Версия каталога не читается */

/** Реестр импортеров версий формата. */
object DataFormats {

  private val importers = mutableListOf<IDataFormatImporter>()

  fun register(importer: IDataFormatImporter) {
    if (importer !in importers) importers += importer
  }

  /** Приводит каталог к актуальной версии; без импортера или при его отказе - UnsupportedDataFormat. Поток "io" */
  fun upgrade(dir: File, version: String) {
    val importer = importers.firstOrNull { it.version.toString() == version }
    if (importer == null || !importer.upgrade(dir)) throw UnsupportedDataFormat(version)
  }
}

/**
* Filename of the application data document inside a data directory.
* @return Filename of the application data document inside a data directory.
*/
const val DATA_FILE = "data.xml"
/**
* Extension without a leading dot for native xTravel exchange archives.
* @return Extension without a leading dot for native xTravel exchange archives.
*/
const val EXCHANGE_EXT = "xtravel.zip"

// Data to write. Built on the main thread, written on the "io" thread.
/**
* Immutable selection of data references to write or export; assemble on the main thread before IO work.
* @param points Selected point records, or a sample count when the parameter is numeric. Default: emptyList().
* @param routes Route records of the selection, each a name and the places of its stops. Default: emptyList().
* @param tracks Selected track headers; sample files are resolved by their names. Default: emptyList().
* @param notes Notes included in the selection, or null to omit them.
* @param nextId Next shared object ID; zero omits it when serializing a partial export.
* @param globals Global state of the application; null leaves the section out, as every partial export does.
* @property points Selected point records, or a sample count when the parameter is numeric. Default: emptyList().
* @property routes Route records of the selection, each a name and the places of its stops. Default: emptyList().
* @property tracks Selected track headers; sample files are resolved by their names. Default: emptyList().
* @property notes Notes included in the selection, or null to omit them.
* @property nextId Next shared object ID; zero omits it when serializing a partial export.
* @property globals Global state of the application; null leaves the section out, as every partial export does.
*/
class DataSet(
  val points: List<MapPoint> = emptyList(),
  val routes: List<RouteRecord> = emptyList(),
  val tracks: List<TrackHeader> = emptyList(),
  val notes: MetaInfo? = null, // null - записок в наборе нет
  val nextId: Long = 0L,
  val globals: GlobalValues? = null
)

// What a read directory holds; file references are relative to dir.
/**
* Mutable import accumulator whose picture and track references are relative to its source directory.
*
* Public and subclass/module-facing members:
* - [points] - Mutable point records collected by registered readers.
* - [routes] - Route records collected by the registered reader.
* - [tracks] - Mutable track headers collected by registered readers.
* - [notes] - Imported notes, or null when the source has no notes section.
* - [nextId] - Next object ID read from the source; PointStore also checks imported IDs before assigning new ones.
* - [globals] - Global state read from the source, or null when it has no global section.
* @param dir Directory from which referenced files are read or into which files are written.
* @property dir Directory from which referenced files are read or into which files are written.
*/
class LoadedData(val dir: File) {
  /**
  * Mutable point records collected by registered readers.
  * @return Mutable point records collected by registered readers.
  */
  val points = mutableListOf<MapPoint>()
  /**
  * Mutable route records collected by registered readers.
  * @return Mutable route records collected by registered readers.
  */
  val routes = mutableListOf<RouteRecord>()
  /**
  * Mutable track headers collected by registered readers.
  * @return Mutable track headers collected by registered readers.
  */
  val tracks = mutableListOf<TrackHeader>()
  var notes: MetaInfo? = null /** Записки источника, null - секции нет */
  /**
  * Next object ID read from the source; PointStore also checks imported IDs before assigning new ones.
  * @return Next object ID read from the source; PointStore also checks imported IDs before assigning new ones.
  */
  var nextId = 1L
  /**
  * Global state read from the source, or null when it has no global section.
  * @return Global state read from the source, or null when it has no global section.
  */
  var globals: GlobalValues? = null
}

// Selections with everything the chosen objects depend on. Main thread.
/**
* Builds main-thread snapshots with the dependencies required by the selected objects.
*
* Public and subclass/module-facing members:
* - [all] - Snapshots all stored points, routes, saved tracks and notes with the next object ID.
* - [points] - Wraps selected points for persistence or export.
* - [routes] - Collects selected routes as records together with the points their stops refer to.
* - [tracks] - Wraps selected track headers for persistence or export.
*/
object DataSelectors {

  /**
  * Snapshots all stored points, routes, saved tracks and notes with the next object ID.
  *
  * Usage: Call on the main thread; the active recording is not included as a saved track.
  * @return The full data selection.
  */
  fun all() = DataSet(
    PointStore.points.toList(),
    RouteStore.routes.map(RouteStore::snapshot),
    TrackStorage.tracks.toList(),
    NotesStore.notes,
    PointStore.nextId,
    GlobalValues(TrackRecorder.visible)
  )

  /**
  * Wraps selected points for persistence or export.
  * @param points Selected point records, or a sample count when the parameter is numeric.
  * @return A DataSet containing those points.
  */
  fun points(points: List<MapPoint>) = DataSet(points = points)

  /**
  * Collects selected routes as records together with the points their stops refer to.
  * @param routes Selected routes of the application.
  * @return A DataSet containing the route records and their available point dependencies.
  */
  fun routes(routes: List<Route>): DataSet {
    val records = routes.map(RouteStore::snapshot)
    val ids = records.flatMap { record -> record.places.map { it.id } }.toSet()
    return DataSet(points = PointStore.points.filter { it.id in ids }, routes = records)
  }

  /**
  * Wraps selected track headers for persistence or export.
  * @param tracks Selected track headers; sample files are resolved by their names.
  * @return A DataSet containing those tracks.
  */
  fun tracks(tracks: List<TrackHeader>) = DataSet(tracks = tracks)
}

/**
* Reads and writes xTravel data directories and packages selected data into exchange archives.
*
* Usage: File IO is synchronous unless an outer caller dispatches it. The shared export directory requires serialized export operations.
*
* Public and subclass/module-facing members:
* - [imagesDir] - Resolves the images subdirectory of a data directory without creating it.
* - [tracksDir] - Resolves the tracks subdirectory of a data directory without creating it.
* - [write] - Writes registered data sections to a temporary UTF-8 XML file and replaces data.xml after serialization.
* - [read] - Loads known XML sections from a data directory, skipping unknown sections.
* - [place] - Copies an existing source file to a target, creating parent directories and overwriting the target.
* - [createExportFile] - Writes a selection into a temporary directory and packs it as a native exchange archive.
*/
object DataIO {

  /**
  * Resolves the images subdirectory of a data directory without creating it.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return The images directory path.
  */
  fun imagesDir(dir: File) = File(dir, "images")
  /**
  * Resolves the tracks subdirectory of a data directory without creating it.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return The tracks directory path.
  */
  fun tracksDir(dir: File) = File(dir, "tracks")

  // data.xml is replaced through a temporary file.
  /**
  * Writes registered data sections to a temporary UTF-8 XML file and replaces data.xml after serialization.
  *
  * Usage: Run on the IO executor. IO failures propagate to the caller.
  * @param set Snapshot of selected application data to serialize or export.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return Unit; writes associated files through registered providers.
  */
  fun write(set: DataSet, dir: File) {
    dir.mkdirs()
    val target = File(dir, DATA_FILE)
    val temp = File(dir, "$DATA_FILE.tmp")
    temp.outputStream().use { out ->
      val xml = Xml.newSerializer()
      xml.setOutput(out, "UTF-8")
      xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
      xml.startDocument("UTF-8", true)
      xml.startTag(null, "xtravel")
      xml.attribute(null, "version", DATA_VERSION.toString())
      DataOwnerManager.write(set, dir, xml)
      xml.endTag(null, "xtravel")
      xml.endDocument()
    }
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    File(dir, VERSION_FILE).writeText(DATA_VERSION.toString())
  }

  // Unknown sections are skipped.
  /**
  * Loads known XML sections from a data directory, skipping unknown sections.
  *
  * Usage: Run on an IO thread; malformed XML and IO errors propagate. Каталог другой версии сначала переводится
  * импортером этой версии; без него или при его отказе - UnsupportedDataFormat, каталог остаётся как был.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return Loaded data, or null if data.xml is absent.
  */
  fun read(dir: File): LoadedData? {
    val file = File(dir, DATA_FILE)
    if (!file.isFile) return null
    val version = version(dir)
    if (version != DATA_VERSION.toString()) {
      DataFormats.upgrade(dir, version)
      if (version(dir) != DATA_VERSION.toString()) throw UnsupportedDataFormat(version)
    }
    val result = LoadedData(dir)
    file.inputStream().use { input ->
      val parser = Xml.newPullParser()
      parser.setInput(input, "UTF-8")
      while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType != XmlPullParser.START_TAG || parser.depth != 1) continue
        parser.forEachChild { tag ->
          DataOwnerManager.readSection(tag, parser, dir, result)
        }
      }
    }
    return result
  }

  /** Версия каталога: из version.txt, без него - из корневого тега data.xml; пустая строка - версии нет */
  private fun version(dir: File): String {
    val text = File(dir, VERSION_FILE)
    if (text.isFile) return text.readText().trim()
    return xmlVersion(File(dir, DATA_FILE))
  }

  private fun xmlVersion(file: File): String = file.inputStream().use { input ->
    val parser = Xml.newPullParser()
    parser.setInput(input, "UTF-8")
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
      if (parser.eventType == XmlPullParser.START_TAG) return@use parser.attr("version").orEmpty()
    }
    ""
  }

  /**
  * Copies an existing source file to a target, creating parent directories and overwriting the target.
  * @param source Existing file to copy; an absent source is ignored.
  * @param target Destination file to overwrite, unless its canonical path equals source.
  * @return Unit; missing sources and equal canonical paths are ignored.
  */
  fun place(source: File, target: File) {
    if (!source.isFile || source.canonicalPath == target.canonicalPath) return
    target.parentFile?.mkdirs()
    source.copyTo(target, overwrite = true)
  }

  // Packs the set into <name>.xtravel.zip in the share directory. Runs on the "io" thread.
  /**
  * Writes a selection into a temporary directory and packs it as a native exchange archive.
  *
  * Usage: Run on the serialized IO executor because exports share a temporary directory; temporary data is removed in finally.
  * @param set Snapshot of selected application data to serialize or export.
  * @param name Archive filename stem under AppDirs.share; callers must provide a safe name.
  * @return The created archive in AppDirs.share.
  */
  fun createExportFile(set: DataSet, name: String): File {
    val dir = File(AppDirs.share, "export")
    try {
      dir.deleteRecursively()
      write(set, dir)
      val zip = File(AppDirs.share, "$name.$EXCHANGE_EXT")
      ZipOutputStream(zip.outputStream().buffered()).use { out ->
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
          out.putNextEntry(ZipEntry(file.relativeTo(dir).invariantSeparatorsPath))
          file.inputStream().use { it.copyTo(out) }
          out.closeEntry()
        }
      }
      return zip
    } finally {
      dir.deleteRecursively()
    }
  }
}

// Calls onStart for every start tag inside the current element until its end tag; onStart may read a child to its end.
/**
* Advances an XML parser through descendants until the current element ends.
*
* Usage: Start on the parent's opening tag. The callback should consume a child subtree when descendants must not be visited separately.
* @param onStart Receives each visited XML start-tag name; it may consume that element's subtree.
* @receiver Parser positioned at the current element.
* @return Unit; leaves the parser at the matching end tag or end of document.
*/
fun XmlPullParser.forEachChild(onStart: (String) -> Unit) {
  val level = depth
  while (true) {
    val event = next()
    if (event == XmlPullParser.END_DOCUMENT) return
    if (event == XmlPullParser.END_TAG && depth == level) return
    if (event == XmlPullParser.START_TAG) onStart(name)
  }
}

/**
* Reads an unnamespaced XML attribute from the current element.
* @param name Name or filename stem identifying the requested object.
* @receiver Parser positioned at the current element.
* @return The attribute value, or null when absent.
*/
fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

// The application data in AppDirs.base: loaded once at the start, written a second after the last change.
/**
* Loads application data and schedules serialized background persistence after main-thread changes.
*
* Public and subclass/module-facing members:
* - [init] - Loads stored data on the IO executor and publishes points, notes and tracks on the main thread.
* - [scheduleSave] - Debounces persistence by scheduling a save one second after the most recent change.
* - [saveNow] - Captures current data and enqueues a write on the IO executor.
* - [saveAfterPending] - Posts a save behind work already queued on the main thread.
*/
object DataStore {

  private const val SAVE_DELAY_MS = 1000L

  private val main = Handler(Looper.getMainLooper())
  private val saveTask = Runnable { saveNow() }
  private var loaded = false

  /**
  * Loads stored data on the IO executor and publishes points, notes and tracks on the main thread.
  *
  * Usage: Initialize directories and functional providers before calling.
  * @return Unit; completion is asynchronous.
  */
  fun init() {
    TrackStorage.io.execute {
      val data = runCatching { DataIO.read(AppDirs.base) }.onFailure { error ->
        Log.w("xTravel", "Data not loaded", error)
        // Каталог чужой версии загрузчик не понимает целиком, поэтому очищается весь.
        if (error is UnsupportedDataFormat) {
          AppDirs.base.listFiles()?.forEach { it.deleteRecursively() }
          AppDialog.message(AppSession.app.getString(R.string.format_unsupported, error.version))
        }
      }.getOrNull()
      val tracks = TrackStorage.collect(data?.tracks.orEmpty())
      main.post {
        PointStore.onLoaded(data)
        RouteStore.onLoaded(data?.routes)
        NotesStore.onLoaded(data?.notes)
        TrackStorage.onLoaded(tracks)
        GlobalData.onLoaded(data?.globals)
        loaded = true
      }
    }
  }

  /**
  * Debounces persistence by scheduling a save one second after the most recent change.
  * @return Unit; replaces the pending save callback.
  */
  fun scheduleSave() {
    main.removeCallbacks(saveTask)
    main.postDelayed(saveTask, SAVE_DELAY_MS)
  }

  /**
  * Captures current data and enqueues a write on the IO executor.
  *
  * Usage: Call on the main thread. Requests before initial loading completes are ignored.
  * @return Unit; does not wait for the file write.
  */
  fun saveNow() {
    main.removeCallbacks(saveTask)
    if (!loaded) return
    val set = DataSelectors.all()
    TrackStorage.io.execute {
      runCatching {
        DataIO.write(set, AppDirs.base)
      }.onFailure { Log.w("xTravel", "Data not saved", it) }
    }
  }

  // Saves after the tasks already posted to the main thread, for example the track finished by TrackRecorder.stop().
  /**
  * Posts a save behind work already queued on the main thread.
  * @return Unit; neither snapshot creation nor disk writing is synchronous.
  */
  fun saveAfterPending() {
    main.post { saveNow() }
  }
}

// Files opened in xTravel or shared to it, and files chosen by the user: an exchange file is unpacked and offered in the data
// selection sheet, other files are only reported. The files are taken one after another: the next one waits for the sheet.
/**
* Queues incoming content URIs, prepares exchange or GPX data and waits for each import sheet to finish.
*
* Public and subclass/module-facing members:
* - [handle] - Extracts import URIs from a VIEW, SEND or SEND_MULTIPLE intent and queues them.
* - [open] - Queues content URIs for sequential preparation and import selection.
*/
object DataImport {

  private val main = Handler(Looper.getMainLooper())
  private val queue = ArrayDeque<Uri>()
  private var busy = false

  /**
  * Extracts import URIs from a VIEW, SEND or SEND_MULTIPLE intent and queues them.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @param intent Android intent carrying startup, import or notification information.
  * @return Unit; null or unsupported intents supply no files.
  */
  fun handle(context: Context, intent: Intent?) {
    if (intent != null) open(context, urisOf(intent))
  }

  // Main thread.
  /**
  * Queues content URIs for sequential preparation and import selection.
  *
  * Usage: Call on the main thread with permission to read the URIs.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @param uris Content URIs with read access for sequential import.
  * @return Unit; files are processed asynchronously, one import sheet at a time.
  */
  fun open(context: Context, uris: List<Uri>) {
    if (uris.isEmpty()) return
    val app = context.applicationContext
    queue.addAll(uris)
    next(app)
  }

  private fun next(context: Context) {
    if (busy) return
    val uri = queue.removeFirstOrNull() ?: return
    busy = true
    TrackStorage.io.execute { prepare(context, uri) }
  }

  private fun done(context: Context) {
    busy = false
    next(context)
  }

  // Runs on the "io" thread.
  private fun prepare(context: Context, uri: Uri) {
    val name = displayName(context, uri) ?: uri.lastPathSegment ?: uri.toString()
    val dir = File(AppDirs.share, "import")
    var unsupported: String? = null
    val data = runCatching {
      dir.deleteRecursively()
      // An exchange file is a zip with data.xml; what is not one is tried as GPX.
      if (unpack(context, uri, dir)) DataIO.read(dir) else GpxImport.read(context, uri, dir)
    }.onFailure {
      Log.w("xTravel", "Import failed: $uri", it)
      if (it is UnsupportedDataFormat) unsupported = it.version
    }.getOrNull()
    main.post {
      if (data == null) {
        dir.deleteRecursively()
        val version = unsupported
        if (version != null) AppDialog.message(context.getString(R.string.format_unsupported, version))
        else Notify.error(R.string.import_not_implemented, name)
        done(context)
      } else {
        DataSelect.openImport(data) { done(context) }
      }
    }
  }

  // False when the file is not a zip with data.xml. Entries leading outside dir are skipped.
  private fun unpack(context: Context, uri: Uri, dir: File): Boolean = runCatching {
    val root = dir.also { it.mkdirs() }.canonicalFile
    val stream = context.contentResolver.openInputStream(uri) ?: return false
    ZipInputStream(stream.buffered()).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        val target = File(root, entry.name).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) continue
        if (entry.isDirectory) {
          target.mkdirs()
        } else {
          target.parentFile?.mkdirs()
          target.outputStream().use { zip.copyTo(it) }
        }
      }
    }
    File(root, DATA_FILE).isFile
  }.getOrDefault(false)

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
