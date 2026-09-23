// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallMissed
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.TurnSlightLeft
import androidx.compose.material.icons.outlined.TurnSlightRight
import androidx.compose.material.icons.outlined.UTurnLeft
import androidx.compose.material.icons.outlined.UTurnRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// FOR LOCAL USE: what the top toolbar shows instead of the clock while a route is being travelled.

private val MANEUVER_SIZE = 32.dp
private val DISTANCES_WIDTH = 64.dp

/** Следующий манёвр (по настройке routeShowManeuver), остановка, к которой идём, и справа в две строки
    длина пути до неё и время в пути по скорости навигации.
*/
@Composable
internal fun RouteFollow(modifier: Modifier) {
  val geometry = RouteStore.activeGeometry()
  val target = RouteStore.activeTarget()
  Row(
    modifier.fillMaxHeight().clickable { askRouteStop() },
    verticalAlignment = Alignment.CenterVertically
  ) {
    val maneuver = if (Settings.routeShowManeuver.value) maneuverIcon(geometry) else null
    if (maneuver != null) {
      Box(Modifier.size(MANEUVER_SIZE), contentAlignment = Alignment.Center) {
        Icon(maneuver, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
      }
    }
    Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.Center) {
      Text(
        target?.caption().orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSecondaryContainer
      )
      target?.description?.takeIf { it.isNotEmpty() }?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = INACTIVE_COLOR)
      }
    }
    val meters = RouteStore.distanceToTarget()
    Column(
      modifier = Modifier.width(DISTANCES_WIDTH).padding(horizontal = 4.dp),
      horizontalAlignment = Alignment.End,
      verticalArrangement = Arrangement.Center
    ) {
      Text(
        distanceText(meters),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSecondaryContainer
      )
      Text(
        RouteStore.travelSeconds(meters)?.let { durationText(it) }.orEmpty(),
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        color = INACTIVE_COLOR
      )
    }
  }
}

/** Манёвр первого шага впереди пройденной части; null - сведений о манёврах нет (их даёт только автомобильный
    ответ), и элемент не выводится. FOR LOCAL USE
*/
private fun maneuverIcon(geometry: RouteGeometry?): ImageVector? = when (geometry?.nextStep(geometry.firstDrawn + 1)?.maneuver) {
  null -> null
  RouteManeuver.STRAIGHT -> Icons.Outlined.North
  RouteManeuver.SLIGHT_LEFT -> Icons.Outlined.TurnSlightLeft
  RouteManeuver.SLIGHT_RIGHT -> Icons.Outlined.TurnSlightRight
  RouteManeuver.LEFT, RouteManeuver.HARD_LEFT -> Icons.Outlined.TurnLeft
  RouteManeuver.RIGHT, RouteManeuver.HARD_RIGHT -> Icons.Outlined.TurnRight
  RouteManeuver.FORK_LEFT, RouteManeuver.EXIT_LEFT -> Icons.AutoMirrored.Outlined.CallMissed
  RouteManeuver.FORK_RIGHT, RouteManeuver.EXIT_RIGHT -> Icons.AutoMirrored.Outlined.CallMade
  RouteManeuver.UTURN_LEFT -> Icons.Outlined.UTurnLeft
  RouteManeuver.UTURN_RIGHT -> Icons.Outlined.UTurnRight
  RouteManeuver.ENTER_ROUNDABOUT -> Icons.AutoMirrored.Outlined.RotateRight
  RouteManeuver.LEAVE_ROUNDABOUT -> Icons.AutoMirrored.Outlined.RotateLeft
  RouteManeuver.FINISH -> Icons.Outlined.Flag
  RouteManeuver.WAYPOINT -> Icons.Outlined.Place
  else -> Icons.Outlined.North
}
