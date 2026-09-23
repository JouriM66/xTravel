// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

// One answer of a map provider: the way from one coordinate to another. The object knows how to draw itself and
// how to answer a touch; where it belongs in a route is the business of the route.

/**
* Names a manoeuvre a provider reports for a step of a route.
*/
enum class RouteManeuver {
  /** Keep going. */
  STRAIGHT,
  /** Bear left. */
  SLIGHT_LEFT,
  /** Bear right. */
  SLIGHT_RIGHT,
  /** Turn left. */
  LEFT,
  /** Turn right. */
  RIGHT,
  /** Turn sharply left. */
  HARD_LEFT,
  /** Turn sharply right. */
  HARD_RIGHT,
  /** Keep left at a fork. */
  FORK_LEFT,
  /** Keep right at a fork. */
  FORK_RIGHT,
  /** Turn around to the left. */
  UTURN_LEFT,
  /** Turn around to the right. */
  UTURN_RIGHT,
  /** Enter a roundabout. */
  ENTER_ROUNDABOUT,
  /** Leave a roundabout. */
  LEAVE_ROUNDABOUT,
  /** Board a ferry. */
  BOARD_FERRY,
  /** Leave a ferry. */
  LEAVE_FERRY,
  /** Take the left exit. */
  EXIT_LEFT,
  /** Take the right exit. */
  EXIT_RIGHT,
  /** Arrive. */
  FINISH,
  /** Pass an intermediate stop. */
  WAYPOINT,
  /** The provider gave no manoeuvre for this step. */
  UNKNOWN
}

/**
* Describes one step of a geometry: where it starts on the line and what is done there.
* @param index Index in the geometry's points where the step begins.
* @param maneuver Manoeuvre reported for the step.
* @param description Provider text for the step, empty when it gave none.
* @property index Index in the geometry's points where the step begins.
* @property maneuver Manoeuvre reported for the step.
* @property description Provider text for the step, empty when it gave none.
*/
class RouteStep(val index: Int, val maneuver: RouteManeuver, val description: String)

// Both states are drawn the same way, a solid line with a white outline; only the color tells them apart.
private val LINE_WIDTH = 6.dp
private val OUTLINE_EXTRA = 3.dp
private val STEP_MARK = 4.dp

// A piece of a line whose screen rectangle is thinner than this in both directions is drawn as one straight line.
private const val MERGE_LIMIT = 3f

// The visible area is grown by half of itself in every direction, so a line leaving the screen keeps being drawn while the map moves.
private const val AREA_MARGIN = 0.5f

// Segments looked at when the position is projected onto the line; the search goes forward from the place reached before.
private const val PROGRESS_WINDOW = 64

/** Сегменты позади достигнутого, которые тоже проверяются: при возврате по пути прогресс откатывается назад
    на них, а за следующие отсчёты - дальше, сегмент за сегментом.
*/
private const val PROGRESS_BEHIND = 1

/**
* Distance in meters within which a projection counts as standing at the end of a line.
* @return Distance in meters within which a projection counts as standing at the end of a line.
*/
const val ROUTE_END_METERS = 1.0

/**
* Greatest distance in dp at which a touch still reaches a route line.
* @return Greatest distance in dp at which a touch still reaches a route line.
*/
val ROUTE_TOUCH_LIMIT = 16.dp

/**
* Opacity of the legs of a route while other legs of it are highlighted in the list.
* @return Opacity of the legs of a route while other legs of it are highlighted in the list.
*/
const val ROUTE_UNSELECTED_ALPHA = 0.4f

/**
* Holds one provider answer for the way between two coordinates and draws it in the state the route gave it.
*
* Usage: The state is read while drawing in the main thread; replace the points list as a whole, never in place.
*
* Public and subclass/module-facing members:
* - [from] - Coordinate the way starts at.
* - [to] - Coordinate the way ends at.
* - [transport] - Way of travelling this answer was requested for.
* - [points] - Line of the way, at least two coordinates.
* - [meters] - Length of the way in meters.
* - [seconds] - Travel time of the way in seconds.
* - [steps] - Steps of the way; empty for providers that report none.
* - [virtual] - Whether the way was made of a straight line instead of a provider answer.
* - [state] - Whether this way is the active one of its leg.
* - [firstDrawn] - Index of the first point of the part still to travel.
* - [startPlace] - Position projected onto the line, where the drawn part begins.
* - [color] - Settings color of the current state.
* - [draw] - Draws the part still to travel with its outline and manoeuvre marks.
* - [hitTest] - Reports whether a screen position touches the line.
* - [nextStep] - Finds the first step at or after a position on the line.
* - [rewind] - Puts the travelled part back, so the whole line is drawn again.
* - [advance] - Moves the progress to the position and tells whether the end is reached.
* - [remaining] - Length in meters of the part still to travel.
*/
class RouteGeometry(
  val from: GeoPoint,
  val to: GeoPoint,
  val transport: TransportKind,
  val points: List<GeoPoint>,
  val meters: Double,
  val seconds: Double,
  val steps: List<RouteStep> = emptyList(),
  // A way nobody was asked for: the straight line the navigation follows while the leg has no answer. Never saved.
  val virtual: Boolean = false
) {

  /**
  * Whether this way is the active one of its leg.
  * @return Whether this way is the active one of its leg.
  */
  var state by mutableStateOf(RouteGeometryState.INACTIVE)

  // Runtime only, never saved: how far along the line the navigation has come.
  /**
  * Index of the first point of the part still to travel.
  * @return Index of the first point of the part still to travel.
  */
  var firstDrawn by mutableIntStateOf(0)
    private set

  /**
  * Position projected onto the line, where the drawn part begins.
  * @return The projected position, or null while nothing was travelled.
  */
  var startPlace by mutableStateOf<GeoPoint?>(null)
    private set

  /**
  * Settings color of the current state.
  * @return Settings color of the current state.
  */
  fun color(): Color = routeStateColor(state)

  /**
  * Draws the part still to travel with its outline and manoeuvre marks.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @param alpha Opacity of the whole drawing, used to dim the legs nobody marked; null keeps the opacity of the settings color.
  * @return Unit; a line of less than two points draws nothing.
  */
  fun draw(context: MapDrawContext, alpha: Float? = null) {
    if (points.size < 2) return
    val area = drawnArea(context.viewport)
    val screen = screenLine(context.viewport)
    if (screen.size < 2) return
    val path = Path()
    path.moveTo(screen[0].x, screen[0].y)
    for (index in 1 until screen.size) {
      val end = screen[index]
      if (visible(screen[index - 1], end, area)) path.lineTo(end.x, end.y) else path.moveTo(end.x, end.y)
    }
    val color = alpha?.let { color().copy(alpha = it) } ?: color()
    with(context.scope) {
      drawPath(path, Color.White.copy(alpha = color.alpha), style = stroke(LINE_WIDTH.toPx() + OUTLINE_EXTRA.toPx()))
      drawPath(path, color, style = stroke(LINE_WIDTH.toPx()))
      // The marks belong to the manoeuvres of the way being travelled; outside the navigation they only clutter the map.
      if (RouteStore.active) drawStepMarks(context, color, area)
    }
  }

  // FOR LOCAL USE: the visible area of the map grown by half of itself in every direction.
  private fun drawnArea(viewport: MapViewport) = Rect(
    -viewport.width * AREA_MARGIN,
    -viewport.height * AREA_MARGIN,
    viewport.width * (1f + AREA_MARGIN),
    viewport.height * (1f + AREA_MARGIN)
  )

  // FOR LOCAL USE: a segment is skipped when its own rectangle lies fully outside the drawn area.
  private fun visible(start: Offset, end: Offset, area: Rect) =
    maxOf(start.x, end.x) >= area.left && minOf(start.x, end.x) <= area.right &&
      maxOf(start.y, end.y) >= area.top && minOf(start.y, end.y) <= area.bottom

  // FOR LOCAL USE: the line on the screen, starting at the position reached. Points that collapse into a spot of a few
  // pixels are gathered into one straight line: the last of them ends it, so nothing of the shape is lost.
  private fun screenLine(viewport: MapViewport): List<Offset> {
    val result = mutableListOf<Offset>()
    val start = viewport.toScreen(startPlace ?: points[firstDrawn])
    result += start
    var minX = start.x
    var maxX = start.x
    var minY = start.y
    var maxY = start.y
    val last = points.size - 1
    for (index in firstDrawn + 1..last) {
      val place = viewport.toScreen(points[index])
      minX = minOf(minX, place.x)
      maxX = maxOf(maxX, place.x)
      minY = minOf(minY, place.y)
      maxY = maxOf(maxY, place.y)
      if (index != last && minOf(maxX - minX, maxY - minY) <= MERGE_LIMIT) continue
      result += place
      minX = place.x
      maxX = place.x
      minY = place.y
      maxY = place.y
    }
    return result
  }

  // FOR LOCAL USE: marks of the manoeuvres still ahead.
  private fun drawStepMarks(context: MapDrawContext, color: Color, area: Rect) {
    with(context.scope) {
      val radius = STEP_MARK.toPx()
      steps.forEach { step ->
        if (step.index <= firstDrawn || step.index >= points.size) return@forEach
        val place = context.viewport.toScreen(points[step.index])
        if (!area.contains(place)) return@forEach
        drawCircle(Color.White.copy(alpha = color.alpha), radius + OUTLINE_EXTRA.toPx() / 2f, place)
        drawCircle(color, radius, place)
      }
    }
  }

  private fun stroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

  /**
  * Reports whether a screen position touches the line.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @param limitPx Greatest accepted distance from the line in pixels.
  * @return True when the position is close enough to the line.
  */
  fun hitTest(screen: Offset, viewport: MapViewport, limitPx: Float): Boolean = nearPolyline(points, screen, viewport, limitPx)

  /**
  * Finds the first step at or after a position on the line.
  * @param index Index in the geometry's points the search starts at.
  * @return The step, or null when none follows.
  */
  fun nextStep(index: Int): RouteStep? = steps.firstOrNull { it.index >= index }

  /**
  * Puts the travelled part back, so the whole line is drawn again.
  * @return Unit; the line itself is never changed.
  */
  fun rewind() {
    firstDrawn = 0
    startPlace = null
  }

  /**
  * Moves the progress to the position and tells whether the end is reached.
  * @param here Geographic position in latitude/longitude degrees.
  * @return True when the projection stands at the end of the line.
  */
  fun advance(here: GeoPoint): Boolean {
    val from = (firstDrawn - PROGRESS_BEHIND).coerceAtLeast(0)
    val projection = projectOnLine(points, here, from, PROGRESS_WINDOW + firstDrawn - from) ?: return true
    firstDrawn = projection.index
    startPlace = projection.place
    val end = points.last()
    return projection.index == points.size - 2 &&
      GeoMath.distance(projection.place.lat, projection.place.lon, end.lat, end.lon) < ROUTE_END_METERS
  }

  /** Копия без пройденной части: линия начинается с места, до которого дошла навигация.
      Длина и время движка уменьшаются в той же доле, что и линия, шаги сдвигаются.
  */
  fun trimmed(): RouteGeometry {
    val start = startPlace ?: return this
    val whole = lineLength(points.first(), 1)
    val share = if (whole > 0.0) remaining() / whole else 1.0
    val line = listOf(start) + points.subList(firstDrawn + 1, points.size)
    val shifted = steps.filter { it.index > firstDrawn }.map { RouteStep(it.index - firstDrawn, it.maneuver, it.description) }
    return RouteGeometry(start, to, transport, line, meters * share, seconds * share, shifted, virtual).also { it.state = state }
  }

  /** Длина линии от места start через точки начиная с индекса from */
  private fun lineLength(start: GeoPoint, from: Int): Double {
    var total = 0.0
    var previous = start
    for (index in from until points.size) {
      val place = points[index]
      total += GeoMath.distance(previous.lat, previous.lon, place.lat, place.lon)
      previous = place
    }
    return total
  }

  /**
  * Length in meters of the part still to travel.
  * @return The length left, which is the whole length while nothing was travelled.
  */
  fun remaining(): Double {
    val start = startPlace ?: return meters
    return lineLength(start, firstDrawn + 1)
  }
}

/**
* Line width in dp of a route line, the same for both states.
* @return Line width in dp.
*/
fun routeLineWidth() = LINE_WIDTH

/**
* Settings color of a geometry state, used also where no geometry exists.
* @param state Whether the way is the active one of its leg.
* @return The color of the state.
*/
fun routeStateColor(state: RouteGeometryState) = Color(
  when (state) {
    RouteGeometryState.ACTIVE -> Settings.routeActiveColor.value
    RouteGeometryState.INACTIVE -> Settings.routeInactiveColor.value
  }
)
