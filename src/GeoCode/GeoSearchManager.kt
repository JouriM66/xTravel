// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Geocoding provider response. */
class GeoAnswer(val point: GeoPoint?, val name: String?, val description: String?) {
  val valid: Boolean get() = point != null || !name.isNullOrBlank() || !description.isNullOrBlank()
}

/** Geocoding search provider. */
interface IGeoSearchEngine {
  val mustBeAsync: Boolean
  fun byPosition(point: GeoPoint): List<GeoAnswer>
  fun byText(text: String): List<GeoAnswer>
}

/** Coordinates registered geocoding providers. */
object GeoSearchManager {
  private val engines = CopyOnWriteArrayList<IGeoSearchEngine>()
  private val main = Handler(Looper.getMainLooper())
  private val workers = Executors.newCachedThreadPool { Thread(it, "geosearch") }

  fun register(engine: IGeoSearchEngine) {
    engines.addIfAbsent(engine)
  }

  fun byPosition(point: GeoPoint, onResult: (List<GeoAnswer>) -> Unit) =
    request({ it.byPosition(point) }, onResult)

  fun byText(text: String, onResult: (List<GeoAnswer>) -> Unit) = request({ it.byText(text) }, onResult)

  private fun request(query: (IGeoSearchEngine) -> List<GeoAnswer>, onResult: (List<GeoAnswer>) -> Unit) {
    val registered = engines.toList()
    if (registered.isEmpty()) {
      main.post { onResult(emptyList()) }
      return
    }
    main.post {
      val answers = arrayOfNulls<List<GeoAnswer>>(registered.size)
      var remaining = registered.size
      registered.forEachIndexed { index, engine ->
        val run = Runnable {
          val result = runCatching { query(engine).filter { it.valid } }.getOrElse {
            Notify.error(R.string.map_data_failed, it.message ?: it.javaClass.simpleName)
            emptyList()
          }
          main.post {
            answers[index] = result
            remaining--
            if (remaining == 0) onResult(answers.flatMap { it.orEmpty() })
          }
        }
        if (engine.mustBeAsync) workers.execute(run) else run.run()
      }
    }
  }
}
