package com.qkt.cli

import com.qkt.backtest.ConditionalAutocorr
import com.qkt.backtest.report.ReportSerializer.jsonString

/** The `conditionalAutocorr` object of `qkt backtest --json`: lag-1 return autocorrelation per symbol. */
internal object AutocorrJson {
    /**
     * Lag-1 return autocorrelation (#460) as a JSON object keyed by symbol, e.g.
     * `{"XAUUSD":{"perHour":{"13":-1.0},"perRegime":{"high":0.31},"hourCounts":{"13":120},
     * "regimeCounts":{"high":600,"low":600}}}`. Empty object when no symbol populated a bucket.
     * Keys are sorted for deterministic output, matching the `dailyPnL` convention.
     */
    fun render(bySymbol: Map<String, ConditionalAutocorr>): String =
        buildString {
            append('{')
            append(
                bySymbol.entries
                    .sortedBy { it.key }
                    .joinToString(",") { (symbol, ac) -> "${jsonString(symbol)}:${autocorrObject(ac)}" },
            )
            append('}')
        }

    private fun autocorrObject(ac: ConditionalAutocorr): String =
        buildString {
            append("{\"perHour\":{")
            append(
                ac.perHour.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value.toPlainString()}" },
            )
            append("},\"perRegime\":{")
            append(
                ac.perRegime.entries
                    .sortedBy { it.key.name }
                    .joinToString(",") { "\"${it.key.name.lowercase()}\":${it.value.toPlainString()}" },
            )
            append("},\"hourCounts\":{")
            append(
                ac.hourCounts.entries
                    .sortedBy { it.key }
                    .joinToString(",") { "\"${it.key}\":${it.value}" },
            )
            append("},\"regimeCounts\":{")
            append(
                ac.regimeCounts.entries
                    .sortedBy { it.key.name }
                    .joinToString(",") { "\"${it.key.name.lowercase()}\":${it.value}" },
            )
            append("}}")
        }
}
