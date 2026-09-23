// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Отдача данных в своём формате обмена: любой набор целиком, с картинками и файлами треков.
    Сам формат - в DataIO.kt, здесь только имя файла и отправка. */
object XTravelShare : IGeoDataShare {

  override val label get() = R.string.share_xtravel
  override val supportedTypes = GeoDataType.entries.toSet()

  override fun checkData(set: DataSet) = set.types().isNotEmpty()

  override fun share(set: DataSet) = GeoDataShareManager.shareFiles(XFileProvider.XTRAVEL_TYPE) { listOf(DataIO.createExportFile(set, fileName())) }

  /** Всегда по версии формата и дню формирования, независимо от содержимого: xTravel-v2-2026-Sep-23 */
  private fun fileName(): String = "xTravel-v$DATA_VERSION-" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MMM-dd", Locale.US))
}
