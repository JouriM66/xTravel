// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Directions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.*

val TOOLBAR_HEIGHT = 64.dp /** Shared by application, sheet and floating toolbars. */
val NORTH_ON_COLOR = Color(0xFF378ADD) /** Color of the active north-orientation control. */
const val WAIT_DIM_ALPHA = 0.25f /** Dim phase of the waiting-for-GPS indicator. */

val DEBUG_TIMES = (0 until 24 * 60 step 30).toList() /** Minutes since midnight. */

val TOOLBAR_COLOR = Color(0xFFE4E2DA) /** Also used by adjacent controls. */

val SELECTED_BACKGROUND = Color(0xFFE6F1FB) /** Rows checked in a selection editor. */

val HIGHLIGHT_BACKGROUND = Color(0xFFFFF0C2) /** Navigation or search highlight retained until another row is highlighted. */

val INACTIVE_COLOR = Color(0xFF888780) /** Inactive icons and secondary text. */

val PASSIVE_COLOR = Color(0xFFB4B2A9) /** Sheet handles and point alarm icons without a schedule. */

val LIST_SCROLLER_WIDTH = 5.dp /** Width of the scroll position indicator of the lists shown in the sheet. */

val DELETE_ICON_COLOR = Color(0xFFD32F2F) /** Tint for destructive action icons. */

val LINK_COLOR = Color(0xFF1565C0) /** Ссылки в тексте */

val START_ICON_COLOR = Color(0xFF2E7D32) /** Tint for icons starting something, and for a ready single choice. */

val VARIANTS_ICON_COLOR = Color(0xFF378ADD) /** Tint for icons offering several variants to choose from. */

val ROUTE_WAYS_ICON: ImageVector get() = Icons.Filled.ForkRight /** Иконка геометрии участка в строке остановки маршрута */

val ROUTE_WAY_NONE_COLOR = Color(0xFF888780) /** Иконка геометрии: участок не рассчитан или путь только виртуальный */

val ROUTE_WAY_ONE_COLOR = Color(0xFF66BB6A) /** Иконка геометрии: единственный рассчитанный путь */

val ROUTE_WAY_MANY_COLOR = Color(0xFF378ADD) /** Иконка геометрии: несколько вариантов пути, рядом счётчик */

val SUBMENU_ICON: ImageVector get() = Icons.AutoMirrored.Outlined.KeyboardArrowRight /** Icon used to open a submenu. */

val DELETE_BACKGROUND = Color(0xFFE24B4A) /** Delete action revealed by swiping a row. */

val MAP_ROTATE_AFTER_IDLE_MS = 3000L /** Delay before map rotation resumes after interaction. */

const val MAP_MIN_ZOOM = 1f /** Minimum supported map zoom. */

const val MAP_MAX_ZOOM = 21f /** Maximum supported map zoom. */

const val ROUTE_SPEED_AVERAGING_POINTS = 3 /** Измерения скорости, по которым навигация считает время пути */

const val GPS_COURSE_AVERAGING_POINTS = 3 /** GPS points used to average the course. */
