package com.qkt.research

import com.qkt.accounting.AccountingEngine
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.instrument.QuoteCurrencyGuard

// Deploy-time symbol checks a replay runs before it builds a broker: the same contracts a live
// deploy enforces, so a backtest refuses what live would refuse. Cold path, run once per replay.

private val log = org.slf4j.LoggerFactory.getLogger(ReplayEngine::class.java)

/** Warn for each strategy whose rules read quote fields a bar-synthesized feed cannot supply. */
internal fun warnQuoteFieldReads(dslStrategies: List<DslCompiledStrategy>) {
    for (s in dslStrategies) {
        if (s.quoteFieldStreams.isNotEmpty()) {
            log.warn(
                "strategy reads quote fields (bid/ask/spread) on streams {} — these evaluate " +
                    "Undefined unless the backtest data source carries real ticks with quotes; " +
                    "bar-synthesized feeds do not, so spread-aware rules will silently never fire " +
                    "(divergence catalog row A10)",
                s.quoteFieldStreams,
            )
        }
    }
}

/** The qkt symbols each broker route must serve, from every strategy's declared streams. */
internal fun brokerSymbolsOf(dslStrategies: List<DslCompiledStrategy>): MutableMap<String, MutableSet<String>> {
    val brokerSymbols: MutableMap<String, MutableSet<String>> = mutableMapOf()
    for (s in dslStrategies) {
        for (key in s.declaredStreams.values) {
            brokerSymbols.getOrPut(key.broker) { mutableSetOf() }.add(key.qktSymbol)
        }
    }
    return brokerSymbols
}

/**
 * Fail the replay up front when a traded [symbols] entry is not quoted in (or convertible to) the
 * account currency, or when a real registry cannot resolve its contract size.
 */
internal fun requireReplaySymbolsResolvable(
    symbols: List<String>,
    accounting: AccountingEngine,
    instruments: InstrumentRegistry,
) {
    QuoteCurrencyGuard
        .assertAccountQuoted(
            symbols,
            accountCurrency = accounting.accountCurrency,
            canConvert = { symbol, _ -> accounting.canConvertSymbol(symbol) },
        )
    // Same deploy-time contract as live: a real registry that cannot resolve a traded
    // symbol fails the run up front instead of silently booking contractSize=1.
    if (instruments !is NoopInstrumentRegistry) {
        for (symbol in symbols.distinct()) {
            if (!QuoteCurrencyGuard
                    .requiresContractSizeMeta(symbol)
            ) {
                continue
            }
            requireNotNull(instruments.lookup(symbol)) {
                "InstrumentMeta unresolvable for $symbol — refusing to backtest " +
                    "(PnL would silently book contractSize=1)"
            }
        }
    }
}
