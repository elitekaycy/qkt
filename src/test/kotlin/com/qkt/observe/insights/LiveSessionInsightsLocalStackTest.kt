package com.qkt.observe.insights

import com.qkt.app.LiveSession
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Cross-system check against a REAL running qkt-insights collector (not a capture).
 * Skipped unless `INSIGHTS_E2E_URL` is set, e.g.:
 *
 * ```
 * INSIGHTS_E2E_URL=http://localhost:8420/ingest INSIGHTS_E2E_TOKEN=t \
 *   ./gradlew test --tests "*.LiveSessionInsightsLocalStackTest"
 * ```
 *
 * The collector Zod-validates every envelope, so a 2xx here proves the Kotlin wire
 * format matches the TypeScript contract — the assertion a captured stub can't make.
 */
class LiveSessionInsightsLocalStackTest {
    private class BuyOnce : Strategy {
        var bought = false

        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {
            if (!bought) {
                bought = true
                emit(Signal.Buy(tick.symbol, Money.of("1")))
            }
        }
    }

    @Test
    fun `real collector accepts every envelope the session produces`() {
        val url = System.getenv("INSIGHTS_E2E_URL")
        assumeTrue(!url.isNullOrBlank(), "INSIGHTS_E2E_URL not set — skipping local-stack e2e")
        val token = System.getenv("INSIGHTS_E2E_TOKEN") ?: "t"

        val now = Instant.parse("2024-01-15T15:00:00Z")
        val src = InMemoryMarketSource()
        src.seedLive(
            "XAUUSD",
            listOf(
                Tick("XAUUSD", Money.of("2350.5"), now.toEpochMilli()),
                Tick("XAUUSD", Money.of("2351.0"), now.plus(Duration.ofSeconds(1)).toEpochMilli()),
            ),
        )
        val sink =
            InsightsSink(
                url = url!!,
                token = token,
                instanceId = "qkt-local-e2e",
                batchSize = 100,
                flushIntervalMs = 50L,
                queueCapacity = 1000,
            )
        val session =
            LiveSession(
                strategies = listOf("buyonce" to BuyOnce()),
                source = src,
                symbols = listOf("XAUUSD"),
                candleWindow = TimeWindow.ONE_MINUTE,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
                insightsSink = sink,
                insightsEvents = InsightsEventFamily.entries.toSet(),
            )
        val handle = session.start()
        assertThat(handle.awaitTermination(Duration.ofSeconds(5))).isTrue()
        Thread.sleep(500)
        sink.close()

        assertThat(sink.sent.get()).isGreaterThan(0L)
        assertThat(sink.failed.get())
            .withFailMessage(
                "collector rejected %d batch(es) — Kotlin envelopes do not match the contract",
                sink.failed.get(),
            ).isEqualTo(0L)
    }
}
