// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlSerializer
import java.io.File

/** Найденный текст и действие, показывающее его источник в интерфейсе. */
class DataFound(val text: String, @DrawableRes val icon: Int, @StringRes val sourceLabel: Int, val onSelect: () -> Unit)

/** Подсистема, владеющая своими данными: хранит их в своей секции файла и ищет по ним текст.
    Реализуется функционалом приложения - точками, треками, маршрутами, записками, глобальным
    состоянием, - а не типом данных. Все методы имеют заглушки: владелец определяет только то,
    что ему нужно. */
interface IDataOwner {
  /** Имя своей секции в data.xml. Пустое имя - владелец без своей секции, только поиск. */
  val tag: String get() = ""

  /** Записывает свою секцию и копирует свои файлы в dir. Вызывается в потоке ввода-вывода,
      сериализатор уже стоит внутри корневого элемента. */
  fun write(set: DataSet, dir: File, xml: XmlSerializer) {}

  /** Читает свою секцию в накопитель импорта. Парсер стоит на открывающем теге секции,
      ссылки на файлы разрешаются относительно dir. Заглушка поглощает секцию целиком. */
  fun read(parser: XmlPullParser, dir: File, result: LoadedData) { parser.forEachChild {} }

  /** Ищет текст в своих данных. */
  fun search(text: String): List<DataFound> = emptyList()
}

/** Хранитель и регистратор владельцев данных: раздаёт им запись, чтение и поиск.
    Регистрация идёт при запуске, до старта читателей в других потоках. */
object DataOwnerManager {
  private val owners = mutableListOf<IDataOwner>()

  /** Повторная регистрация того же экземпляра пропускается; занятое непустое имя секции
      бросает IllegalArgumentException. */
  fun register(owner: IDataOwner) {
    if (owners.any { it === owner }) return
    require(owner.tag.isEmpty() || owners.none { it.tag == owner.tag }) {
      "Data owner tag is already registered: ${owner.tag}"
    }
    owners.add(owner)
  }

  fun write(set: DataSet, dir: File, xml: XmlSerializer) {
    owners.forEach { it.write(set, dir, xml) }
  }

  /** Отдаёт секцию её владельцу; незнакомую секцию поглощает. */
  fun readSection(tag: String, parser: XmlPullParser, dir: File, result: LoadedData) {
    val owner = owners.firstOrNull { it.tag == tag }
    if (owner != null) owner.read(parser, dir, result) else parser.forEachChild {}
  }

  /** Ищет все варианты запроса у каждого владельца, отбрасывая повторные тексты внутри владельца. */
  fun search(variants: List<String>): List<DataFound> = owners.flatMap { owner ->
    val seen = mutableSetOf<String>()
    variants.flatMap { variant ->
      val matches = owner.search(variant).filter { it.text !in seen }
      seen.addAll(matches.map { it.text })
      matches
    }
  }
}
