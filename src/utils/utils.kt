// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import android.os.Handler
import android.os.Looper
import android.util.Patterns
import android.view.Choreographer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.vector.ImageVector
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** FOR LOCAL USE: icons using the shared delete tint. */
private val DELETE_ICONS_ID_LIST by lazy { listOf(Icons.Outlined.Delete, Icons.Outlined.DeleteSweep, Icons.Outlined.DeleteForever) }

/** Returns the delete tint for destructive icons and the supplied tint for other icons. */
fun iconTint(icon: ImageVector?, default: Color): Color =
  if (icon != null && DELETE_ICONS_ID_LIST.any { it == icon }) DELETE_ICON_COLOR else default

/** Formats a count using compact K, M or G suffixes. */
fun formatCount(count: Int): String = formatCount(count.toLong())

/** Formats a count using compact K, M or G suffixes. */
fun formatCount(count: Long): String = when {
  count < 1_000 -> count.toString()
  count < 1_000_000 -> compact(count / 1_000.0) + "K"
  count < 1_000_000_000 -> compact(count / 1_000_000.0) + "M"
  else -> compact(count / 1_000_000_000.0) + "G"
}

/** Текущие дата и время до секунды для имён файлов: 2026-Oct-01_15-58-02, без недопустимых в именах символов */
fun fileTimeStamp(): String = LocalDateTime.now().format(FILE_TIME_STAMP)

private val FILE_TIME_STAMP = DateTimeFormatter.ofPattern("yyyy-MMM-dd_HH-mm-ss", Locale.US)

/** Имя файла из произвольного текста: недопустимые в именах файлов символы заменяются на "_" */
fun safeFileName(name: String): String = name.trim().replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").ifEmpty { "_" }

/** FOR LOCAL USE: omits the fractional separator when rounding produces a whole number. */
private fun compact(value: Double): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")

/** Расстояние в метрах или километрах, как distanceText, но вне композиции (фоновые потоки, обработчики) */
fun distanceString(meters: Double): String =
  if (meters < 1000) AppSession.context.getString(R.string.scale_m, meters.roundToInt())
  else AppSession.context.getString(R.string.distance_km, compact(meters / 1000))

/** Application version in the form of the name of the package file: name, build number and the kind of the build. */
fun appVersion(): String = "${BuildConfig.VERSION_NAME}.${BuildConfig.VERSION_CODE}-" + if (BuildConfig.DEBUG) "debug" else "release"

/** Текст с найденными веб-ссылками (начинаются с http://, https:// или www.), синими подчёркнутыми.
    С clickable касание ссылки открывает её в браузере, касание мимо ссылки уходит обработчику самого текста.
*/
fun linkedText(text: String, clickable: Boolean): AnnotatedString = buildAnnotatedString {
  append(text)
  val style = SpanStyle(color = LINK_COLOR, textDecoration = TextDecoration.Underline)
  val matcher = Patterns.WEB_URL.matcher(text)
  while (matcher.find()) {
    val found = matcher.group()
    val lower = found.lowercase(Locale.ROOT)
    if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.startsWith("www.")) continue
    val url = if (lower.startsWith("www.")) "https://$found" else found
    if (clickable) addLink(LinkAnnotation.Url(url, TextLinkStyles(style)), matcher.start(), matcher.end())
    else addStyle(style, matcher.start(), matcher.end())
  }
}

/** action в главном потоке после того, как отрисованы frames кадров: так показанное только что окно успевает появиться
    до долгой работы в главном потоке. Звать из главного потока.
*/
fun afterFrames(frames: Int = 2, action: () -> Unit) {
  if (frames <= 0) {
    Handler(Looper.getMainLooper()).post(action)
    return
  }
  Choreographer.getInstance().postFrameCallback { afterFrames(frames - 1, action) }
}
