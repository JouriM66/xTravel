// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

// Subscriber of the system timer. Blinking elements show the highlight on onBlinkOn and the normal look on onBlinkOff;
// once-a-second work goes to onBlinkOn.
/**
* Receives alternating timer phases; use the on phase for approximately once-per-second work.
*
* Public and subclass/module-facing members:
* - [onBlinkOn] - Handles the highlighted timer phase, occurring approximately once per second.
* - [onBlinkOff] - Handles the unhighlighted timer phase between on phases.
*/
interface IBlinkListener {
  /**
  * Handles the highlighted timer phase, occurring approximately once per second.
  * @return Unit; default implementation does nothing.
  */
  fun onBlinkOn() {}
  /**
  * Handles the unhighlighted timer phase between on phases.
  * @return Unit; default implementation does nothing.
  */
  fun onBlinkOff() {}
}

// The only timer of the application: the phases change every TICK_MS. Each subscriber gets the calls on the thread of its handler.
// Without subscribers the timer does not run.
/**
* Shares a 500 ms phase timer and dispatches each listener on its chosen Handler.
*
* Public and subclass/module-facing members:
* - [subscribe] - Registers a listener once and starts the shared phase timer when needed.
* - [unsubscribe] - Removes all registrations for the listener instance.
*/
object TimerManager {

  private const val TICK_MS = 500L

  private class Entry(val listener: IBlinkListener, val handler: Handler)

  private val main = Handler(Looper.getMainLooper())
  private val entries = CopyOnWriteArrayList<Entry>()
  private var running = false
  private var on = false

  private val tick = object : Runnable {
    override fun run() {
      if (entries.isEmpty()) {
        running = false
        return
      }
      on = !on
      val phase = on
      entries.forEach { entry ->
        val call = Runnable { if (phase) entry.listener.onBlinkOn() else entry.listener.onBlinkOff() }
        if (entry.handler.looper === Looper.getMainLooper()) call.run() else entry.handler.post(call)
      }
      main.postDelayed(this, TICK_MS)
    }
  }

  // Any thread.
  /**
  * Registers a listener once and starts the shared phase timer when needed.
  *
  * Usage: May be called from any thread; pair with unsubscribe when the owner stops.
  * @param listener Listener to register or remove; callbacks follow the owning manager's threading contract.
  * @param handler Handler on whose thread timer or sensor callbacks are delivered. Default: main.
  * @return Unit; dispatch uses the provided Handler.
  */
  fun subscribe(listener: IBlinkListener, handler: Handler = main) {
    if (entries.none { it.listener === listener }) entries += Entry(listener, handler)
    main.post {
      if (!running && entries.isNotEmpty()) {
        running = true
        main.postDelayed(tick, TICK_MS)
      }
    }
  }

  /**
  * Removes all registrations for the listener instance.
  * @param listener Listener to register or remove; callbacks follow the owning manager's threading contract.
  * @return Unit; the timer stops on a subsequent tick when no listeners remain.
  */
  fun unsubscribe(listener: IBlinkListener) {
    entries.removeAll { it.listener === listener }
  }
}

// Receives the interactive actions of the user. hold/resume go around long operations (a finger on the screen, a dialog):
// while held, no action counts as idle time; resume starts the idle time anew.
/**
* Receives user activity and nested holds that suspend automatic idle-time actions.
*
* Public and subclass/module-facing members:
* - [touch] - Records a brief user interaction.
* - [hold] - Begins a nested user interaction that suspends idle actions.
* - [resume] - Ends a nested user interaction and restarts idle timing.
*/
interface IUserListener {
  /**
  * Records a brief user interaction.
  * @return Unit; invoked on the main thread.
  */
  fun touch()
  /**
  * Begins a nested user interaction that suspends idle actions.
  * @return Unit; pair with resume.
  */
  fun hold()
  /**
  * Ends a nested user interaction and restarts idle timing.
  * @return Unit; invoked on the main thread.
  */
  fun resume()
}

// Subscribers of the user actions; all calls on the main thread.
/**
* Broadcasts user interaction to registered listeners on the main thread.
*
* Public and subclass/module-facing members:
* - [subscribe] - Adds a user-activity listener if not already registered.
* - [unsubscribe] - Removes a registered user-activity listener.
* - [touch] - Notifies listeners of a brief user interaction.
* - [hold] - Notifies listeners that a sustained interaction begins.
* - [resume] - Notifies listeners that a sustained interaction ends.
*/
object UserActivity {

  private val listeners = CopyOnWriteArrayList<IUserListener>()

  /**
  * Adds a user-activity listener if not already registered.
  * @param listener Listener to register or remove; callbacks follow the owning manager's threading contract.
  * @return Unit; call on the main thread.
  */
  fun subscribe(listener: IUserListener) {
    if (listener !in listeners) listeners += listener
  }

  /**
  * Removes a registered user-activity listener.
  * @param listener Listener to register or remove; callbacks follow the owning manager's threading contract.
  * @return Unit; call on the main thread.
  */
  fun unsubscribe(listener: IUserListener) {
    listeners -= listener
  }

  /**
  * Notifies listeners of a brief user interaction.
  * @return Unit; listeners update their idle origin.
  */
  fun touch() = listeners.forEach { it.touch() }

  /**
  * Notifies listeners that a sustained interaction begins.
  * @return Unit; pair every hold with resume.
  */
  fun hold() = listeners.forEach { it.hold() }

  /**
  * Notifies listeners that a sustained interaction ends.
  * @return Unit; nested holds must be balanced.
  */
  fun resume() = listeners.forEach { it.resume() }
}

// Idle time of the user for the subscribers that act by themselves after a pause; holds may be nested.
/**
* Tracks elapsed idle time with nested holds; subclasses use it to defer automatic actions.
*
* Public and subclass/module-facing members:
* - [idleMs] - Measures idle time using monotonic uptime.
* - [restart] - Resets the idle-time origin to the current monotonic uptime.
* - [touch] - Restarts idle timing after a brief interaction.
* - [hold] - Adds one nested hold that suspends idle timing.
* - [resume] - Releases one hold, never below zero, and restarts idle timing.
*/
abstract class IdleWatcher : IUserListener {

  private var holds = 0
  private var since = SystemClock.uptimeMillis()

  /**
  * Measures idle time using monotonic uptime.
  * @return Elapsed milliseconds, or zero while at least one hold is active.
  */
  fun idleMs(): Long = if (holds > 0) 0L else SystemClock.uptimeMillis() - since

  /**
  * Resets the idle-time origin to the current monotonic uptime.
  * @return Unit; does not change the nested hold count.
  */
  protected fun restart() {
    since = SystemClock.uptimeMillis()
  }

  /**
  * Restarts idle timing after a brief interaction.
  * @return Unit; updates the idle origin.
  */
  override fun touch() = restart()

  /**
  * Adds one nested hold that suspends idle timing.
  * @return Unit; must be balanced by resume.
  */
  override fun hold() {
    holds++
  }

  /**
  * Releases one hold, never below zero, and restarts idle timing.
  * @return Unit; updates the hold count and idle origin.
  */
  override fun resume() {
    holds = (holds - 1).coerceAtLeast(0)
    restart()
  }
}
