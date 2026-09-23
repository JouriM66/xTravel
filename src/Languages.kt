// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

// Names are always English, whatever the interface language is.
/**
* Pairs a locale tag with its user-facing language name.
* @param tag Language tag supported by AppCompat and the matching app resources.
* @param name English name displayed in the language picker.
* @property tag Language tag supported by AppCompat and the matching app resources.
* @property name English name displayed in the language picker.
*/
class AppLanguage(val tag: String, val name: String)

/**
* Selects the application locale independently of the system locale.
*
* Public and subclass/module-facing members:
* - [all] - Supported explicit language choices: English and Russian; each requires matching resources and locale configuration.
* - [systemLocale] - Reads the device locale independent of the selected application language.
* - [current] - Selected AppCompat application language, falling back to English when unrecognized.
* - [select] - Changes the application locale through AppCompat.
* - [init] - Selects a supported device language on the first launch, otherwise English; preserves an existing app-language selection.
*/
object Languages {

  // Every language here needs res/app/values-<tag> and an entry in res/app/xml/locales_config.xml.
  /**
  * Supported explicit language choices: English and Russian; each requires matching resources and locale configuration.
  * @return Supported explicit language choices: English and Russian; each requires matching resources and locale configuration.
  */
  val all = listOf(
    AppLanguage("en", "English"),
    AppLanguage("ru", "Russian")
  )

  private val default get() = all.first()

  // Phone language, independent of the language selected in the application.
  /**
  * Reads the device locale independent of the selected application language.
  * @return The system locale.
  */
  fun systemLocale(): Locale = Resources.getSystem().configuration.locales[0]

  /**
  * Selected AppCompat application language, falling back to English when unrecognized.
  * @return Selected AppCompat application language, falling back to English when unrecognized.
  */
  val current: AppLanguage
    get() {
      val tag = AppCompatDelegate.getApplicationLocales()[0]?.language
      return all.firstOrNull { it.tag == tag } ?: default
    }

  /**
  * Changes the application locale through AppCompat.
  * @param language Supported application-language choice to apply through AppCompat.
  * @return Unit; language application may recreate activity UI.
  */
  fun select(language: AppLanguage) {
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
  }

  // On the first launch picks the phone language if it is supported, English otherwise.
  /**
  * Selects a supported device language on the first launch, otherwise English; preserves an existing app-language selection.
  * @return Unit; changes locale through AppCompat.
  */
  fun init() {
    if (!AppCompatDelegate.getApplicationLocales().isEmpty) return
    val system = systemLocale().language
    select(all.firstOrNull { it.tag == system } ?: default)
  }
}
