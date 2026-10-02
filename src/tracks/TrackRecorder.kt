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
// Samples stay in memory until the minimal count is reached and the data is loaded, then the data file tracks/<id>.xtrack is created,
// the track becomes active in data.xml and every sample is appended at once. The track goes on until it is finished (Rec)
// or deleted; the exit only closes the file, and the next start continues it. Only the last "tail length" points are kept for drawing.
/**
* Records accepted GPS positions on the GPS worker, buffering short tracks and publishing display snapshots.
*
* Usage: Recording work is serialized on the GPS handler. Published UI state is updated on the main thread; stop/adopt wait and must not block the GPS worker.
*
* Public and subclass/module-facing members:
* - [line] - Published projected tail geometry; readers must honor the published size and from indices.
* - [current] - Observable metadata snapshot of recorded samples, or null before the first sample.
* - [name] - Имя текущей записи для показа, null - пока его нет.
* - [hasFile] - Whether a recording data file has been created after reaching the sample threshold.
* - [visible] - Runtime visibility of the active recording's map layer.
* - [pointCount] - Number of samples in the current published recording header, or zero.
* - [start] - Queues activation of recording and subscription to accepted GPS samples.
* - [stop] - Queues closure of the current writer and waits up to three seconds.
* - [restart] - Queues completion of the current track and resets recording for a new track.
* - [discard] - Queues deletion of the current recording's data file and resets recording state.
* - [adopt] - Продолжает активный трек из data.xml, если текущая запись пуста.
* - [rename] - Проверяет и публикует новое имя текущей записи.
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

  // Main thread state: the header of the samples recorded so far (null without samples) and the name of the track.
  /**
  * Observable metadata snapshot of recorded samples, or null before the first sample.
  * @return Observable metadata snapshot of recorded samples, or null before the first sample.
  */
  var current by mutableStateOf<TrackHeader?>(null)
    private set
  var name by mutableStateOf<String?>(null) /** Имя текущей записи для показа, null - пока его нет */
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
  private var fileId: String? = null /** id файла точек; null - файла ещё нет */
  private var plannedName: String? = null
  private var header = HeaderBuilder()
  private var wx = DoubleArray(LINE_CAPACITY)
  private var wy = DoubleArray(LINE_CAPACITY)
  private var lineSize = 0
  @Volatile private var projection: IMapProjection = MercatorProjection /** Проекция хвоста; её задаёт карта (useProjection) */

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
    fileId?.let { TrackFiles.dataFile(it).delete() }
    reset()
    main.post { DataStore.scheduleSave() }
  }

  /** Активный трек из data.xml становится текущим, если текущая запись пуста и поля файла - TrackFields.ALL.
      Зовётся из потока "io", ждёт ответа до 30 с.
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

  /** Новое имя текущей записи; файл не переименовывается, имя попадает в data.xml. Главный поток */
  fun rename(newName: String): RenameResult {
    if (!TrackStorage.validName(newName)) return RenameResult.INVALID
    if (newName == name) return RenameResult.OK
    if (TrackStorage.nameTaken(newName)) return RenameResult.EXISTS
    name = newName
    GpsDataManager.post {
      plannedName = newName
      publish()
      main.post { DataStore.scheduleSave() }
    }
    return RenameResult.OK
  }

  /** Удаляет точки из файла текущей записи (TrackFiles.removeSamples); сводка и хвост для рисования строятся по файлу заново */
  fun removePoints(rows: Map<Int, String>) = GpsDataManager.post {
    val id = fileId
    closeWriter()
    val removed = id != null && TrackFiles.removeSamples(TrackFiles.dataFile(id), rows)
    if (!removed) {
      main.post { Notify.error(R.string.track_point_not_removed) }
      return@post
    }
    val builder = HeaderBuilder()
    resetLine()
    TrackFiles.forEachSample(TrackFiles.dataFile(id)) { time, lat, lon, _ ->
      builder.add(time, lat, lon)
      appendToLine(lat, lon)
    }
    header = builder
    publish()
    main.post { DataStore.scheduleSave() }
  }

  /** Карта рисует в другой проекции: хвост пересчитывается в неё, новые точки сразу идут в ней. Любой поток */
  fun useProjection(target: IMapProjection) {
    if (projection === target) return
    GpsDataManager.post {
      if (projection === target) return@post
      projection = target
      val converted = TrackLine(wx, wy, lineSize, line.from, line.projection).reprojected(target)
      wx = converted.wx.copyOf(max(LINE_CAPACITY, wx.size))
      wy = converted.wy.copyOf(max(LINE_CAPACITY, wy.size))
      line = TrackLine(wx, wy, lineSize, converted.from, target)
      ModuleHost.requestRedraw()
    }
  }

  private fun tryAdopt(saved: TrackHeader): Boolean {
    if (header.count > 0 || fileId != null) return false
    val builder = HeaderBuilder()
    var fields: List<String> = emptyList()
    TrackFiles.forEachSample(TrackFiles.dataFile(saved.id), { fields = it }) { time, lat, lon, _ ->
      builder.add(time, lat, lon)
      appendToLine(lat, lon)
    }
    if (fields != TrackFields.ALL) {
      resetLine()
      return false
    }
    header = builder
    fileId = saved.id
    plannedName = saved.name
    main.post { visible = saved.visible }
    publish()
    return true
  }

  private fun add(fix: GpsMeasure) {
    header.add(fix.time, fix.lat, fix.lon)
    appendToLine(fix.lat, fix.lon)
    val id = fileId
    if (id == null) {
      pending += fix
      // До загрузки данных каталог не трогается: формат каталога может оказаться несовместимым.
      if (header.count >= max(1, Settings.minPoints.value) && DataStore.isLoaded) openFile()
    } else {
      val out = writer ?: BufferedWriter(FileWriter(TrackFiles.dataFile(id), true)).also { writer = it }
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
    val world = projection.toWorld(lat, lon)
    wx[size] = world.x
    wy[size] = world.y
    size++
    lineSize = size
    line = TrackLine(wx, wy, size, if (tail > 0) max(0, size - tail) else 0, projection)
  }

  private fun openFile() {
    val id = TrackFiles.newId()
    plannedName = plannedName ?: TrackTime.name(header.startTime)
    val out = TrackFiles.dataFile(id).bufferedWriter()
    TrackFiles.writeFieldLine(out)
    pending.forEach { TrackFiles.writeFix(out, it) }
    pending.clear()
    out.flush()
    writer = out
    fileId = id
    main.post { DataStore.scheduleSave() }
  }

  private fun closeWriter() {
    writer?.let { runCatching { it.close() } }
    writer = null
  }

  private fun finishCurrent() {
    closeWriter()
    val id = fileId ?: return
    if (header.count == 0) {
      TrackFiles.dataFile(id).delete()
      return
    }
    val saved = header.build(id, plannedName ?: TrackTime.name(header.startTime), visible)
    main.post { TrackStorage.onSaved(saved) }
  }

  private fun reset() {
    writer = null
    fileId = null
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
    val shownName = plannedName ?: if (count > 0) TrackTime.name(header.startTime) else null
    val snapshot = if (count > 0 && shownName != null) header.build(fileId.orEmpty(), shownName, visible) else null
    val file = fileId != null
    main.post {
      current = snapshot
      name = shownName
      hasFile = file
    }
    ModuleHost.requestRedraw()
    GpsService.updatePoints(count)
  }
}
