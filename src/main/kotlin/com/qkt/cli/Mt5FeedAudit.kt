package com.qkt.cli

import com.qkt.broker.mt5.MT5Tick
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class ObservedMt5Tick(
    val observedAtMs: Long,
    val tick: MT5Tick,
)

internal data class MillisecondDistribution(
    val median: Long,
    val p95: Long,
    val max: Long,
)

internal data class Mt5FeedAuditResult(
    val symbol: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val pollSamples: Int,
    val uniqueLiveTicks: Int,
    val historyTicks: Int,
    val exactTimestampMatches: Int,
    val exactPriceMatches: Int,
    val timestampPriceMismatches: Int,
    val missingFromHistory: Int,
    val invalidLiveQuotes: Int,
    val quoteAgeMs: MillisecondDistribution,
    val medianSpread: BigDecimal,
    val maxSpread: BigDecimal,
) {
    val passed: Boolean
        get() = uniqueLiveTicks > 0 && exactPriceMatches == uniqueLiveTicks && invalidLiveQuotes == 0
}

internal object Mt5FeedAudit {
    fun inputSymbol(
        qktSymbol: String,
        explicitMt5Symbol: String?,
    ): String = explicitMt5Symbol ?: qktSymbol.substringAfter(':')

    fun compare(
        symbol: String,
        startedAtMs: Long,
        endedAtMs: Long,
        observations: List<ObservedMt5Tick>,
        history: List<MT5Tick>,
    ): Mt5FeedAuditResult {
        require(endedAtMs >= startedAtMs) { "MT5 feed audit ends before it starts" }
        require(observations.isNotEmpty()) { "MT5 feed audit captured no live observations" }

        val live =
            observations
                .map(ObservedMt5Tick::tick)
                .filter { it.timeMs in startedAtMs..endedAtMs }
                .distinctBy { it.identity() }
        val historyByTimestamp = history.groupBy(MT5Tick::timeMs)
        var timestampMatches = 0
        var priceMatches = 0
        var timestampPriceMismatches = 0
        live.forEach { tick ->
            val candidates = historyByTimestamp[tick.timeMs]
            if (candidates == null) return@forEach
            timestampMatches++
            if (candidates.any { it.bid.compareTo(tick.bid) == 0 && it.ask.compareTo(tick.ask) == 0 }) {
                priceMatches++
            } else {
                timestampPriceMismatches++
            }
        }

        val invalidLiveQuotes =
            live.count { tick ->
                tick.bid.signum() <= 0 || tick.ask.signum() <= 0 || tick.bid > tick.ask
            }
        val quoteAges = observations.map { (observedAtMs, tick) -> observedAtMs - tick.timeMs }
        val spreads = observations.map { it.tick.ask.subtract(it.tick.bid) }.sorted()
        return Mt5FeedAuditResult(
            symbol = symbol,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            pollSamples = observations.size,
            uniqueLiveTicks = live.size,
            historyTicks = history.size,
            exactTimestampMatches = timestampMatches,
            exactPriceMatches = priceMatches,
            timestampPriceMismatches = timestampPriceMismatches,
            missingFromHistory = live.size - timestampMatches,
            invalidLiveQuotes = invalidLiveQuotes,
            quoteAgeMs = distribution(quoteAges),
            medianSpread = spreads[spreads.size / 2],
            maxSpread = spreads.last(),
        )
    }

    fun artifactJson(
        result: Mt5FeedAuditResult,
        venueSymbol: String,
        profileName: String,
        durationSeconds: Long,
        pollMs: Long,
        settleMs: Long,
    ): String {
        val exactRatio =
            if (result.uniqueLiveTicks == 0) {
                BigDecimal.ZERO
            } else {
                BigDecimal(result.exactPriceMatches)
                    .divide(BigDecimal(result.uniqueLiveTicks), MATH_CONTEXT)
            }
        return buildJsonObject {
            put("schema", "qkt-mt5-live-history-audit-v1")
            put("symbol", result.symbol)
            put("venue_symbol", venueSymbol)
            put("mt5_profile", profileName)
            put("reference", "mt5-history")
            put("duration_seconds", durationSeconds)
            put("poll_ms", pollMs)
            put("settle_ms", settleMs)
            put("started_at_utc", Instant.ofEpochMilli(result.startedAtMs).toString())
            put("ended_at_utc", Instant.ofEpochMilli(result.endedAtMs).toString())
            put("poll_samples", result.pollSamples)
            put("unique_live_ticks", result.uniqueLiveTicks)
            put("history_ticks", result.historyTicks)
            put("exact_timestamp_matches", result.exactTimestampMatches)
            put("exact_price_matches", result.exactPriceMatches)
            put("exact_match_ratio", exactRatio.toPlainString())
            put("timestamp_price_mismatches", result.timestampPriceMismatches)
            put("missing_from_history", result.missingFromHistory)
            put("invalid_live_quotes", result.invalidLiveQuotes)
            put("quote_age_median_ms", result.quoteAgeMs.median)
            put("quote_age_p95_ms", result.quoteAgeMs.p95)
            put("quote_age_max_ms", result.quoteAgeMs.max)
            put("median_spread", result.medianSpread.toPlainString())
            put("max_spread", result.maxSpread.toPlainString())
            put("passed", result.passed)
        }.toString()
    }

    private fun MT5Tick.identity(): String = "$timeMs|${bid.toPlainString()}|${ask.toPlainString()}"

    private fun distribution(values: List<Long>): MillisecondDistribution {
        val sorted = values.sorted()
        return MillisecondDistribution(
            median = sorted[sorted.size / 2],
            p95 = sorted[(sorted.size * 95 / 100).coerceAtMost(sorted.lastIndex)],
            max = sorted.last(),
        )
    }

    private val MATH_CONTEXT = MathContext(8, RoundingMode.HALF_EVEN)
}
