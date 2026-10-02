// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Пересчёт мир <-> экран для одной камеры и одного размера холста; при смене любого из них создаётся заново.
    Калибровка подложки, когда она есть, заменяет расчётный центр холста и масштаб: тогда объекты ложатся точно на карту.
*/
class MapViewport(
  val width: Float,
  val height: Float,
  val camera: CameraState,
  val density: Float,
  val projection: IMapProjection,
  calibration: MapCalibration? = null
) {
  val center: WorldPoint = projection.toWorld(camera.center)
  val worldPx: Double = calibration?.worldPx ?: (projection.tileSizeDp * 2.0.pow(camera.zoom.toDouble()) * density) /** Ширина мира в пикселях */
  private val originX = calibration?.center?.x ?: (width / 2)
  private val originY = calibration?.center?.y ?: (height / 2)
  private val cos = cos(Math.toRadians(camera.azimuth.toDouble()))
  private val sin = sin(Math.toRadians(camera.azimuth.toDouble()))

  fun screenX(wx: Double, wy: Double): Float {
    val dx = (wx - center.x) * worldPx
    val dy = (wy - center.y) * worldPx
    return (dx * cos + dy * sin + originX).toFloat()
  }

  fun screenY(wx: Double, wy: Double): Float {
    val dx = (wx - center.x) * worldPx
    val dy = (wy - center.y) * worldPx
    return (-dx * sin + dy * cos + originY).toFloat()
  }

  fun toScreen(world: WorldPoint) = Offset(screenX(world.x, world.y), screenY(world.x, world.y))

  fun toScreen(point: GeoPoint) = toScreen(projection.toWorld(point))

  fun toWorld(screen: Offset): WorldPoint {
    val x = screen.x - originX
    val y = screen.y - originY
    val dx = x * cos - y * sin
    val dy = x * sin + y * cos
    return WorldPoint(center.x + dx / worldPx, center.y + dy / worldPx)
  }

  fun toGeo(screen: Offset): GeoPoint = projection.toGeo(toWorld(screen))

  /** Центр камеры, при котором точка мира окажется в заданном месте экрана. */
  fun centerFor(world: WorldPoint, screen: Offset): WorldPoint {
    val current = toWorld(screen)
    return WorldPoint(center.x + world.x - current.x, center.y + world.y - current.y)
  }

  /** Метры на пиксель на широте центра камеры. */
  fun metersPerPixel(): Double = projection.metersPerWorldUnit(camera.center.lat) / worldPx

  /** Экранный угол направления, заданного в градусах от севера. */
  fun screenAngle(bearing: Float): Float = bearing - camera.azimuth
}
