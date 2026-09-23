// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

/** "Поделиться" метаинформацией одним вызовом, чтобы её сразу видели в мессенджере: все картинки и все тексты,
    склеенные через перевод строки. У точки первой строкой идёт ссылка на её место в Яндекс Картах, у записок места нет.
    Берёт одну точку или записки.
*/
object TextPhotoShare : IGeoDataShare {

  override val label get() = R.string.share_text_photo
  override val supportedTypes = setOf(GeoDataType.POINTS, GeoDataType.METADATA)
  override val limit get() = 1

  override fun checkData(set: DataSet) = set.points.isNotEmpty() || set.notes?.elements?.isNotEmpty() == true

  override fun count(set: DataSet) = set.points.size + if (set.notes != null) 1 else 0

  override fun share(set: DataSet) {
    val point = set.points.firstOrNull()
    val info = point?.info ?: set.notes ?: return
    val texts = listOfNotNull(point?.let { yandexMapsLink(it.geo) }) + info.elements.filterIsInstance<PointElement.Text>().map { it.text }
    val files = info.pictures.map(PointStore::pictureFile).filter { it.exists() }
    Sharing.share(files, "image/*", texts.joinToString("\n"))
  }
}
