package com.qkt.cli

import com.qkt.backtest.spreadModels
import com.qkt.backtest.toEvidence
import com.qkt.evidence.ExecutionEvidence

/**
 * This run's execution evidence: its simulation config's, with the venue spread each traded symbol
 * states in `instruments.yaml` added to the fill price source when the mt5-sim broker applies it
 * (e.g. `…; instruments.yaml spread: EXNESS:XAUUSD fixed 260 points`).
 */
internal fun BacktestContext.executionEvidence(): ExecutionEvidence =
    executionConfig.toEvidence().withSpreadModels(spreadModels(instruments, symbols, brokerKind))

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
