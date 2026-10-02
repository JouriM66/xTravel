// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/** Ответ построителя на запрос пути; оба метода зовутся в главном потоке, ровно один и один раз. */
interface IRouteListener {
  /** Найденные пути в порядке построителя; пустой список - ничего не найдено. */
  fun onRouteReady(geometries: List<RouteGeometry>)
  fun onRouteFailed(message: String)
}

/** Построитель путей между двумя местами. Реализации регистрируются в RouteBuilders. */
interface IRouteBuilder {
  val id: String /** Постоянный идентификатор, хранится в Settings.routeBuilder */
  @get:StringRes val label: Int

  /** Строки полноэкранного редактора настроек (редакторы из SettingsScreen.kt); null - настроек нет. */
  val settings: (@Composable ColumnScope.() -> Unit)? get() = null

  /** Запрос в главном потоке, ответ слушателю тоже в главном.
      false - запрос не отправлен (построитель не настроен или отказал), слушатель не вызывается.
  */
  fun request(from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener): Boolean
}

/** Реестр построителей маршрутов и выбранный пользователем. */
object RouteBuilders {
  private val builders = mutableListOf<IRouteBuilder>()
  private var refusalShown = false

  val all: List<IRouteBuilder> get() = builders

  /** Выбранный в настройках; null - выбранный не зарегистрирован. */
  val current: IRouteBuilder? get() = builders.firstOrNull { it.id == Settings.routeBuilder.value }

  fun register(builder: IRouteBuilder) {
    if (builders.none { it.id == builder.id }) builders += builder
  }

  /** Запрос выбранному построителю. Отказ сообщается модальным окном один раз за запуск приложения. */
  fun request(from: GeoPoint, to: GeoPoint, transport: TransportKind, listener: IRouteListener): Boolean {
    if (current?.request(from, to, transport, listener) == true) return true
    if (!refusalShown) {
      refusalShown = true
      AppSession.message(R.string.route_not_supported)
    }
    return false
  }
}
