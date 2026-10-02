// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Полноэкранные модальные окна приложения: стек, виден верхний, "назад" и close снимают его. */
object ModalScreen {

  /** FOR LOCAL USE: окно стека. */
  private class Entry(val ownerId: String, val onClose: (() -> Unit)?, val content: @Composable () -> Unit)

  private val stack = mutableStateListOf<Entry>()

  /** Владелец верхнего окна, null - окон нет. */
  val ownerId: String? get() = stack.lastOrNull()?.ownerId

  /** Новый стек из одного окна. Снятые окна получают onClose.
      onClose зовётся, когда окно закрыто (не когда его перекрыло окно push): его содержимое при этом уже не в композиции.
  */
  fun open(ownerId: String, onClose: (() -> Unit)? = null, content: @Composable () -> Unit) {
    while (stack.isNotEmpty()) close()
    stack += Entry(ownerId, onClose, content)
  }

  /** Окно поверх текущего; его закрытие возвращает к предыдущему. */
  fun push(ownerId: String, onClose: (() -> Unit)? = null, content: @Composable () -> Unit) {
    stack += Entry(ownerId, onClose, content)
  }

  /** Закрывает верхнее окно. */
  fun close() {
    if (stack.isEmpty()) return
    stack.removeAt(stack.lastIndex).onClose?.invoke()
  }

  @Composable
  fun Host() {
    BackHandler(enabled = stack.isNotEmpty()) { close() }
    val top = stack.lastOrNull() ?: return
    Surface(Modifier.fillMaxSize(), color = Color.White) {
      Box(Modifier.systemBarsPadding()) { key(top) { top.content() } }
    }
  }
}
