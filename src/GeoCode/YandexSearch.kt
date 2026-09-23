// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import org.json.JSONObject
import java.net.URLEncoder

/** Yandex HTTP geocoding provider. */
object YandexSearchEngine : IGeoSearchEngine, ILicenseInfo {

  private const val API_VERSION = "1.x"
  private val LANGUAGES = setOf("ru_RU", "uk_UA", "be_BY", "en_RU", "en_US", "tr_TR")
  private const val FALLBACK_LANGUAGE = "en_US"

  override val mustBeAsync = true
  override fun byPosition(point: GeoPoint): List<GeoAnswer> = request("${point.lon},${point.lat}")
  override fun byText(text: String): List<GeoAnswer> = request(text)

  override fun licenseInfo(): Array<LicenseInfo> =
    if (Settings.geocoderKey.value.isBlank()) {
      emptyArray()
    } else {
      arrayOf(
        LicenseInfo(
          "Yandex Geocoder HTTP API",
          AppSession.context.getString(R.string.license_yandex_geocoder, API_VERSION)
        )
      )
    }

  private fun request(text: String): List<GeoAnswer> {
    val key = Settings.geocoderKey.value.trim()
    if (key.isEmpty()) {
      Notify.error(R.string.geocoder_key_missing)
      return emptyList()
    }
    val system = Languages.systemLocale()
    val locale = "${system.language}_${system.country.ifEmpty { system.language.uppercase() }}"
    val language = if (locale in LANGUAGES) locale else FALLBACK_LANGUAGE
    return runCatching { query(text, key, language) }.getOrElse {
      Notify.error(R.string.map_data_failed, it.message ?: it.javaClass.simpleName)
      emptyList()
    }
  }

  private fun query(text: String, key: String, language: String): List<GeoAnswer> {
    val results = Settings.geocoderResults.value.coerceAtLeast(1)
    val url = "https://geocode-maps.yandex.ru/$API_VERSION/?apikey=${URLEncoder.encode(key, "UTF-8")}" +
      "&geocode=${URLEncoder.encode(text, "UTF-8")}&format=json&results=$results&lang=$language"
    return parse(JSONObject(NetworkRequestManager.fetch(url)), language)
  }

  private fun parse(json: JSONObject, language: String): List<GeoAnswer> {
    val members = json.getJSONObject("response")
      .getJSONObject("GeoObjectCollection")
      .optJSONArray("featureMember")
      ?: return emptyList()
    return (0 until members.length()).mapNotNull { index ->
      val obj = members.optJSONObject(index)?.optJSONObject("GeoObject") ?: return@mapNotNull null
      val pos = obj.optJSONObject("Point")?.optString("pos")?.trim()?.split(Regex("\\s+"))
      val lon = pos?.getOrNull(0)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
      val lat = pos?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
      val meta = obj.optJSONObject("metaDataProperty")?.optJSONObject("GeocoderMetaData")
      GeoAnswer(
        point = if (lat != null && lon != null) GeoPoint(lat, lon) else null,
        name = obj.textOrNull("name")?.let { StreetPrefixes.strip(it, language.substringBefore('_')) },
        description = meta?.textOrNull("text")
      ).takeIf { it.valid }
    }
  }

  private fun JSONObject.textOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifBlank { null }
}

/** Removes street prefixes from Yandex place names. */
object StreetPrefixes {

  private val byLanguage: Map<String, List<String>> by lazy {
    runCatching {
      AppSession.app.resources.openRawResource(R.raw.street_prefixes).bufferedReader().readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") && '=' in it }
        .associate { line ->
          line.substringBefore('=').trim() to line.substringAfter('=').split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        }
    }.getOrDefault(emptyMap())
  }

  fun strip(name: String, language: String): String {
    val prefix = byLanguage[language].orEmpty().firstOrNull { name.startsWith("$it ", ignoreCase = true) } ?: return name
    return name.substring(prefix.length).trim().ifEmpty { name }
  }
}
