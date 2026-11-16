package com.qkt.cli

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5FeedAuditEmptyTest {
    @Test
    fun `an audit that saw no live ticks has a zero match ratio and does not pass`() {
        val result =
            Mt5FeedAuditResult(
                symbol = "EXNESS:EURUSD",
                startedAtMs = 1_000,
                endedAtMs = 2_000,
                pollSamples = 4,
                uniqueLiveTicks = 0,
                historyTicks = 0,
                exactTimestampMatches = 0,
                exactPriceMatches = 0,
                timestampPriceMismatches = 0,
                missingFromHistory = 0,
                invalidLiveQuotes = 0,
                quoteAgeMs = MillisecondDistribution(0, 0, 0),
                medianSpread = BigDecimal.ZERO,
                maxSpread = BigDecimal.ZERO,
            )

        val artifact =
            Json
                .parseToJsonElement(Mt5FeedAudit.artifactJson(result, "EURUSDm", "exness", 600, 250, 15_000))
                .jsonObject

        assertThat(artifact.getValue("exact_match_ratio").jsonPrimitive.content).isEqualTo("0")
        assertThat(artifact.getValue("passed").jsonPrimitive.content).isEqualTo("false")
    }
}
