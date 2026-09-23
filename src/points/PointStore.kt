// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File
import java.util.Collections
import java.util.UUID

// What another subject may learn about the points: a point changed, or points are gone. The points know nothing
// about the listeners; who needs the news registers itself in PointStore.listeners.
/**
* Receives the news about changed and removed points.
*
* Public and subclass/module-facing members:
* - [onPointChanged] - Reports that a point was replaced by a new snapshot.
* - [onPointsChanged] - Reports that the whole set of points was replaced or added to.
* - [onPointsRemoved] - Reports that points no longer exist.
*/
interface IPointListener {
  /**
  * Reports that a point was replaced by a new snapshot.
  * @param id Stable identifier of the changed point.
  * @return Unit; called in the main thread.
  */
  fun onPointChanged(id: Long) {}

  /**
  * Reports that the whole set of points was replaced or added to.
  * @return Unit; called in the main thread.
  */
  fun onPointsChanged() {}

  /**
  * Reports that points no longer exist.
  * @param ids Identifiers of the removed points.
  * @return Unit; called in the main thread after the points are gone.
  */
  fun onPointsRemoved(ids: Collection<Long>) {}
}

// Points; the list order is the manual order of the user. Changes on the main thread, saving is done by DataStore,
// after it pictures nobody refers to are deleted.
/**
* Owns the points and the pending picture references and applies point imports on the main thread.
*
* Usage: Change collections on the main thread through store operations. Removing records does not immediately delete their files; explicit cleanup owns orphan removal.
*
* Public and subclass/module-facing members:
* - [points] - Observable points in user-defined order; use PointStore operations to schedule persistence.
* - [scrollTarget] - Point ID requested for initial list scrolling, or null.
* - [nextId] - Next shared point/route identifier, advanced by creation and import.
* - [onLoaded] - Replaces the points with the loaded ones and advances the ID counter past stored identifiers.
* - [listeners] - Registry of the objects told about changed and removed points.
* - [findOrCreate] - Returns the point of a place, creating it from the record when the application has none.
* - [find] - Looks up a point by its ID.
* - [findAt] - Looks up the point standing at a place.
* - [add] - Creates an independent point with a fresh ID at the end of the list.
* - [update] - Replaces a point with the transform result.
* - [remove] - Removes points with the supplied IDs and schedules persistence.
* - [focus] - Requests scrolling to a point and highlights only that point.
* - [highlight] - Marks the supplied point IDs and clears all other highlights.
* - [move] - Moves a point between valid list indices and schedules persistence.
* - [newPictureFile] - Allocates a unique image path and protects its name from cleanup while unattached.
* - [releasePicture] - Releases the temporary cleanup protection for a picture filename.
* - [pictureFile] - Resolves an image filename within the application's images directory.
* - [usedPictures] - Collects attached and temporarily protected picture filenames.
* - [Import] - Carries prepared point, route and note data with copied pictures protected from cleanup.
* - [prepareImport] - Copies imported images and filters duplicate pictures for coordinate-matching points and notes.
* - [applyImport] - Merges matching points, allocates new IDs, merges notes and releases imported-picture protection.
* - [merged] - Combines missing metadata and additional attachments into an existing point snapshot.
*/
object PointStore {

  // Bytes compared, after the size, to find a picture the point already has.
  private const val PICTURE_PROBE = 200

  private val pendingPictures = Collections.synchronizedSet(mutableSetOf<String>())

  /**
  * Observable points in user-defined order; use PointStore operations to schedule persistence.
  * @return Observable points in user-defined order; use PointStore operations to schedule persistence.
  */
  val points = mutableStateListOf<MapPoint>()
  // Points tell what happened to them; who listens is not their business.
  /**
  * Registry of the objects told about changed and removed points.
  * @return Registry of the objects told about changed and removed points.
  */
  val listeners = ListenersManager<IPointListener>()
  /**
  * Point ID requested for initial list scrolling, or null.
  * @return Point ID requested for initial list scrolling, or null.
  */
  var scrollTarget by mutableStateOf<Long?>(null)

  /**
  * Next point identifier, advanced by creation and import.
  * @return Next point identifier, advanced by creation and import.
  */
  var nextId = 1L
    private set

  /**
  * Replaces the points with the loaded ones and advances the ID counter past stored identifiers.
  * @param data Loaded source data to publish or prepare for import.
  * @return Unit; null data leaves empty collections.
  */
  fun onLoaded(data: LoadedData?) {
    points.clear()
    if (data == null) return
    points.addAll(data.points)
    nextId = maxOf(data.nextId, (data.points.maxOfOrNull { it.id } ?: 0L) + 1)
    listeners.notifyEach { it.onPointsChanged() }
  }

  // A place given by another subject, for example a stop of a route read from a file: the point of that place is
  // the one already standing there, otherwise a new point made of what the record carries.
  /**
  * Returns the point of a place, creating it from the record when the application has none.
  * @param record Point record carrying at least the coordinates of the place.
  * @return The existing point at that place, or the newly created one.
  */
  fun findOrCreate(record: MapPoint): MapPoint {
    points.firstOrNull { samePlace(it, record) }?.let { return it }
    val created = record.copy(id = nextId++, visited = false, status = PointStatus.INDEPENDENT, highlighted = false)
    points.add(created)
    DataStore.scheduleSave()
    listeners.notifyEach { it.onPointsChanged() }
    return created
  }

  fun find(id: Long) = points.firstOrNull { it.id == id }

  /**
  * Looks up the point standing at a place.
  * @param place Geographic position in latitude/longitude degrees.
  * @return The point of that place, or null when none stands there.
  */
  fun findAt(place: GeoPoint) = points.firstOrNull { GeoMath.samePlace(it.lat, it.lon, place.lat, place.lon) }

  // New points go to the end of the list.
  /**
  * Creates an independent point with a fresh ID at the end of the list.
  * @param point Geographic position in latitude/longitude degrees.
  * @return The new point; persistence is scheduled.
  */
  fun add(point: GeoPoint): MapPoint {
    val created = MapPoint(id = nextId++, lat = point.lat, lon = point.lon)
    points.add(created)
    DataStore.scheduleSave()
    listeners.notifyEach { it.onPointsChanged() }
    return created
  }

  /**
  * Replaces a point with the transform result.
  *
  * Usage: Call on the main thread; keep the original identity when transforming.
  * @param id Stable point identifier.
  * @param transform Pure replacement function receiving the current immutable point snapshot.
  * @return Unit; an unknown point ID does nothing.
  */
  fun update(id: Long, transform: (MapPoint) -> MapPoint) {
    val index = points.indexOfFirst { it.id == id }
    if (index < 0) return
    points[index] = transform(points[index])
    DataStore.scheduleSave()
    listeners.notifyEach { it.onPointChanged(id) }
  }

  /**
  * Removes points with the supplied IDs and schedules persistence.
  * @param ids Identifiers of the points targeted by the operation.
  * @return Unit; attached files are left for explicit cleanup.
  */
  fun remove(ids: Collection<Long>) {
    points.removeAll { it.id in ids }
    DataStore.scheduleSave()
    listeners.notifyEach { it.onPointsRemoved(ids) }
  }

  // The list is asked to show this point: it scrolls to it and marks the row. The mark stays until another point is marked.
  /**
  * Requests scrolling to a point and highlights only that point.
  * @param id Stable point identifier.
  * @return Unit; affects runtime list state.
  */
  fun focus(id: Long) {
    scrollTarget = id
    highlight(listOf(id))
  }

  // Marks the rows of the given points and clears the mark of all the others. The flag lives in memory only, nothing is saved.
  /**
  * Marks the supplied point IDs and clears all other highlights.
  * @param ids Identifiers of the points targeted by the operation.
  * @return Unit; highlight flags are not persisted.
  */
  fun highlight(ids: Collection<Long>) {
    points.forEachIndexed { index, point ->
      val on = point.id in ids
      if (point.highlighted != on) points[index] = point.copy(highlighted = on)
    }
  }

  /**
  * Moves a point between valid list indices and schedules persistence.
  * @param from Existing point-list index to remove.
  * @param to Destination point-list index in the original valid index range.
  * @return Unit; equal or out-of-range indices do nothing.
  */
  fun move(from: Int, to: Int) {
    if (from == to || from !in points.indices || to !in points.indices) return
    points.add(to, points.removeAt(from))
    DataStore.scheduleSave()
  }

  // File for a new picture; protected from the cleanup until released.
  /**
  * Allocates a unique image path and protects its name from cleanup while unattached.
  *
  * Usage: Pair with releasePicture after attachment or cancellation; cancellation must delete the file separately.
  * @param extension Image suffix without a dot; the caller must write the returned path before attaching it.
  * @return A new file path; the image file itself is not yet written.
  */
  fun newPictureFile(extension: String): File {
    val name = "${UUID.randomUUID()}.$extension"
    pendingPictures += name
    return File(AppDirs.images, name)
  }

  /**
  * Releases the temporary cleanup protection for a picture filename.
  * @param file Previously allocated image path whose pending protection should be released.
  * @return Unit; does not delete the file.
  */
  fun releasePicture(file: File) {
    pendingPictures -= file.name
  }

  /**
  * Resolves an image filename within the application's images directory.
  * @param name Image filename relative to AppDirs.images.
  * @return The image File.
  */
  fun pictureFile(name: String) = File(AppDirs.images, name)

  // Names of the pictures the data points at, together with the ones just taken and not saved yet.
  /**
  * Collects attached and temporarily protected picture filenames.
  * @param set Snapshot of selected application data to serialize or export.
  * @return A set of names that cleanup must retain.
  */
  fun usedPictures(set: DataSet): Set<String> {
    val elements = set.points.flatMap { it.elements } + set.notes?.elements.orEmpty()
    val pending = synchronized(pendingPictures) { pendingPictures.toSet() }
    return elements.filterIsInstance<PointElement.Picture>().map { it.file }.toSet() + pending
  }

  // Points and notes of an import with their pictures already copied; pictures stay protected until applied.
  /**
  * Carries prepared point, route and note data with copied pictures protected from cleanup.
  * @param points Imported points whose image elements already reference copied application files.
  * @param notes Imported notes with copied image references, or null.
  * @param pictures Newly copied image files protected from cleanup until applyImport releases them.
  * @property points Imported points whose image elements already reference copied application files.
  * @property notes Imported notes with copied image references, or null.
  * @property pictures Newly copied image files protected from cleanup until applyImport releases them.
  */
  class Import(val points: List<MapPoint>, val notes: MetaInfo?, val pictures: List<File>)

  // Copies the pictures of the imported data; a picture the matching point already has is dropped. Runs on the "io" thread.
  /**
  * Copies imported images and filters duplicate pictures for coordinate-matching points and notes.
  *
  * Usage: Run on the IO executor; apply the plan on the main thread, releasing its files when done.
  * @param data Parsed import data whose dir owns the source images and whose point IDs are still source IDs.
  * @return A prepared import plan with protected copied files.
  */
  fun prepareImport(data: LoadedData): Import {
    val existing = points.toList()
    val images = DataIO.imagesDir(data.dir)
    val copied = mutableListOf<File>()

    fun copy(element: PointElement.Picture): PointElement.Picture? {
      val source = File(images, element.file)
      if (!source.isFile) return null
      val target = newPictureFile(source.extension.ifEmpty { "jpg" })
      copied += target
      source.copyTo(target, overwrite = true)
      return PointElement.Picture(target.name)
    }

    fun copyAll(elements: List<PointElement>, own: List<File>): List<PointElement> = elements.mapNotNull { element ->
      when {
        element !is PointElement.Picture -> element
        own.any { samePicture(File(images, element.file), it) } -> null
        else -> copy(element)
      }
    }

    val imported = data.points.map { point ->
      val own = existing.firstOrNull { samePlace(it, point) }?.elements.orEmpty()
        .filterIsInstance<PointElement.Picture>().map { pictureFile(it.file) }
      point.copy(info = MetaInfo(copyAll(point.elements, own)))
    }
    val ownNotes = NotesStore.notes.pictures.map(::pictureFile)
    val notes = data.notes?.let { MetaInfo(copyAll(it.elements, ownNotes)) }
    return Import(imported, notes, copied)
  }

  // A point at the place of an existing one is merged into it. Returns the number of imported points.
  /**
  * Merges matching points, allocates new IDs, merges notes and releases imported-picture protection.
  *
  * Usage: Call on the main thread after prepareImport.
  * @param plan Prepared import with remapped picture filenames and cleanup protection.
  * @return The number of incoming point records processed.
  */
  fun applyImport(plan: Import): Int {
    val added = mutableListOf<MapPoint>()
    // Points the import was merged into: they are marked in the list, so the duplicates are seen at once.
    val duplicates = mutableListOf<Long>()
    plan.points.forEach { point ->
      val index = points.indexOfFirst { samePlace(it, point) }
      val addedIndex = added.indexOfFirst { samePlace(it, point) }
      when {
        index >= 0 -> {
          points[index] = merged(points[index], point)
          duplicates += points[index].id
        }
        addedIndex >= 0 -> added[addedIndex] = merged(added[addedIndex], point)
        else -> {
          // A point new to the application starts plain: what it was elsewhere says nothing about this travel.
          added += point.copy(id = nextId++, visited = false, status = PointStatus.INDEPENDENT)
        }
      }
    }
    points.addAll(added)
    plan.notes?.let(NotesStore::applyImport)
    plan.pictures.forEach(::releasePicture)
    val marked = duplicates.ifEmpty { added.map { it.id } }
    highlight(marked)
    scrollTarget = marked.firstOrNull()
    DataStore.scheduleSave()
    listeners.notifyEach { it.onPointsChanged() }
    return plan.points.size
  }

  // Texts the target does not have and all new pictures go to the end; the other values only fill what is missing,
  // the schedule line by line. The status of the point stays as it is, a visit is taken over.
  /**
  * Combines missing metadata and additional attachments into an existing point snapshot.
  *
  * Usage: Text is deduplicated by exact content; picture duplication must be handled while preparing import.
  * @param target Existing snapshot whose identity and status must be retained.
  * @param source Incoming metadata and attachments to merge into the target snapshot.
  * @return A merged point preserving the target identity and status.
  */
  internal fun merged(target: MapPoint, source: MapPoint): MapPoint {
    return target.copy(
      info = target.info.merged(source.info),
      alt = target.alt ?: source.alt,
      icon = target.icon.ifEmpty { source.icon },
      schedule = target.schedule + source.schedule.filter { it !in target.schedule },
      visited = target.visited || source.visited,
      autoVisit = target.autoVisit || source.autoVisit,
      visitRadius = if (target.autoVisit) target.visitRadius else source.visitRadius,
      scheduleDisplay = if (target.scheduleDisplay == ScheduleDisplay.NONE) source.scheduleDisplay else target.scheduleDisplay,
      scheduleControl = target.scheduleControl || source.scheduleControl,
      warnBefore = if (target.scheduleControl) target.warnBefore else source.warnBefore
    )
  }

  private fun samePlace(a: MapPoint, b: MapPoint) = GeoMath.samePlace(a.lat, a.lon, b.lat, b.lon)

  private fun samePicture(a: File, b: File): Boolean {
    if (!a.isFile || !b.isFile || a.length() != b.length()) return false
    return runCatching { probe(a).contentEquals(probe(b)) }.getOrDefault(false)
  }

  private fun probe(file: File): ByteArray = file.inputStream().use { input ->
    val buffer = ByteArray(PICTURE_PROBE)
    var read = 0
    while (read < buffer.size) {
      val count = input.read(buffer, read, buffer.size - read)
      if (count < 0) break
      read += count
    }
    buffer.copyOf(read)
  }
}

/** Владелец точек: секция points в data.xml, картинки - файлами в images.
    Здесь же writePoint и readPoint - запись и чтение одной точки. */
object PointsData : IDataOwner {

  override val tag = "points"

  /** Ищет по текстам точек и по именам их картинок. */
  override fun search(text: String): List<DataFound> = PointStore.points.flatMap { point ->
    val nameIndex = point.elements.indexOfFirst { it is PointElement.Text }
    point.elements.mapIndexedNotNull { index, element ->
      if (element !is PointElement.Text || !element.text.contains(text, ignoreCase = true)) return@mapIndexedNotNull null
      DataFound(element.text, R.drawable.ic_point, R.string.points) {
        val current = PointStore.find(point.id)
        if (current != null) {
          if (index == nameIndex) {
            PointStore.focus(point.id)
            AppCommands.showPoints.execute()
          } else {
            val target = current.elements.indexOfFirst { it === element }
              .takeIf { it >= 0 } ?: current.elements.indexOf(element)
            BottomSheet.open("point-info", PointInfoEditor(InfoOwner.Point(point.id), target))
          }
        }
      }
    }
  }

  /**
  * Writes points and their image references as an XML section and places associated files in the target directory.
  * @param set Snapshot of selected application data to serialize or export.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @param xml XML serializer already positioned inside the owning document or section.
  * @return Unit; run on the IO executor with the serializer inside the xTravel root.
  */
  override fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    xml.startTag(null, tag)
    if (set.nextId > 0) xml.attribute(null, "next-id", set.nextId.toString())
    set.points.forEach { point ->
      writePoint(xml, point)
      PointXml.placePictures(point.elements, dir)
    }
    xml.endTag(null, tag)
  }

  /**
  * Reads points and their image references into the import accumulator.
  *
  * Usage: Start on the section opening tag; file references resolve relative to dir.
  * @param parser XML pull parser positioned at the opening tag to consume.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @param result Mutable import accumulator receiving parsed records.
  * @return Unit; consumes the current XML section.
  */
  override fun read(parser: XmlPullParser, dir: File, result: LoadedData) {
    parser.attr("next-id")?.toLongOrNull()?.let { result.nextId = it }
    parser.forEachChild { name -> if (name == "point") result.points += readPoint(parser) }
  }

  /**
  * Writes one point, its attachments and schedule inside a point XML element.
  * @param xml XML serializer already positioned inside the owning document or section.
  * @param point Point snapshot whose attachments, schedule or metadata are processed; null represents absent loaded notes where accepted.
  * @return Unit; does not copy image files itself.
  */
  internal fun writePoint(xml: XmlSerializer, point: MapPoint) {
    xml.startTag(null, "point")
    xml.attribute(null, "id", point.id.toString())
    xml.attribute(null, "lat", point.lat.toString())
    xml.attribute(null, "lon", point.lon.toString())
    point.alt?.let { xml.attribute(null, "alt", it.toString()) }
    if (point.icon.isNotEmpty()) xml.attribute(null, "icon", point.icon)
    xml.attribute(null, "visible", point.visible.toString())
    xml.attribute(null, "visited", point.visited.toString())
    xml.attribute(null, "status", point.status.name)
    xml.attribute(null, "auto-visit", point.autoVisit.toString())
    xml.attribute(null, "visit-radius", point.visitRadius.toString())
    xml.attribute(null, "schedule-display", point.scheduleDisplay.name)
    xml.attribute(null, "schedule-control", point.scheduleControl.toString())
    xml.attribute(null, "warn-before", point.warnBefore.toString())
    PointXml.writeElements(xml, point.elements)
    if (point.schedule.isNotEmpty()) {
      xml.startTag(null, "schedule")
      point.schedule.forEach { rule ->
        xml.startTag(null, "rule")
        xml.attribute(null, "days", rule.days.joinToString("") { if (it) "1" else "0" })
        xml.attribute(null, "from", formatTime(rule.from))
        xml.attribute(null, "to", formatTime(rule.to))
        xml.endTag(null, "rule")
      }
      xml.endTag(null, "schedule")
    }
    xml.endTag(null, "point")
  }

  /**
  * Reads one point and its nested elements, applying defaults.
  *
  * Usage: Start at a point opening tag; the parser advances through its end.
  * @param parser XML pull parser positioned at the opening tag to consume.
  * @return The parsed MapPoint.
  */
  internal fun readPoint(parser: XmlPullParser): MapPoint {
    val id = parser.attr("id")?.toLongOrNull() ?: 0L
    val lat = parser.attr("lat")?.toDoubleOrNull() ?: 0.0
    val lon = parser.attr("lon")?.toDoubleOrNull() ?: 0.0
    val alt = parser.attr("alt")?.toDoubleOrNull()
    val icon = parser.attr("icon").orEmpty()
    val visible = parser.attr("visible")?.toBoolean() ?: true
    val visited = parser.attr("visited")?.toBoolean() ?: false
    val status = PointStatus.entries.firstOrNull { it.name == parser.attr("status") } ?: PointStatus.INDEPENDENT
    val autoVisit = parser.attr("auto-visit")?.toBoolean() ?: false
    val visitRadius = parser.attr("visit-radius")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_VISIT_RADIUS
    val display = ScheduleDisplay.entries.firstOrNull { it.name == parser.attr("schedule-display") } ?: ScheduleDisplay.NONE
    val control = parser.attr("schedule-control")?.toBoolean() ?: false
    val warnBefore = parser.attr("warn-before")?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_WARN_BEFORE
    val elements = mutableListOf<PointElement>()
    var schedule: List<ScheduleRule> = emptyList()
    parser.forEachChild { name ->
      if (!PointXml.readElement(parser, name, elements) && name == "schedule") schedule = readSchedule(parser)
    }
    return MapPoint(id, lat, lon, alt, icon, MetaInfo(elements), schedule, visible, visited, status, autoVisit, visitRadius, display, control, warnBefore)
  }

  // Lines of the schedule; attributes of older and foreign files that the model has no place for are skipped.
  private fun readSchedule(parser: XmlPullParser): List<ScheduleRule> {
    val rules = mutableListOf<ScheduleRule>()
    parser.forEachChild { name ->
      if (name != "rule") return@forEachChild
      val text = parser.attr("days").orEmpty()
      val days = List(7) { index -> text.getOrNull(index) != '0' }
      val from = parser.attr("from")?.let(::parseTime)
      val to = parser.attr("to")?.let(::parseTime)
      parser.forEachChild {}
      if (from != null && to != null) rules += ScheduleRule(days, from, to)
    }
    return rules
  }
}

// Элементы метаинформации, общие для точек и записок.
/**
* Serializes shared text and picture elements and copies their referenced image files.
*
* Public and subclass/module-facing members:
* - [writeElements] - Writes text and picture elements in their existing order.
* - [readElement] - Appends a recognized text or picture element from the current XML tag.
* - [placePictures] - Copies attached images from application storage into another data directory.
*/
object PointXml {

  /**
  * Writes text and picture elements in their existing order.
  * @param xml XML serializer already positioned inside the owning document or section.
  * @param elements Ordered text and picture attachments to process.
  * @return Unit; image contents are not embedded.
  */
  fun writeElements(xml: XmlSerializer, elements: List<PointElement>) {
    elements.forEach { element ->
      when (element) {
        is PointElement.Text -> xml.startTag(null, "text").text(element.text).endTag(null, "text")
        is PointElement.Picture -> xml.startTag(null, "picture").attribute(null, "file", element.file).endTag(null, "picture")
      }
    }
  }

  // Returns false when the tag is not an element.
  /**
  * Appends a recognized text or picture element from the current XML tag.
  * @param parser XML pull parser positioned at the opening tag to consume.
  * @param name Name or filename stem identifying the requested object.
  * @param elements Ordered text and picture attachments to process.
  * @return True for a recognized tag, false for another tag.
  */
  fun readElement(parser: XmlPullParser, name: String, elements: MutableList<PointElement>): Boolean {
    when (name) {
      "text" -> elements += PointElement.Text(parser.nextText())
      "picture" -> parser.attr("file")?.let { elements += PointElement.Picture(it) }
      else -> return false
    }
    return true
  }

  /**
  * Copies attached images from application storage into another data directory.
  * @param elements Ordered text and picture attachments to process.
  * @param dir Directory from which referenced files are read or into which files are written.
  * @return Unit; delegates missing-file and same-path handling to DataIO.place.
  */
  fun placePictures(elements: List<PointElement>, dir: File) {
    elements.filterIsInstance<PointElement.Picture>().forEach { picture ->
      DataIO.place(PointStore.pictureFile(picture.file), File(DataIO.imagesDir(dir), picture.file))
    }
  }
}
