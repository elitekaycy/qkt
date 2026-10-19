package com.qkt.backtest

import com.qkt.broker.FillQuotes
import java.math.BigDecimal

/**
 * What one run's fills actually rested on (#1437), recorded by the replay so the summary, `--verbose`
 * and report.html describe the run rather than the broker kind's defaults: where fill prices came from,
 * the slippage applied, the execution stresses that were on, and which symbols went to the futures and
 * option venues. [ExecutionFactsText] turns it into the plain-language lines every output prints.
 */
data class ExecutionFacts(
    val broker: BrokerKind,
    /**
     * Symbols the run's simulated broker filled, i.e. those not routed to the futures or option venues.
     */
    val brokerSymbols: Set<String> = emptySet(),
    /** Of [brokerSymbols], those replayed from bars on the paper broker: stops and limits fill at their level. */
    val barLevelSymbols: Set<String> = emptySet(),
    /** How the mt5-sim broker priced its fills; null on the paper broker or when not recorded. */
    val quotes: FillQuotes? = null,
    /** Points of the spread the mt5-sim broker synthesises around a mid-only quote. */
    val syntheticSpreadPoints: Int = 2,
    /** Spreads `instruments.yaml` states for traded symbols, e.g. `EXNESS:XAUUSD fixed 260 points`. */
    val spreadModels: List<String> = emptyList(),
    val slippage: SlippageSpec = SlippageSpec.ZERO,
    /** Points for [SlippageSpec.FIXED_POINTS], or the upper bound for [SlippageSpec.UNIFORM_RANDOM]. */
    val slippagePoints: Int = 0,
    /** Each slipping symbol's `instruments.yaml` slippagePoints when [slippage] is [SlippageSpec.INSTRUMENT]. */
    val instrumentSlippagePoints: Map<String, Int> = emptyMap(),
    val latencyMs: Long = 0L,
    val stopLatencyMs: Long = 0L,
    val partialFillFraction: BigDecimal? = null,
    val rejectEvery: Int? = null,
    val futures: Set<String> = emptySet(),
    val options: Set<String> = emptySet(),
    /**
     * Per symbol, the bars of a `--bars` run that had no recorded ticks and so filled at their own levels.
     * Empty when every bar had ticks or the run replayed ticks.
     */
    val barsWithoutTicks: Map<String, Int> = emptyMap(),
    /** False for an [assumed] description: nothing above was observed, so only the broker's model is stated. */
    val recorded: Boolean = true,
) {
    /** True when [slippage] moves any fill: a non-zero model, or some symbol's non-zero slippagePoints. */
    val slips: Boolean
        get() =
            when (slippage) {
                SlippageSpec.ZERO -> false
                SlippageSpec.INSTRUMENT -> instrumentSlippagePoints.values.any { it > 0 }
                else -> slippagePoints > 0
            }

    companion object {
        /** What a run on [broker] assumes when the replay recorded nothing (hand-built or legacy results). */
        fun assumed(
            broker: BrokerKind,
            futures: Set<String> = emptySet(),
            options: Set<String> = emptySet(),
        ): ExecutionFacts = ExecutionFacts(broker = broker, futures = futures, options = options, recorded = false)
    }
}
