// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

private val INDICATOR_TRACK = Color(0xFFD0D0D0) // FOR LOCAL USE: the whole scrollable length
private val INDICATOR_THUMB = Color(0xFF6E6E6E) // FOR LOCAL USE: the shown part of it

// The indicator never reacts to touches and never hides, so it is drawn over the content instead of taking layout space.
private fun Modifier.indicator(width: Dp, portion: () -> Pair<Float, Float>?): Modifier = drawWithContent {
  drawContent()
  val (start, length) = portion() ?: return@drawWithContent
  val barWidth = width.toPx()
  val x = size.width - barWidth
  drawRect(INDICATOR_TRACK, Offset(x, 0f), Size(barWidth, size.height))
  drawRect(INDICATOR_THUMB, Offset(x, start * size.height), Size(barWidth, (length * size.height).coerceAtLeast(barWidth)))
}

/** Draws a passive position indicator along the right edge of a scrollable container.
    Usage: Apply to the scrollable composable itself; nothing is drawn while the content fits.
    @param state Scroll state of the container the indicator belongs to.
*/
fun Modifier.scrollIndicator(state: ScrollState, width: Dp = LIST_SCROLLER_WIDTH): Modifier = indicator(width) {
  val total = (state.viewportSize + state.maxValue).toFloat()
  if ( total <= 0 ) return@indicator null
  (state.value / total) to (state.viewportSize / total)
}

/** Draws a passive position indicator along the right edge of a list.
    Usage: Apply to the list composable itself; nothing is drawn while all items fit. Items of different heights give an averaged position.
    @param state List state of the container the indicator belongs to.
*/
fun Modifier.scrollIndicator(state: LazyListState, width: Dp = LIST_SCROLLER_WIDTH): Modifier = indicator(width) {
  val info = state.layoutInfo
  val shown = info.visibleItemsInfo
  if (shown.isEmpty() || info.totalItemsCount == 0) return@indicator null
  val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
  val item = shown.sumOf { it.size }.toFloat() / shown.size + info.mainAxisItemSpacing
  val total = item * info.totalItemsCount
  if (total <= viewport) return@indicator null
  val offset = state.firstVisibleItemIndex * item + state.firstVisibleItemScrollOffset
  ((offset / total).coerceIn(0f, 1f)) to (viewport / total)
}
