// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import java.text.Collator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Geocoding provider response. */
class GeoAnswer(val point: GeoPoint?, val name: String?, val description: String?) {
  val valid: Boolean get() = point != null || !name.isNullOrBlank() || !description.isNullOrBlank()
}

/** Geocoding search provider. */
interface IGeoSearchEngine {
  val mustBeAsync: Boolean
  val configured: Boolean /** Готов к запросам (например, введён ключ); ненастроенный не опрашивается */
  fun byPosition(point: GeoPoint): List<GeoAnswer>
  fun byText(text: String): List<GeoAnswer>
}

/** Опрашивает все настроенные геокодеры и сводит их ответы в один список без повторов текста. */
object GeoSearchManager {
  private val engines = CopyOnWriteArrayList<IGeoSearchEngine>()
  private val main = Handler(Looper.getMainLooper())
  private val workers = Executors.newCachedThreadPool { Thread(it, "geosearch") }

  fun register(engine: IGeoSearchEngine) {
    engines.addIfAbsent(engine)
  }

  /** Порядок ответов сохраняется: первым идёт самый точный ответ первого геокодера. */
  fun byPosition(point: GeoPoint, onResult: (List<GeoAnswer>) -> Unit) =
    request({ it.byPosition(point) }, sorted = false, onResult)

  /** Ответы по алфавиту. */
  fun byText(text: String, onResult: (List<GeoAnswer>) -> Unit) = request({ it.byText(text) }, sorted = true, onResult)

  private fun request(query: (IGeoSearchEngine) -> List<GeoAnswer>, sorted: Boolean, onResult: (List<GeoAnswer>) -> Unit) {
    val registered = engines.filter { it.configured }
    if (registered.isEmpty()) {
      Notify.error(R.string.geocoder_key_missing)
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
            if (remaining == 0) onResult(merge(answers.map { it.orEmpty() }, sorted))
          }
        }
        if (engine.mustBeAsync) workers.execute(run) else run.run()
      }
    }
  }

  /** Повтор - совпадение названия и описания без учёта регистра и лишних пробелов. Ответы без текста не сравниваются. FOR LOCAL USE */
  private fun merge(lists: List<List<GeoAnswer>>, sorted: Boolean): List<GeoAnswer> {
    val seen = HashSet<String>()
    val unique = lists.flatten().filter { answer ->
      val key = listOfNotNull(answer.name, answer.description)
        .joinToString("\n") { it.trim().replace(SPACES, " ").lowercase() }
      key.isBlank() || seen.add(key)
    }
    if (!sorted) return unique
    val collator = Collator.getInstance()
    return unique.sortedWith(compareBy(collator) { it.name ?: it.description ?: "" })
  }

  private val SPACES = Regex("\\s+")
}
