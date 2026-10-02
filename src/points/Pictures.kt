// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Pictures are kept as given; the list shows a copy decoded to the needed width.
/**
* Caches downsampled image decoding and reads image aspect ratios.
*
* Usage: Decoded images and aspect ratios are keyed by full file path. Decoding belongs on an IO thread; forget evicts all cached paths sharing the requested basename.
*
* Public and subclass/module-facing members:
* - [decode] - Decodes and caches a power-of-two-downsampled image for a requested display size.
* - [aspect] - Reads and caches an image's width-to-height ratio from its header.
* - [forget] - Evicts decoded images and cached aspect ratios for every path with the supplied basename.
*/
object Pictures {

  private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
  }

  // The whole path is the key: pictures of other directories, for example of an import, may have the name of an own one.
  /**
  * Decodes and caches a power-of-two-downsampled image for a requested display size.
  *
  * Usage: Use a positive maxSide and call off the UI thread. Cache identity uses the full file path and requested size.
  * @param file Image to decode; cache identity uses its full path and requested size.
  * @param maxSide Positive requested decode size in pixels; power-of-two sampling can leave the result larger than this target.
  * @return The decoded ImageBitmap, or null for an unreadable image.
  */
  fun decode(file: File, maxSide: Int): ImageBitmap? {
    val key = "${file.path}@$maxSide"
    cache.get(key)?.let { return it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    return bitmap.asImageBitmap().also { cache.put(key, it) }
  }

  /** Размер картинки в файле по заголовку; null - не прочитан */
  fun originalSize(file: File): IntSize? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    return if (bounds.outWidth > 0 && bounds.outHeight > 0) IntSize(bounds.outWidth, bounds.outHeight) else null
  }

  private val aspects = HashMap<String, Float>()

  // Width to height from the file header; 1 when it cannot be read.
  /**
  * Reads and caches an image's width-to-height ratio from its header.
  *
  * Usage: The cache uses the full file path; forget removes matching filename entries from both caches.
  * @param file Image whose encoded width and height are inspected.
  * @return The aspect ratio, or 1 when image bounds cannot be read.
  */
  @Synchronized
  fun aspect(file: File): Float = aspects.getOrPut(file.path) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth > 0 && bounds.outHeight > 0) bounds.outWidth.toFloat() / bounds.outHeight else 1f
  }

  // Everything kept about the file, in whatever directory it was read from.
  /**
  * Evicts decoded images and cached aspect ratios for every path with the supplied basename.
  * @param name Image basename whose bitmap and aspect-ratio cache entries should be evicted across directories.
  * @return Unit; does not delete image files.
  */
  fun forget(name: String) {
    cache.snapshot().keys.filter { File(it.substringBeforeLast('@')).name == name }.forEach(cache::remove)
    forgetAspect(name)
  }

  @Synchronized
  private fun forgetAspect(name: String) {
    aspects.keys.removeAll { File(it).name == name }
  }
}

private fun pictureOf(name: String, dir: File?) = if (dir == null) PointStore.pictureFile(name) else File(dir, name)

// The whole picture in the given size; dir holds the file when it is not one of the application, for example an imported one.
/**
* Loads an image asynchronously and stretches it into the supplied bounds.
*
* Usage: Supply bounds matching the aspect ratio when distortion is undesirable; the composable uses FillBounds.
* @param name Image basename resolved in dir or AppDirs.images.
* @param modifier Compose layout and drawing modifier applied to the emitted host. Default: Modifier.
* @param dir Image directory override; null resolves the filename in AppDirs.images.
* @return Unit; emits the image or an empty placeholder.
*/
@Composable
fun FitPicture(name: String, modifier: Modifier = Modifier, dir: File? = null) {
  BoxWithConstraints(modifier) {
    val side = maxOf(constraints.maxWidth, constraints.maxHeight)
    val image by produceState<ImageBitmap?>(null, name, side) {
      value = withContext(Dispatchers.IO) { Pictures.decode(pictureOf(name, dir), side) }
    }
    val bitmap = image
    if (bitmap == null) {
      Box(Modifier.fillMaxSize().background(Color(0xFFF1EFE8)))
    } else {
      Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
  }
}

// The middle square of the picture, for collages.
/**
* Loads an image asynchronously and center-crops it for a thumbnail.
*
* Usage: Supply square bounds for a square thumbnail.
* @param name Image basename resolved in dir or AppDirs.images.
* @param modifier Compose layout and drawing modifier applied to the emitted host. Default: Modifier.
* @param dir Image directory override; null resolves the filename in AppDirs.images.
* @return Unit; emits the image or placeholder.
*/
@Composable
fun SquareThumb(name: String, modifier: Modifier = Modifier, dir: File? = null) {
  BoxWithConstraints(modifier) {
    val side = constraints.maxWidth * 2
    val image by produceState<ImageBitmap?>(null, name, side) {
      value = withContext(Dispatchers.IO) { Pictures.decode(pictureOf(name, dir), side) }
    }
    val bitmap = image
    if (bitmap == null) {
      Box(Modifier.fillMaxSize().background(Color(0xFFF1EFE8)))
    } else {
      Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
  }
}

/** FOR LOCAL USE: картинка в просмотре и размер её файла; original null - размер не прочитан */
private class ViewedPicture(val image: ImageBitmap, val original: IntSize?)

/** Просмотр во всю отведённую область, картинка за её пределы не рисуется. Наименьший масштаб - картинка вмещена,
    наибольший - 1:1 к пикселям файла (не меньше вмещённого). Сдвиг только в увеличенном виде и не дальше краёв картинки,
    без увеличения свайп уходит листающему пейджеру. Двойное касание: из вмещённого вида - 1:1 вокруг места касания,
    из любого другого - вмещённый.
*/
@Composable
fun PictureViewer(name: String, dir: File? = null) {
  BoxWithConstraints(Modifier.fillMaxSize().clipToBounds().background(Color.Black), contentAlignment = Alignment.Center) {
    val maxSide = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() * 2 }
    val viewed by produceState<ViewedPicture?>(null, name) {
      value = withContext(Dispatchers.IO) {
        val file = pictureOf(name, dir)
        Pictures.decode(file, maxSide)?.let { ViewedPicture(it, Pictures.originalSize(file)) }
      }
    }
    val picture = viewed ?: return@BoxWithConstraints
    val boxWidth = constraints.maxWidth.toFloat()
    val boxHeight = constraints.maxHeight.toFloat()
    val image = picture.image
    // Размер вмещённой картинки на экране.
    val ratio = image.width.toFloat() / image.height
    val fitWidth = minOf(boxWidth, boxHeight * ratio)
    val fitHeight = fitWidth / ratio
    val maxScale = maxOf(1f, (picture.original?.width ?: image.width) / fitWidth)

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun limited(value: Offset, at: Float): Offset {
      val x = maxOf(0f, (fitWidth * at - boxWidth) / 2)
      val y = maxOf(0f, (fitHeight * at - boxHeight) / 2)
      return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    // Масштаб меняется вокруг точки между пальцами.
    val state = rememberTransformableState { centroid, zoom, pan, _ ->
      val newScale = (scale * zoom).coerceIn(1f, maxScale)
      val fromCenter = centroid - Offset(boxWidth / 2, boxHeight / 2)
      offset = limited(fromCenter - (fromCenter - offset) * (newScale / scale) + pan, newScale)
      scale = newScale
    }
    Image(
      image,
      contentDescription = null,
      modifier = Modifier
        .fillMaxSize()
        .pointerInput(maxScale, boxWidth, boxHeight) {
          detectTapGestures(onDoubleTap = { tap ->
            if (scale > 1f) {
              scale = 1f
              offset = Offset.Zero
            } else {
              // Место касания остаётся под пальцем.
              val fromCenter = tap - Offset(boxWidth / 2, boxHeight / 2)
              scale = maxScale
              offset = limited(fromCenter * (1 - maxScale), maxScale)
            }
          })
        }
        .transformable(state, canPan = { scale > 1f })
        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
      contentScale = ContentScale.Fit
    )
  }
}
