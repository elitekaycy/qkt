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
    init {
        if (!staleAgeMultiple.isFinite() || staleAgeMultiple <= 0.0) {
            throw IllegalArgumentException("market_data.stale_age_multiple must be a finite positive number")
        }
        if (minStaleAgeMs <= 0L) {
            throw IllegalArgumentException("market_data.min_stale_age_ms must be positive")
        }
        if (!outlierSigma.isFinite() || outlierSigma <= 0.0) {
            throw IllegalArgumentException("market_data.outlier_sigma must be a finite positive number")
        }
        if (maxClockSkewMs < 0L) {
            throw IllegalArgumentException("market_data.max_clock_skew_ms must be non-negative")
        }
    }

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
                staleAgeMultiple = parseDouble(map, "stale_age_multiple", DEFAULT.staleAgeMultiple),
                minStaleAgeMs = parseLong(map, "min_stale_age_ms", DEFAULT.minStaleAgeMs),
                outlierSigma = parseDouble(map, "outlier_sigma", DEFAULT.outlierSigma),
                maxClockSkewMs = parseLong(map, "max_clock_skew_ms", DEFAULT.maxClockSkewMs),
            )
        }

        private fun parseDouble(
            map: Map<String, Any?>,
            key: String,
            default: Double,
        ): Double {
            val raw = map[key] ?: return default
            val parsed: Double? = raw.toString().toDoubleOrNull()
            if (parsed == null) {
                throw IllegalStateException("market_data.$key must be a number")
            }
            return parsed
        }

        private fun parseLong(
            map: Map<String, Any?>,
            key: String,
            default: Long,
        ): Long {
            val raw = map[key] ?: return default
            val parsed: Long? = raw.toString().toLongOrNull()
            if (parsed == null) {
                throw IllegalStateException("market_data.$key must be a whole number")
            }
            return parsed
        }
    }
}
