// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/** Подписи встроенной карты: горизонтальные при любом повороте, без наложений.
    Приоритет как у Protomaps: страны, населённые пункты, области, районы; внутри - sort_key.
    Размеры шрифта - правила places_* из protomaps/basemaps, в dp.
*/
class LabelPainter(private val density: Float) {

  private val text = Paint(Paint.ANTI_ALIAS_FLAG)
  private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
    strokeWidth = 2 * HALO_DP * density
  }
  private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
  private val placed = ArrayList<RectF>()

  /** zoom - масштаб MapLibre (масштаб камеры минус 1). */
  fun draw(canvas: Canvas, viewport: MapViewport, labels: List<MapLabel>, zoom: Float, style: MapStyle) {
    placed.clear()
    val seen = HashSet<String>()
    val ordered = labels.filter { it.minZoom <= zoom + 1 }
      .sortedWith(compareBy({ it.kind.ordinal }, { it.sortKey }, { -it.rank }))
    var count = 0
    for (label in ordered) {
      if (count >= MAX_LABELS) break
      if (!seen.add(label.kind.name + label.text)) continue
      if (place(canvas, viewport, label, zoom, style)) count++
    }
  }

  /** Отметка источника данных в правом нижнем углу: условие лицензии ODbL. */
  fun attribution(canvas: Canvas, width: Float, height: Float, style: MapStyle) {
    setup(ATTRIBUTION_DP, false, style.color("city_label"), style.color("city_label_halo"))
    val margin = 4 * density
    val x = width - text.measureText(ATTRIBUTION) - margin
    val y = height - margin - text.descent()
    canvas.drawText(ATTRIBUTION, x, y, halo)
    canvas.drawText(ATTRIBUTION, x, y, text)
  }

  private fun place(canvas: Canvas, viewport: MapViewport, label: MapLabel, zoom: Float, style: MapStyle): Boolean {
    val short = label.kind == LabelKind.REGION && zoom < 6 && label.shortText != null
    val caption = if (short) label.shortText else label.text
    val sizeDp = sizeDp(label, zoom)
    val (color, haloColor) = colors(label.kind, style)
    setup(sizeDp, label.bold, color, haloColor)
    val unit = if (short) label.unitShortWidth else label.unitWidth
    val width = if (unit >= 0) unit * text.textSize else measure(label, short, caption)
    val point = viewport.toScreen(WorldPoint(label.wx, label.wy))
    if (point.x < -width || point.y < -text.textSize || point.x > viewport.width + width || point.y > viewport.height + text.textSize) {
      return false
    }
    // Населённый пункт мелкого масштаба - точка и подпись над ней, остальное - подпись по центру точки.
    val withDot = label.kind == LabelKind.LOCALITY && zoom < 8
    val ascent = -text.ascent()
    val descent = text.descent()
    val baseline = if (withDot) point.y - DOT_DP * density - 0.3f * text.textSize - descent else point.y + (ascent - descent) / 2
    val padding = PADDING_DP * density
    val rect = RectF(point.x - width / 2 - padding, baseline - ascent - padding, point.x + width / 2 + padding, baseline + descent + padding)
    if (withDot) rect.union(point.x - DOT_DP * density, point.y + DOT_DP * density)
    if (placed.any { RectF.intersects(it, rect) }) return false
    placed += rect
    if (withDot) {
      dot.color = color
      dot.style = if (label.capital) Paint.Style.STROKE else Paint.Style.FILL
      dot.strokeWidth = density
      canvas.drawCircle(point.x, point.y, DOT_DP * density, dot)
    }
    canvas.drawText(caption, point.x - width / 2, baseline, halo)
    canvas.drawText(caption, point.x - width / 2, baseline, text)
    return true
  }

  private fun measure(label: MapLabel, short: Boolean, caption: String): Float {
    val width = text.measureText(caption)
    if (short) label.unitShortWidth = width / text.textSize else label.unitWidth = width / text.textSize
    return width
  }

  private fun setup(sizeDp: Float, bold: Boolean, color: Int, haloColor: Int) {
    val face = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    text.typeface = face
    halo.typeface = face
    text.textSize = sizeDp * density
    halo.textSize = text.textSize
    text.color = color
    halo.color = haloColor
  }

  private fun colors(kind: LabelKind, style: MapStyle): Pair<Int, Int> = when (kind) {
    LabelKind.COUNTRY -> style.color("country_label") to style.color("state_label_halo")
    LabelKind.LOCALITY -> style.color("city_label") to style.color("city_label_halo")
    LabelKind.REGION -> style.color("state_label") to style.color("state_label_halo")
    LabelKind.SUBPLACE -> style.color("subplace_label") to style.color("subplace_label_halo")
  }

  private fun sizeDp(label: MapLabel, zoom: Float): Float {
    val r = label.rank
    fun pick(limit: Int, small: Float, large: Float) = if (r < limit) small else large
    return when (label.kind) {
      LabelKind.COUNTRY -> styleValue(zoom, 1f, 2f, pick(10, 8f, 12f), 6f, pick(8, 10f, 18f), 8f, pick(7, 11f, 20f))
      LabelKind.LOCALITY -> styleValue(
        zoom, 1f,
        2f, pick(13, 8f, 13f), 4f, pick(13, 10f, 15f), 6f, pick(12, 11f, 17f),
        8f, pick(11, 11f, 18f), 10f, pick(9, 12f, 20f), 15f, pick(8, 12f, 22f)
      )
      LabelKind.REGION -> styleValue(zoom, 1f, 3f, 11f, 7f, 16f)
      LabelKind.SUBPLACE -> styleValue(zoom, 1.2f, 11f, 8f, 14f, 14f, 18f, 24f)
    }
  }

  private companion object {
    const val MAX_LABELS = 120
    const val HALO_DP = 1f
    const val DOT_DP = 2.5f
    const val PADDING_DP = 4f
    const val ATTRIBUTION_DP = 9f
    const val ATTRIBUTION = "© OpenStreetMap"
  }
}
