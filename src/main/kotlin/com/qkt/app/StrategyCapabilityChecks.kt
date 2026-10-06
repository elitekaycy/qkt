package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.compile.TradeFlowReader
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.pnl.BookBalanceView

/**
 * Phase 27: refuse to deploy a strategy whose `STACK_AT` symbols route to a broker
 * that doesn't declare [OrderTypeCapability.MULTI_POSITION_PER_SYMBOL].
 * The capability is checked per-symbol via [Broker.capabilitiesFor]
 * so [com.qkt.broker.CompositeBroker] routing differences across symbols are honored.
 */
internal fun requireMultiPositionCapability(
    strategyId: String,
    strategy: DslCompiledStrategy,
    broker: Broker,
) {
    for (symbol in strategy.multiPositionPerSymbolSymbols) {
        val caps = broker.capabilitiesFor(symbol)
        require(OrderTypeCapability.MULTI_POSITION_PER_SYMBOL in caps) {
            "Strategy '$strategyId' uses STACK_AT on $symbol but routing broker " +
                "'${broker.name}' does not declare MULTI_POSITION_PER_SYMBOL"
        }
    }
}

/**
 * #301: refuse to deploy a strategy that binds a volume-weighted indicator (VWAP/OBV) to a feed
 * that can't supply volume — otherwise the indicator never becomes ready and the strategy
 * silently never fires. Only enforced when the source actually serves the symbol's stream
 * (`TICKS`/`BARS`/`LIVE_TICKS`); a source that doesn't (e.g. [com.qkt.marketdata.source.NullMarketSource]
 * behind an injected-tick backtest) carries no judgment about volume. Per-symbol via
 * [MarketSource.capabilitiesFor] so routing across a basket is honored.
 */
internal fun requireVolumeCapability(
    strategyId: String,
    strategy: DslCompiledStrategy,
    source: MarketSource,
) {
    for (symbol in strategy.volumeRequiringSymbols) {
        val caps = source.capabilitiesFor(symbol)
        val servesStream =
            caps.any {
                it == MarketSourceCapability.TICKS ||
                    it == MarketSourceCapability.BARS ||
                    it == MarketSourceCapability.LIVE_TICKS
            }
        require(!servesStream || MarketSourceCapability.VOLUME in caps) {
            "Strategy '$strategyId' binds a volume-weighted indicator (VWAP/OBV) on $symbol but its " +
                "data feed ('${source.name}') does not supply volume — bind a volume-bearing feed or remove the indicator"
        }
    }
}

/**
 * Refuse to start a strategy that reads a contract's mark or index on a symbol whose data source serves no
 * marks, or serves them with a problem (a gateway that does not declare `mark_prices`), so a rule never reads
 * an Undefined that no venue will ever fill. Per symbol, through [MarketSource.marksFor].
 */
internal fun requireMarkPrices(
    strategyId: String,
    strategy: DslCompiledStrategy,
    source: MarketSource,
) {
    for (symbol in strategy.markSymbols) {
        val marks = source.marksFor(symbol)
        val problem =
            if (marks ==
                null
            ) {
                "its data feed ('${source.name}') serves no mark prices"
            } else {
                marks.problem(symbol)
            }
        require(problem == null) { "Strategy '$strategyId' reads the mark or index of $symbol but $problem" }
    }
}

/**
 * Refuse to start a strategy that reads an option's mark IV or Greeks (`<alias>.iv`, `.delta`, ...) on a symbol
 * that is not a catalogued option, or whose data source serves no option marks or serves them with a problem
 * (a gateway that does not declare `option_marks`, a root with no chain series), so a rule never reads an
 * Undefined that nothing will ever fill. Per symbol, through [MarketSource.optionMarksFor].
 */
internal fun requireOptionMarks(
    strategyId: String,
    strategy: DslCompiledStrategy,
    source: MarketSource,
    instruments: InstrumentRegistry,
) {
    for (symbol in strategy.optionMarkSymbols) {
        val marks = source.optionMarksFor(symbol)
        val problem =
            when {
                instruments.lookup(symbol)?.derivative !is OptionTerms -> "it is not a catalogued option contract"
                marks == null -> "its data feed ('${source.name}') serves no option marks"
                else -> marks.problem(symbol)
            }
        require(
            problem == null,
        ) { "Strategy '$strategyId' reads the implied volatility or Greeks of $symbol but $problem" }
    }
}

/**
 * Refuse to start a strategy that reads trade flow (`<alias>.buy_volume[1]`, `.long_liq_volume[1]`, ...) on a symbol
 * whose data source serves none, or serves it with a problem (a gateway not declaring `trades` or `liquidations`),
 * so a rule never reads an Undefined that no venue will ever fill. Per symbol and series.
 */
internal fun requireTradeFlow(
    strategyId: String,
    strategy: DslCompiledStrategy,
    source: MarketSource,
) {
    val reads = (strategy as? TradeFlowReader)?.flowReads ?: return
    for (read in reads) {
        val flow = source.tradeFlowFor(read.symbol)
        val problem =
            if (flow ==
                null
            ) {
                "its data feed ('${source.name}') serves no trade tape"
            } else {
                flow.problem(read.symbol, read.kind)
            }
        require(
            problem == null,
        ) { "Strategy '$strategyId' reads the ${read.kind.capability} of ${read.symbol} but $problem" }
    }
}

/**
 * Refuse to deploy a strategy that sizes with `RISK OF BOOK` when no portfolio book is bound, so
 * the missing capability fails at deploy rather than at the first signal.
 */
internal fun requireBookCapability(
    strategyId: String,
    strategy: DslCompiledStrategy,
    bookBalance: BookBalanceView?,
) {
    require(!strategy.usesBookSizing || bookBalance != null) {
        "Strategy '$strategyId' sizes with RISK OF BOOK but no portfolio book is bound — " +
            "deploy it as a child of a PORTFOLIO with CAPITAL, or use RISK/PCT RISK sizing"
    }
}
