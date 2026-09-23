// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// Conversion between the world and the screen for one camera and one canvas size.
// The calibration of the background, when it gives one, replaces the computed center of the canvas and the computed scale:
// the objects then lie exactly on the map the background drew.
/**
* Converts geographic, world and screen coordinates for one camera, canvas and optional engine calibration.
*
* Usage: Create for the current canvas and camera; reuse only while both stay unchanged. Engine calibration overrides the assumed screen center and world scale.
*
* Public and subclass/module-facing members:
* - [center] - Camera center in normalized projected coordinates.
* - [worldPx] - Width of one projected world unit in screen pixels, using calibration when available.
* - [screenX] - Projects a world coordinate onto the viewport's horizontal axis.
* - [screenY] - Projects a world coordinate onto the viewport's vertical axis.
* - [toScreen] - Converts a geographic or projected point to the rotated, calibrated screen.
* - [toWorld] - Converts a screen position through inverse camera rotation and scaling.
* - [toGeo] - Converts a screen position into geographic coordinates.
* - [centerFor] - Computes a camera world center that places a world point at a chosen screen position.
* - [metersPerPixel] - Computes map scale at the camera center latitude.
* - [screenAngle] - Converts a north-referenced bearing into the viewport's rotated frame.
* @param width Viewport width in screen pixels.
* @param height Viewport height in screen pixels.
* @param camera Camera snapshot used for this projection or background measurement.
* @param density Screen pixels per dp.
* @param projection Projection used to convert geographic and world coordinates.
* @param calibration Optional actual background alignment; null uses the calculated canvas center and scale. Default: null.
* @property width Viewport width in screen pixels.
* @property height Viewport height in screen pixels.
* @property camera Camera snapshot used for this projection or background measurement.
* @property density Screen pixels per dp.
* @property projection Projection used to convert geographic and world coordinates.
*/
class MapViewport(
  val width: Float,
  val height: Float,
  val camera: CameraState,
  val density: Float,
  val projection: IMapProjection,
  calibration: MapCalibration? = null
) {
  /**
  * Camera center in normalized projected coordinates.
  * @return Camera center in normalized projected coordinates.
  */
  val center: WorldPoint = projection.toWorld(camera.center)
  /**
  * Width of one projected world unit in screen pixels, using calibration when available.
  * @return Width of one projected world unit in screen pixels, using calibration when available.
  */
  val worldPx: Double = calibration?.worldPx ?: (projection.tileSizeDp * 2.0.pow(camera.zoom.toDouble()) * density)
  private val originX = calibration?.center?.x ?: (width / 2)
  private val originY = calibration?.center?.y ?: (height / 2)
  private val cos = cos(Math.toRadians(camera.azimuth.toDouble()))
  private val sin = sin(Math.toRadians(camera.azimuth.toDouble()))

  /**
  * Projects a world coordinate onto the viewport's horizontal axis.
  * @param wx Projected horizontal coordinate, or array of those coordinates.
  * @param wy Projected vertical coordinate, or array of those coordinates.
  * @return Screen x in pixels.
  */
  fun screenX(wx: Double, wy: Double): Float {
    val dx = (wx - center.x) * worldPx
    val dy = (wy - center.y) * worldPx
    return (dx * cos + dy * sin + originX).toFloat()
  }

  /**
  * Projects a world coordinate onto the viewport's vertical axis.
  * @param wx Projected horizontal coordinate, or array of those coordinates.
  * @param wy Projected vertical coordinate, or array of those coordinates.
  * @return Screen y in pixels.
  */
  fun screenY(wx: Double, wy: Double): Float {
    val dx = (wx - center.x) * worldPx
    val dy = (wy - center.y) * worldPx
    return (-dx * sin + dy * cos + originY).toFloat()
  }

  /**
  * Converts a geographic or projected point to the rotated, calibrated screen.
  * @param world Position in normalized projected world coordinates.
  * @return The screen offset in pixels.
  */
  fun toScreen(world: WorldPoint) = Offset(screenX(world.x, world.y), screenY(world.x, world.y))

  /**
  * Converts a geographic or projected point to the rotated, calibrated screen.
  * @param point Geographic position in latitude/longitude degrees.
  * @return The screen offset in pixels.
  */
  fun toScreen(point: GeoPoint) = toScreen(projection.toWorld(point))

  /**
  * Converts a screen position through inverse camera rotation and scaling.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @return Normalized projected world coordinates.
  */
  fun toWorld(screen: Offset): WorldPoint {
    val x = screen.x - originX
    val y = screen.y - originY
    val dx = x * cos - y * sin
    val dy = x * sin + y * cos
    return WorldPoint(center.x + dx / worldPx, center.y + dy / worldPx)
  }

  /**
  * Converts a screen position into geographic coordinates.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @return Latitude and longitude in degrees.
  */
  fun toGeo(screen: Offset): GeoPoint = projection.toGeo(toWorld(screen))

  // Center that puts the world point at the screen position.
  /**
  * Computes a camera world center that places a world point at a chosen screen position.
  * @param world Projected point that must appear at screen after recentering.
  * @param screen Desired pixel location of world within this viewport.
  * @return The required projected center; does not mutate the camera.
  */
  fun centerFor(world: WorldPoint, screen: Offset): WorldPoint {
    val current = toWorld(screen)
    return WorldPoint(center.x + world.x - current.x, center.y + world.y - current.y)
  }

  /**
  * Computes map scale at the camera center latitude.
  * @return Meters per screen pixel.
  */
  fun metersPerPixel(): Double = projection.metersPerWorldUnit(camera.center.lat) / worldPx

  // Screen angle of a direction given in degrees from north.
  /**
  * Converts a north-referenced bearing into the viewport's rotated frame.
  * @param bearing Geographic bearing in degrees clockwise from north.
  * @return Screen-relative angle in degrees.
  */
  fun screenAngle(bearing: Float): Float = bearing - camera.azimuth
}
