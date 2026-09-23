// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Castle
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Cottage
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalAtm
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalGroceryStore
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wc
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter

// Icons a point can be marked with: the name is what the data file keeps, the icon is drawn white on the pin.
// The list fills a square grid in the chooser, where the first cell is "none".
/**
* Maps stored icon names to vectors and prepares painters for map rendering.
*
* Public and subclass/module-facing members:
* - [ICON_COLOR] - Color applied when drawing an icon inside a point pin.
* - [GRID] - Number of columns used by the point-icon selection grid.
* - [names] - Supported persisted point-icon names in picker order.
* - [vector] - Looks up a material vector by its persisted icon name.
* - [Prepare] - Creates and publishes Compose painters used when drawing point icons on the map.
* - [drawPointIcon] - Draws a prepared named icon in a square centered at the specified screen location.
*/
object PointIcons {

  /**
  * Color applied when drawing an icon inside a point pin.
  * @return Color applied when drawing an icon inside a point pin.
  */
  val ICON_COLOR = Color.White

  private val icons: List<Pair<String, ImageVector>> = listOf(
    "restaurant" to Icons.Filled.Restaurant,
    "cafe" to Icons.Filled.LocalCafe,
    "bar" to Icons.Filled.LocalBar,
    "fastfood" to Icons.Filled.Fastfood,
    "grocery" to Icons.Filled.LocalGroceryStore,
    "store" to Icons.Filled.Storefront,
    "hotel" to Icons.Filled.Hotel,
    "cottage" to Icons.Filled.Cottage,
    "camp" to Icons.Filled.Cabin,
    "home" to Icons.Filled.Home,
    "fuel" to Icons.Filled.LocalGasStation,
    "parking" to Icons.Filled.LocalParking,
    "bus" to Icons.Filled.DirectionsBus,
    "train" to Icons.Filled.Train,
    "airport" to Icons.Filled.Flight,
    "boat" to Icons.Filled.DirectionsBoat,
    "museum" to Icons.Filled.Museum,
    "castle" to Icons.Filled.Castle,
    "church" to Icons.Filled.Church,
    "theater" to Icons.Filled.TheaterComedy,
    "attraction" to Icons.Filled.Attractions,
    "photo" to Icons.Filled.PhotoCamera,
    "viewpoint" to Icons.Filled.Landscape,
    "mountain" to Icons.Filled.Terrain,
    "park" to Icons.Filled.Park,
    "forest" to Icons.Filled.Forest,
    "water" to Icons.Filled.WaterDrop,
    "beach" to Icons.Filled.BeachAccess,
    "trail" to Icons.Filled.Hiking,
    "wc" to Icons.Filled.Wc,
    "pharmacy" to Icons.Filled.LocalPharmacy,
    "atm" to Icons.Filled.LocalAtm,
    "info" to Icons.Filled.Info,
    "danger" to Icons.Filled.Warning,
    "star" to Icons.Filled.Star
  )

  // Side of the chooser grid: the icons and the "none" cell fill it exactly.
  /**
  * Number of columns used by the point-icon selection grid.
  * @return Number of columns used by the point-icon selection grid.
  */
  val GRID = 6

  /**
  * Supported persisted point-icon names in picker order.
  * @return Supported persisted point-icon names in picker order.
  */
  val names: List<String> = icons.map { it.first }

  /**
  * Looks up a material vector by its persisted icon name.
  * @param name Persisted icon name from the picker registry.
  * @return The vector, or null for an unknown name.
  */
  fun vector(name: String): ImageVector? = icons.firstOrNull { it.first == name }?.second

  // Painters are made in composition; the map layer draws outside it.
  @Volatile
  private var painters: Map<String, VectorPainter> = emptyMap()

  /**
  * Creates and publishes Compose painters used when drawing point icons on the map.
  * @return Unit; call from composition before map drawing uses the painters.
  */
  @Composable
  fun Prepare() {
    val prepared = icons.associate { it.first to rememberVectorPainter(it.second) }
    SideEffect { painters = prepared }
  }

  // Icon of the point in the coordinates of the drawing scope; nothing is drawn without an icon.
  /**
  * Draws a prepared named icon in a square centered at the specified screen location.
  * @param name Registered name whose painter has been prepared by Prepare.
  * @param center Center of the icon's square in screen pixels.
  * @param size Drawing size in screen pixels unless the function documents another unit.
  * @param alpha Drawing opacity multiplier, normally in 0..1. Default: 1f.
  * @receiver Active canvas drawing scope for this frame.
  * @return Unit; an unknown or unprepared icon draws nothing.
  */
  fun DrawScope.drawPointIcon(name: String, center: Offset, size: Float, alpha: Float = 1f) {
    val painter = painters[name] ?: return
    translate(center.x - size / 2, center.y - size / 2) {
      with(painter) { draw(Size(size, size), alpha = alpha, colorFilter = ColorFilter.tint(ICON_COLOR)) }
    }
  }
}
