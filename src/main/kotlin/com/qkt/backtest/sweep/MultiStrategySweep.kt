package com.qkt.backtest.sweep

import com.qkt.backtest.Backtest

/**
 * Runs an entire grid as ONE multi-strategy backtest: every combo is a strategy sharing a single
 * decoded and aggregated feed, and each combo's result is split back out from the per-strategy
 * reports. This shares the dominant per-tick work — tick decode, candle aggregation, price tracking —
 * across the whole grid instead of repeating it once per combo.
 *
 * Bit-identical to running each combo as its own backtest on the sweep metrics (see
 * MultiStrategySweepParityTest), as long as the combos do not couple through shared account state.
 * The caller guarantees that by only using this path when no account-protection halts are configured
 * (`BacktestContext.hasAccountHalts` is false); otherwise it falls back to an isolated per-combo
 * fan-out.
 */
class MultiStrategySweep<C>(
    private val combos: List<Pair<String, C>>,
    private val overridesOf: (C) -> Map<String, String>,
    private val backtestFor: (List<Pair<String, Map<String, String>>>) -> Backtest,
) {
    init {
        require(combos.isNotEmpty()) { "combos must not be empty" }
        require(combos.map { it.first }.toSet().size == combos.size) {
            "combo labels must be unique: ${combos.map { it.first }}"
        }
    }

    fun run(): SweepResult<C> {
        val labeled = combos.map { (label, c) -> label to overridesOf(c) }
        val result = backtestFor(labeled).run()
        return SweepResult(
            combos.map { (label, c) ->
                val report = result.perStrategy.getValue(label)
                SweepRun(
                    label,
                    c,
                    result.copy(
                        global = report,
                        perStrategy = mapOf(label to report),
                        trades = result.trades.filter { it.strategyId == label },
                    ),
                )
            },
        )
    }
}
