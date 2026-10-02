// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Мировые координаты: x и y в 0..1, y растёт к югу. */
data class WorldPoint(val x: Double, val y: Double)

/** Эллипсоидальный Меркатор на WGS-84 (EPSG:3395) - проекция Яндекс Карт. */
object MercatorProjection : IMapProjection {

  const val MAX_LAT = 85.08405905

  private const val E = 0.0818191908426 /** Эксцентриситет WGS-84 */
  private const val EQUATOR_RADIUS = 6378137.0

  override val tileSizeDp = 256.0

  override fun toWorld(lat: Double, lon: Double): WorldPoint {
    val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    val es = E * sin(phi)
    val y = ln(tan(PI / 4 + phi / 2) * ((1 - es) / (1 + es)).pow(E / 2))
    return WorldPoint((lon + 180) / 360, 0.5 - y / (2 * PI))
  }

  /** Широта уточняется итерациями: обратного выражения у эллипсоидального Меркатора нет. */
  override fun toGeo(world: WorldPoint): GeoPoint {
    val t = exp(-(0.5 - world.y) * 2 * PI)
    var phi = PI / 2 - 2 * atan(t)
    repeat(6) {
      val es = E * sin(phi)
      phi = PI / 2 - 2 * atan(t * ((1 - es) / (1 + es)).pow(E / 2))
    }
    return GeoPoint(Math.toDegrees(phi), world.x * 360 - 180)
  }

  /** Длина параллели на широте lat: единица мира по x. */
  override fun metersPerWorldUnit(lat: Double): Double {
    val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    val es = E * sin(phi)
    return 2 * PI * EQUATOR_RADIUS * cos(phi) / sqrt(1 - es * es)
  }
}
