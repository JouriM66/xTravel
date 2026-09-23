// русский текст для того чтобы редакторы не путали кодировку
package com.jm.xtravel

const val GPS_MOVE_STOP_DELAY_MS            = 5000L /** Maximum sample age in milliseconds for course-based movement detection. */
const val GPS_DATA_DELAY_TO_OF              = 3000L /** Raw-measurement age in milliseconds after which GPS status becomes WAITING. */
const val GPS_DELAY_TO_SEND_DATA_IF_MISSING = 1300L /** Minimum interval in milliseconds between periodic updates when location callbacks are silent. */

/** Distinguishes unavailable GPS, waiting for fresh samples, and a currently fresh raw position. */
enum class GpsStatus {
  UNAVAILABLE, /** Location service or GPS provider is unavailable. */
  WAITING, /** Provider is available but raw measurements are absent or older than the freshness threshold. */
  OK /** Provider is available and the latest raw measurement is fresh. */
}
