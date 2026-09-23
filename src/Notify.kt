// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes

/**
* Selects toast, modal-dialog or log delivery of ordinary application notifications.
*/
enum class NotifyMode { 
  /** Deliver grouped messages as Android toasts. */
  TOAST, 
  /** Deliver grouped messages in the application dialog queue. */
  DIALOG, 
  /** Write grouped messages to the Android log. */
  LOG }

/**
* Collects notifications from any thread and delivers grouped messages according to Settings.notifyMode.
*
* Public and subclass/module-facing members:
* - [info] - Queues an ordinary informational message, formatting a resource when supplied.
* - [error] - Queues an error message, formatting a resource when supplied.
*/
object Notify {
  // FOR LOCAL USE: messages collected during one display interval.
  private class Message(val text: () -> String, val error: Boolean)

  private val main = Handler(Looper.getMainLooper())
  private val pending = mutableListOf<Message>()
  private const val DELAY_MS = 1000L

  /**
  * Queues an ordinary informational message, formatting a resource when supplied.
  * @param text Text to display, search, parse or share as specified by this operation.
  * @return Unit; safe to call from any thread; delivery follows notifyMode.
  */
  fun info(text: String) = enqueue(Message({ text }, false))
  /**
  * Queues an error message, formatting a resource when supplied.
  * @param text Text to display, search, parse or share as specified by this operation.
  * @return Unit; safe to call from any thread; delivery follows notifyMode.
  */
  fun error(text: String) = enqueue(Message({ text }, true))
  /**
  * Queues an ordinary informational message, formatting a resource when supplied.
  * @param text String resource ID formatted with args using the application context.
  * @param args Formatting arguments for the localized message resource.
  * @return Unit; safe to call from any thread; delivery follows notifyMode.
  */
  fun info(@StringRes text: Int, vararg args: Any) = enqueue(Message({ AppSession.context.getString(text, *args) }, false))
  /**
  * Queues an error message, formatting a resource when supplied.
  * @param text String resource ID formatted with args using the application context.
  * @param args Formatting arguments for the localized message resource.
  * @return Unit; safe to call from any thread; delivery follows notifyMode.
  */
  fun error(@StringRes text: Int, vararg args: Any) = enqueue(Message({ AppSession.context.getString(text, *args) }, true))

  private fun enqueue(message: Message) {
    main.post {
      pending += message
      if (pending.size == 1) main.postDelayed(::flush, DELAY_MS)
    }
  }

  private fun flush() {
    val messages = pending.toList()
    pending.clear()
    val text = messages.joinToString("\n") { it.text() }
    when (Settings.notifyMode.value) {
      NotifyMode.TOAST -> Toast.makeText(AppSession.context, text, Toast.LENGTH_LONG).show()
      NotifyMode.DIALOG -> AppDialog.message(text)
      NotifyMode.LOG -> if (messages.any { it.error }) Log.e("xTravel", text) else Log.i("xTravel", text)
    }
  }
}
