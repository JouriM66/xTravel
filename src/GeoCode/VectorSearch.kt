// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.net.Uri
import java.io.File

/** Геокодер без сети по данным встроенной карты: страны, области, населённые пункты и районы слоя places
    встроенного архива и файла карты пользователя (Settings.vectorMapFile).
    Индекс строится при первом запросе в потоке геокодера (долго - чтение всех тайлов одного уровня) и сохраняется
    в cacheDir; ключ - архив и его размер, поэтому смена файла строит индекс заново.
*/
object VectorSearchEngine : IGeoSearchEngine {

  /** FOR LOCAL USE: место индекса; names - name, name:en, name:ru, пустые - нет. */
  private class Place(val lat: Double, val lon: Double, val kind: LabelKind, val rank: Int, val names: List<String>) {
    val search: List<String> = names.filter { it.isNotEmpty() }.map(::normalize)

    fun name(): String {
      val index = NAME_KEYS.indexOf("name:" + Languages.current.tag)
      return names.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: names[0]
    }
  }

  private val NAME_KEYS = listOf("name", "name:en", "name:ru")
  private const val INDEX_LEVEL = 12
  private const val MAX_RESULTS = 10
  private const val NEAREST = 3
  private const val NEAREST_METERS = 30_000.0
  private const val DEDUP_DEGREES = 0.05

  private var indexKey: String? = null
  private var places: List<Place> = emptyList()

  override val mustBeAsync = true
  override val configured = true

  override fun byText(text: String): List<GeoAnswer> {
    val query = normalize(text)
    if (query.isEmpty()) return emptyList()
    return index().mapNotNull { place -> score(place, query)?.let { place to it } }
      .sortedWith(compareBy({ it.second }, { it.first.kind.ordinal }, { -it.first.rank }))
      .take(MAX_RESULTS)
      .map { (place, _) -> GeoAnswer(GeoPoint(place.lat, place.lon), place.name(), kindText(place.kind)) }
  }

  /** Ближайшие населённые пункты и районы не дальше NEAREST_METERS, ближний первым. */
  override fun byPosition(point: GeoPoint): List<GeoAnswer> =
    index().asSequence()
      .filter { it.kind == LabelKind.LOCALITY || it.kind == LabelKind.SUBPLACE }
      .map { it to GeoMath.distance(point.lat, point.lon, it.lat, it.lon) }
      .filter { it.second <= NEAREST_METERS }
      .sortedBy { it.second }
      .take(NEAREST)
      .map { (place, meters) -> GeoAnswer(GeoPoint(place.lat, place.lon), place.name(), "${kindText(place.kind)}, ${distanceString(meters)}") }
      .toList()

  // 0 - совпадение названия, 1 - начало названия, 2 - начало слова, 3 - внутри; null - не подходит.
  private fun score(place: Place, query: String): Int? = place.search.mapNotNull { name ->
    when {
      name == query -> 0
      name.startsWith(query) -> 1
      name.contains(" $query") || name.contains("-$query") -> 2
      name.contains(query) -> 3
      else -> null
    }
  }.minOrNull()

  private fun kindText(kind: LabelKind): String = AppSession.context.getString(
    when (kind) {
      LabelKind.COUNTRY -> R.string.place_country
      LabelKind.REGION -> R.string.place_region
      LabelKind.LOCALITY -> R.string.place_locality
      LabelKind.SUBPLACE -> R.string.place_subplace
    }
  )

  private fun normalize(text: String): String = text.trim().lowercase().replace('ё', 'е').replace(SPACES, " ")

  private val SPACES = Regex("\\s+")

  // Индекс текущих архивов: из памяти, из кэша на диске или построенный заново.
  @Synchronized
  private fun index(): List<Place> {
    val archives = openArchives()
    try {
      val key = archives.joinToString("|") { it.first }
      if (key == indexKey) return places
      val merged = ArrayList<Place>()
      val seen = HashSet<String>()
      // Пользовательский архив подробнее: его места идут первыми, повторы из встроенного отбрасываются.
      archives.reversed().forEach { (archiveKey, archive) ->
        load(archiveKey, archive).forEach { place ->
          val dedup = "${place.kind}|${place.names[0]}|${Math.round(place.lat / DEDUP_DEGREES)}|${Math.round(place.lon / DEDUP_DEGREES)}"
          if (seen.add(dedup)) merged += place
        }
      }
      places = merged
      indexKey = key
      return merged
    } finally {
      archives.forEach { runCatching { it.second.close() } }
    }
  }

  private fun openArchives(): List<Pair<String, PmTiles>> {
    val result = ArrayList<Pair<String, PmTiles>>()
    runCatching { PmTiles(ChannelBytes.asset(ChannelBytes.BUILTIN_MAP)) }.getOrNull()?.let { result += "builtin-${it.fileSize}" to it }
    val file = Settings.vectorMapFile.value
    if (file.isNotEmpty()) {
      runCatching { PmTiles(ChannelBytes.uri(Uri.parse(file))) }.getOrNull()?.let { result += "$file-${it.fileSize}" to it }
    }
    return result
  }

  private fun cacheFile(key: String) = File(AppSession.app.cacheDir, "places-${key.hashCode().toUInt()}.tsv")

  private fun load(key: String, archive: PmTiles): List<Place> {
    val file = cacheFile(key)
    if (file.exists()) runCatching { return read(file) }
    val built = build(archive)
    runCatching { write(file, built) }
    return built
  }

  private fun build(archive: PmTiles): List<Place> {
    val level = INDEX_LEVEL.coerceIn(archive.minZoom, archive.maxZoom)
    val result = ArrayList<Place>()
    val scale = 1.0 / (1L shl level)
    archive.forEachTile(level) { x, y, data ->
      val layer = MvtTile.parse(data, PLACES).layers["places"] ?: return@forEachTile
      layer.features.forEach { feature ->
        if (feature.type != MvtFeature.POINT) return@forEach
        val kind = when (layer.string(feature, "kind")) {
          "country" -> LabelKind.COUNTRY
          "region" -> LabelKind.REGION
          "locality" -> LabelKind.LOCALITY
          "neighbourhood", "macrohood" -> LabelKind.SUBPLACE
          else -> return@forEach
        }
        val point = feature.geometry().firstOrNull() ?: return@forEach
        // Точки из буфера по краю тайла есть и в соседнем тайле: берутся только свои.
        if (point[0] < 0 || point[1] < 0 || point[0] >= layer.extent || point[1] >= layer.extent) return@forEach
        val names = NAME_KEYS.map { clean(layer.string(feature, it)) }
        if (names[0].isEmpty()) return@forEach
        val geo = WebMercatorProjection.toGeo(WorldPoint((x + point[0].toDouble() / layer.extent) * scale, (y + point[1].toDouble() / layer.extent) * scale))
        result += Place(geo.lat, geo.lon, kind, layer.number(feature, "population_rank")?.toInt() ?: 0, names)
      }
    }
    return result
  }

  private val PLACES = setOf("places")

  private fun clean(text: String?): String = text.orEmpty().replace('\t', ' ').replace('\n', ' ').trim()

  private fun write(file: File, list: List<Place>) {
    file.bufferedWriter().use { out ->
      list.forEach { place ->
        out.write(listOf(place.lat, place.lon, place.kind.name, place.rank).joinToString("\t"))
        place.names.forEach { out.write("\t"); out.write(it) }
        out.write("\n")
      }
    }
  }

  private fun read(file: File): List<Place> = file.bufferedReader().useLines { lines ->
    lines.filter { it.isNotEmpty() }.map { line ->
      val parts = line.split('\t')
      Place(parts[0].toDouble(), parts[1].toDouble(), LabelKind.valueOf(parts[2]), parts[3].toInt(), List(NAME_KEYS.size) { parts.getOrElse(4 + it) { "" } })
    }.toList()
  }
}
