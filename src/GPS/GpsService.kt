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
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Maintains foreground GPS collection and its status notification while the activity is closed. */
class GpsService : Service() {

  /** Controls the active service instance and its foreground notification. */
  companion object {
    private const val CHANNEL_ID = "track"
    private const val NOTIFICATION_ID = 1

    // Action of the button of the notification. It is taken in the GPS worker, not in the main thread, so the application
    // can be ended from the notification even when its interface does not answer any more.
    private const val ACTION_EXIT = "com.jm.xtravel.gps.EXIT"

    // How long the ending waits for the main thread and for the writing of the data before the process is taken down anyway.
    private const val MAIN_WAIT_MS = 1500L
    private const val SAVE_WAIT_MS = 3000L

    @Volatile
    private var instance: GpsService? = null

    @Volatile
    private var points = 0

    /** Updates the current track point count and refreshes the foreground notification. */
    fun updatePoints(count: Int) {
      points = count
      refresh()
    }

    /** Rebuilds the active GPS notification after its displayed state changes. */
    fun refresh() {
      val service = instance ?: return
      Failures.guard(FailureSource.GPS) {
        val manager = NotificationManagerCompat.from(service)
        if (manager.areNotificationsEnabled()) {
          manager.notify(NOTIFICATION_ID, service.notification())
        }
      }
    }

    /** Requests the foreground service after GPS permissions and platform restrictions have been satisfied. */
    fun start(context: Context) {
      Failures.guard(FailureSource.GPS) {
        ContextCompat.startForegroundService(context, Intent(context, GpsService::class.java))
      }
    }

    /** Requests shutdown of the foreground service, which stops GPS updates during destruction. */
    fun stop(context: Context) {
      Failures.guard(FailureSource.GPS) { context.stopService(Intent(context, GpsService::class.java)) }
    }
  }

  // The button of the notification arrives here in the GPS worker thread, see the action of the companion.
  private val buttons = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == ACTION_EXIT) exitApplication()
    }
  }

  /** Creates the notification channel, enters foreground mode and starts GPS updates. */
  override fun onCreate() {
    super.onCreate()
    val filter = IntentFilter(ACTION_EXIT)
    ContextCompat.registerReceiver(this, buttons, filter, null, GpsDataManager.handler, ContextCompat.RECEIVER_NOT_EXPORTED)
    val started = Failures.guard(FailureSource.GPS) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.notification_channel),
        NotificationManager.IMPORTANCE_LOW
      )
      getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
      ServiceCompat.startForeground(
        this,
        NOTIFICATION_ID,
        notification(),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
      )
    }
    if (started == null) {
      stopSelf()
      return
    }
    instance = this
    GpsDataManager.startUpdates(this)
  }

  /** Prevents automatic restart after the service is terminated. */
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

  /** Clears the active instance and queues GPS-update shutdown. */
  override fun onDestroy() {
    instance = null
    Failures.guard(FailureSource.GPS) { unregisterReceiver(buttons) }
    GpsDataManager.stopUpdates()
    super.onDestroy()
  }

  // Ending asked for in the notification: the application is closed properly when it still can, and the process is taken down
  // in any case, so a hung interface does not keep the application alive.
  private fun exitApplication() {
    Failures.guard(FailureSource.GPS) { NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID) }
    val closed = CountDownLatch(1)
    Handler(Looper.getMainLooper()).post {
      Failures.guard(FailureSource.GPS) { AppSession.activity?.let(AppSession::exit) }
      closed.countDown()
    }
    closed.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)
    val written = CountDownLatch(1)
    TrackStorage.io.execute { written.countDown() }
    written.await(SAVE_WAIT_MS, TimeUnit.MILLISECONDS)
    Failures.guard(FailureSource.GPS) { stopService(Intent(this, AlertService::class.java)) }
    stopSelf()
    Process.killProcess(Process.myPid())
  }

  // The button of the ending. The icon is drawn only where the system shows it at all: since Android 12 the buttons
  // of a notification carry their text alone.
  private fun action(@androidx.annotation.StringRes label: Int, icon: Int, action: String): NotificationCompat.Action {
    val intent = Intent(action).setPackage(packageName)
    val pending = PendingIntent.getBroadcast(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    return NotificationCompat.Action.Builder(icon, getString(label), pending).build()
  }

  /** Rejects binding because GPS runs as a started foreground service. */
  override fun onBind(intent: Intent?): IBinder? = null

  /** Uses the position indicator icon and applies its color where the system supports notification tinting. */
  private fun notification(): Notification {
    val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(
      this,
      0,
      open,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val indicator = PositionIndicator.current()
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(indicator.notificationIcon)
      .setColor(indicator.color.toArgb())
      .setContentIntent(pending)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentTitle(getString(R.string.notification_title))
      .setContentText(getString(R.string.notification_text, getString(indicator.label), points))
      .addAction(action(R.string.notification_exit, android.R.drawable.ic_lock_power_off, ACTION_EXIT))
      .build()
  }
}
