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

/** Сохранённые треки: сводки всех, геометрия только видимых. Список меняется в главном потоке, работа с файлами -
    в потоке "io". Трек везде опознаётся по id (имя файла точек), имя - только для показа. Сводки сохраняет DataStore.
*/
object TrackStorage {

  private val main = Handler(Looper.getMainLooper())
  /**
  * Serialized executor for file operations, wrapped in the background failure guard.
  * @return Serialized executor for file operations, wrapped in the background failure guard.
  */
  val io: Executor = Failures.guarded(Executors.newSingleThreadExecutor { Thread(it, "io") }, FailureSource.BACKGROUND)

  // Sorted: newer first; with the same start the longer one (a merged track) goes above.
  val tracks = mutableStateListOf<TrackHeader>()

  /** Геометрия видимых треков по id; фоновая загрузка заменяет карту целиком */
  @Volatile
  var loaded: Map<String, TrackLine> = emptyMap()
    private set

  var scrollTarget by mutableStateOf<String?>(null) /** id трека, к которому один раз прокручивается список */
  var currentHighlighted by mutableStateOf(false) /** Строка текущей записи подсвечена */
    private set
  var scrollToCurrent by mutableStateOf(false) /** Запрос прокрутки списка к текущей записи */

  fun focusCurrent() {
    highlight(emptyList())
    currentHighlighted = true
    scrollTarget = null
    scrollToCurrent = true
  }

  private val order = compareByDescending<TrackHeader> { it.start }.thenByDescending { it.end }

  /** Сверка data.xml с файлами при загрузке, поток "io". Трек без файла отбрасывается; файл, которого data.xml
      не знает, становится видимым треком со сводкой из файла. Активный трек продолжает запись, если текущая пуста,
      иначе закрывается по своему файлу и попадает в список.
  */
  fun collect(listed: List<TrackHeader>, active: TrackHeader?): List<TrackHeader> {
    val present = listed.filter { TrackFiles.dataFile(it.id).isFile }
    val known = (listed.map { it.id } + listOfNotNull(active?.id)).toSet()
    val unlisted = AppDirs.tracks.listFiles { file -> file.extension == TRACK_DATA_EXT }.orEmpty().mapNotNull { file ->
      val id = file.nameWithoutExtension
      if (id in known) null else TrackFiles.scan(id, null, visible = true)
    }
    val all = present + unlisted
    if (active == null || !TrackFiles.dataFile(active.id).isFile || TrackRecorder.adopt(active)) return all
    return all + listOfNotNull(TrackFiles.scan(active.id, active.name, active.visible))
  }

  fun onLoaded(headers: List<TrackHeader>) {
    tracks.clear()
    tracks.addAll(headers.sortedWith(order))
    headers.filter { it.visible }.forEach(::load)
  }

  fun find(id: String) = tracks.firstOrNull { it.id == id }

  /** Добавляет или заменяет законченный трек и загружает геометрию видимого */
  fun onSaved(header: TrackHeader) {
    insert(header)
    if (header.visible) load(header)
    DataStore.scheduleSave()
  }

  /** Тот же трек уже сохранён: совпадают время и место начала и конца и число точек */
  fun duplicateOf(header: TrackHeader): TrackHeader? = tracks.firstOrNull {
    it.start == header.start && it.end == header.end && it.points == header.points &&
      GeoMath.samePlace(it.startLat, it.startLon, header.startLat, header.startLon) &&
      GeoMath.samePlace(it.endLat, it.endLon, header.endLat, header.endLon)
  }

  /** Прокрутка списка к треку и подсветка только его; подсветка держится до следующей */
  fun focus(id: String) {
    scrollToCurrent = false
    scrollTarget = id
    highlight(listOf(id))
  }

  /** Подсветка строк треков из ids, у остальных снимается. Только в памяти */
  fun highlight(ids: Collection<String>) {
    currentHighlighted = false
    tracks.forEachIndexed { index, header ->
      val on = header.id in ids
      if (header.highlighted != on) tracks[index] = header.copy(highlighted = on)
    }
  }

  fun onImported(headers: List<TrackHeader>) {
    headers.forEach(::onSaved)
    headers.firstOrNull()?.let {
      scrollToCurrent = false
      scrollTarget = it.id
    }
  }

  fun setVisible(header: TrackHeader, visible: Boolean) {
    val updated = header.copy(visible = visible)
    replace(updated)
    DataStore.scheduleSave()
    if (visible) load(updated) else unload(header.id)
  }

  /** Убирает треки из списка и ставит удаление их файлов в поток "io" */
  fun delete(headers: List<TrackHeader>) {
    headers.forEach { header ->
      tracks.removeAll { it.id == header.id }
      unload(header.id)
    }
    DataStore.scheduleSave()
    io.execute { headers.forEach { TrackFiles.dataFile(it.id).delete() } }
  }

  /** Удаляет точки трека (TrackFiles.removeSamples) и пересчитывает сводку по файлу; трек без точек удаляется */
  fun removePoints(header: TrackHeader, rows: Map<Int, String>) {
    val id = header.id
    io.execute {
      val removed = TrackFiles.removeSamples(TrackFiles.dataFile(id), rows)
      val updated = if (removed) TrackFiles.scan(id, header.name, header.visible) else null
      main.post {
        val actual = find(id) ?: return@post
        when {
          !removed -> Notify.error(R.string.track_point_not_removed)
          updated == null -> delete(listOf(actual))
          else -> {
            replace(updated.copy(name = actual.name, visible = actual.visible, highlighted = actual.highlighted))
            DataStore.scheduleSave()
            if (actual.visible) load(updated)
          }
        }
      }
    }
  }

  fun validName(name: String) = name.isNotBlank()

  /** Имя трека не связано с файлом: меняется только сводка. Занятое имя другого трека или текущей записи - EXISTS */
  fun rename(header: TrackHeader, newName: String): RenameResult {
    if (!validName(newName)) return RenameResult.INVALID
    if (newName == header.name) return RenameResult.OK
    if (nameTaken(newName, header.id) || newName == TrackRecorder.name) return RenameResult.EXISTS
    replace(header.copy(name = newName))
    DataStore.scheduleSave()
    return RenameResult.OK
  }

  /** Имя занято сохранённым треком, кроме трека exceptId */
  fun nameTaken(name: String, exceptId: String? = null) = tracks.any { it.name == name && it.id != exceptId }

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
      if (deleteSources && merged != null) sources.forEach { TrackFiles.dataFile(it.id).delete() }
      main.post {
        if (merged == null) {
          Notify.error(R.string.merge_failed)
          return@post
        }
        if (deleteSources) sources.forEach { source -> tracks.removeAll { it.id == source.id }; unload(source.id) }
        insert(merged)
        focus(merged.id)
        DataStore.scheduleSave()
      }
    }
  }

  private fun writeMerged(sources: List<TrackHeader>): TrackHeader {
    val name = "${TrackTime.name(sources.first().start)} - ${TrackTime.name(sources.last().end)}"
    val id = TrackFiles.newId()
    val fields = TrackFields.ALL
    val builder = HeaderBuilder()
    TrackFiles.dataFile(id).bufferedWriter().use { out ->
      TrackFiles.writeFieldLine(out, fields)
      sources.forEach { source ->
        var sourceFields: List<String> = emptyList()
        TrackFiles.forEachSample(TrackFiles.dataFile(source.id), { sourceFields = it }) { time, lat, lon, parts ->
          val row = fields.map { field -> sourceFields.indexOf(field).let { if (it >= 0) parts.getOrElse(it) { "" } else "" } }
          out.write(row.joinToString(";"))
          out.write("\n")
          builder.add(time, lat, lon)
        }
      }
    }
    return builder.build(id, name, visible = false)
  }

  private fun insert(header: TrackHeader) {
    tracks.removeAll { it.id == header.id }
    val index = tracks.indexOfFirst { order.compare(header, it) < 0 }
    if (index < 0) tracks.add(header) else tracks.add(index, header)
  }

  private fun replace(header: TrackHeader) {
    val index = tracks.indexOfFirst { it.id == header.id }
    if (index >= 0) tracks[index] = header
  }

  /** Проекция геометрии треков: её задаёт карта при рисовании (useProjection). Главный поток */
  private var projection: IMapProjection = MercatorProjection

  /** Карта рисует в другой проекции: загруженная геометрия пересчитывается в потоке "io", новая читается сразу в ней */
  fun useProjection(target: IMapProjection) {
    if (target === projection) return
    projection = target
    val lines = loaded
    io.execute {
      val converted = lines.mapValues { it.value.reprojected(target) }
      main.post {
        if (projection !== target) return@post
        loaded = loaded.mapValues { (id, line) -> if (line.projection === target) line else converted[id] ?: line }
        ModuleHost.requestRedraw()
      }
    }
  }

  private fun load(header: TrackHeader) {
    val id = header.id
    val target = projection
    io.execute {
      val line = TrackFiles.readLine(id, target)
      main.post {
        if (find(id)?.visible != true) return@post
        // Пока файл читался, карта сменила проекцию: читается заново.
        if (line.projection !== projection) return@post load(header)
        loaded = loaded + (id to line)
        ModuleHost.requestRedraw()
      }
    }
  }

  private fun unload(id: String) {
    if (id !in loaded) return
    loaded = loaded - id
    ModuleHost.requestRedraw()
  }
}

/** Владелец секции tracks: сводки сохранённых треков и активной записи (атрибут active), файлы точек - tracks/<id>.xtrack */
object TracksData : IDataOwner {

  /** Ищет по именам сохранённых треков. */
  override fun search(text: String): List<DataFound> {
    val current = TrackRecorder.name?.takeIf { it.contains(text, ignoreCase = true) }?.let { name ->
      DataFound(name, R.drawable.ic_track, R.string.tracks) {
        if (TrackRecorder.name == name) {
          TrackStorage.focusCurrent()
          AppCommands.showTracks.execute()
        }
      }
    }
    return listOfNotNull(current) + TrackStorage.tracks.mapNotNull { header ->
      if (!header.name.contains(text, ignoreCase = true)) return@mapNotNull null
      DataFound(header.name, R.drawable.ic_track, R.string.tracks) {
        if (TrackStorage.find(header.id) != null) {
          TrackStorage.focus(header.id)
          AppCommands.showTracks.execute()
        }
      }
    }
  }

  override val tag = "tracks"

  override fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    val target = DataIO.tracksDir(dir)
    xml.startTag(null, tag)
    set.active?.let { xml.attribute(null, "active", it.id) }
    (set.tracks + listOfNotNull(set.active)).forEach { header ->
      xml.startTag(null, "track")
      xml.attribute(null, "file", header.id)
      xml.attribute(null, "name", header.name)
      xml.attribute(null, "visible", header.visible.toString())
      xml.attribute(null, "start", TrackTime.format(header.start))
      xml.attribute(null, "start-lat", header.startLat.toString())
      xml.attribute(null, "start-lon", header.startLon.toString())
      xml.attribute(null, "end", TrackTime.format(header.end))
      xml.attribute(null, "end-lat", header.endLat.toString())
      xml.attribute(null, "end-lon", header.endLon.toString())
      xml.attribute(null, "points", header.points.toString())
      xml.endTag(null, "track")
      DataIO.place(TrackFiles.dataFile(header.id), TrackFiles.dataFile(header.id, target))
    }
    xml.endTag(null, tag)
  }

  // Трек без файла пропускается; неполная сводка строится по файлу. Из источника импорта (dir = null) файла ещё нет:
  // трек с неполной сводкой отмечается в rescan, сводку построит импорт после распаковки файла.
  override fun read(parser: XmlPullParser, dir: File?, result: LoadedData) {
    val source = dir?.let(DataIO::tracksDir)
    val active = parser.attr("active")
    parser.forEachChild { name ->
      if (name != "track") return@forEachChild
      val id = parser.attr("file") ?: return@forEachChild
      if (source != null && !TrackFiles.dataFile(id, source).isFile) return@forEachChild
      val visible = parser.attr("visible")?.toBoolean() ?: true
      val header = readHeader(parser, id) ?: when (source) {
        null -> TrackHeader(id, parser.attr("name") ?: id, 0L, 0L, 0.0, 0.0, 0.0, 0.0, 0, visible).also { result.rescan += id }
        else -> TrackFiles.scan(id, parser.attr("name"), visible, source)
      }
      if (header == null) return@forEachChild
      if (id == active) result.active = header else result.tracks += header
    }
  }

  private fun readHeader(parser: XmlPullParser, id: String): TrackHeader? = runCatching {
    TrackHeader(
      id = id,
      name = parser.attr("name")!!,
      start = TrackTime.parse(parser.attr("start")!!)!!,
      end = TrackTime.parse(parser.attr("end")!!)!!,
      startLat = parser.attr("start-lat")!!.toDouble(),
      startLon = parser.attr("start-lon")!!.toDouble(),
      endLat = parser.attr("end-lat")!!.toDouble(),
      endLon = parser.attr("end-lon")!!.toDouble(),
      points = parser.attr("points")!!.toInt(),
      visible = parser.attr("visible")?.toBoolean() ?: true
    )
  }.getOrNull()
}
