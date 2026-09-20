package com.qkt.app

import java.time.Instant

/**
 * #65 — soak coverage for the live engine: the slow resource growth that only a long
 * uptime surfaces, which unit and stress tests miss.
 *
 * Two angles (see `docs/superpowers/specs/2026-06-04-issue65-soak-design.md`):
 *  1. session churn ([LiveSessionSoakSessionChurnTest]) — start/stop many sessions, prove no
 *     thread/executor leak as the daemon cycles strategies over its uptime.
 *  2. sustained ingest ([LiveSessionSoakSustainedIngestTest]) — one non-trading session ingests
 *     millions of ticks, prove the candle ring stays bounded and retained heap plateaus.
 *
 * Both are tagged `soak` so they stay out of default CI (excluded in `build.gradle.kts`). Run via:
 *
 *   ./gradlew test -PincludeTags=soak --tests 'com.qkt.app.LiveSessionSoak*'
 *
 * Scale for a real multi-hour soak with `-Dsoak.ticks=` / `-Dsoak.cycles=`; the defaults
 * keep each test to tens of seconds.
 */
object LiveSessionSoakFixtures {
    val now: Instant = Instant.parse("2024-01-15T15:00:00Z")
    val symbol = "BACKTEST:BTCUSDT"

    inline fun awaitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            if (condition()) return true
            Thread.sleep(2)
        }
        return condition()
    }
}
