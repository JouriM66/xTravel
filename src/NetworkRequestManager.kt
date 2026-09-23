// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// The single place the application talks HTTP from. Providers build the address, the manager does the request.
/**
* Receives the body of a finished network request or the reason it failed.
*
* Public and subclass/module-facing members:
* - [onNetworkResult] - Accepts the body of a successful request.
* - [onNetworkError] - Accepts the reason a request failed.
*/
interface INetworkListener {
  /**
  * Accepts the body of a successful request.
  * @param body Response body decoded as UTF-8 text.
  * @return Unit; called in the main thread.
  */
  fun onNetworkResult(body: String)

  /**
  * Accepts the reason a request failed.
  * @param message Human-readable failure description, already shortened for display.
  * @return Unit; called in the main thread.
  */
  fun onNetworkError(message: String)
}

/**
* Performs HTTP requests, asynchronously through a listener or blocking for callers that already run in a background thread.
*
* Usage: The asynchronous form answers in the main thread; the blocking form throws and must not be called in the main thread.
*
* Public and subclass/module-facing members:
* - [DEFAULT_TIMEOUT_MS] - Connect and read timeout used when the caller names none.
* - [request] - Runs a request in the network thread and answers the listener in the main thread.
* - [fetch] - Performs a request in the calling thread and returns its body.
*/
object NetworkRequestManager {

  /**
  * Connect and read timeout used when the caller names none.
  * @return Timeout in milliseconds.
  */
  const val DEFAULT_TIMEOUT_MS = 15_000

  private const val ERROR_BODY_LIMIT = 200

  private val net = Failures.guarded(Executors.newSingleThreadExecutor { task -> Thread(task, "net") }, FailureSource.BACKGROUND)
  private val main = Handler(Looper.getMainLooper())

  /**
  * Runs a request in the network thread and answers the listener in the main thread.
  * @param url Full address of the request, already encoded.
  * @param listener Receiver of the body or of the failure reason.
  * @param timeoutMs Connect and read timeout in milliseconds. Default: DEFAULT_TIMEOUT_MS.
  * @return Unit; the listener is called exactly once.
  */
  fun request(url: String, listener: INetworkListener, timeoutMs: Int = DEFAULT_TIMEOUT_MS) {
    net.execute {
      val result = runCatching { fetch(url, timeoutMs) }
      main.post {
        result.fold(
          onSuccess = listener::onNetworkResult,
          onFailure = { listener.onNetworkError(it.message ?: it.javaClass.simpleName) }
        )
      }
    }
  }

  // Blocking form for callers the application already moved off the main thread, for example a geocoding engine.
  /**
  * Performs a request in the calling thread and returns its body.
  *
  * Usage: Never call in the main thread; failures are thrown, not reported.
  * @param url Full address of the request, already encoded.
  * @param timeoutMs Connect and read timeout in milliseconds. Default: DEFAULT_TIMEOUT_MS.
  * @return The response body decoded as UTF-8 text.
  */
  fun fetch(url: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
      connection.connectTimeout = timeoutMs
      connection.readTimeout = timeoutMs
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("$code: ${serverMessage(connection)}")
      return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  // FOR LOCAL USE: an error answer is usually JSON with "message"; anything else is shown as it is, shortened.
  private fun serverMessage(connection: HttpURLConnection): String {
    val body = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
    val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
    return message?.ifBlank { null } ?: body.take(ERROR_BODY_LIMIT).ifBlank { connection.responseMessage.orEmpty() }
  }
}
