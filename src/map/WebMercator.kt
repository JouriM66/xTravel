// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

/** Сферический Меркатор EPSG:3857 - проекция тайлов OSM и Protomaps. Мировые координаты 0..1, y растёт к югу. */
object WebMercatorProjection : IMapProjection {

  const val MAX_LAT = 85.0511287798

  private const val EQUATOR_RADIUS = 6378137.0

  override val tileSizeDp = 256.0

  override fun toWorld(lat: Double, lon: Double): WorldPoint {
    val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    return WorldPoint((lon + 180) / 360, 0.5 - ln(tan(PI / 4 + phi / 2)) / (2 * PI))
  }

  override fun toGeo(world: WorldPoint): GeoPoint =
    GeoPoint(Math.toDegrees(2 * atan(exp((0.5 - world.y) * 2 * PI)) - PI / 2), world.x * 360 - 180)

  override fun metersPerWorldUnit(lat: Double): Double =
    2 * PI * EQUATOR_RADIUS * cos(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
}
