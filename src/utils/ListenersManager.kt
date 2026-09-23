// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import java.util.concurrent.CopyOnWriteArrayList

/** Keeps one owner's listeners; copy-on-write permits changes during notification without affecting delivery. */
class ListenersManager<L : Any> {

  private val listeners = CopyOnWriteArrayList<L>()

  val isEmpty: Boolean get() = listeners.isEmpty()

  fun register(listener: L) {
    listeners.addIfAbsent(listener)
  }

  fun unregister(listener: L) {
    listeners.remove(listener)
  }

  /** Invokes listeners on the calling thread. */
  fun notifyEach(action: (L) -> Unit) {
    listeners.forEach(action)
  }
}
