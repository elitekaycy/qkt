package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.CliDataRoot
import com.qkt.cli.ExitCodes
import com.qkt.cli.InstrumentFiles
import com.qkt.common.SystemClock
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.run.ImportRun
import java.nio.file.Path

/**
 * `qkt data import <file|dir>... --as <SYMBOL> [--kind] [--preset] [--map] [--time-format] [--tz] [--tf]
 * [--price] [--label] [--available|--available-lag] [--digits] [--decimal] [--delimiter] [--on-overlap]
 * [--sort] [--trust-clock] [--encoding] [--dry-run] [--plan] [--json] [--data-root] [--instruments]`
 *
 * Turns market-data files from any source (MT5 and MT4 exports, Dukascopy, HistData, TradingView,
 * Binance, Bybit, OKX, Kraken, Yahoo, Alpha Vantage, Polygon, OANDA, ccxt, or any CSV/JSON described with
 * flags) into qkt's own store, exactly: see `docs/how-to/import-data.md`. Validates everything before
 * writing anything; refuses rather than guesses when the evidence cannot settle a clock, a format or a
 * meaning. Exit 0 when written (or a clean dry run), 1 when refused, 2 for bad arguments.
 */
internal fun dataImport(args: Args): Int {
    val paths = positionalPaths(args)
    if (paths.isEmpty()) {
        System.err.println("qkt: missing input. usage: qkt data import <file|dir>... --as <SYMBOL> [--dry-run]")
        return ExitCodes.ARG_ERROR
    }
    val json = args.flag("json")
    val dataRoot = CliDataRoot.of(args)
    if (args.option("kind") == CorporateActionImport.KIND) return CorporateActionImport.run(args, paths, dataRoot)
    val request =
        try {
            DataImportOptions.request(args, paths, dataRoot)
        } catch (e: IllegalArgumentException) {
            System.err.println("qkt: ${e.message}")
            return ExitCodes.ARG_ERROR
        }
    return try {
        val registry = InstrumentFiles.registry(dataRoot, args.option("instruments")?.let(Path::of))
        val report = ImportRun(request, registry, SystemClock()).run()
        when {
            json -> println(ImportReportJson.render(report))
            request.planOnly -> print(report.plan)
            else -> print(ImportReportText.render(report))
        }
        ExitCodes.SUCCESS
    } catch (e: ImportRefusal) {
        if (json) {
            println(ImportReportJson.refusal(e))
        } else {
            System.err.println("qkt: ${e.describe()}\n  Nothing was written.")
        }
        ExitCodes.USER_ERROR
    }
}

/** Every positional after `import`: the files and directories to read. */
private fun positionalPaths(args: Args): List<Path> {
    val out = mutableListOf<Path>()
    var i = 1
    while (true) {
        out.add(Path.of(args.positional(i) ?: return out))
        i++
    }
}
