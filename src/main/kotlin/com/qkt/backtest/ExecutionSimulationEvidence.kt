package com.qkt.backtest

import com.qkt.evidence.ExecutionEvidence

/** What the run's `evidence.execution` records about this execution model. */
fun ExecutionSimulationConfig.toEvidence(): ExecutionEvidence =
    ExecutionEvidence(
        preset = preset.id,
        broker = brokerKind.id,
        seed = seed,
        realistic = preset == ExecutionPreset.MT5_REALISTIC || preset == ExecutionPreset.STRESS,
        warning =
            when (preset) {
                ExecutionPreset.PAPER_FAST ->
                    "Optimistic fills: no spread, slippage, latency, rejection, queue, or partial-fill model."
                ExecutionPreset.MT5_BASIC ->
                    "MT5 basic models spread/slippage and venue sizing but not strict stop-distance/latency stress."
                ExecutionPreset.MT5_REALISTIC -> null
                ExecutionPreset.STRESS -> "Adverse stress execution; use for robustness, not base-case expectation."
            },
        fillPriceSource =
            when (preset) {
                ExecutionPreset.PAPER_FAST -> "latest tracked price / trigger level for bars"
                else -> "bid/ask when available, synthetic spread fallback"
            },
        latencyModel = latencyLabel(),
        stopLatencyModel = if (stopLatencyMs == 0L) "on-trigger" else "fixed:${stopLatencyMs}ms",
        takeProfitFillModel = takeProfitFill.id,
        candleCloseModel = "heartbeat:${heartbeatIntervalMs}ms grace:${candleCloseGraceMs}ms",
        slippageModel = slippageLabel(),
        rejectionModel = rejectEvery?.let { "reject-every:$it" } ?: "none",
        partialFillModel = partialFillFraction?.let { "fraction:$it" } ?: "none",
        venueRules =
            if (enforceStopsLevel) {
                "volume step/min, price digits, bid/ask, tradeStopsLevel"
            } else if (brokerKind == BrokerKind.MT5_SIM) {
                "volume step/min, price digits, bid/ask"
            } else {
                "none"
            },
        commissionModel = "per-lot instruments.yaml commissionPerLot",
        financingModel =
            "signed swap points at configured UTC rollover; triple configured weekday; perpetual funding at " +
                "stored venue rates (quantity x contract size x price x rate at each rate's time)",
        ocoMode = "engine-managed deterministic siblings",
    )
