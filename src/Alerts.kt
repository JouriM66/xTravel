// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

// Alarm plays on the alarm stream (heard in the silent mode), message on the notification stream.
/**
* Chooses the Android audio usage for an alarm or ordinary notification.
* @param usage Android AudioAttributes usage value controlling sound routing.
* @property usage Android AudioAttributes usage value controlling sound routing.
*/
enum class AlertImportance(val usage: Int) {
  
  /** Play on the Android alarm audio usage. */
  ALARM(AudioAttributes.USAGE_ALARM),
  
  /** Play on the Android notification audio usage. */
  MESSAGE(AudioAttributes.USAGE_NOTIFICATION)
}

/**
* Chooses a one-shot or looped alert sound.
*/
enum class AlertRepeat { 
  /** Play the sound once. */
  ONCE, 
  /** Repeat the sound until the alert is disabled. */
  LOOP }

// Sounds chosen in the phone settings.
/**
* Identifies a ringtone category configured in Android system settings.
* @param type Android ringtone category understood by RingtoneManager.
* @property type Android ringtone category understood by RingtoneManager.
*/
enum class SystemSound(val type: Int) {
  
  /** Use the system notification sound. */
  NOTIFICATION(RingtoneManager.TYPE_NOTIFICATION),
  
  /** Use the system alarm sound. */
  ALARM(RingtoneManager.TYPE_ALARM),
  
  /** Use the system ringtone. */
  RINGTONE(RingtoneManager.TYPE_RINGTONE)
}

/**
* Represents either a system-selected sound or a URI-based audio source.
*
* Public and subclass/module-facing members:
* - [System] - Uses the current Android sound for the selected ringtone category.
* - [File] - Uses a file, content or Android resource URI as the audio source.
*/
sealed class AlertSound {
  /**
  * Uses the current Android sound for the selected ringtone category.
  * @param sound System-selected or URI-based sound source.
  * @property sound System-selected or URI-based sound source.
  */
  class System(val sound: SystemSound) : AlertSound()

  // A file on the device or a resource of the application ("android.resource://...").
  /**
  * Uses a file, content or Android resource URI as the audio source.
  * @param uri Content, file or resource URI consumed by the operation.
  * @property uri Content, file or resource URI consumed by the operation.
  */
  class File(val uri: Uri) : AlertSound()
}

/**
* Describes a sound, optional notification text, repeat policy and cancellation callback.
* @param sound System-selected or URI-based sound source.
* @param repeat One-shot or looping playback policy. Default: AlertRepeat.ONCE.
* @param importance Audio-usage category selecting alarm or notification playback. Default: AlertImportance.MESSAGE.
* @param text Text to display, search, parse or share as specified by this operation. Default: null.
* @param onCancel Callback invoked when the owning action or alert is explicitly cancelled. Default: null.
* @property sound System-selected or URI-based sound source.
* @property repeat One-shot or looping playback policy. Default: AlertRepeat.ONCE.
* @property importance Audio-usage category selecting alarm or notification playback. Default: AlertImportance.MESSAGE.
* @property text Text to display, search, parse or share as specified by this operation. Default: null.
* @property onCancel Callback invoked when the owning action or alert is explicitly cancelled. Default: null.
*/
class Alert(
  val sound: AlertSound,
  val repeat: AlertRepeat = AlertRepeat.ONCE,
  val importance: AlertImportance = AlertImportance.MESSAGE,
  val text: String? = null,
  val onCancel: (() -> Unit)? = null
)

// Sounds of the application; all calls on the main thread.
// Without a text: a short sound plays at once, once, unless an alert is playing. With a text: an entry in the notification shade
// with "Disable", kept by AlertService; its sound plays only when nothing plays. A looped alert that comes while a single sound plays
// waits in the queue and starts when that sound ends by itself. Disabling any alert by hand stops the queue that exists then.
/**
* Manages alert notifications and sound playback on the main thread, including queued looping alerts.
*
* Usage: Initialize before use and call on the main thread. Cancelling any alert clears queued loops; lack of foreground-service permission leaves ordinary notifications.
*
* Public and subclass/module-facing members:
* - [ACTION_DISABLE] - Broadcast action identifying an explicit alert-disable request.
* - [ACTION_DELETE] - Broadcast action identifying an alert notification swiped away.
* - [EXTRA_ID] - Intent extra key carrying the alert notification ID.
* - [init] - Stores application context and creates the silent notification channel used for alert entries.
* - [notify] - Submits an alert for immediate short playback or notification-backed playback.
* - [cancel] - Removes an alert notification, updates the foreground anchor and invokes its cancellation callback.
* - [anchor] - Builds the notification used to anchor the alert foreground service.
*/
object Alerts {

  private const val CHANNEL_ID = "alerts"
  private const val FIRST_ID = 1000
  /**
  * Broadcast action identifying an explicit alert-disable request.
  * @return Broadcast action identifying an explicit alert-disable request.
  */
  const val ACTION_DISABLE = "com.jm.xtravel.alert.DISABLE"
  /**
  * Broadcast action identifying an alert notification swiped away.
  * @return Broadcast action identifying an alert notification swiped away.
  */
  const val ACTION_DELETE = "com.jm.xtravel.alert.DELETE"
  /**
  * Intent extra key carrying the alert notification ID.
  * @return Intent extra key carrying the alert notification ID.
  */
  const val EXTRA_ID = "id"

  private class Entry(val id: Int, val alert: Alert)

  private lateinit var app: Context
  private val entries = LinkedHashMap<Int, Entry>()
  private val queue = ArrayDeque<Entry>()
  private var nextId = FIRST_ID
  private var playing: Entry? = null
  private var player: MediaPlayer? = null
  private var shortPlayer: MediaPlayer? = null

  /**
  * Stores application context and creates the silent notification channel used for alert entries.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  */
  fun init(context: Context) {
    app = context.applicationContext
    guard {
      val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_HIGH)
      channel.setSound(null, null)
      app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
  }

  /**
  * Submits an alert for immediate short playback or notification-backed playback.
  *
  * Usage: Textless sounds are one-shot. A looping sound may queue behind a one-shot; other simultaneous sounds do not replace the current one.
  * @param alert Sound, notification and cancellation settings for the submitted alert.
  */
  fun notify(alert: Alert) {
    if (alert.text == null) {
      playShort(alert)
      return
    }
    val entry = Entry(nextId++, alert)
    entries[entry.id] = entry
    guard { NotificationManagerCompat.from(app).notify(entry.id, notification(entry)) }
    AlertService.ensureRunning(app)
    val current = playing
    when {
      current == null -> play(entry)
      current.alert.repeat == AlertRepeat.ONCE && alert.repeat == AlertRepeat.LOOP -> queue.addLast(entry)
    }
  }

  // "Disable" or the entry swiped away.
  /**
  * Removes an alert notification, updates the foreground anchor and invokes its cancellation callback.
  * @param id Notification ID of the alert entry to cancel or reanchor.
  */
  fun cancel(id: Int) {
    queue.clear()
    val entry = entries.remove(id)
    // The service lets the notification go first: a foreground one cannot be cancelled.
    AlertService.onRemoved(id)
    guard { NotificationManagerCompat.from(app).cancel(id) }
    if (entry == null) return
    if (playing === entry) stopPlayback()
    entry.alert.onCancel?.let { guard(it) }
  }

  // The oldest alert: its notification keeps the service in the foreground.
  /**
  * Builds the notification used to anchor the alert foreground service.
  * @return The oldest alert ID and notification, or null when there are no entries.
  */
  fun anchor(): Pair<Int, Notification>? = entries.values.firstOrNull()?.let { it.id to notification(it) }

  private fun play(entry: Entry) {
    stopShort()
    playing = entry
    player = createPlayer(entry.alert, entry.alert.repeat == AlertRepeat.LOOP) { onCompleted(entry) }
    if (player == null) playing = null
  }

  // A single sound ended by itself: the next looped alert of the queue starts.
  private fun onCompleted(entry: Entry) {
    if (playing !== entry) return
    stopPlayback()
    while (queue.isNotEmpty()) {
      val next = queue.removeFirst()
      if (next.id in entries) {
        play(next)
        return
      }
    }
  }

  private fun stopPlayback() {
    player?.let { runCatching { it.stop() }; it.release() }
    player = null
    playing = null
  }

  private fun playShort(alert: Alert) {
    if (playing != null) return
    stopShort()
    shortPlayer = createPlayer(alert, looping = false) { stopShort() }
  }

  private fun stopShort() {
    shortPlayer?.let { runCatching { it.stop() }; it.release() }
    shortPlayer = null
  }

  private fun createPlayer(alert: Alert, looping: Boolean, onDone: () -> Unit): MediaPlayer? = Failures.guard(FailureSource.BACKGROUND) {
    MediaPlayer().apply {
      setAudioAttributes(AudioAttributes.Builder().setUsage(alert.importance.usage).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
      setDataSource(app, uriOf(alert.sound))
      isLooping = looping
      setOnPreparedListener { it.start() }
      setOnCompletionListener { onDone() }
      setOnErrorListener { _, _, _ ->
        onDone()
        true
      }
      prepareAsync()
    }
  }

  private fun uriOf(sound: AlertSound): Uri = when (sound) {
    is AlertSound.System -> RingtoneManager.getDefaultUri(sound.sound.type)
    is AlertSound.File -> sound.uri
  }

  private fun notification(entry: Entry): Notification {
    val context = AppSession.context
    val text = entry.alert.text.orEmpty()
    val open = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    return NotificationCompat.Builder(app, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_popup_reminder)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(if (entry.alert.importance == AlertImportance.ALARM) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
      .setSilent(true)
      .setContentIntent(PendingIntent.getActivity(app, entry.id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
      .setDeleteIntent(broadcast(ACTION_DELETE, entry.id))
      .addAction(0, context.getString(R.string.disable), broadcast(ACTION_DISABLE, entry.id))
      .build()
  }

  private fun broadcast(action: String, id: Int): PendingIntent {
    val intent = Intent(app, AlertReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id)
    val request = id * 2 + if (action == ACTION_DELETE) 1 else 0
    return PendingIntent.getBroadcast(app, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
  }

  private fun guard(block: () -> Unit) {
    Failures.guard(FailureSource.BACKGROUND, block)
  }
}

// "Disable" and swiping an alert away.
/**
* Handles explicit dismissal and swipe-away broadcasts from alert notifications.
*
* Public and subclass/module-facing members:
* - [onReceive] - Extracts an alert ID from a notification broadcast and cancels that alert.
*/
class AlertReceiver : BroadcastReceiver() {
  /**
  * Extracts an alert ID from a notification broadcast and cancels that alert.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @param intent Android intent carrying startup, import or notification information.
  */
  override fun onReceive(context: Context, intent: Intent) {
    val id = intent.getIntExtra(Alerts.EXTRA_ID, -1)
    if (id >= 0) Alerts.cancel(id)
  }
}

// Keeps the process in the foreground while alerts are in the shade: bound to the notification of the oldest alert.
// When the service cannot start (Android 12+ forbids it from the background), the alerts stay plain notifications.
/**
* Keeps alert playback in the foreground using the oldest remaining alert notification.
*
* Public and subclass/module-facing members:
* - [Companion] - Shared factory, state and lifecycle operations for AlertService.
* - [onStartCommand] - Anchors the service to a remaining alert or posts a required placeholder before stopping.
* - [onDestroy] - Clears the shared service instance if it points to this service.
* - [onBind] - Rejects bound-service use.
*/
class AlertService : Service() {

  /**
  * Shared factory, state and lifecycle operations for AlertService.
  *
  * Public and subclass/module-facing members:
  * - [ensureRunning] - Requests a foreground service once while no instance or start request exists.
  * - [onRemoved] - Reanchors the running service when its current notification was removed.
  */
  companion object {
    private const val PLACEHOLDER_ID = 999
    private var instance: AlertService? = null
    private var starting = false
    private var anchorId = 0

    /**
    * Requests a foreground service once while no instance or start request exists.
    * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
    */
    fun ensureRunning(context: Context) {
      if (instance != null || starting) return
      starting = Failures.guard(FailureSource.BACKGROUND) {
        ContextCompat.startForegroundService(context, Intent(context, AlertService::class.java))
      } != null
    }

    /**
    * Reanchors the running service when its current notification was removed.
      does nothing for another notification or an absent service.
    * @param id Notification ID of the alert entry to cancel or reanchor.
    */
    fun onRemoved(id: Int) {
      val service = instance ?: return
      if (id == anchorId) service.anchor()
    }
  }

  /**
  * Anchors the service to a remaining alert or posts a required placeholder before stopping.
  * @param intent Android intent carrying startup, import or notification information.
  * @param flags Android service-start flags supplied by the framework.
  * @param startId Android identifier for this service start request.
  * @return START_NOT_STICKY; the service is not automatically recreated for a lost request.
  */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    starting = false
    instance = this
    if (Alerts.anchor() == null) {
      // A started foreground service must call startForeground even when the alerts are gone already.
      Failures.guard(FailureSource.BACKGROUND) { ServiceCompat.startForeground(this, PLACEHOLDER_ID, placeholder(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) }
      finish(ServiceCompat.STOP_FOREGROUND_REMOVE)
    } else {
      anchor()
    }
    return START_NOT_STICKY
  }

  /** Clears the shared service instance if it points to this service. */
  override fun onDestroy() {
    if (instance === this) instance = null
    super.onDestroy()
  }

  /** Rejects bound-service use.
      this is a started foreground service.
  */
  override fun onBind(intent: Intent?): IBinder? = null

  private fun anchor() {
    val (id, notification) = Alerts.anchor() ?: return finish(ServiceCompat.STOP_FOREGROUND_DETACH)
    val bound = Failures.guard(FailureSource.BACKGROUND) {
      ServiceCompat.startForeground(this, id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    } != null
    if (bound) anchorId = id else finish(ServiceCompat.STOP_FOREGROUND_DETACH)
  }

  private fun finish(mode: Int) {
    runCatching { ServiceCompat.stopForeground(this, mode) }
    anchorId = 0
    instance = null
    stopSelf()
  }

  private fun placeholder(): Notification = NotificationCompat.Builder(this, "alerts")
    .setSmallIcon(android.R.drawable.ic_popup_reminder)
    .setSilent(true)
    .build()
}
