// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import android.util.Log
import java.io.File

enum class GeoDataType { POINTS, ROUTES, TRACKS, METADATA } /** Типы данных набора для "поделиться"; METADATA - записки */

/** Типы данных, которые есть в наборе */
fun DataSet.types(): Set<GeoDataType> = buildSet {
  if (points.isNotEmpty()) add(GeoDataType.POINTS)
  if (routes.isNotEmpty()) add(GeoDataType.ROUTES)
  if (tracks.isNotEmpty()) add(GeoDataType.TRACKS)
  if (notes != null) add(GeoDataType.METADATA)
}

/** Провайдер "поделиться": передаёт набор данных приложения в своём формате - файлами, текстом или ссылкой.
    Набор в формате общего хранилища, любые его части могут отсутствовать. Провайдер сам решает, что создать
    и как отправить; файлы для отправки кладёт в AppDirs.share, каталог очищается при запуске приложения.
    Регистрируется в GeoDataShareManager, порядок регистрации - порядок пунктов меню.
*/
interface IGeoDataShare {
  @get:StringRes val label: Int /** Название пункта меню */
  val supportedTypes: Set<GeoDataType> /** Типы данных, с которыми провайдер работает; по ним фильтруется меню */
  val limit: Int get() = 0 /** Сколько объектов провайдер берёт за раз, 0 - без ограничения */

  @Composable
  fun icon(): Painter = rememberVectorPainter(Icons.Outlined.Share)

  /** true, если в наборе есть хотя бы один элемент, который провайдер умеет передать */
  fun checkData(set: DataSet): Boolean

  /** Сколько объектов набора провайдер передал бы; с ним сравнивается limit */
  fun count(set: DataSet): Int = set.points.size + set.routes.size + set.tracks.size + if (set.notes != null) 1 else 0

  /** Отправка проверенного набора; сверх limit провайдер берёт первые объекты сам */
  fun share(set: DataSet)
}

/** Реестр провайдеров "поделиться". Главный поток. */
object GeoDataShareManager {

  private val providers = mutableListOf<IGeoDataShare>()
  private val main = Handler(Looper.getMainLooper())

  fun register(provider: IGeoDataShare) {
    if (provider !in providers) providers += provider
  }

  /** Провайдеры, которые могут работать с набором */
  fun providers(set: DataSet): List<IGeoDataShare> {
    val types = set.types()
    return providers.filter { provider -> provider.supportedTypes.any { it in types } && provider.checkData(set) }
  }

  /** Передача с проверкой лимита: сверх него спрашивает, отправить ли первые; отмена отменяет отправку */
  fun share(provider: IGeoDataShare, set: DataSet) {
    val limit = provider.limit
    if (limit == 0 || provider.count(set) <= limit) return provider.share(set)
    AppDialog.confirmPlural(R.plurals.share_limit_confirm, limit) { provider.share(set) }
  }

  /** Общий путь файловых провайдеров: make создаёт файлы в потоке "io", отправка - в главном, сбой - сообщением */
  fun shareFiles(mimeType: String, text: String = "", make: () -> List<File>) {
    TrackStorage.io.execute {
      val files = runCatching(make).onFailure { Log.w("xTravel", "Share data not prepared", it) }.getOrNull()
      main.post { if (files.isNullOrEmpty()) Notify.error(R.string.export_failed) else Sharing.share(files, mimeType, text) }
    }
  }

  /** Очистка каталога отправки: при запуске приложения, прежние отправки к этому времени давно прочитаны */
  fun clearFiles() = TrackStorage.io.execute { AppDirs.share.listFiles()?.forEach { it.deleteRecursively() } }
}

/** Подпись пункта провайдера: при лимите и наборе больше одного объекта дописывается "(до Х)". FOR LOCAL USE */
@Composable
private fun providerLabel(provider: IGeoDataShare, set: DataSet): String {
  val label = stringResource(provider.label)
  if (provider.limit == 0 || provider.count(set) <= 1) return label
  return "$label ${stringResource(R.string.share_limit_suffix, provider.limit)}"
}

/** Меню "поделиться" для любого набора: пункты провайдеров, которые могут с ним работать; close зовётся перед передачей */
@Composable
fun ColumnScope.PopupShareMenu(set: DataSet, close: () -> Unit) {
  GeoDataShareManager.providers(set).forEach { provider ->
    MenuItem(provider.icon(), providerLabel(provider, set)) {
      close()
      GeoDataShareManager.share(provider, set)
    }
  }
}

/** Пункт "поделиться" контекстного меню: серый, если провайдеров для набора нет; onOpen показывает PopupShareMenu на месте меню */
@Composable
fun ShareMenuItem(set: DataSet, onOpen: () -> Unit) {
  MenuItem(Icons.Outlined.Share, stringResource(R.string.share), enabled = GeoDataShareManager.providers(set).isNotEmpty(), trailing = SUBMENU_ICON) {
    onOpen()
  }
}

/** Кнопка "поделиться" тулбара: серая, если провайдеров для набора нет, иначе открывает PopupShareMenu.
    onShared зовётся после выбора провайдера, например чтобы снять выбор в списке.
*/
@Composable
fun ShareToolbarItem(onShared: () -> Unit = {}, set: () -> DataSet) {
  var menu by remember { mutableStateOf(false) }
  val data = set()
  Box {
    ToolbarItem(Icons.Outlined.Share, R.string.share, enabled = GeoDataShareManager.providers(data).isNotEmpty()) { menu = true }
    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
      PopupShareMenu(data) {
        menu = false
        onShared()
      }
    }
  }
}
