// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicBoolean

// Something found on the map by a tap.
/**
* Defines selection and optional drag behavior for an object hit on the map.
*
* Public and subclass/module-facing members:
* - [selected] - Handles a map-object selection at a screen coordinate.
* - [dragId] - Stable drag identity, or null when the object cannot be dragged.
* - [dragStart] - Begins the object's drag operation.
* - [dragTo] - Updates a draggable object's geographic position or preview.
* - [dragEnd] - Completes the current drag operation.
*/
interface ISelectableMapObject {
  /**
  * Handles a map-object selection at a screen coordinate.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @return Unit; usually opens the object's menu.
  */
  fun selected(screen: Offset) {}

  // Non-null for objects that can be dragged over the map.
  /**
  * Stable drag identity, or null when the object cannot be dragged.
  * @return Stable drag identity, or null when the object cannot be dragged.
  */
  val dragId: Long? get() = null
  /**
  * Begins the object's drag operation.
  * @return Unit; the default implementation does nothing.
  */
  fun dragStart() {}
  /**
  * Updates a draggable object's geographic position or preview.
  * @param point Geographic position in latitude/longitude degrees.
  * @return Unit; the default implementation does nothing.
  */
  fun dragTo(point: GeoPoint) {}
  /**
  * Completes the current drag operation.
  * @return Unit; the default implementation does nothing.
  */
  fun dragEnd() {}
}

// Localized texts for drawing on the map, format strings with one or two integers.
/**
* Carries localized distance and duration format strings for map drawing.
* @param meters Localized format string for meters.
* @param kilometers Localized format string for kilometers.
* @param minutes Localized format string for a minute duration.
* @param hoursMinutes Localized format string for a duration in hours and minutes.
* @param daysHours Localized format string for a duration in days and hours.
* @property meters Localized format string for meters.
* @property kilometers Localized format string for kilometers.
* @property minutes Localized format string for a minute duration.
* @property hoursMinutes Localized format string for a duration in hours and minutes.
* @property daysHours Localized format string for a duration in days and hours.
*/
class MapTexts(
  val meters: String,
  val kilometers: String,
  val minutes: String,
  val hoursMinutes: String,
  val daysHours: String
)

/**
* Bundles the active drawing scope, viewport, text measurer and localized map strings.
* @param scope Active Compose drawing scope; do not retain beyond the current draw call.
* @param viewport Coordinate converter for the current camera and canvas.
* @param textMeasurer Compose text measurer associated with the current drawing composition.
* @param texts Localized format strings for map labels.
* @property scope Active Compose drawing scope; do not retain beyond the current draw call.
* @property viewport Coordinate converter for the current camera and canvas.
* @property textMeasurer Compose text measurer associated with the current drawing composition.
* @property texts Localized format strings for map labels.
*/
class MapDrawContext(val scope: DrawScope, val viewport: MapViewport, val textMeasurer: TextMeasurer, val texts: MapTexts)

// Element of the application: a service, a map layer or both. Order in AppModules.all is the start order and the drawing order.
// Threads: data written in a background thread and read in draw() is kept in a @Volatile field and replaced as a whole
// (a new list or snapshot is built and assigned), never changed in place; after the change the module calls ModuleHost.requestRedraw().
/**
* Defines lifecycle, drawing and hit-testing hooks for application services and map layers.
*
* Usage: Background state read by draw must be published as a coherent snapshot, not mutated in place; call ModuleHost.requestRedraw after publishing.
*
* Public and subclass/module-facing members:
* - [start] - Starts subscriptions and resources owned by the module.
* - [stop] - Stops subscriptions and resources owned by the module.
* - [draw] - Draws the module's map layer in the supplied frame context.
* - [hitTest] - Finds a selectable object at a screen position.
*/
interface IAppModule {
  /**
  * Starts subscriptions and resources owned by the module.
  * @return Unit; invoked in registry order.
  */
  fun start() {}
  /**
  * Stops subscriptions and resources owned by the module.
  * @return Unit; invoked in reverse registry order.
  */
  fun stop() {}
  /**
  * Draws the module's map layer in the supplied frame context.
  * @param context Current frame's drawing scope, calibrated viewport and localized label formats.
  * @return Unit; publish background data as coherent snapshots before drawing.
  */
  fun draw(context: MapDrawContext) {}
  /**
  * Finds a selectable object at a screen position.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return The hit object or null; the default returns null.
  */
  fun hitTest(screen: Offset, viewport: MapViewport): ISelectableMapObject? = null
}

/**
* Starts and stops registered modules, coalesces redraw requests and resolves hits from topmost layers.
*
* Public and subclass/module-facing members:
* - [requestRedraw] - Coalesces requests into one main-thread observable redraw update.
* - [observeRedraw] - Reads the observable redraw counter so a Compose drawing scope tracks redraw requests.
* - [startAll] - Starts registered modules once in registry order.
* - [stopAll] - Stops running modules in reverse registry order.
* - [hitTest] - Searches modules in reverse drawing order for the first selectable object.
*/
object ModuleHost {

  private val redrawTick = mutableIntStateOf(0)
  private val redrawPending = AtomicBoolean(false)
  private val main = Handler(Looper.getMainLooper())
  private var started = false

  // Any thread; several requests within one frame give one redraw.
  /**
  * Coalesces requests into one main-thread observable redraw update.
  * @return Unit; safe to call from any thread.
  */
  fun requestRedraw() {
    if (redrawPending.compareAndSet(false, true)) {
      main.post {
        redrawPending.set(false)
        redrawTick.intValue++
      }
    }
  }

  // Read by the map canvas, makes it redraw on request.
  /**
  * Reads the observable redraw counter so a Compose drawing scope tracks redraw requests.
  * @return The current redraw counter.
  */
  fun observeRedraw(): Int = redrawTick.intValue

  /**
  * Starts registered modules once in registry order.
  * @return Unit; repeated starts while running are ignored.
  */
  fun startAll() {
    if (started) return
    started = true
    AppModules.all.forEach { it.start() }
  }

  /**
  * Stops running modules in reverse registry order.
  * @return Unit; repeated stops while inactive are ignored.
  */
  fun stopAll() {
    if (!started) return
    started = false
    AppModules.all.asReversed().forEach { it.stop() }
  }

  // Top module first.
  /**
  * Searches modules in reverse drawing order for the first selectable object.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return The topmost hit, or null.
  */
  fun hitTest(screen: Offset, viewport: MapViewport): ISelectableMapObject? =
    AppModules.all.asReversed().firstNotNullOfOrNull { it.hitTest(screen, viewport) }
}

private val TEXT_OUTLINE = android.graphics.Color.WHITE

// Text with a white outline, drawn by android.graphics.Paint: Compose text drawing ignored the color and stroke overrides here.
// alignX, alignY: which part of the text is placed at the anchor, 0 = left/top, 1 = right/bottom.
// Returns the height of the drawn line, 0 when nothing is drawn.
/**
* Draws bold text with a white outline at an anchored screen position.
*
* Usage: Call within an active map drawing context; alignments are fractions from the leading to trailing edge.
* @param text Single label string to draw; an empty string emits no text.
* @param sizeSp Text size in scaled pixels; zero or negative suppresses drawing.
* @param color Color used for the drawn text or indicator.
* @param anchor Screen position in pixels at which the specified text anchor is placed.
* @param alignX Horizontal anchor fraction: 0 for left and 1 for right.
* @param alignY Vertical anchor fraction: 0 for top and 1 for bottom.
* @receiver Current map drawing context.
* @return The text-line height in pixels, or zero for empty text or a nonpositive font size.
*/
fun MapDrawContext.drawOutlinedText(text: String, sizeSp: Int, color: Color, anchor: Offset, alignX: Float, alignY: Float): Float {
  if (sizeSp <= 0 || text.isEmpty()) return 0f
  val fillColor = color.toArgb()
  with(scope) {
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = sizeSp.sp.toPx()
      typeface = Typeface.DEFAULT_BOLD
      this.color = fillColor
    }
    val outline = Paint(fill).apply {
      style = Paint.Style.STROKE
      strokeWidth = 3.dp.toPx()
      strokeJoin = Paint.Join.ROUND
      this.color = TEXT_OUTLINE
    }
    val metrics = fill.fontMetrics
    val width = fill.measureText(text)
    val height = metrics.descent - metrics.ascent
    val x = anchor.x - width * alignX
    val baseline = anchor.y - height * alignY - metrics.ascent
    drawIntoCanvas { canvas ->
      canvas.nativeCanvas.drawText(text, x, baseline, outline)
      canvas.nativeCanvas.drawText(text, x, baseline, fill)
    }
    return height
  }
}
