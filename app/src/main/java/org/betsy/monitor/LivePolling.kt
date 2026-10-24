package org.betsy.monitor

/** Target start-to-start cadence; a slow adapter must not incur another full idle interval. */
object LivePolling {
    const val DEFAULT_INTERVAL_MS = 250L

    fun delayMs(
        intervalMs: Long,
        elapsedMs: Long,
    ): Long = (intervalMs.coerceAtLeast(250) - elapsedMs.coerceAtLeast(0)).coerceAtLeast(25)
}
