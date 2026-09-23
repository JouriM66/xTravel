// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

/** Метаинформация: тексты и картинки по порядку. Её держат точка (первый текст служит точке именем) и записки. */
data class MetaInfo(val elements: List<PointElement> = emptyList()) {

  val pictures: List<String> get() = elements.filterIsInstance<PointElement.Picture>().map { it.file } /** Имена файлов картинок */

  /** Первая строка первого текста, null - текста нет или он пуст */
  fun caption(): String? {
    val text = elements.firstOrNull { it is PointElement.Text } as? PointElement.Text ?: return null
    return text.text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
  }

  /** Слияние при импорте: в конец идут тексты, которых нет (по точному совпадению), и все картинки источника.
      Повторы картинок отсекаются раньше, при копировании файлов импорта.
  */
  fun merged(source: MetaInfo): MetaInfo {
    val texts = elements.filterIsInstance<PointElement.Text>().map { it.text }.toMutableSet()
    return MetaInfo(elements + source.elements.filter { it !is PointElement.Text || texts.add(it.text) })
  }
}

/** Владелец метаинформации: точка или записки. Редактор метаинформации работает только через него. */
sealed class InfoOwner {

  abstract val info: MetaInfo? /** null - владельца больше нет */

  abstract val caption: String /** Заголовок редактора */

  abstract fun update(transform: (MetaInfo) -> MetaInfo)

  /** Набор для "поделиться": владелец только с переданными элементами, по умолчанию со всеми */
  abstract fun shareSet(elements: List<PointElement>? = null): DataSet

  data class Point(val id: Long) : InfoOwner() {
    override val info get() = PointStore.find(id)?.info
    override val caption: String get() = AppSession.context.getString(R.string.sheet_point_data, PointStore.find(id)?.fullCaption().orEmpty())
    override fun update(transform: (MetaInfo) -> MetaInfo) = PointStore.update(id) { it.copy(info = transform(it.info)) }
    override fun shareSet(elements: List<PointElement>?): DataSet {
      val point = PointStore.find(id) ?: return DataSet()
      return DataSet(points = listOf(if (elements == null) point else point.copy(info = MetaInfo(elements))))
    }
  }

  object Notes : InfoOwner() {
    override val info get() = NotesStore.notes
    override val caption: String get() = AppSession.context.getString(R.string.sheet_notes)
    override fun update(transform: (MetaInfo) -> MetaInfo) = NotesStore.update(transform)
    override fun shareSet(elements: List<PointElement>?) = DataSet(notes = if (elements == null) NotesStore.notes else MetaInfo(elements))
  }
}
