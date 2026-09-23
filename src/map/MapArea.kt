// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

private const val ZOOM_SLOP = 0.05f
private const val ROTATION_SLOP_DEGREES = 10f
private const val DOUBLE_TAP_DISTANCE_DP = 48
private const val MIN_FLING_VELOCITY_DP = 300f
private const val STOP_FLING_VELOCITY_DP = 20f
private const val FLING_FRICTION = 4f
private const val ZOOM_ANIMATION_MS = 250f

// Map background of the current engine, application modules above it and the common gesture handling.
/**
* Combines the map background, custom layers, gesture handling and map popup host.
* @param modifier Compose layout and drawing modifier applied to the emitted host. Default: Modifier.
* @return Unit; emits the interactive map area.
*/
@Composable
fun MapArea(modifier: Modifier = Modifier) {
  val density = LocalDensity.current.density
  val textMeasurer = rememberTextMeasurer()
  val scope = rememberCoroutineScope()
  val haptic = LocalHapticFeedback.current
  val gestures = remember { MapGestures(scope) }
  gestures.haptic = haptic
  val texts = MapTexts(
    meters = stringResource(R.string.scale_m),
    kilometers = stringResource(R.string.scale_km),
    minutes = stringResource(R.string.time_m),
    hoursMinutes = stringResource(R.string.time_h_m),
    daysHours = stringResource(R.string.time_d_h)
  )
  // Icons of the points are turned into painters here: the layer draws them outside composition.
  PointIcons.Prepare()
  Box(modifier.clipToBounds()) {
    val engine = Maps.engine
    key(engine) { engine.Backdrop(Modifier.fillMaxSize()) }
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { with(gestures) { handle() } }) {
      ModuleHost.observeRedraw()
      val viewport = MapViewport(size.width, size.height, Maps.camera, density, engine.projection, engine.calibration(Maps.camera))
      Maps.lastViewport = viewport
      val context = MapDrawContext(this, viewport, textMeasurer, texts)
      AppModules.all.forEach { it.draw(context) }
    }
    MapPopup.Host()
  }
}

// Tap on the map: closes the open sheet; otherwise an own object gets selected(), or the place menu is shown.
/**
* Routes map taps to drawn objects, map-provider actions or the empty-place menu.
*
* Public and subclass/module-facing members:
* - [onTap] - Dispatches a tap to selectable overlays, a map-provider action or a menu for an empty place.
* - [showPlace] - Shows the menu of a place given by its coordinates, moving the map to it when it is outside the view.
*/
object MapTap {
  /**
  * Dispatches a tap to selectable overlays, a map-provider action or a menu for an empty place.
  * @param screen Position in the map viewport's screen coordinates, in pixels.
  * @param viewport Coordinate converter for the current camera and canvas.
  * @return Unit; the bottom sheet is left as it is.
  */
  fun onTap(screen: Offset, viewport: MapViewport) {
    if (PointMoveMode.activeId != null) return
    // The sheet stays open while the map is touched; it closes with the hardware back button.
    if (FloatingBar.isOpen) {
      FloatingBar.cancel()
      return
    }
    MapPopup.close()
    if (Maps.engine.handleTap(screen, viewport)) return
    val hit = ModuleHost.hitTest(screen, viewport)
    if (hit != null) {
      hit.selected(screen)
      return
    }
    showPlace(viewport.toGeo(screen), viewport)
  }

  // The place of the menu chosen by hand: the map is moved to it only when it is off the screen.
  /**
  * Shows the menu of a place given by its coordinates, moving the map to it when it is outside the view.
  * @param place Geographic place the menu is about.
  * @param viewport Coordinate converter of the current frame; the last drawn one is used when it is not supplied. Default: null.
  * @return Unit; nothing happens before the first drawn frame.
  */
  fun showPlace(place: GeoPoint, viewport: MapViewport? = null) {
    val view = viewport ?: Maps.lastViewport ?: return
    val at = view.toScreen(place)
    val outside = at.x !in 0f..view.width || at.y !in 0f..view.height
    if (outside) Maps.centerOn(place)
    val info = Maps.engine.getInfoForPoint(place)
    MapPopup.show(if (outside) Offset(view.width / 2, view.height / 2) else at, marker = info.point) { EmptyPlaceMenu(info) }
  }
}

private class MapGestures(private val scope: CoroutineScope) {

  var haptic: HapticFeedback? = null
  private var animation: Job? = null
  private var pendingTap: Job? = null
  private var lastTapTime = 0L
  private var lastTapPosition = Offset.Zero

  suspend fun PointerInputScope.handle() {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      animation?.cancel()
      val width = size.width.toFloat()
      val height = size.height.toFloat()
      fun viewport() = MapViewport(width, height, Maps.camera, density, Maps.engine.projection, Maps.engine.calibration(Maps.camera))

      var dragObject: ISelectableMapObject? = null
      val movingId = PointMoveMode.activeId
      if (movingId != null) {
        val hit = ModuleHost.hitTest(down.position, viewport())
        if (hit != null && hit.dragId == movingId) {
          dragObject = hit
          hit.dragStart()
        }
      }

      val tracker = VelocityTracker()
      tracker.addPosition(down.uptimeMillis, down.position)
      var transforming = false
      var rotating = false
      var longPressed = false
      var slopPan = Offset.Zero
      var slopZoom = 1f
      var slopRotation = 0f
      var maxPointers = 1
      var lastPosition = down.position
      var upTime = down.uptimeMillis
      val longPressTimeout = viewConfiguration.longPressTimeoutMillis

      while (true) {
        val waitLongPress = dragObject == null && !transforming && !longPressed && maxPointers == 1
        val event = if (waitLongPress) {
          val remaining = longPressTimeout - (SystemClock.uptimeMillis() - down.uptimeMillis)
          withTimeoutOrNull(max(1L, remaining)) { awaitPointerEvent() }
        } else {
          awaitPointerEvent()
        }
        if (event == null) {
          longPressed = true
          val hit = ModuleHost.hitTest(down.position, viewport())
          if (hit?.dragId != null) {
            dragObject = hit
            haptic?.performHapticFeedback(HapticFeedbackType.LongPress)
            hit.dragStart()
          }
          continue
        }
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) {
          upTime = event.changes.first().uptimeMillis
          break
        }
        maxPointers = max(maxPointers, pressed.size)
        lastPosition = pressed[0].position

        val target = dragObject
        if (target != null) {
          val position = Offset(lastPosition.x.coerceIn(0f, width), lastPosition.y.coerceIn(0f, height))
          target.dragTo(viewport().toGeo(position))
          event.changes.forEach { if (it.positionChanged()) it.consume() }
          continue
        }
        if (longPressed) continue

        val pan = event.calculatePan()
        val zoomChange = event.calculateZoom()
        val rotation = event.calculateRotation()

        if (!transforming) {
          slopPan += pan
          slopZoom *= zoomChange
          transforming = slopPan.getDistance() > viewConfiguration.touchSlop || abs(1 - slopZoom) > ZOOM_SLOP
        }
        if (transforming && !rotating) {
          slopRotation += rotation
          rotating = abs(slopRotation) > ROTATION_SLOP_DEGREES
        }
        if (transforming) {
          transform(width, height, density, pan, zoomChange, if (rotating) rotation else 0f, panGesture = maxPointers == 1)
          event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
        if (pressed.size == 1) tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position) else tracker.resetTracking()
      }

      dragObject?.let {
        it.dragEnd()
        return@awaitEachGesture
      }
      if (longPressed) return@awaitEachGesture
      if (transforming) {
        if (maxPointers == 1) {
          val velocity = tracker.calculateVelocity()
          fling(width, height, density, Offset(velocity.x, velocity.y))
        }
        return@awaitEachGesture
      }
      if (upTime - down.uptimeMillis >= longPressTimeout) return@awaitEachGesture
      if (maxPointers >= 2) {
        animateZoom(width, height, density, -1f)
        return@awaitEachGesture
      }
      val isDoubleTap = down.uptimeMillis - lastTapTime < viewConfiguration.doubleTapTimeoutMillis &&
        (down.position - lastTapPosition).getDistance() < DOUBLE_TAP_DISTANCE_DP.dp.toPx()
      if (isDoubleTap) {
        pendingTap?.cancel()
        lastTapTime = 0L
        animateZoom(width, height, density, 1f)
      } else {
        lastTapTime = upTime
        lastTapPosition = down.position
        val tapAt = down.position
        val timeout = viewConfiguration.doubleTapTimeoutMillis
        val tapDensity = density
        pendingTap = scope.launch {
          delay(timeout)
          val camera = Maps.camera
          MapTap.onTap(tapAt, MapViewport(width, height, camera, tapDensity, Maps.engine.projection, Maps.engine.calibration(camera)))
        }
      }
    }
  }

  // Zoom and rotation always go around the center of the screen, so the place shown stays where it is and the position
  // mode survives them; only a one-finger drag moves the map. Maps checks that drag when UserActivity resumes.
  private fun transform(
    width: Float, height: Float, density: Float,
    pan: Offset, zoomFactor: Float, rotation: Float, panGesture: Boolean
  ) {
    val camera = Maps.camera
    val projection = Maps.engine.projection
    val anchorScreen = Offset(width / 2, height / 2)
    val shift = if (panGesture) pan else Offset.Zero
    val viewport = MapViewport(width, height, camera, density, projection)
    val anchor = viewport.toWorld(anchorScreen)
    val zoom = (camera.zoom + log2(zoomFactor)).coerceIn(MAP_MIN_ZOOM, MAP_MAX_ZOOM)
    val azimuth = if (Settings.mapDirection.value == MapDirection.NORTH) 0f else normalizeAngle(camera.azimuth - rotation)
    val moved = camera.copy(zoom = zoom, azimuth = azimuth)
    val center = MapViewport(width, height, moved, density, projection).centerFor(anchor, anchorScreen + shift)
    val clamped = WorldPoint(center.x, center.y.coerceIn(0.0, 1.0))
    Maps.moveByGesture(moved.copy(center = projection.toGeo(clamped)), viewport, panGesture)
  }

  private fun fling(width: Float, height: Float, density: Float, velocity: Offset) {
    if (velocity.getDistance() < MIN_FLING_VELOCITY_DP * density) return
    animation = scope.launch {
      var speed = velocity
      var last = withFrameNanos { it }
      while (speed.getDistance() > STOP_FLING_VELOCITY_DP * density) {
        val now = withFrameNanos { it }
        val dt = (now - last) / 1e9f
        last = now
        transform(width, height, density, speed * dt, 1f, 0f, panGesture = true)
        speed *= exp(-FLING_FRICTION * dt)
      }
    }
  }

  private fun animateZoom(width: Float, height: Float, density: Float, delta: Float) {
    animation?.cancel()
    animation = scope.launch {
      val start = withFrameNanos { it }
      var done = 0f
      do {
        val now = withFrameNanos { it }
        val t = ((now - start) / 1e6f / ZOOM_ANIMATION_MS).coerceAtMost(1f)
        val target = delta * t
        transform(width, height, density, Offset.Zero, 2f.pow(target - done), 0f, panGesture = false)
        done = target
      } while (t < 1f)
    }
  }

  private fun normalizeAngle(degrees: Float) = ((degrees % 360f) + 360f) % 360f
}
