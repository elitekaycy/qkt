package com.qkt.cli

import com.qkt.common.Clock
import com.qkt.dsl.ast.CHAIN_BROKER
import com.qkt.dsl.ast.HUB_BROKER
import com.qkt.dsl.ast.OPTIONS_BROKER
import com.qkt.instrument.CommissionOverrideRegistry
import com.qkt.instrument.ContractCatalogRegistry
import com.qkt.instrument.SwapScaleRegistry
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.FundingCoverage
import com.qkt.instrument.FundingRateStore
import com.qkt.instrument.FuturesRootsFile
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.OptionCatalogRegistry
import com.qkt.instrument.OptionRootsFile
import com.qkt.instrument.RefreshingOptionCatalogs
import com.qkt.instrument.RollHistoryStore
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import com.qkt.marketdata.depth.BookDepthCoverage
import com.qkt.marketdata.depth.BookDepthStore
import com.qkt.marketdata.depth.BookDepthSymbol
import com.qkt.marketdata.openinterest.OpenInterestCoverage
import com.qkt.marketdata.openinterest.OpenInterestStore
import com.qkt.marketdata.openinterest.OpenInterestSymbol
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * The instrument metadata backtests and live sessions resolve symbols against: the `instruments:`
 * entries of the instruments file, then its `futures:` roots joined with `<dataRoot>/contracts/`
 * catalogs (and each perpetual's stored funding rates), then its `options:` roots joined with their option
 * catalogs, then the built-in standard table. Without an instruments file it is the standard table alone.
 */
internal object InstrumentFiles {
    /**
     * The registry for [dataRoot], reading [explicit] (`--instruments`) or `<dataRoot>/instruments.yaml`.
     * Fails before any data is read when one of [symbols] belongs to a declared futures root but
     * cannot be resolved (e.g. a contract missing from its catalog). A live session passes [liveClock]:
     * its option catalogs then reload when their files change ([RefreshingOptionCatalogs]), as venues list
     * new expiries while it runs.
     */
    fun registry(
        dataRoot: Path,
        explicit: Path?,
        symbols: Collection<String> = emptyList(),
        liveClock: Clock? = null,
        funding: Boolean = true,
    ): InstrumentRegistry {
        val path = explicit ?: dataRoot.resolve("instruments.yaml")
        if (!Files.exists(path)) {
            if (explicit != null) throw BacktestContext.Companion.SetupError("--instruments file not found: $path")
            return StandardInstrumentRegistry
        }
        val roots = FuturesRootsFile.load(path)
        val futures =
            if (roots.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    ContractCatalogRegistry.load(
                        roots,
                        ContractCatalogStore(dataRoot),
                        RollHistoryStore(dataRoot),
                        FundingRateStore(dataRoot).takeIf { funding },
                    ),
                )
            }
        val optionRoots = OptionRootsFile.load(path)
        val options =
            if (optionRoots.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    liveClock?.let { RefreshingOptionCatalogs(optionRoots, dataRoot, it) }
                        ?: OptionCatalogRegistry.load(optionRoots, dataRoot),
                )
            }
        val registry =
            LayeredInstrumentRegistry(
                listOf(YamlInstrumentRegistry.load(path)) + futures + options + StandardInstrumentRegistry,
            )
        val unresolved =
            symbols.firstNotNullOfOrNull { s ->
                if (registry.lookup(s) ==
                    null
                ) {
                    registry.missingReason(s)
                } else {
                    null
                }
            }
        if (unresolved != null) throw BacktestContext.Companion.SetupError(unresolved)
        return registry
    }

    /**
     * The backtest's registry for [args] (`--instruments`, `--funding on|off`): perpetuals pay funding from
     * the stored rates unless `--funding off`, and a traded perpetual whose rates do not cover [from]..[to]
     * fails setup naming the fetch that would ([FundingCoverage]), as does an open-interest or depth stream whose
     * stored series does not cover them ([OpenInterestCoverage], [BookDepthCoverage]).
     */
    fun forBacktest(
        dataRoot: Path,
        args: Args,
        symbols: Collection<String>,
        from: Instant,
        to: Instant,
    ): InstrumentRegistry {
        val funding =
            when (val mode = args.option("funding") ?: "on") {
                "on" -> true
                "off" -> false
                else -> throw BacktestContext.Companion.SetupError("--funding must be on or off, not '$mode'")
            }
        val registry = registry(dataRoot, args.option("instruments")?.let(Path::of), symbols, funding = funding)
        if (funding) {
            FundingCoverage.problem(registry, symbols, from.toEpochMilli(), to.toEpochMilli())?.let {
                throw BacktestContext.Companion.SetupError(it)
            }
        }
        val (fromMs, toMs) = from.toEpochMilli() to to.toEpochMilli()
        (
            OpenInterestCoverage.problem(OpenInterestStore(dataRoot), symbols, fromMs, toMs)
                ?: BookDepthCoverage.problem(BookDepthStore(dataRoot), symbols, fromMs, toMs)
        )?.let { throw BacktestContext.Companion.SetupError(it) }
        // Run-level costing questions answered without editing instruments.yaml: every fill in
        // this run books the override rates instead of the file's per-symbol ones. Symbols without
        // metadata bill nothing either way, so name them — otherwise the flags silently do nothing.
        val commissionOverride = nonnegativeDecimal(args, "commission-per-lot", "a non-negative amount per 1.0 lot")
        val swapScale = nonnegativeDecimal(args, "swap-scale", "a non-negative financing multiplier")
        var withCosts: InstrumentRegistry = registry
        if (commissionOverride != null) withCosts = CommissionOverrideRegistry(withCosts, commissionOverride)
        if (swapScale != null) withCosts = SwapScaleRegistry(withCosts, swapScale)
        if ((commissionOverride != null && commissionOverride.signum() != 0) ||
            (swapScale != null && swapScale.compareTo(BigDecimal.ONE) != 0)
        ) {
            val active =
                listOfNotNull(
                    "--commission-per-lot".takeIf { commissionOverride != null },
                    "--swap-scale".takeIf { swapScale != null },
                )
            val unbillable =
                symbols
                    .filter { sym -> sym.substringBefore(':') !in NON_TRADABLE_BROKERS }
                    .filter { sym -> withCosts.lookup(sym) == null }
            if (unbillable.isNotEmpty()) {
                System.err.println(
                    "qkt: WARNING — ${active.joinToString(" and ")} do not apply to ${unbillable.joinToString()}: " +
                        "no instrument metadata (add it to instruments.yaml or pass --instruments <file>)",
                )
            }
        }
        return withCosts
    }

    private fun nonnegativeDecimal(
        args: Args,
        flag: String,
        what: String,
    ): BigDecimal? =
        args.option(flag)?.let { raw ->
            val value =
                raw.toBigDecimalOrNull()
                    ?: throw BacktestContext.Companion.SetupError("bad --$flag '$raw': expected $what")
            if (value.signum() < 0) {
                throw BacktestContext.Companion.SetupError("bad --$flag '$raw': expected $what")
            }
            value
        }

    /** Stream brokers that never trade, so a missing commission rate for them is not warned about. */
    private val NON_TRADABLE_BROKERS =
        setOf(
            "MACRO",
            HUB_BROKER,
            CHAIN_BROKER,
            OPTIONS_BROKER,
            BookDepthSymbol.BROKER,
            OpenInterestSymbol.BROKER,
        )
}
