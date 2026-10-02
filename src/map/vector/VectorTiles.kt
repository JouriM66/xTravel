// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Отрисованный тайл сетки приложения и подписи, точки которых лежат в нём. */
class TileImage(val bitmap: Bitmap, val labels: List<MapLabel>)

/** Тайлы встроенной карты: архивы, кэши, фоновый поток отрисовки и вывод на экран.
    Тайлы рисуются в фоне в bitmap (сетка 256 dp, уровень - округлённый масштаб камеры), экран собирается из них;
    пока тайла нет, показывается часть готового предка или потомки. Подписи рисуются поверх, горизонтально.
    Архивы: пользовательский файл (Settings.vectorMapFile) и встроенный assets\world.pmtiles. Где пользовательского
    нет или тайл выходит за его границы, рисуется встроенный, пользовательский - поверх.
*/
class VectorTiles(private val density: Float) : Closeable {

  /** FOR LOCAL USE: архивы, открываются в фоновом потоке. */
  private class Sources(val builtin: PmTiles?, val user: PmTiles?) : Closeable {
    override fun close() {
      runCatching { builtin?.close() }
      runCatching { user?.close() }
    }
  }

  /** FOR LOCAL USE: часть тайла из одного архива; user - из пользовательского. */
  private class Part(val tile: PreparedTile, val user: Boolean)

  /** Меняется после каждого готового тайла: чтение в рисовании перерисовывает экран. */
  var revision by mutableIntStateOf(0)
    private set

  @Volatile var style: MapStyle = MapStyle.load(Settings.vectorMapStyle.value)
    private set

  @Volatile private var language: String = Languages.current.tag
  private val tilePx = ceil(TILE_DP * density).toInt()
  private val main = Handler(Looper.getMainLooper())
  private val renderer = TileRenderer(density)
  private val labels = LabelPainter(density)
  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val dst = RectF()
  private val src = Rect()

  private val images = object : LruCache<Long, TileImage>(cacheBytes()) {
    override fun sizeOf(key: Long, value: TileImage) = value.bitmap.byteCount
  }
  private val prepared = LruCache<String, Any>(PREPARED_TILES)

  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
  private val lock = java.lang.Object() /** Ожидание фонового потока: wait/notifyAll есть только у java.lang.Object */
  private var wanted: List<Long> = emptyList()
  private var generation = 0
  private val failed = HashSet<Long>()
  private var sources: Sources? = null
  @Volatile private var running = true
  private val worker = Thread(::work, "vmap").apply {
    isDaemon = true
    start()
  }

  /** Сменились файл, оформление или язык: всё рисуется заново. */
  fun reset() {
    style = MapStyle.load(Settings.vectorMapStyle.value)
    language = Languages.current.tag
    synchronized(lock) {
      generation++
      failed.clear()
      sources?.close()
      sources = null
      wanted = emptyList()
      lock.notifyAll()
    }
    images.evictAll()
    prepared.evictAll()
    revision++
  }

  override fun close() {
    running = false
    synchronized(lock) { lock.notifyAll() }
  }

  /** Подложка на холсте размера width x height; вызывается при рисовании, главный поток. */
  fun draw(canvas: Canvas, width: Float, height: Float, camera: CameraState) {
    val viewport = MapViewport(width, height, camera, density, WebMercatorProjection)
    val style = style
    canvas.drawColor(style.color("background"))
    val z = camera.zoom.roundToInt().coerceIn(1, MAX_TILE_ZOOM)
    val n = 1 shl z
    val corners = listOf(Offset(0f, 0f), Offset(width, 0f), Offset(0f, height), Offset(width, height)).map(viewport::toWorld)
    val x0 = floor(corners.minOf { it.x } * n).toInt().coerceIn(0, n - 1)
    val x1 = floor(corners.maxOf { it.x } * n).toInt().coerceIn(0, n - 1)
    val y0 = floor(corners.minOf { it.y } * n).toInt().coerceIn(0, n - 1)
    val y1 = floor(corners.maxOf { it.y } * n).toInt().coerceIn(0, n - 1)
    val centerX = viewport.center.x * n
    val centerY = viewport.center.y * n
    val tiles = ArrayList<Long>()
    for (x in x0..x1) for (y in y0..y1) tiles += key(z, x, y)
    tiles.sortBy { val dx = keyX(it) + 0.5 - centerX; val dy = keyY(it) + 0.5 - centerY; dx * dx + dy * dy }

    val size = (viewport.worldPx / n).toFloat()
    val shown = ArrayList<MapLabel>()
    val missing = ArrayList<Long>()
    canvas.save()
    canvas.translate(width / 2, height / 2)
    canvas.rotate(-camera.azimuth)
    tiles.forEach { tile ->
      val left = ((keyX(tile).toDouble() / n - viewport.center.x) * viewport.worldPx).toFloat()
      val top = ((keyY(tile).toDouble() / n - viewport.center.y) * viewport.worldPx).toFloat()
      // Полпикселя нахлёста убирают щели между тайлами при дробном масштабе.
      dst.set(left, top, left + size + SEAM, top + size + SEAM)
      val image = images.get(tile)
      if (image != null) {
        canvas.drawBitmap(image.bitmap, null, dst, bitmapPaint)
        shown += image.labels
      } else {
        missing += tile
        drawStandIn(canvas, tile, shown)
      }
    }
    canvas.restore()
    labels.draw(canvas, viewport, shown, camera.zoom - 1f, style)
    labels.attribution(canvas, width, height, style)
    request(missing)
  }

  // Часть готового предка или готовые потомки на месте тайла, которого ещё нет.
  private fun drawStandIn(canvas: Canvas, tile: Long, shown: MutableList<MapLabel>) {
    val z = keyZ(tile)
    val x = keyX(tile)
    val y = keyY(tile)
    for (depth in 1..min(MAX_STAND_IN_DEPTH, z)) {
      val image = images.get(key(z - depth, x shr depth, y shr depth)) ?: continue
      val k = 1 shl depth
      val cell = image.bitmap.width.toFloat() / k
      val cx = (x - ((x shr depth) shl depth)) * cell
      val cy = (y - ((y shr depth) shl depth)) * cell
      src.set(cx.toInt(), cy.toInt(), ceil(cx + cell).toInt(), ceil(cy + cell).toInt())
      canvas.drawBitmap(image.bitmap, src, dst, bitmapPaint)
      val scale = 1.0 / (1 shl z)
      image.labels.filterTo(shown) { it.wx >= x * scale && it.wx < (x + 1) * scale && it.wy >= y * scale && it.wy < (y + 1) * scale }
      return
    }
    if (z >= MAX_TILE_ZOOM) return
    val half = dst.width() / 2
    val left = dst.left
    val top = dst.top
    for (dx in 0..1) for (dy in 0..1) {
      val image = images.get(key(z + 1, x * 2 + dx, y * 2 + dy)) ?: continue
      val part = RectF(left + dx * half, top + dy * half, left + (dx + 1) * half + SEAM, top + (dy + 1) * half + SEAM)
      canvas.drawBitmap(image.bitmap, null, part, bitmapPaint)
      shown += image.labels
    }
  }

  private fun request(missing: List<Long>) {
    synchronized(lock) {
      if (missing == wanted) return
      wanted = missing
      lock.notifyAll()
    }
  }

  private fun work() {
    while (running) {
      val task = synchronized(lock) {
        val next = wanted.firstOrNull { images.get(it) == null && it !in failed }
        if (next == null) {
          lock.wait()
          null
        } else {
          next to generation
        }
      } ?: continue
      val (tile, taskGeneration) = task
      val image = runCatching { render(tile) }.getOrElse { error ->
        synchronized(lock) { failed += tile }
        Failures.report(FailureSource.BACKGROUND, error)
        null
      }
      synchronized(lock) {
        if (taskGeneration != generation) return@synchronized
        if (image != null) images.put(tile, image)
        wanted = wanted - tile
      }
      main.post { revision++ }
    }
    synchronized(lock) { sources?.close() }
  }

  private fun openSources(): Sources {
    sources?.let { return it }
    val builtin = runCatching { PmTiles(ChannelBytes.asset(ChannelBytes.BUILTIN_MAP)) }
      .onFailure { Notify.error(R.string.vector_builtin_unavailable, it.message ?: it.javaClass.simpleName) }.getOrNull()
    val file = Settings.vectorMapFile.value
    val user = if (file.isEmpty()) null else runCatching { PmTiles(ChannelBytes.uri(Uri.parse(file))) }
      .onFailure { Notify.error(R.string.vector_file_unavailable, it.message ?: it.javaClass.simpleName) }.getOrNull()
    return Sources(builtin, user).also { sources = it }
  }

  private fun render(tile: Long): TileImage {
    val z = keyZ(tile)
    val x = keyX(tile)
    val y = keyY(tile)
    val opened = synchronized(lock) { openSources() }
    val parts = parts(opened, z, x, y)
    val bitmap = Bitmap.createBitmap(tilePx, tilePx, Bitmap.Config.ARGB_8888)
    renderer.render(bitmap, z, x, y, parts.map { it.tile }, style)
    val scale = 1.0 / (1 shl z)
    val user = opened.user
    val tileLabels = parts.flatMap { part ->
      part.tile.labels.filter { label ->
        label.wx >= x * scale && label.wx < (x + 1) * scale && label.wy >= y * scale && label.wy < (y + 1) * scale &&
          (parts.size == 1 || part.user == (user != null && inside(user, label.wx, label.wy)))
      }
    }
    return TileImage(bitmap, tileLabels)
  }

  // Слои тайла: встроенный архив, если пользовательского нет или тайл выходит за его границы, затем пользовательский.
  private fun parts(sources: Sources, z: Int, x: Int, y: Int): List<Part> {
    val level = z - 1
    val result = ArrayList<Part>(2)
    var userCovers = false
    var userPart: Part? = null
    sources.user?.let { user ->
      val scale = 1.0 / (1 shl z)
      val west = x * scale
      val east = (x + 1) * scale
      val north = y * scale
      val south = (y + 1) * scale
      val box = userBox(user)
      if (east > box.left && west < box.right && south > box.top && north < box.bottom) {
        val zu = min(level, user.maxZoom)
        if (zu >= user.minZoom) {
          prepare(user, "u", zu, x shr (z - zu), y shr (z - zu))?.let { userPart = Part(it, true) }
          userCovers = west >= box.left && east <= box.right && north >= box.top && south <= box.bottom
        }
      }
    }
    if (userPart == null || !userCovers) {
      sources.builtin?.let { builtin ->
        val zb = max(min(level, builtin.maxZoom), builtin.minZoom)
        if (zb <= z) prepare(builtin, "b", zb, x shr (z - zb), y shr (z - zb))?.let { result += Part(it, false) }
      }
    }
    userPart?.let { result += it }
    return result
  }

  private fun prepare(archive: PmTiles, tag: String, z: Int, x: Int, y: Int): PreparedTile? {
    val cacheKey = "$tag/$z/$x/$y"
    when (val cached = prepared.get(cacheKey)) {
      is PreparedTile -> return cached
      EMPTY -> return null
    }
    val data = archive.tile(z, x, y)
    val tile = data?.let { PreparedTile.prepare(MvtTile.parse(it), z, x, y, language) }
    prepared.put(cacheKey, tile ?: EMPTY)
    return tile
  }

  // Границы архива в мировых координатах: left/right - x, top/bottom - y.
  private fun userBox(user: PmTiles): RectF {
    val nw = WebMercatorProjection.toWorld(user.maxLat, user.minLon)
    val se = WebMercatorProjection.toWorld(user.minLat, user.maxLon)
    return RectF(nw.x.toFloat(), nw.y.toFloat(), se.x.toFloat(), se.y.toFloat())
  }

  private fun inside(user: PmTiles, wx: Double, wy: Double): Boolean = userBox(user).contains(wx.toFloat(), wy.toFloat())

  private companion object {
    const val TILE_DP = 256f
    const val MAX_TILE_ZOOM = 22
    const val MAX_STAND_IN_DEPTH = 6
    const val PREPARED_TILES = 24
    const val SEAM = 0.5f
    val EMPTY = Any()

    fun cacheBytes(): Int = min(Runtime.getRuntime().maxMemory() / 5, 128L * 1024 * 1024).toInt()

    fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()
    fun keyZ(key: Long): Int = (key ushr 56).toInt()
    fun keyX(key: Long): Int = ((key ushr 28) and 0xFFFFFFF).toInt()
    fun keyY(key: Long): Int = (key and 0xFFFFFFF).toInt()
  }
}
