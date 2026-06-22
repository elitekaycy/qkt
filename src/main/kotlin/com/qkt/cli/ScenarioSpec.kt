package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.dsl.ast.StrategyAst
import com.qkt.instrument.InstrumentRegistry
import java.math.BigDecimal

/**
 * One backtest configuration in a fan-out sweep. Every field here may differ between the engines that
 * share a single decoded feed; `null` means "use the context default". A scenario may NOT change the
 * symbol set or timeframes — the shared feed is keyed to those (enforced by [BacktestContext.backtest]).
 *
 * e.g. param-only `ScenarioSpec("fast2", params = mapOf("fast" to "2"))` is today's grid combo;
 * `ScenarioSpec("rf-200k", startingBalance = BigDecimal("200000"))` is a risk-fit sizing scenario.
 */
data class ScenarioSpec(
    val label: String,
    val params: Map<String, String> = emptyMap(),
    val ast: StrategyAst? = null,
    val brokerKind: BrokerKind? = null,
    val instruments: InstrumentRegistry? = null,
    val startingBalance: BigDecimal? = null,
)
