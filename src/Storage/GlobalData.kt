// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File

/** Секция данных для состояния, которое не принадлежит ни одному объекту и всё же должно
    пережить перезапуск. Его держит только свой формат данных и пишет только полное
    сохранение: экспорт несёт объекты, а не состояние сеанса. */

class GlobalValues(val currentTrackVisible: Boolean)

/** Владелец глобального состояния: пишет и читает секцию global и применяет прочитанное. */
object GlobalData : IDataOwner {

  override val tag = "global"

  /** Набор без глобальных значений не пишет ничего. */
  override fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    val values = set.globals ?: return
    xml.startTag(null, tag)
    xml.startTag(null, "track")
    xml.attribute(null, "visible", values.currentTrackVisible.toString())
    xml.endTag(null, "track")
    xml.endTag(null, tag)
  }

  override fun read(parser: XmlPullParser, dir: File, result: LoadedData) {
    var visible = true
    parser.forEachChild { child ->
      if (child == "track") visible = parser.attr("visible")?.toBooleanStrictOrNull() ?: true
    }
    result.globals = GlobalValues(visible)
  }

  /** Применяет прочитанное, при отсутствии данных оставляет умолчания. Главный поток. */
  fun onLoaded(values: GlobalValues?) {
    TrackRecorder.visible = values?.currentTrackVisible ?: true
  }
}
