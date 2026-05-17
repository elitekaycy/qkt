package com.qkt.notify

import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Fires a [NotificationEvent.DailySummary] at the configured UTC tick. The summary is
 * built on demand by [producer] so the data reflects the moment of dispatch.
 *
 * Production callers use [startAtUtc] to align to a `HH:MM UTC` time. Tests can use
 * [start] with an explicit initial delay + period for deterministic timing.
 */
class DailySummaryScheduler(
    private val notifier: Notifier,
    private val producer: () -> NotificationEvent.DailySummary,
    private val periodMs: Long = TimeUnit.DAYS.toMillis(1),
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "qkt-daily-summary").apply { isDaemon = true }
        },
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(DailySummaryScheduler::class.java)

    /** Start firing every [periodMs] beginning [initialDelayMs] from now. */
    fun start(initialDelayMs: Long) {
        executor.scheduleAtFixedRate({
            try {
                notifier.notify(producer())
            } catch (t: Throwable) {
                log.warn("[notify] daily summary fire failed", t)
            }
        }, initialDelayMs, periodMs, TimeUnit.MILLISECONDS)
    }

    /** Convenience: start at the next occurrence of `HH:MM` UTC, then every [periodMs]. */
    fun startAtUtc(hhmm: String) {
        val (h, m) = hhmm.split(":").map { it.toInt() }
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        var target = now.with(LocalTime.of(h, m, 0, 0))
        if (!target.isAfter(now)) target = target.plusDays(1)
        val delay = ChronoUnit.MILLIS.between(now, target)
        start(initialDelayMs = delay)
    }

    override fun close() {
        executor.shutdownNow()
    }
}
