package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.evidence.ExecutionEvidence
import com.qkt.instrument.InstrumentRegistry

/**
 * This run's execution evidence: its simulation config's, with the venue spread each traded symbol
 * states in `instruments.yaml` added to the fill price source when the mt5-sim broker applies it
 * (e.g. `…; instruments.yaml spread: EXNESS:XAUUSD fixed 260 points`).
 */
internal fun BacktestContext.executionEvidence(): ExecutionEvidence =
    executionConfig.toEvidence().withSpreadModels(spreadModels(instruments, symbols, brokerKind))

/** The spread model each of [symbols] states, as `SYMBOL fixed N points` or `SYMBOL min N points`. */
internal fun spreadModels(
    instruments: InstrumentRegistry,
    symbols: List<String>,
    brokerKind: BrokerKind,
): List<String> {
    if (brokerKind != BrokerKind.MT5_SIM) return emptyList()
    return symbols.distinct().sorted().mapNotNull { symbol ->
        val meta = instruments.lookup(symbol) ?: return@mapNotNull null
        meta.spreadPoints?.let { "$symbol fixed $it points" }
            ?: meta.minSpreadPoints?.let { "$symbol min $it points" }
    }
}

internal fun ExecutionEvidence.withSpreadModels(models: List<String>): ExecutionEvidence =
    if (models.isEmpty()) {
        this
    } else {
        copy(
            fillPriceSource =
                listOfNotNull(
                    fillPriceSource,
                    "instruments.yaml spread: ${models.joinToString()}",
                ).joinToString("; "),
        )
    }
