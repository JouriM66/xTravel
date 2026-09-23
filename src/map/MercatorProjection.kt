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

// Normalized world coordinates: x and y in 0..1, y grows to the south.
/**
* Stores normalized projected coordinates in the map's world coordinate system.
* @param x Normalized projected x coordinate.
* @param y Normalized projected y coordinate.
* @property x Normalized projected x coordinate.
* @property y Normalized projected y coordinate.
*/
data class WorldPoint(val x: Double, val y: Double)

// Elliptical Mercator on the WGS-84 ellipsoid (EPSG:3395), the projection of Yandex Maps.
/**
* Implements the ellipsoidal WGS-84 Mercator projection used by the Yandex background.
*
* Public and subclass/module-facing members:
* - [MAX_LAT] - Maximum absolute latitude accepted by this Mercator projection.
* - [tileSizeDp] - Base tile width in dp used to derive world-pixel scale at zoom zero.
* - [toWorld] - Projects longitude and clamped latitude with ellipsoidal Mercator equations.
* - [toGeo] - Inverts normalized ellipsoidal Mercator coordinates.
* - [metersPerWorldUnit] - Computes ellipsoidal ground scale for the specified latitude.
*/
object MercatorProjection : IMapProjection {

  /**
  * Maximum absolute latitude accepted by this Mercator projection.
  * @return Maximum absolute latitude accepted by this Mercator projection.
  */
  const val MAX_LAT = 85.08405905

  private const val E = 0.0818191908426
  private const val EQUATOR_RADIUS = 6378137.0

  /**
  * Base tile width in dp used to derive world-pixel scale at zoom zero.
  * @return Base tile width in dp used to derive world-pixel scale at zoom zero.
  */
  override val tileSizeDp = 256.0

  /**
  * Projects longitude and clamped latitude with ellipsoidal Mercator equations.
  * @param lat Latitude in geographic degrees.
  * @param lon Longitude in geographic degrees.
  * @return Normalized projected coordinates.
  */
  override fun toWorld(lat: Double, lon: Double): WorldPoint {
    val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    val es = E * sin(phi)
    val y = ln(tan(PI / 4 + phi / 2) * ((1 - es) / (1 + es)).pow(E / 2))
    return WorldPoint((lon + 180) / 360, 0.5 - y / (2 * PI))
  }

  /**
  * Inverts normalized ellipsoidal Mercator coordinates.
  * @param world Position in normalized projected world coordinates.
  * @return The reconstructed geographic position.
  */
  override fun toGeo(world: WorldPoint): GeoPoint {
    val t = exp(-(0.5 - world.y) * 2 * PI)
    var phi = PI / 2 - 2 * atan(t)
    repeat(6) {
      val es = E * sin(phi)
      phi = PI / 2 - 2 * atan(t * ((1 - es) / (1 + es)).pow(E / 2))
    }
    return GeoPoint(Math.toDegrees(phi), world.x * 360 - 180)
  }

  // Length of the parallel at the latitude: one world unit along x.
  /**
  * Computes ellipsoidal ground scale for the specified latitude.
  * @param lat Latitude in geographic degrees.
  * @return Meters per projected world unit.
  */
  override fun metersPerWorldUnit(lat: Double): Double {
    val phi = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
    val es = E * sin(phi)
    return 2 * PI * EQUATOR_RADIUS * cos(phi) / sqrt(1 - es * es)
  }
}
