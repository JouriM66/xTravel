// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.Composable

/** Строки редакторов настроек Яндекса. Ключ MapKit один на карту и маршруты: MapKit берёт его один раз за процесс. */
object YandexSettings {

  /** Карта и геокодер: геокодер того же поставщика, свой выбор ему не нужен. */
  @Composable
  fun MapRows() {
    Section(R.string.settings_map)
    KeyRow(R.string.mapkit_key, R.string.mapkit_key_help, Settings.mapkitKey)
    SwitchRow(R.string.map_3d, Settings.map3d)
    Section(R.string.settings_geocoder)
    KeyRow(R.string.geocoder_key, R.string.geocoder_key_help, Settings.geocoderKey)
    NumberRow(R.string.geocoder_results, Settings.geocoderResults, min = 1)
  }

  @Composable
  fun RouteRows() {
    KeyRow(R.string.mapkit_key, R.string.mapkit_key_help, Settings.mapkitKey)
  }
}
