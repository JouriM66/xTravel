// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

// Components whose failures are caught; limit: failures of the running copy after which the component is switched off
// (0: at the first one, null: never).
/**
* Identifies a failure domain and the number of tolerated failures before automatic shutdown.
* @param label String resource identifying the displayed label.
* @param limit Number of tolerated failures; shutdown happens when the count exceeds this value, null disables automatic shutdown.
* @property label String resource identifying the displayed label.
* @property limit Number of tolerated failures; shutdown happens when the count exceeds this value, null disables automatic shutdown.
*/
enum class FailureSource(@StringRes val label: Int, val limit: Int?) {
  
  /** Map-provider failures; the first reported failure disables the background. */
  MAP(R.string.failure_map, 0),
  
  /** GPS failures; shutdown occurs after the tolerated failure count is exceeded. */
  GPS(R.string.failure_gps, 3),
  
  /** Background failures are reported without automatic component shutdown. */
  BACKGROUND(R.string.failure_background, null)
}

// Failures of external calls and background work. The first failure of a component is shown in a dialog with a report that can be
// shared; later ones only go to the log of a debug build. A crash nobody caught is written to a file and shown at the next start.
/**
* Reports recoverable failures, disables repeatedly failing components and persists uncaught crash reports.
*
* Public and subclass/module-facing members:
* - [guard] - Runs a block and reports recoverable failures under the specified component.
* - [reset] - Clears a component's failure count and first-report suppression after manual re-enabling.
* - [report] - Counts a failure and schedules its report and any component shutdown on the main thread.
* - [guarded] - Wraps an executor so every submitted task runs inside the chosen failure guard.
* - [installCrashHandler] - Installs an uncaught-exception handler that writes a report before invoking the previous handler.
* - [showLastCrash] - Reads and removes the prior run's crash report and presents it once.
*/
object Failures {

  private const val CRASH_FILE = "last-crash.txt"
  private const val STACK_LINES = 40

  private val main = Handler(Looper.getMainLooper())
  private val counts = ConcurrentHashMap<FailureSource, AtomicInteger>()
  private val shown = ConcurrentHashMap.newKeySet<FailureSource>()

  // Runs the block; on a failure reports it and returns null. Errors of the virtual machine are not caught.
  /**
  * Runs a block and reports recoverable failures under the specified component.
  *
  * Usage: VirtualMachineError is rethrown; a null result does not distinguish a failure from a block returning null.
  * @param T Result type produced by the guarded block.
  * @param source Failure domain whose counter and shutdown policy handle caught exceptions.
  * @param block Operation executed inside the failure guard.
  * @return The block result, or null after a caught failure.
  */
  fun <T> guard(source: FailureSource, block: () -> T): T? = try {
    block()
  } catch (e: VirtualMachineError) {
    throw e
  } catch (e: Throwable) {
    report(source, e)
    null
  }

  // The component was switched on by hand: its failures are counted and shown anew.
  /**
  * Clears a component's failure count and first-report suppression after manual re-enabling.
  * @param source Failure domain whose counters and report suppression are cleared.
  * @return Unit; resets only that failure domain.
  */
  fun reset(source: FailureSource) {
    counts.remove(source)
    shown.remove(source)
  }

  /**
  * Counts a failure and schedules its report and any component shutdown on the main thread.
  *
  * Usage: Shutdown occurs when the count exceeds the configured limit; later reports are suppressed for the running instance.
  * @param source Failure domain to count and optionally disable.
  * @param error Failure to report, including its stack trace.
  * @return Unit; can be called from a worker.
  */
  fun report(source: FailureSource, error: Throwable) {
    if (BuildConfig.DEBUG) Log.w("xTravel", "Failure: $source", error)
    val count = counts.getOrPut(source) { AtomicInteger() }.incrementAndGet()
    val text = reportText(source.name, error)
    // Posted: the failure may happen during composition or before the objects it switches are ready.
    main.post {
      val limit = source.limit
      if (limit != null && count > limit) switchOff(source)
      if (shown.add(source)) showReport(R.string.failure_title, AppSession.context.getString(source.label), text)
    }
  }

  private fun switchOff(source: FailureSource) {
    when (source) {
      FailureSource.MAP -> Maps.disableEngine()
      FailureSource.GPS -> if (Settings.useGps.value) Settings.useGps.value = false
      FailureSource.BACKGROUND -> Unit
    }
  }

  // Tasks of an executor, each one guarded.
  /**
  * Wraps an executor so every submitted task runs inside the chosen failure guard.
  * @param executor Executor that actually schedules wrapped background tasks.
  * @param source Failure domain assigned to every task failure.
  * @return An Executor delegating task scheduling to the supplied executor.
  */
  fun guarded(executor: Executor, source: FailureSource) = Executor { task -> executor.execute { guard(source) { task.run() } } }

  // Must be called first in Application.onCreate.
  /**
  * Installs an uncaught-exception handler that writes a report before invoking the previous handler.
  *
  * Usage: Call first during Application.onCreate.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @return Unit; replaces the process-wide handler.
  */
  fun installCrashHandler(context: Context) {
    val file = File(context.filesDir, CRASH_FILE)
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
      runCatching { file.writeText(reportText("thread ${thread.name}", error)) }
      previous?.uncaughtException(thread, error)
    }
  }

  // The crash of the last run, once.
  /**
  * Reads and removes the prior run's crash report and presents it once.
  * @param context Android context used to access the required resources or services; retained contexts are converted to application context where implemented.
  * @return Unit; does nothing if the report cannot be read.
  */
  fun showLastCrash(context: Context) {
    val file = File(context.filesDir, CRASH_FILE)
    val text = runCatching { file.readText() }.getOrNull() ?: return
    file.delete()
    showReport(R.string.crash_title, null, text)
  }

  private fun reportText(where: String, error: Throwable): String = buildString {
    appendLine("xTravel ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Time: ${TrackTime.format(System.currentTimeMillis())}")
    appendLine("Where: $where")
    appendLine()
    append(error.stackTraceToString().lineSequence().take(STACK_LINES).joinToString("\n"))
  }

  private fun showReport(@StringRes title: Int, component: String?, text: String) = AppDialog.show {
    AlertDialog(
      onDismissRequest = AppDialog::close,
      icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null) },
      title = { Text(if (component != null) stringResource(title, component) else stringResource(title)) },
      text = {
        Text(
          text,
          modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
          fontFamily = FontFamily.Monospace,
          fontSize = 11.sp,
          lineHeight = 14.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      },
      confirmButton = {
        DialogButton(Icons.Outlined.Share, R.string.share, primary = true) {
          AppDialog.close()
          Sharing.shareText(text, "xTravel error report")
        }
      },
      dismissButton = { TextButton(onClick = AppDialog::close) { Text(stringResource(R.string.close)) } }
    )
  }
}
