// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.BufferedWriter
import java.io.FileWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

// Records the current track from the positions accepted by the GPS filter. Works on the "gps" thread.
// Samples stay in memory until the minimal count is reached, then the data file and the header of an unfinished track are created
// and every sample is appended at once. The track goes on until it is finished (Rec) or deleted; the exit only closes the file,
// and the next start continues it. Only the last "tail length" points are kept for drawing.
/**
* Records accepted GPS positions on the GPS worker, buffering short tracks and publishing display snapshots.
*
* Usage: Recording work is serialized on the GPS handler. Published UI state is updated on the main thread; stop/adopt wait and must not block the GPS worker.
*
* Public and subclass/module-facing members:
* - [line] - Published projected tail geometry; readers must honor the published size and from indices.
* - [current] - Observable metadata snapshot of recorded samples, or null before the first sample.
* - [name] - Current or planned recording filename stem, or null before a name can be assigned.
* - [hasFile] - Whether a recording data file has been created after reaching the sample threshold.
* - [visible] - Runtime visibility of the active recording's map layer.
* - [pointCount] - Number of samples in the current published recording header, or zero.
* - [start] - Queues activation of recording and subscription to accepted GPS samples.
* - [stop] - Queues closure of the current writer and waits up to three seconds.
* - [restart] - Queues completion of the current track and resets recording for a new track.
* - [discard] - Queues deletion of the current recording's paired files and resets recording state.
* - [adopt] - Attempts to resume an unfinished saved track if the current recording is still empty.
* - [rename] - Validates and publishes a new current-track name and queues the physical rename.
*/
object TrackRecorder {

  private const val LINE_CAPACITY = 1024

  private val main = Handler(Looper.getMainLooper())

  // Line of the current track for drawing: arrays are only appended, readers use the published size.
  /**
  * Published projected tail geometry; readers must honor the published size and from indices.
  * @return Published projected tail geometry; readers must honor the published size and from indices.
  */
  @Volatile
  var line: TrackLine = TrackLine.EMPTY
    private set

  // Main thread state: the header of the samples recorded so far (null without samples), the name the file has or will have.
  /**
  * Observable metadata snapshot of recorded samples, or null before the first sample.
  * @return Observable metadata snapshot of recorded samples, or null before the first sample.
  */
  var current by mutableStateOf<TrackHeader?>(null)
    private set
  /**
  * Current or planned recording filename stem, or null before a name can be assigned.
  * @return Current or planned recording filename stem, or null before a name can be assigned.
  */
  var name by mutableStateOf<String?>(null)
    private set
  /**
  * Whether a recording data file has been created after reaching the sample threshold.
  * @return Whether a recording data file has been created after reaching the sample threshold.
  */
  var hasFile by mutableStateOf(false)
    private set
  /**
  * Runtime visibility of the active recording's map layer.
  * @return Runtime visibility of the active recording's map layer.
  */
  var visible by mutableStateOf(true)

  /**
  * Number of samples in the current published recording header, or zero.
  * @return Number of samples in the current published recording header, or zero.
  */
  val pointCount: Int get() = current?.points ?: 0

  private var running = false
  private val pending = ArrayList<GpsMeasure>()
  private var writer: BufferedWriter? = null
  private var fileName: String? = null
  private var plannedName: String? = null
  private var header = HeaderBuilder()
  private var wx = DoubleArray(LINE_CAPACITY)
  private var wy = DoubleArray(LINE_CAPACITY)
  private var lineSize = 0

  private val onUpdate: (GpsUpdate) -> Unit = { update ->
    val fix = update.fix
    if (running && update.fixIsNew && fix != null) add(fix)
  }

  /**
  * Queues activation of recording and subscription to accepted GPS samples.
  * @return Unit; repeated starts are ignored.
  */
  fun start() = GpsDataManager.post {
    if (running) return@post
    running = true
    GpsDataManager.subscribe(onUpdate)
  }

  // Exit: waits until the file is closed; the track stays unfinished.
  /**
  * Queues closure of the current writer and waits up to three seconds.
  *
  * Usage: Do not call from the GPS worker, which must execute the queued shutdown.
  * @return Unit; the track remains unfinished for resumption.
  */
  fun stop() {
    val done = CountDownLatch(1)
    GpsDataManager.post {
      if (running) {
        running = false
        GpsDataManager.unsubscribe(onUpdate)
        closeWriter()
      }
      done.countDown()
    }
    done.await(3, TimeUnit.SECONDS)
  }

  // Rec button and routes: the current track is finished and a new one starts.
  /**
  * Queues completion of the current track and resets recording for a new track.
  * @return Unit; a file is not created for an empty new recording.
  */
  fun restart() = GpsDataManager.post {
    finishCurrent()
    reset()
  }

  // The data of the current track is deleted and the recording starts anew.
  /**
  * Queues deletion of the current recording's paired files and resets recording state.
  * @return Unit; deletion happens on the GPS worker.
  */
  fun discard() = GpsDataManager.post {
    closeWriter()
    fileName?.let {
      TrackFiles.dataFile(it).delete()
      TrackFiles.headerFile(it).delete()
    }
    reset()
  }

  // An unfinished track found at the start becomes the current one, unless the current one has data already.
  // Called on the "io" thread, waits for the answer.
  /**
  * Attempts to resume an unfinished saved track if the current recording is still empty.
  *
  * Usage: Call on the IO worker; waits up to thirty seconds for GPS work. The field list must match TrackFields.ALL.
  * @param saved Unfinished track header offered for resuming the current recording.
  * @return True when adoption completed successfully.
  */
  fun adopt(saved: TrackHeader): Boolean {
    var adopted = false
    val done = CountDownLatch(1)
    GpsDataManager.post {
      try {
        adopted = tryAdopt(saved)
      } finally {
        done.countDown()
      }
    }
    done.await(30, TimeUnit.SECONDS)
    return adopted
  }

  // Main thread. Before the file exists only the planned name changes; the file is renamed at once otherwise.
  /**
  * Validates and publishes a new current-track name and queues the physical rename.
  *
  * Usage: Call on the main thread.
  * @param newName Requested current-track filename stem without extension.
  * @return Validation result; OK means the rename was queued, not that every later IO operation succeeded.
  */
  fun rename(newName: String): RenameResult {
    if (!TrackStorage.validName(newName)) return RenameResult.INVALID
    if (newName == name) return RenameResult.OK
    if (TrackFiles.dataFile(newName).exists() || TrackStorage.find(newName) != null) return RenameResult.EXISTS
    name = newName
    GpsDataManager.post {
      plannedName = newName
      val old = fileName
      if (old != null && old != newName) {
        closeWriter()
        if (TrackFiles.dataFile(old).renameTo(TrackFiles.dataFile(newName))) {
          TrackFiles.headerFile(old).renameTo(TrackFiles.headerFile(newName))
          fileName = newName
        }
      }
      publish()
    }
    return RenameResult.OK
  }

  private fun tryAdopt(saved: TrackHeader): Boolean {
    if (header.count > 0 || fileName != null) return false
    val builder = HeaderBuilder()
    var fields: List<String> = emptyList()
    TrackFiles.forEachSample(TrackFiles.dataFile(saved.name), { fields = it }) { time, lat, lon, _ ->
      builder.add(time, lat, lon)
      appendToLine(lat, lon)
    }
    if (fields != TrackFields.ALL) {
      resetLine()
      return false
    }
    header = builder
    fileName = saved.name
    plannedName = saved.name
    main.post { visible = saved.visible }
    publish()
    return true
  }

  private fun add(fix: GpsMeasure) {
    header.add(fix.time, fix.lat, fix.lon)
    appendToLine(fix.lat, fix.lon)
    val name = fileName
    if (name == null) {
      pending += fix
      if (header.count >= max(1, Settings.minPoints.value)) openFile()
    } else {
      val out = writer ?: BufferedWriter(FileWriter(TrackFiles.dataFile(name), true)).also { writer = it }
      TrackFiles.writeFix(out, fix)
      out.flush()
    }
    publish()
  }

  // Only the last points of the tail stay: when the arrays are full, the tail is copied into new ones.
  private fun appendToLine(lat: Double, lon: Double) {
    val tail = Settings.tailPoints.value
    var size = lineSize
    if (size == wx.size) {
      val keep = if (tail > 0) min(size, tail) else size
      val capacity = max(LINE_CAPACITY, keep * 2)
      wx = wx.copyOfRange(size - keep, size).copyOf(capacity)
      wy = wy.copyOfRange(size - keep, size).copyOf(capacity)
      size = keep
    }
    val world = MercatorProjection.toWorld(lat, lon)
    wx[size] = world.x
    wy[size] = world.y
    size++
    lineSize = size
    line = TrackLine(wx, wy, size, if (tail > 0) max(0, size - tail) else 0)
  }

  private fun openFile() {
    val name = plannedName ?: TrackTime.name(header.startTime)
    main.post { TrackStorage.forget(name) }
    val out = TrackFiles.dataFile(name).bufferedWriter()
    TrackFiles.writeFieldLine(out)
    pending.forEach { TrackFiles.writeFix(out, it) }
    pending.clear()
    out.flush()
    TrackFiles.writeHeader(header.buildOpen(name, visible, TrackFields.ALL))
    writer = out
    fileName = name
  }

  private fun closeWriter() {
    writer?.let { runCatching { it.close() } }
    writer = null
  }

  private fun finishCurrent() {
    closeWriter()
    val name = fileName ?: return
    if (header.count == 0) {
      TrackFiles.dataFile(name).delete()
      TrackFiles.headerFile(name).delete()
      return
    }
    val saved = header.build(name, visible, TrackFields.ALL)
    runCatching { TrackFiles.writeHeader(saved) }
    main.post { TrackStorage.onSaved(saved) }
  }

  private fun reset() {
    writer = null
    fileName = null
    plannedName = null
    pending.clear()
    header = HeaderBuilder()
    resetLine()
    publish()
  }

  private fun resetLine() {
    wx = DoubleArray(LINE_CAPACITY)
    wy = DoubleArray(LINE_CAPACITY)
    lineSize = 0
    line = TrackLine.EMPTY
  }

  private fun publish() {
    val count = header.count
    val shownName = fileName ?: plannedName ?: if (count > 0) TrackTime.name(header.startTime) else null
    val snapshot = if (count > 0 && shownName != null) header.build(shownName, visible, TrackFields.ALL) else null
    val file = fileName != null
    main.post {
      current = snapshot
      name = shownName
      hasFile = file
    }
    ModuleHost.requestRedraw()
    GpsService.updatePoints(count)
  }
}
