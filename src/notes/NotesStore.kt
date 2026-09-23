// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File

/** Записки путешествия: метаинформация без привязки к месту. Главный поток. */
object NotesStore {

  var notes by mutableStateOf(MetaInfo())
    private set

  fun onLoaded(info: MetaInfo?) {
    notes = info ?: MetaInfo()
  }

  fun update(transform: (MetaInfo) -> MetaInfo) {
    notes = transform(notes)
    DataStore.scheduleSave()
  }

  /** Импортированные записки сливаются со своими по правилам метаинформации */
  fun applyImport(source: MetaInfo) = update { it.merged(source) }
}

/** Владелец записок: секция notes, в ней элементы метаинформации по порядку. */
object NotesData : IDataOwner {

  override val tag = "notes"

  /** Ищет по текстам записок. */
  override fun search(text: String): List<DataFound> = NotesStore.notes.elements.mapNotNull { element ->
    if (element !is PointElement.Text || !element.text.contains(text, ignoreCase = true)) return@mapNotNull null
    DataFound(element.text, R.drawable.ic_notes, R.string.notes) {
      val elements = NotesStore.notes.elements
      val target = elements.indexOfFirst { it === element }.takeIf { it >= 0 } ?: elements.indexOf(element)
      BottomSheet.open("notes", PointInfoEditor(InfoOwner.Notes, target))
    }
  }

  override fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    val notes = set.notes ?: return
    xml.startTag(null, tag)
    PointXml.writeElements(xml, notes.elements)
    PointXml.placePictures(notes.elements, dir)
    xml.endTag(null, tag)
  }

  override fun read(parser: XmlPullParser, dir: File, result: LoadedData) {
    val elements = mutableListOf<PointElement>()
    parser.forEachChild { name -> PointXml.readElement(parser, name, elements) }
    result.notes = MetaInfo(elements)
  }
}
