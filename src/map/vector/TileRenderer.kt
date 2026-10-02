// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Что рисуется: слои правил Protomaps basemaps (styles/src/base_layers.ts), упрощённые.
    Порядок значений - порядок рисования.
*/
enum class VectorLayer {
  EARTH, LANDCOVER, PARK, URBAN_GREEN, HOSPITAL, INDUSTRIAL, SCHOOL, BEACH, ZOO, AERODROME, RUNWAY_AREA,
  WATER, RIVER, STREAM, PEDESTRIAN, PIER,
  TUNNEL, BUILDING, ROAD, RAIL, BOUNDARY, BRIDGE;

  /** Слой дорог: обводки всех его линий рисуются раньше самих линий, иначе обводка перекрывает перекрёстки. */
  val roads: Boolean get() = this == TUNNEL || this == ROAD || this == BRIDGE
}

/** Класс дороги - выбирает цвет и ширину. */
enum class RoadClass(val colorName: String) { OTHER("other"), MINOR_SERVICE("minor_service"), MINOR("minor"), LINK("link"), MAJOR("major"), HIGHWAY("highway") }

/** Объект тайла, готовый к рисованию: путь в координатах тайла (0..extent) и его границы. */
class VectorFeature(val layer: VectorLayer, val path: Path, val bounds: RectF, val colorName: String, val road: RoadClass?, val country: Boolean)

enum class LabelKind { COUNTRY, LOCALITY, REGION, SUBPLACE }

/** Подпись населённого пункта или области в мировых координатах 0..1. Порядок отбора - kind, затем sortKey. */
class MapLabel(
  val wx: Double,
  val wy: Double,
  val text: String,
  val shortText: String?,
  val kind: LabelKind,
  val minZoom: Float,
  val rank: Int,
  val sortKey: Float,
  val bold: Boolean,
  val capital: Boolean
) {
  var unitWidth = -1f /** Ширина текста при размере шрифта 1, считается при первом рисовании */
  var unitShortWidth = -1f /** То же для shortText */
}

/** Тайл архива, разобранный и разложенный по слоям; extent - размер тайла в его координатах. */
class PreparedTile(val z: Int, val x: Int, val y: Int, val extent: Int, val features: List<VectorFeature>, val labels: List<MapLabel>) {

  companion object {
    private val PARK_B = setOf("national_park", "park", "cemetery", "protected_area", "nature_reserve", "forest", "golf_course")
    private val PARK_ALL = PARK_B + setOf("wood", "scrub", "grassland", "grass", "glacier", "sand", "military", "naval_base", "airfield")
    private val SCRUB = setOf("scrub", "grassland", "grass")
    private val MILITARY = setOf("military", "naval_base", "airfield")

    /** Язык подписей - язык интерфейса приложения: name:<язык>, без него name. */
    fun prepare(tile: MvtTile, z: Int, x: Int, y: Int, language: String): PreparedTile {
      val features = ArrayList<VectorFeature>()
      val labels = ArrayList<MapLabel>()
      var extent = 4096
      tile.layers.values.forEach { layer ->
        extent = layer.extent
        layer.features.forEach { feature ->
          if (layer.name == "places") {
            label(layer, feature, z, x, y, language)?.let(labels::add)
          } else {
            classify(layer, feature)?.let { (kind, color, road) ->
              val path = buildPath(feature) ?: return@let
              val bounds = RectF().also { path.computeBounds(it, true) }
              val country = layer.name == "boundaries" && (layer.number(feature, "kind_detail") ?: 99.0) <= 2
              features += VectorFeature(kind, path, bounds, color, road, country)
            }
          }
        }
      }
      features.sortWith(compareBy({ it.layer.ordinal }, { it.road?.ordinal ?: 0 }))
      return PreparedTile(z, x, y, extent, features, labels)
    }

    // Слой, имя цвета и класс дороги; null - объект не рисуется.
    private fun classify(layer: MvtLayer, feature: MvtFeature): Triple<VectorLayer, String, RoadClass?>? {
      val kind = layer.string(feature, "kind").orEmpty()
      val polygon = feature.type == MvtFeature.POLYGON
      return when (layer.name) {
        "earth" -> if (polygon) Triple(VectorLayer.EARTH, "earth", null) else null
        "landcover" -> if (polygon) Triple(VectorLayer.LANDCOVER, "landcover.$kind", null) else null
        "water" -> when {
          polygon -> Triple(VectorLayer.WATER, "water", null)
          feature.type != MvtFeature.LINE -> null
          kind == "river" -> Triple(VectorLayer.RIVER, "water", null)
          kind == "stream" -> Triple(VectorLayer.STREAM, "water", null)
          else -> null
        }
        "landuse" -> if (polygon) landuse(kind) else null
        "buildings" -> if (polygon && (kind == "building" || kind == "building_part")) Triple(VectorLayer.BUILDING, "buildings", null) else null
        "roads" -> if (feature.type == MvtFeature.LINE) road(layer, feature, kind) else null
        "boundaries" -> if (feature.type == MvtFeature.LINE) Triple(VectorLayer.BOUNDARY, "boundaries", null) else null
        else -> null
      }
    }

    private fun landuse(kind: String): Triple<VectorLayer, String, RoadClass?>? = when (kind) {
      in PARK_ALL -> Triple(
        VectorLayer.PARK,
        when (kind) {
          in PARK_B -> "park_b"
          "wood" -> "wood_b"
          in SCRUB -> "scrub_b"
          "glacier" -> "glacier"
          "sand" -> "sand"
          in MILITARY -> "zoo"
          else -> "earth"
        },
        null
      )
      "allotments", "village_green", "playground" -> Triple(VectorLayer.URBAN_GREEN, "park_b", null)
      "hospital" -> Triple(VectorLayer.HOSPITAL, "hospital", null)
      "industrial" -> Triple(VectorLayer.INDUSTRIAL, "industrial", null)
      "school", "university", "college" -> Triple(VectorLayer.SCHOOL, "school", null)
      "beach" -> Triple(VectorLayer.BEACH, "beach", null)
      "zoo" -> Triple(VectorLayer.ZOO, "zoo", null)
      "aerodrome" -> Triple(VectorLayer.AERODROME, "aerodrome", null)
      "runway", "taxiway" -> Triple(VectorLayer.RUNWAY_AREA, "runway", null)
      "pedestrian", "dam" -> Triple(VectorLayer.PEDESTRIAN, "pedestrian", null)
      "pier" -> Triple(VectorLayer.PIER, "pier", null)
      else -> null
    }

    private fun road(layer: MvtLayer, feature: MvtFeature, kind: String): Triple<VectorLayer, String, RoadClass?>? {
      if (kind == "rail") return Triple(VectorLayer.RAIL, "railway", null)
      val detail = layer.string(feature, "kind_detail")
      if (detail == "runway" || detail == "taxiway" || detail == "pier") return null
      val road = when {
        layer.flag(feature, "is_link") -> RoadClass.LINK
        kind == "highway" -> RoadClass.HIGHWAY
        kind == "major_road" -> RoadClass.MAJOR
        kind == "minor_road" -> if (detail == "service") RoadClass.MINOR_SERVICE else RoadClass.MINOR
        kind == "other" || kind == "path" -> RoadClass.OTHER
        else -> return null
      }
      return when {
        layer.flag(feature, "is_tunnel") -> Triple(VectorLayer.TUNNEL, "tunnel_", road)
        layer.flag(feature, "is_bridge") -> Triple(VectorLayer.BRIDGE, "bridges_", road)
        else -> Triple(VectorLayer.ROAD, "", road)
      }
    }

    private fun buildPath(feature: MvtFeature): Path? {
      val parts = feature.geometry()
      if (parts.isEmpty()) return null
      val path = Path()
      if (feature.type == MvtFeature.POLYGON) path.fillType = Path.FillType.EVEN_ODD
      parts.forEach { part ->
        if (part.size < 4) return@forEach
        path.moveTo(part[0], part[1])
        var i = 2
        while (i + 1 < part.size) {
          path.lineTo(part[i], part[i + 1])
          i += 2
        }
        if (feature.type == MvtFeature.POLYGON) path.close()
      }
      return path
    }

    private fun label(layer: MvtLayer, feature: MvtFeature, z: Int, x: Int, y: Int, language: String): MapLabel? {
      if (feature.type != MvtFeature.POINT) return null
      val kind = when (layer.string(feature, "kind")) {
        "country" -> LabelKind.COUNTRY
        "region" -> LabelKind.REGION
        "locality" -> LabelKind.LOCALITY
        "neighbourhood", "macrohood" -> LabelKind.SUBPLACE
        else -> return null
      }
      val text = layer.string(feature, "name:$language") ?: layer.string(feature, "name") ?: return null
      val point = feature.geometry().firstOrNull() ?: return null
      val scale = 1.0 / (1L shl z)
      val minZoom = layer.number(feature, "min_zoom")?.toFloat() ?: 0f
      val short = if (kind == LabelKind.REGION) layer.string(feature, "ref:en") ?: layer.string(feature, "ref") else null
      return MapLabel(
        wx = (x + point[0].toDouble() / layer.extent) * scale,
        wy = (y + point[1].toDouble() / layer.extent) * scale,
        text = if (kind == LabelKind.REGION || kind == LabelKind.SUBPLACE) text.uppercase() else text,
        shortText = short?.uppercase(),
        kind = kind,
        minZoom = minZoom,
        rank = layer.number(feature, "population_rank")?.toInt() ?: 0,
        sortKey = layer.number(feature, "sort_key")?.toFloat() ?: minZoom,
        bold = kind == LabelKind.COUNTRY || (kind == LabelKind.LOCALITY && minZoom <= 5),
        capital = layer.string(feature, "capital") == "yes"
      )
    }
  }
}

/** Рисует тайл сетки приложения (256 dp) из подготовленных тайлов архива. Один экземпляр - один поток. */
class TileRenderer(private val density: Float) {

  private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val visible = RectF()

  /** Тайл z/x/y в bitmap; sources - слои по порядку, первый закрашивает фон.
      styleZoom - масштаб MapLibre для правил: тайл 256 dp на уровне z соответствует его масштабу z - 1.
  */
  fun render(target: Bitmap, z: Int, x: Int, y: Int, sources: List<PreparedTile>, style: MapStyle) {
    val canvas = Canvas(target)
    val size = target.width.toFloat()
    val styleZoom = z - 1f
    canvas.drawColor(style.color("background"))
    sources.forEach { source ->
      val k = (1 shl (z - source.z)).toFloat()
      val offsetX = x - source.x * k
      val offsetY = y - source.y * k
      val scale = k * size / source.extent
      val unit = source.extent / k
      canvas.save()
      canvas.translate(-offsetX * size, -offsetY * size)
      canvas.scale(scale, scale)
      // Запас на толщину линий: дороги шире 20 dp не бывают.
      val margin = 20 * density / scale
      visible.set(offsetX * unit - margin, offsetY * unit - margin, (offsetX + 1) * unit + margin, (offsetY + 1) * unit + margin)
      val features = source.features
      var start = 0
      while (start < features.size) {
        val layer = features[start].layer
        var end = start
        while (end < features.size && features[end].layer == layer) end++
        val passes = if (layer.roads) listOf(true, false) else listOf(false)
        passes.forEach { casing ->
          for (i in start until end) {
            val feature = features[i]
            if (!RectF.intersects(visible, feature.bounds)) continue
            if (layer.roads) road(canvas, feature, style, styleZoom, scale, casing) else draw(canvas, feature, style, styleZoom, scale)
          }
        }
        start = end
      }
      canvas.restore()
    }
  }

  private fun draw(canvas: Canvas, feature: VectorFeature, style: MapStyle, zoom: Float, scale: Float) {
    when (feature.layer) {
      VectorLayer.EARTH, VectorLayer.HOSPITAL, VectorLayer.INDUSTRIAL, VectorLayer.SCHOOL, VectorLayer.BEACH, VectorLayer.ZOO,
      VectorLayer.AERODROME, VectorLayer.RUNWAY_AREA, VectorLayer.WATER, VectorLayer.PEDESTRIAN, VectorLayer.PIER ->
        fillPath(canvas, feature, style.color(feature.colorName), 1f)
      VectorLayer.LANDCOVER -> if (style.has(feature.colorName)) {
        fillPath(canvas, feature, style.color(feature.colorName, "landcover.forest"), styleValue(zoom, 1f, 5f, 1f, 7f, 0f))
      }
      VectorLayer.PARK -> fillPath(canvas, feature, style.color(feature.colorName), styleValue(zoom, 1f, 6f, 0f, 11f, 1f))
      VectorLayer.URBAN_GREEN -> fillPath(canvas, feature, style.color(feature.colorName), 0.7f)
      VectorLayer.BUILDING -> fillPath(canvas, feature, style.color(feature.colorName), 0.5f)
      VectorLayer.RIVER -> if (zoom >= 9) stroke(canvas, feature, style.color("water"), styleValue(zoom, 1.6f, 9f, 0f, 9.5f, 1f, 18f, 12f), scale)
      VectorLayer.STREAM -> if (zoom >= 14) stroke(canvas, feature, style.color("water"), 0.5f, scale)
      VectorLayer.RAIL -> stroke(canvas, feature, style.color("railway"), styleValue(zoom, 1.6f, 3f, 0f, 6f, 0.15f, 18f, 9f), scale, 0.5f, 0.3f, 0.75f)
      VectorLayer.BOUNDARY -> {
        val width = if (feature.country) 1.5f else 0.8f
        stroke(canvas, feature, style.color("boundaries"), width, scale)
      }
      VectorLayer.TUNNEL, VectorLayer.ROAD, VectorLayer.BRIDGE -> {}
    }
  }

  /** Дорога или, при casing, только её обводка (обводки есть у магистралей и крупных дорог). */
  private fun road(canvas: Canvas, feature: VectorFeature, style: MapStyle, zoom: Float, scale: Float, casing: Boolean) {
    val road = feature.road ?: return
    val prefix = feature.colorName
    val name = if (prefix.isNotEmpty() && road == RoadClass.MINOR_SERVICE) "minor" else road.colorName
    val width = when (road) {
      RoadClass.OTHER -> styleValue(zoom, 1.6f, 14f, 0.5f, 20f, 12f)
      RoadClass.MINOR_SERVICE -> styleValue(zoom, 1.6f, 13f, 0f, 18f, 8f)
      RoadClass.MINOR -> styleValue(zoom, 1.6f, 11f, 0f, 12.5f, 0.5f, 15f, 2f, 18f, 11f)
      RoadClass.LINK -> styleValue(zoom, 1.6f, 13f, 0f, 13.5f, 1f, 18f, 11f)
      RoadClass.MAJOR -> styleValue(zoom, 1.6f, 6f, 0f, 12f, 1.6f, 15f, 3f, 18f, 13f)
      RoadClass.HIGHWAY -> styleValue(zoom, 1.6f, 3f, 0f, 6f, 1.1f, 12f, 1.6f, 15f, 5f, 18f, 15f)
    }
    if (width <= 0f) return
    if (casing) {
      val casingWidth = when (road) {
        RoadClass.MAJOR -> styleValue(zoom, 1.6f, 9f, 0f, 9.5f, 1f)
        RoadClass.HIGHWAY -> styleValue(zoom, 1.6f, 7f, 0f, 7.5f, 1f)
        else -> 0f
      }
      if (casingWidth <= 0f) return
      val casingName = when {
        prefix.isNotEmpty() -> "$prefix${name}_casing"
        zoom < 12 -> "${name}_casing_early"
        else -> "${name}_casing_late"
      }
      stroke(canvas, feature, style.color(casingName, "${name}_casing_early"), width + 2 * casingWidth, scale)
      return
    }
    val color = if (road == RoadClass.MINOR && prefix.isEmpty()) {
      blendColor(style.color("minor_a"), style.color("minor_b"), styleFraction(zoom, 1.6f, 11f, 16f))
    } else {
      style.color("$prefix$name", name)
    }
    stroke(canvas, feature, color, width, scale)
  }

  private fun fillPath(canvas: Canvas, feature: VectorFeature, color: Int, opacity: Float) {
    if (opacity <= 0f) return
    fill.color = color
    if (opacity < 1f) fill.alpha = (fill.alpha * opacity).toInt()
    canvas.drawPath(feature.path, fill)
  }

  // Ширина и штрихи - в dp, как в стиле MapLibre; штрихи заданы в долях ширины.
  private fun stroke(canvas: Canvas, feature: VectorFeature, color: Int, widthDp: Float, scale: Float, opacity: Float = 1f, vararg dash: Float) {
    if (widthDp <= 0f) return
    val width = widthDp * density / scale
    line.color = color
    if (opacity < 1f) line.alpha = (line.alpha * opacity).toInt()
    line.strokeWidth = width
    line.pathEffect = if (dash.isEmpty()) null else DashPathEffect(FloatArray(dash.size) { dash[it] * width }, 0f)
    line.strokeCap = if (dash.isEmpty()) Paint.Cap.ROUND else Paint.Cap.BUTT
    canvas.drawPath(feature.path, line)
  }
}
