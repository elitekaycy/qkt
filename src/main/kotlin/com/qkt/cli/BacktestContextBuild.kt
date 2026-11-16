package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.dsl.ast.StrategyAst
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.hub.resolveHubRoot
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Builds a single-strategy [BacktestContext] from the command line: the window, the symbols (`--symbols` narrows the
 * declared ones), the data store, instruments, config-driven execution, accounting and halt rules, bar replay and the
 * provisioner. Moved out of [BacktestContext] unchanged.
 */
internal object BacktestContextBuild {
    fun build(
        args: Args,
        ast: StrategyAst,
        fetcherOverride: DataFetcher?,
    ): BacktestContext {
        val from = BacktestContext.parseInstant(args.requireOption("from"))
        val to = BacktestContext.parseInstant(args.requireOption("to"))
        val declaredSymbols = ast.streams.map { it.qktSymbol }.distinct()
        val symbolsOverride =
            args
                .option("symbols")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
        val symbols =
            if (symbolsOverride != null) {
                val unknown = symbolsOverride.filter { it !in declaredSymbols }
                if (unknown.isNotEmpty()) {
                    throw BacktestContext.Companion.SetupError(
                        "--symbols contains unknown symbols $unknown; strategy declares $declaredSymbols",
                    )
                }
                symbolsOverride
            } else {
                declaredSymbols
            }
        val datasetContext = BacktestDatasetEvidence.datasetContext(args, listOf(ast), symbols, from, to)
        // Default to the shared store (~/.qkt/data) so `qkt backtest` reads the same place
        // `qkt fetch` / `qkt data convert` write. A pinned dataset carries its own root unless
        // the caller explicitly overrides it with --data-root for relocated snapshots.
        val dataRoot = args.option("data-root") ?: datasetContext.dataRoot ?: CliDataRoot.of(args).toString()

        val stores = BacktestSetup.stores(args, dataRoot, fetcherOverride)
        val store = stores.ticks
        val barStore = stores.bars

        val candleWindow =
            ast.streams
                .firstOrNull()
                ?.timeframe
                ?.let { TimeWindow.parse(it) }

        val instruments: InstrumentRegistry =
            InstrumentFiles.forBacktest(Paths.get(dataRoot), args, symbols, from, to)

        val brokerKind = BacktestSetup.brokerKind(args)
        // Same config-driven halt/accounting construction the live daemon uses, so a strategy
        // that would halt live halts at the same point in its backtest. The basis balance is
        // the backtest's own starting balance, which defaults to the config's like the daemon's.
        val cfg = Config.load(Config.resolvePath(args.option("config")))
        val startingBalance = backtestStartingBalance(args, cfg)
        val executionConfig = BacktestSimulationOptions.executionConfig(args, cfg, brokerKind)
        val accountingConfig = BacktestSimulationOptions.accountingConfig(args, cfg)
        val replaySymbols = (symbols + accountingConfig.normalizedSymbols.values).distinct()
        warnStandaloneBookRiskIgnored(cfg.bookRisk)
        val barReplay =
            BacktestBarReplay.resolveBarReplay(
                args = args,
                dataRoot = dataRoot,
                from = from,
                to = to,
                symbols = symbols,
                streams = ast.streams,
                candleWindow = candleWindow,
                executionConfig = executionConfig,
            )
        val forceBars = barReplay.forceBars
        val tickFills = barReplay.tickFills
        val binaryBarStore = barReplay.binaryBarStore
        val barWindows = barReplay.barWindows

        val calendars = cfg.backtestCalendars(args.option("venue"), instruments)
        val provisioner =
            BacktestSetup.provisioner(
                args,
                dataRoot,
                stores,
                from to to,
                replaySymbols to barReplay,
                ast.streams,
                instruments,
                calendars::calendarFor,
            )

        val haltConfig = BacktestSetup.haltConfig(cfg)

        return BacktestContext(
            ast = ast,
            from = from,
            to = to,
            brokerKind = executionConfig.brokerKind,
            executionConfig = executionConfig,
            symbols = symbols,
            store = store,
            instruments = instruments,
            barStore = barStore,
            datasetEvidence = datasetContext.evidence,
            hubStoreRoot = resolveHubRoot(args.option("hub-root"), cfg.hub, Path.of(dataRoot)),
            accountingConfig = accountingConfig,
            candleWindow = candleWindow,
            startingBalance = startingBalance,
            haltConfig = haltConfig,
            provisioner = provisioner,
            replaySymbols = replaySymbols,
            perStrategyRisk = cfg.perStrategyRisk,
            maxOrderQty = cfg.maxOrderQty,
            maxOrderNotional = cfg.maxOrderNotional,
            priceCollarFrac = cfg.priceCollarFrac,
            enforceLiveBreakers = args.flag("enforce-live-breakers"),
            runawayMaxRoundTrips = cfg.runawayMaxRoundTrips,
            runawayMaxRejections = cfg.runawayMaxRejections,
            forceBars = forceBars,
            barWindows = barWindows,
            binaryBarStore = binaryBarStore,
            tickFills = tickFills,
            calendars = calendars,
        )
    }
}
