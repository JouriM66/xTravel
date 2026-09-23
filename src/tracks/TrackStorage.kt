// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
* Distinguishes successful renaming, an occupied target name and an invalid or failed rename.
*/
enum class RenameResult { 
  /** Name accepted or rename completed according to the calling API. */
  OK, 
  /** The requested target name is already occupied. */
  EXISTS, 
  /** The name is invalid or the rename could not be applied. */
  INVALID }

// Saved tracks: headers of all of them, data only of the visible ones. The list is changed on the main thread,
// file work goes to the "io" thread. Names and visibility are saved by DataStore.
/**
* Owns saved-track headers on the main thread and loads their geometry on a serialized IO executor.
*
* Usage: Change observable headers on the main thread. Use io for file operations; loaded geometry is replaced as a snapshot for drawing.
*
* Public and subclass/module-facing members:
* - [io] - Serialized executor for file operations, wrapped in the background failure guard.
* - [tracks] - Observable saved-track headers sorted newest first; mutate through store operations on the main thread.
* - [loaded] - Published geometry snapshots indexed by filename stem; background loads replace the map as a whole.
* - [scrollTarget] - Saved-track name requested for one-time list scrolling, or null.
* - [currentHighlighted] - Whether the current recording's row is highlighted at runtime.
* - [scrollToCurrent] - Pending request to scroll the list to the active recording.
* - [focusCurrent] - Requests scrolling to the current recording and clears saved-track highlights.
* - [collect] - Reconciles listed tracks with data files and offers the newest unfinished track to the recorder.
* - [onLoaded] - Replaces stored headers, sorts them newest first and queues geometry loads for visible tracks.
* - [find] - Looks up a saved track by its filename stem.
* - [forget] - Removes an old header and cached geometry after its name is reused by a recording.
* - [onSaved] - Adds or replaces a finished track header and loads its geometry when visible.
* - [importFiles] - Copies imported track data under unused names and writes matching headers.
* - [duplicateOf] - Finds a completed track with matching times, count and rounded endpoint coordinates.
* - [focus] - Requests scrolling to one saved track and highlights only that track.
* - [highlight] - Updates runtime selection highlights for saved tracks.
* - [onImported] - Publishes imported tracks and focuses the first imported header.
* - [setVisible] - Updates a track's visibility and queues loading or unloads cached geometry.
* - [delete] - Removes selected saved tracks from memory and queues deletion of their paired files.
* - [validName] - Checks that a track filename stem is nonblank and contains no forbidden path characters.
* - [rename] - Renames a track's data and header files and updates cached metadata.
* - [merge] - Concatenates tracks oldest-first into a hidden track and optionally deletes originals.
* - [uniqueName] - Finds an unused filename stem by adding numbered suffixes.
* - [tracksInArea] - Finds completed tracks whose geographic bounding boxes intersect an area.
* - [nearestByStart] - Finds the saved track whose start is closest to a position.
*/
object TrackStorage {

  private val main = Handler(Looper.getMainLooper())
  /**
  * Serialized executor for file operations, wrapped in the background failure guard.
  * @return Serialized executor for file operations, wrapped in the background failure guard.
  */
  val io: Executor = Failures.guarded(Executors.newSingleThreadExecutor { Thread(it, "io") }, FailureSource.BACKGROUND)
  private val INVALID_NAME_CHARS = Regex("""[\\/:*?"<>|]""")

  // Sorted: newer first; with the same start the longer one (a merged track) goes above.
  /**
  * Observable saved-track headers sorted newest first; mutate through store operations on the main thread.
  * @return Observable saved-track headers sorted newest first; mutate through store operations on the main thread.
  */
  val tracks = mutableStateListOf<TrackHeader>()

  /**
  * Published geometry snapshots indexed by filename stem; background loads replace the map as a whole.
  * @return Published geometry snapshots indexed by filename stem; background loads replace the map as a whole.
  */
  @Volatile
  var loaded: Map<String, TrackLine> = emptyMap()
    private set

  /**
  * Saved-track name requested for one-time list scrolling, or null.
  * @return Saved-track name requested for one-time list scrolling, or null.
  */
  var scrollTarget by mutableStateOf<String?>(null)
  /**
  * Whether the current recording's row is highlighted at runtime.
  * @return Whether the current recording's row is highlighted at runtime.
  */
  var currentHighlighted by mutableStateOf(false)
    private set
  /**
  * Pending request to scroll the list to the active recording.
  * @return Pending request to scroll the list to the active recording.
  */
  var scrollToCurrent by mutableStateOf(false)

  /**
  * Requests scrolling to the current recording and clears saved-track highlights.
  * @return Unit; affects runtime UI state only.
  */
  fun focusCurrent() {
    highlight(emptyList())
    currentHighlighted = true
    scrollTarget = null
    scrollToCurrent = true
  }

  private val order = compareByDescending<TrackHeader> { it.start }.thenByDescending { it.end }

  // Tracks listed in data.xml plus data files it does not know, which become visible. The newest unfinished track goes on
  // as the current one, unless the current one has data already. Runs on the "io" thread.
  /**
  * Reconciles listed tracks with data files and offers the newest unfinished track to the recorder.
  *
  * Usage: Run on the IO executor; adoption may wait for the GPS worker.
  * @param listed Track headers referenced by the loaded application data.
  * @return Saved-track headers excluding a successfully resumed recording.
  */
  fun collect(listed: List<TrackHeader>): List<TrackHeader> {
    val names = listed.map { it.name }.toSet()
    val unlisted = AppDirs.tracks.listFiles { file -> file.extension == TRACK_DATA_EXT }.orEmpty().mapNotNull { file ->
      val name = file.nameWithoutExtension
      if (name in names) return@mapNotNull null
      TrackFiles.readHeader(name, visible = true) ?: TrackFiles.buildHeader(name, visible = true)?.also { TrackFiles.writeHeader(it) }
    }
    val all = listed + unlisted
    val candidate = all.filter { !it.complete }.maxByOrNull { it.start } ?: return all
    return if (TrackRecorder.adopt(candidate)) all.filter { it.name != candidate.name } else all
  }

  /**
  * Replaces stored headers, sorts them newest first and queues geometry loads for visible tracks.
  * @param headers Track metadata records selected for the operation.
  * @return Unit; call on the main thread.
  */
  fun onLoaded(headers: List<TrackHeader>) {
    tracks.clear()
    tracks.addAll(headers.sortedWith(order))
    headers.filter { it.visible }.forEach(::load)
  }

  /**
  * Looks up a saved track by its filename stem.
  * @param name Saved-track filename stem used as the lookup key.
  * @return The header, or null when absent.
  */
  fun find(name: String) = tracks.firstOrNull { it.name == name }

  // A new track took the name of an old file.
  /**
  * Removes an old header and cached geometry after its name is reused by a recording.
  * @param name Filename stem whose old metadata and loaded geometry must be forgotten.
  * @return Unit; schedules persistence when the list changed; does not delete the reused file.
  */
  fun forget(name: String) {
    if (tracks.removeAll { it.name == name }) DataStore.scheduleSave()
    unload(name)
  }

  /**
  * Adds or replaces a finished track header and loads its geometry when visible.
  * @param header Track metadata used by the requested operation.
  * @return Unit; schedules persistence.
  */
  fun onSaved(header: TrackHeader) {
    insert(header)
    if (header.visible) load(header)
    DataStore.scheduleSave()
  }

  // Copies imported tracks under free names with their headers. Runs on the "io" thread.
  /**
  * Copies imported track data under unused names and writes matching headers.
  *
  * Usage: Run on the IO executor; publish results separately through onImported.
  * @param headers Track metadata records selected for the operation.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return Successfully imported headers; individual copy failures are logged.
  */
  fun importFiles(headers: List<TrackHeader>, dir: File): List<TrackHeader> {
    val source = DataIO.tracksDir(dir)
    return headers.mapNotNull { header ->
      val name = uniqueName(header.name)
      runCatching {
        TrackFiles.dataFile(header.name, source).copyTo(TrackFiles.dataFile(name))
        val imported = if (header.complete) header.copy(name = name) else TrackFiles.buildHeader(name, header.visible) ?: header.copy(name = name)
        imported.also { TrackFiles.writeHeader(it) }
      }.onFailure { Log.w("xTravel", "Track not imported: ${header.name}", it) }.getOrNull()
    }
  }

  // Track already saved: the same start and end, the same number of points, the same first and last point. An unfinished track
  // has nothing to compare, the one being recorded is not in the list; both are always taken as new.
  /**
  * Finds a completed track with matching times, count and rounded endpoint coordinates.
  * @param header Track metadata used by the requested operation.
  * @return The matching saved header, or null; unfinished tracks never match.
  */
  fun duplicateOf(header: TrackHeader): TrackHeader? {
    if (!header.complete) return null
    return tracks.firstOrNull {
      it.complete && it.start == header.start && it.end == header.end && it.points == header.points &&
        GeoMath.samePlace(it.startLat, it.startLon, header.startLat, header.startLon) &&
        GeoMath.samePlace(it.endLat, it.endLon, header.endLat, header.endLon)
    }
  }

  // The list is asked to show this track: it scrolls to it and marks the row. The mark stays until another track is marked.
  /**
  * Requests scrolling to one saved track and highlights only that track.
  * @param name Saved-track filename stem to scroll to and highlight.
  * @return Unit; clears the current-recording highlight.
  */
  fun focus(name: String) {
    scrollToCurrent = false
    scrollTarget = name
    highlight(listOf(name))
  }

  // Marks the rows of the given tracks and clears the mark of all the others. The flag lives in memory only, nothing is saved.
  /**
  * Updates runtime selection highlights for saved tracks.
  * @param names Track filename stems to highlight.
  * @return Unit; does not persist highlight state.
  */
  fun highlight(names: Collection<String>) {
    currentHighlighted = false
    tracks.forEachIndexed { index, header ->
      val on = header.name in names
      if (header.highlighted != on) tracks[index] = header.copy(highlighted = on)
    }
  }

  /**
  * Publishes imported tracks and focuses the first imported header.
  * @param headers Track metadata records selected for the operation.
  * @return Unit; delegates saving and geometry loading to onSaved.
  */
  fun onImported(headers: List<TrackHeader>) {
    headers.forEach(::onSaved)
    headers.firstOrNull()?.let { focus(it.name) }
  }

  /**
  * Updates a track's visibility and queues loading or unloads cached geometry.
  * @param header Track metadata used by the requested operation.
  * @param visible Whether the point, route or track should be displayed.
  * @return Unit; schedules persistence.
  */
  fun setVisible(header: TrackHeader, visible: Boolean) {
    val updated = header.copy(visible = visible)
    replace(header.name, updated)
    DataStore.scheduleSave()
    if (visible) load(updated) else unload(header.name)
  }

  /**
  * Removes selected saved tracks from memory and queues deletion of their paired files.
  * @param headers Track metadata records selected for the operation.
  * @return Unit; file removal is asynchronous.
  */
  fun delete(headers: List<TrackHeader>) {
    headers.forEach { header ->
      tracks.removeAll { it.name == header.name }
      unload(header.name)
    }
    DataStore.scheduleSave()
    io.execute {
      headers.forEach {
        TrackFiles.dataFile(it.name).delete()
        TrackFiles.headerFile(it.name).delete()
      }
    }
  }

  /**
  * Checks that a track filename stem is nonblank and contains no forbidden path characters.
  * @param name Candidate filename stem checked for blank text and forbidden characters.
  * @return True when the name passes these checks.
  */
  fun validName(name: String) = name.isNotBlank() && !INVALID_NAME_CHARS.containsMatchIn(name)

  /**
  * Renames a track's data and header files and updates cached metadata.
  *
  * Usage: Call on the main thread; file operations here are synchronous.
  * @param header Track metadata used by the requested operation.
  * @param newName Validated filename stem without extension; existing data or active-recording names cause EXISTS.
  * @return OK, EXISTS or INVALID according to validation and the data-file rename outcome.
  */
  fun rename(header: TrackHeader, newName: String): RenameResult {
    if (!validName(newName)) return RenameResult.INVALID
    if (newName == header.name) return RenameResult.OK
    if (TrackFiles.dataFile(newName).exists() || newName == TrackRecorder.name) return RenameResult.EXISTS
    val renamedData = TrackFiles.dataFile(header.name).renameTo(TrackFiles.dataFile(newName))
    if (!renamedData) return RenameResult.INVALID
    TrackFiles.headerFile(header.name).renameTo(TrackFiles.headerFile(newName))
    val updated = header.copy(name = newName)
    replace(header.name, updated)
    loaded[header.name]?.let { line -> loaded = loaded - header.name + (newName to line) }
    DataStore.scheduleSave()
    return RenameResult.OK
  }

  // Joins the tracks from the oldest to the newest; the result is hidden and goes above its first source.
  /**
  * Concatenates tracks oldest-first into a hidden track and optionally deletes originals.
  *
  * Usage: Fewer than two tracks do nothing; merge errors are reported through Notify.
  * @param headers Track metadata records selected for the operation.
  * @param deleteSources Whether original tracks and files are removed after a successful merge.
  * @return Unit; IO is queued and UI updates return to the main thread.
  */
  fun merge(headers: List<TrackHeader>, deleteSources: Boolean) {
    val sources = headers.sortedBy { it.start }
    if (sources.size < 2) return
    io.execute {
      val merged = runCatching { writeMerged(sources) }.onFailure { Log.w("xTravel", "Merge failed", it) }.getOrNull()
      if (deleteSources && merged != null) {
        sources.forEach {
          TrackFiles.dataFile(it.name).delete()
          TrackFiles.headerFile(it.name).delete()
        }
      }
      main.post {
        if (merged == null) {
          Notify.error(R.string.merge_failed)
          return@post
        }
        if (deleteSources) sources.forEach { source -> tracks.removeAll { it.name == source.name }; unload(source.name) }
        insert(merged)
        focus(merged.name)
        DataStore.scheduleSave()
      }
    }
  }

  private fun writeMerged(sources: List<TrackHeader>): TrackHeader {
    val last = sources.last()
    val baseName = "${TrackTime.name(sources.first().start)} - ${TrackTime.name(if (last.complete) last.end else last.start)}"
    val name = uniqueName(baseName)
    val fields = TrackFields.ALL
    val builder = HeaderBuilder()
    TrackFiles.dataFile(name).bufferedWriter().use { out ->
      TrackFiles.writeFieldLine(out, fields)
      sources.forEach { source ->
        var sourceFields: List<String> = emptyList()
        TrackFiles.forEachSample(TrackFiles.dataFile(source.name), { sourceFields = it }) { time, lat, lon, parts ->
          val row = fields.map { field -> sourceFields.indexOf(field).let { if (it >= 0) parts.getOrElse(it) { "" } else "" } }
          out.write(row.joinToString(";"))
          out.write("\n")
          builder.add(time, lat, lon)
        }
      }
    }
    val header = builder.build(name, visible = false, fields = fields)
    TrackFiles.writeHeader(header)
    return header
  }

  // "Name", "Name (2)", "Name (3)"...
  /**
  * Finds an unused filename stem by adding numbered suffixes.
  *
  * Usage: This lookup does not reserve the returned name.
  * @param base Preferred filename stem before adding a unique numbered suffix.
  * @return A name unused by existing files, saved headers and the active recording.
  */
  fun uniqueName(base: String): String {
    var name = base
    var index = 2
    while (TrackFiles.dataFile(name).exists() || tracks.any { it.name == name } || name == TrackRecorder.name) name = "$base (${index++})"
    return name
  }

  /**
  * Finds completed tracks whose geographic bounding boxes intersect an area.
  * @param minLat Southern bound in geographic degrees.
  * @param minLon Western bound in geographic degrees.
  * @param maxLat Northern bound in geographic degrees.
  * @param maxLon Eastern bound in geographic degrees.
  * @return Matching headers; this tests bounding boxes rather than every sample.
  */
  fun tracksInArea(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): List<TrackHeader> =
    tracks.filter { it.complete && it.maxLat >= minLat && it.minLat <= maxLat && it.maxLon >= minLon && it.minLon <= maxLon }

  /**
  * Finds the saved track whose start is closest to a position.
  * @param point Geographic position in latitude/longitude degrees.
  * @return The nearest header, or null when the collection is empty.
  */
  fun nearestByStart(point: GeoPoint): TrackHeader? =
    tracks.minByOrNull { GeoMath.distance(point.lat, point.lon, it.startLat, it.startLon) }

  private fun insert(header: TrackHeader) {
    tracks.removeAll { it.name == header.name }
    val index = tracks.indexOfFirst { order.compare(header, it) < 0 }
    if (index < 0) tracks.add(header) else tracks.add(index, header)
  }

  private fun replace(name: String, header: TrackHeader) {
    val index = tracks.indexOfFirst { it.name == name }
    if (index >= 0) tracks[index] = header
  }

  // The header of an unfinished track is completed on the way: all its data is read anyway.
  private fun load(header: TrackHeader) {
    val name = header.name
    io.execute {
      val builder = if (header.complete) null else HeaderBuilder()
      val line = TrackFiles.readLine(name, builder)
      val rebuilt = builder?.takeIf { it.count > 0 }?.build(name, header.visible, header.fields)
      rebuilt?.let { runCatching { TrackFiles.writeHeader(it) } }
      main.post {
        val known = find(name) ?: return@post
        if (rebuilt != null) replace(name, rebuilt.copy(visible = known.visible))
        if (known.visible) {
          loaded = loaded + (name to line)
          ModuleHost.requestRedraw()
        }
      }
    }
  }

  private fun unload(name: String) {
    if (name !in loaded) return
    loaded = loaded - name
    ModuleHost.requestRedraw()
  }
}

/** Владелец сохранённых треков: в секции tracks только имя и видимость,
    всё остальное лежит в парных файлах трека. */
object TracksData : IDataOwner {

  /** Ищет по именам сохранённых треков. */
  override fun search(text: String): List<DataFound> {
    val current = TrackRecorder.name?.takeIf { it.contains(text, ignoreCase = true) }?.let { name ->
      DataFound(name, R.drawable.ic_track, R.string.tracks) {
        if (TrackRecorder.name == name) {
          TrackStorage.focusCurrent()
          AppCommands.showTracks.execute()
        } else if (TrackStorage.find(name) != null) {
          TrackStorage.focus(name)
          AppCommands.showTracks.execute()
        }
      }
    }
    return listOfNotNull(current) + TrackStorage.tracks.mapNotNull { header ->
      if (!header.name.contains(text, ignoreCase = true)) return@mapNotNull null
      DataFound(header.name, R.drawable.ic_track, R.string.tracks) {
        if (TrackStorage.find(header.name) != null) {
          TrackStorage.focus(header.name)
          AppCommands.showTracks.execute()
        }
      }
    }
  }

  override val tag = "tracks"

  /**
  * Writes saved-track names, visibility and paired files as an XML section and places associated files in the target directory.
  * @param set Snapshot of selected application data to serialize or export.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @param xml XML serializer already positioned inside the owning document or section.
  * @return Unit; run on the IO executor with the serializer inside the xTravel root.
  */
  override fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    val target = DataIO.tracksDir(dir)
    xml.startTag(null, tag)
    set.tracks.forEach { header ->
      xml.startTag(null, "track")
      xml.attribute(null, "name", header.name)
      xml.attribute(null, "visible", header.visible.toString())
      xml.endTag(null, "track")
      DataIO.place(TrackFiles.dataFile(header.name), TrackFiles.dataFile(header.name, target))
      if (!TrackFiles.headerFile(header.name, target).exists()) TrackFiles.writeHeader(header, target)
    }
    xml.endTag(null, tag)
  }

  // A track without its data file is dropped; a missing header is built from the data.
  /**
  * Reads saved-track names, visibility and paired files into the import accumulator.
  *
  * Usage: Start on the section opening tag; file references resolve relative to dir.
  * @param parser XML pull parser positioned at the opening tag to consume.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @param result Mutable import accumulator receiving parsed records.
  * @return Unit; consumes the current XML section.
  */
  override fun read(parser: XmlPullParser, dir: File, result: LoadedData) {
    val source = DataIO.tracksDir(dir)
    parser.forEachChild { name ->
      if (name != "track") return@forEachChild
      val track = parser.attr("name") ?: return@forEachChild
      val visible = parser.attr("visible")?.toBoolean() ?: true
      if (!TrackFiles.dataFile(track, source).isFile) return@forEachChild
      val header = TrackFiles.readHeader(track, visible, source)
        ?: TrackFiles.buildHeader(track, visible, source)?.also { runCatching { TrackFiles.writeHeader(it, source) } }
      header?.let { result.tracks += it }
    }
  }
}
