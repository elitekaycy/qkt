package com.qkt.derivatives.options.chain

/**
 * A chain analytics stream symbol, `CHAIN:<VENUE>.<ROOT>.<metric>.<tenor>`
 * (`CHAIN:DERIBIT.BTC_USDC.atm_iv.30d`): [metric] of option root [root] (`DERIBIT:BTC_USDC`) at a
 * tenor of [tenorDays] days.
 */
data class ChainAnalyticsSymbol(
    val root: String,
    val metric: ChainMetric,
    val tenorDays: Int,
) {
    companion object {
        /** The broker prefix of chain analytics symbols. */
        const val PREFIX = "CHAIN:"
        private const val FORMAT = "CHAIN:<VENUE>.<ROOT>.<metric>.<tenor>"
        private val TENOR = Regex("([1-9][0-9]*)d")

        /** [qktSymbol] as a chain analytics symbol, or a failure saying what is malformed. */
        fun parse(qktSymbol: String): Result<ChainAnalyticsSymbol> =
            runCatching {
                require(qktSymbol.startsWith(PREFIX)) { "not a chain analytics symbol: $qktSymbol" }
                val parts = qktSymbol.removePrefix(PREFIX).split('.')
                require(parts.size == 4 && parts.none { it.isEmpty() }) { "$qktSymbol must be $FORMAT" }
                val (venue, root, metricToken, tenor) = parts
                val metric =
                    requireNotNull(ChainMetric.of(metricToken)) {
                        "unknown chain metric '$metricToken' in $qktSymbol; use ${ChainMetric.entries.joinToString {
                            it.token
                        }}"
                    }
                val days =
                    requireNotNull(
                        TENOR.matchEntire(tenor),
                    ) { "chain tenor must be <N>d days in $qktSymbol, got '$tenor'" }
                ChainAnalyticsSymbol("$venue:$root", metric, days.groupValues[1].toInt())
            }
    }
}
