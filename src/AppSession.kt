// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import java.io.File
import java.lang.ref.WeakReference

// Application context, current activity and the application run: start after launch, full stop on exit.
/**
* Owns application and activity contexts and coordinates starting and ending an application session.
*
* Public and subclass/module-facing members:
* - [app] - Initialized Application instance; accessing it before init is invalid.
* - [activity] - Currently attached activity through a weak reference, or null when none is retained.
* - [context] - Localized activity context when available, otherwise the Application context.
* - [init] - Stores the Application instance used by application-wide services.
* - [attach] - Retains a weak reference to the current activity for localized context and dialogs.
* - [detach] - Clears the activity reference only if it still refers to the given activity.
* - [ensureRunning] - Starts recording and registered modules once per active application session.
* - [exit] - Stops recording, modules and GPS, schedules saving and removes the activity task.
* - [message] - Formats a localized informational message and delegates to the application dialog queue.
* - [toast] - Shows a brief localized gesture hint.
*/
object AppSession {

  private const val KILL_DELAY_MS = 1500L

  /**
  * Initialized Application instance; accessing it before init is invalid.
  * @return Initialized Application instance; accessing it before init is invalid.
  */
  lateinit var app: Application
    private set

  private var activityRef: WeakReference<MainActivity>? = null
  private var running = false

  /**
  * Currently attached activity through a weak reference, or null when none is retained.
  * @return Currently attached activity through a weak reference, or null when none is retained.
  */
  val activity: MainActivity? get() = activityRef?.get()

  // Localized context: the activity one follows the application language.
  /**
  * Localized activity context when available, otherwise the Application context.
  * @return Localized activity context when available, otherwise the Application context.
  */
  val context: Context get() = activity ?: app

  /**
  * Stores the Application instance used by application-wide services.
  * @param application Application instance retained for process-wide services.
  * @return Unit; call once before services access AppSession.app.
  */
  fun init(application: Application) {
    app = application
  }

  /**
  * Retains a weak reference to the current activity for localized context and dialogs.
  * @param activity Activity owning the UI operation or lifecycle attachment.
  * @return Unit; call during activity creation.
  */
  fun attach(activity: MainActivity) {
    activityRef = WeakReference(activity)
  }

  /**
  * Clears the activity reference only if it still refers to the given activity.
  * @param activity Activity owning the UI operation or lifecycle attachment.
  * @return Unit; call during activity destruction.
  */
  fun detach(activity: MainActivity) {
    if (activityRef?.get() === activity) activityRef = null
  }

  // Starts a new track and the modules; after an exit the process may live on, so this runs on every activity creation.
  /**
  * Starts recording and registered modules once per active application session.
  * @return Unit; repeated calls while running are ignored.
  */
  fun ensureRunning() {
    if (running) return
    running = true
    TrackRecorder.start()
    ModuleHost.startAll()
  }

  // Exit: saves the data, stops the track recording and GPS, closes the activity. The process usually lives on;
  // it ends when a new map key has to be taken, after the saving had time to finish.
  /**
  * Stops recording, modules and GPS, schedules saving and removes the activity task.
  * @param activity Activity owning the UI operation or lifecycle attachment.
  * @return Unit; process termination is delayed when a changed MapKit key requires restart.
  */
  fun exit(activity: Activity) {
    TrackRecorder.stop()
    ModuleHost.stopAll()
    GpsService.stop(activity)
    DataStore.saveAfterPending()
    Maps.save()
    running = false
    activity.finishAndRemoveTask()
    if (YandexMapEngine.restartNeeded) Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, KILL_DELAY_MS)
  }

  // Information and errors: a dialog with OK.
  /**
  * Formats a localized informational message and delegates to the application dialog queue.
  * @param text String resource ID formatted with args using the application context.
  * @param args Formatting arguments for the localized message resource.
  * @return Unit; displays a message requiring dismissal.
  */
  fun message(@StringRes text: Int, vararg args: Any) {
    AppDialog.message(context.getString(text, *args))
  }

  // Only a hint to a gesture; messages go to message().
  /**
  * Shows a brief localized gesture hint.
  * @param text String resource ID formatted with args using the application context.
  * @param args Formatting arguments for the localized message resource.
  * @return Unit; ordinary errors should use Notify instead.
  */
  fun toast(@StringRes text: Int, vararg args: Any) {
    Toast.makeText(context, context.getString(text, *args), Toast.LENGTH_SHORT).show()
  }
}

// Data directory: Android/data/<package>, not the official .../files, with a fallback to it.
/**
* Resolves persistent data and temporary sharing directories after initialization.
*
* Public and subclass/module-facing members:
* - [base] - Chosen persistent data root; available after init.
* - [tracks] - Track data directory; its getter creates the directory when needed.
* - [images] - Attached-image directory; its getter creates the directory when needed.
* - [share] - Temporary sharing directory inside the application cache; its getter creates it when needed.
* - [init] - Chooses a writable data root, preferring the parent of external files and falling back to the files directory.
*/
object AppDirs {

  /**
  * Chosen persistent data root; available after init.
  * @return Chosen persistent data root; available after init.
  */
  lateinit var base: File
    private set

  /**
  * Track data directory; its getter creates the directory when needed.
  * @return Track data directory; its getter creates the directory when needed.
  */
  val tracks: File get() = File(base, "tracks").also { it.mkdirs() }
  /**
  * Attached-image directory; its getter creates the directory when needed.
  * @return Attached-image directory; its getter creates the directory when needed.
  */
  val images: File get() = File(base, "images").also { it.mkdirs() }
  /**
  * Temporary sharing directory inside the application cache; its getter creates it when needed.
  * @return Temporary sharing directory inside the application cache; its getter creates it when needed.
  */
  val share: File get() = File(AppSession.app.cacheDir, "share").also { it.mkdirs() }

  /**
  * Chooses a writable data root, preferring the parent of external files and falling back to the files directory.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @return Unit; initializes base after a writeability probe.
  */
  fun init(context: Context) {
    val files = context.getExternalFilesDir(null) ?: context.filesDir
    val preferred = files.parentFile
    base = if (preferred != null && writable(preferred)) {
      preferred
    } else {
      Log.w("xTravel", "Data directory ${preferred?.path} is not writable, using ${files.path}")
      files
    }
  }

  private fun writable(dir: File): Boolean = runCatching {
    dir.mkdirs()
    val probe = File(dir, ".probe")
    probe.writeText("")
    probe.delete()
  }.getOrDefault(false)
}
