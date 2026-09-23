// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import java.util.Locale

/** Builds keyboard-layout and transliteration variants from bundled language tables. */
object TextSearch {
  private class Table(val keyboard: Map<Char, Char>, val translit: Map<Char, String>, val aliases: Map<String, String>)

  private val tables: Map<String, Table> by lazy {
    val entries = mutableMapOf<String, MutableMap<String, String>>()
    var language = ""
    AppSession.app.resources.openRawResource(R.raw.text_search).bufferedReader(Charsets.UTF_8).useLines { lines ->
      lines.map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.forEach { line ->
        if (line.startsWith('[') && line.endsWith(']')) {
          language = line.substring(1, line.lastIndex)
          entries.getOrPut(language) { linkedMapOf() }
        } else if ('=' in line) {
          entries.getValue(language)[line.substringBefore('=')] = line.substringAfter('=')
        }
      }
    }
    entries.mapValues { (_, values) ->
      val source = values["keys_from"].orEmpty()
      val target = values["keys_to"].orEmpty()
      val keyboard = source.zip(target).toMap()
      val translit = values.filterKeys { it.length == 1 }.mapKeys { it.key.single() }
      val aliases = values.filterKeys { it.startsWith("alias.") }.mapKeys { it.key.removePrefix("alias.") }
      Table(keyboard, translit, aliases)
    }
  }

  /** Uses Russian data because GPX filenames must not depend on the device language. */
  val TRANSLIT: Map<Char, String> get() = tables["ru"]?.translit.orEmpty()

  /** Builds normalized, alternate-layout and transliterated search variants. */
  fun variants(text: String): List<String> {
    val original = text.trim().lowercase(Locale.ROOT)
    if (original.length < 2) return emptyList()
    val table = tables[Languages.systemLocale().language] ?: return listOf(original)
    val native = original.any { it in table.translit }
    val keyboard = if (native) table.keyboard.entries.associate { it.value to it.key } else table.keyboard
    val layout = original.map { keyboard[it] ?: it }.joinToString("")
    val transliterated = if (native) {
      original.map { table.translit[it] ?: it.toString() }.joinToString("")
    } else {
      val reverse = linkedMapOf<String, String>()
      table.translit.forEach { (letter, latin) -> if (latin.isNotEmpty()) reverse.putIfAbsent(latin, letter.toString()) }
      reverse.putAll(table.aliases)
      val tokens = reverse.keys.sortedByDescending { it.length }
      buildString {
        var index = 0
        while (index < original.length) {
          val token = tokens.firstOrNull { original.startsWith(it, index) }
          if (token == null) append(original[index++]) else {
            append(reverse.getValue(token))
            index += token.length
          }
        }
      }
    }
    return listOf(original, layout, transliterated).filter { it.isNotBlank() }.distinct()
  }
}
