package com.qkt.marketdata

/**
 * Operator-tunable thresholds for [MarketDataGate], parsed from the `market_data:` block of
 * `qkt.config.yaml`. Every default matches the gate's built-in constant, so an absent block
 * (or an absent key) keeps the historical hard-coded behavior.
 *
 * ```yaml
 * market_data:
 *   stale_age_multiple: 5.0
 *   min_stale_age_ms: 10000
 *   outlier_sigma: 6.0
 *   max_clock_skew_ms: 60000
 * ```
 */
data class MarketDataGateConfig(
    /** Staleness threshold as a multiple of the symbol's smoothed inter-tick gap. */
    val staleAgeMultiple: Double,
    /** Floor for the staleness threshold, in milliseconds. */
    val minStaleAgeMs: Long,
    /** Standard deviations from the short-window mean beyond which a tick is rejected. */
    val outlierSigma: Double,
    /** Tolerance between broker tick timestamps and the local clock, in milliseconds. */
    val maxClockSkewMs: Long,
) {
    companion object {
        /** The gate's built-in thresholds — what every deployment ran before this knob existed. */
        val DEFAULT: MarketDataGateConfig =
            MarketDataGateConfig(
                staleAgeMultiple = MarketDataGate.DEFAULT_STALE_AGE_MULTIPLE,
                minStaleAgeMs = MarketDataGate.DEFAULT_MIN_STALE_AGE_MS,
                outlierSigma = MarketDataGate.DEFAULT_OUTLIER_SIGMA,
                maxClockSkewMs = MarketDataGate.DEFAULT_MAX_CLOCK_SKEW_MS,
            )

        @Suppress("UNCHECKED_CAST")
        fun parse(raw: Any?): MarketDataGateConfig {
            val map = raw as? Map<String, Any?> ?: return DEFAULT
            return MarketDataGateConfig(
                staleAgeMultiple = map["stale_age_multiple"]?.toString()?.toDoubleOrNull() ?: DEFAULT.staleAgeMultiple,
                minStaleAgeMs = map["min_stale_age_ms"]?.toString()?.toLongOrNull() ?: DEFAULT.minStaleAgeMs,
                outlierSigma = map["outlier_sigma"]?.toString()?.toDoubleOrNull() ?: DEFAULT.outlierSigma,
                maxClockSkewMs = map["max_clock_skew_ms"]?.toString()?.toLongOrNull() ?: DEFAULT.maxClockSkewMs,
            )
        }
    }
}
