package com.qkt.cli

import com.qkt.broker.mt5.MT5Tick
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5FeedAuditTest {
    @Test
    fun `MT5 input symbol strips a source prefix unless explicitly overridden`() {
        assertThat(Mt5FeedAudit.inputSymbol("EXNESS:AUDUSD", null)).isEqualTo("AUDUSD")
        assertThat(Mt5FeedAudit.inputSymbol("OANDA:XAUUSD", "GOLD")).isEqualTo("GOLD")
    }

    @Test
    fun `matches deduplicated in-window live ticks against raw history`() {
        val initial = tick(900, "1.0999", "1.1001")
        val first = tick(1_100, "1.1000", "1.1002")
        val second = tick(1_800, "1.1001", "1.1003")
        val observations =
            listOf(
                ObservedMt5Tick(1_010, initial),
                ObservedMt5Tick(1_150, first),
                ObservedMt5Tick(1_400, first),
                ObservedMt5Tick(1_850, second),
            )

        val result = Mt5FeedAudit.compare("EXNESS:EURUSD", 1_000, 2_000, observations, listOf(first, second))

        assertThat(result.passed).isTrue()
        assertThat(result.pollSamples).isEqualTo(4)
        assertThat(result.uniqueLiveTicks).isEqualTo(2)
        assertThat(result.exactTimestampMatches).isEqualTo(2)
        assertThat(result.exactPriceMatches).isEqualTo(2)
        assertThat(result.missingFromHistory).isZero()
        assertThat(result.invalidLiveQuotes).isZero()
        assertThat(result.quoteAgeMs.median).isEqualTo(110)
        assertThat(result.quoteAgeMs.p95).isEqualTo(300)
    }

    @Test
    fun `fails when a timestamp changes price or a live tick is absent from history`() {
        val changed = tick(1_100, "1.1000", "1.1002")
        val missing = tick(1_800, "1.1001", "1.1003")
        val observations =
            listOf(
                ObservedMt5Tick(1_150, changed),
                ObservedMt5Tick(1_850, missing),
            )
        val history = listOf(tick(1_100, "1.0998", "1.1000"))

        val result = Mt5FeedAudit.compare("EXNESS:EURUSD", 1_000, 2_000, observations, history)

        assertThat(result.passed).isFalse()
        assertThat(result.exactTimestampMatches).isEqualTo(1)
        assertThat(result.exactPriceMatches).isZero()
        assertThat(result.timestampPriceMismatches).isEqualTo(1)
        assertThat(result.missingFromHistory).isEqualTo(1)
    }

    @Test
    fun `fails on a crossed live quote even when history reproduces it`() {
        val crossed = tick(1_100, "1.1002", "1.1000")

        val result =
            Mt5FeedAudit.compare(
                symbol = "EXNESS:EURUSD",
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                observations = listOf(ObservedMt5Tick(1_150, crossed)),
                history = listOf(crossed),
            )

        assertThat(result.exactPriceMatches).isEqualTo(1)
        assertThat(result.invalidLiveQuotes).isEqualTo(1)
        assertThat(result.passed).isFalse()
    }

    @Test
    fun `artifact records capture parameters and exact comparison result`() {
        val venueTick = tick(1_100, "1.1000", "1.1002")
        val result =
            Mt5FeedAudit.compare(
                symbol = "EXNESS:EURUSD",
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                observations = listOf(ObservedMt5Tick(1_150, venueTick)),
                history = listOf(venueTick),
            )

        val artifact =
            Json
                .parseToJsonElement(
                    Mt5FeedAudit.artifactJson(
                        result = result,
                        venueSymbol = "EURUSDm",
                        profileName = "exness",
                        durationSeconds = 600,
                        pollMs = 250,
                        settleMs = 15_000,
                    ),
                ).jsonObject

        assertThat(artifact.getValue("schema").jsonPrimitive.content)
            .isEqualTo("qkt-mt5-live-history-audit-v1")
        assertThat(artifact.getValue("venue_symbol").jsonPrimitive.content).isEqualTo("EURUSDm")
        assertThat(artifact.getValue("mt5_profile").jsonPrimitive.content).isEqualTo("exness")
        assertThat(artifact.getValue("duration_seconds").jsonPrimitive.content).isEqualTo("600")
        assertThat(artifact.getValue("poll_ms").jsonPrimitive.content).isEqualTo("250")
        assertThat(artifact.getValue("settle_ms").jsonPrimitive.content).isEqualTo("15000")
        assertThat(artifact.getValue("exact_match_ratio").jsonPrimitive.content).isEqualTo("1")
        assertThat(artifact.getValue("passed").jsonPrimitive.content).isEqualTo("true")
    }

    private fun tick(
        timeMs: Long,
        bid: String,
        ask: String,
    ): MT5Tick =
        MT5Tick(
            symbol = "EURUSDm",
            bid = BigDecimal(bid),
            ask = BigDecimal(ask),
            time = timeMs / 1_000,
            timeMs = timeMs,
        )
}
