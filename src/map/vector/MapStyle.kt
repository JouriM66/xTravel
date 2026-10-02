// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import kotlin.math.pow

/** Оформления встроенной карты: таблицы цветов Protomaps в res\ext\raw\map_style_*.txt. Правила отрисовки - в TileRenderer. */
enum class MapStyleKind(@param:RawRes val resource: Int, @param:StringRes val label: Int) {
  LIGHT(R.raw.map_style_light, R.string.map_style_light),
  WHITE(R.raw.map_style_white, R.string.map_style_white),
  GRAYSCALE(R.raw.map_style_grayscale, R.string.map_style_grayscale),
  DARK(R.raw.map_style_dark, R.string.map_style_dark),
  BLACK(R.raw.map_style_black, R.string.map_style_black)
}

/** Цвета оформления по именам Protomaps (earth, water, highway, landcover.forest...), ARGB. */
class MapStyle private constructor(private val colors: Map<String, Int>) {

  /** Цвет по имени; нет имени - запасное имя, нет и его - серый. */
  fun color(name: String, fallback: String? = null): Int = colors[name] ?: fallback?.let { colors[it] } ?: MISSING

  fun has(name: String): Boolean = name in colors

  companion object {
    private const val MISSING = 0xFF808080.toInt()

    fun load(kind: MapStyleKind): MapStyle {
      val colors = HashMap<String, Int>()
      AppSession.app.resources.openRawResource(kind.resource).bufferedReader().useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }.forEach { line ->
          val value = line.substringAfter('=').trim()
          runCatching { colors[line.substringBefore('=').trim()] = android.graphics.Color.parseColor(value) }
        }
      }
      return MapStyle(colors)
    }
  }
}

/** Значение по ступеням масштаба, как interpolate MapLibre: stops - пары масштаб, значение по возрастанию масштаба;
    base 1 - линейно, больше 1 - экспоненциально. Вне ступеней - крайнее значение.
*/
fun styleValue(zoom: Float, base: Float, vararg stops: Float): Float {
  if (zoom <= stops[0]) return stops[1]
  var i = 0
  while (i + 2 < stops.size) {
    val z0 = stops[i]
    val z1 = stops[i + 2]
    if (zoom <= z1) return stops[i + 1] + (stops[i + 3] - stops[i + 1]) * styleFraction(zoom, base, z0, z1)
    i += 2
  }
  return stops[stops.size - 1]
}

/** Доля пути от z0 к z1 для интерполяции MapLibre с основанием base. */
fun styleFraction(zoom: Float, base: Float, z0: Float, z1: Float): Float {
  val t = ((zoom - z0) / (z1 - z0)).coerceIn(0f, 1f)
  if (base == 1f) return t
  val range = z1 - z0
  return ((base.toDouble().pow((t * range).toDouble()) - 1) / (base.toDouble().pow(range.toDouble()) - 1)).toFloat()
}

/** Смесь двух цветов ARGB, t 0..1. */
fun blendColor(from: Int, to: Int, t: Float): Int {
  fun channel(shift: Int): Int {
    val a = (from ushr shift) and 0xFF
    val b = (to ushr shift) and 0xFF
    return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
  }
  return channel(24) or channel(16) or channel(8) or channel(0)
}
