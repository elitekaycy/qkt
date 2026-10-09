package com.qkt.cli

/**
 * Plain-language flag descriptions for the backtest family (`backtest`, `sweep`, `walkforward`,
 * `research`), which share one option set (#1380). Written for non-experts: what the flag does,
 * its default, and an example where one clarifies. Other commands render their schema flag names
 * without descriptions until they grow their own map.
 */
internal object BacktestFlagHelp {
    /**
     * The flags that matter on first contact: identity of the run, where data comes from, what
     * fills model, and where output goes. Everything else waits behind `--help --all`. Account,
     * currency and costs are config, not flags — the core list points there instead.
     */
    val core: Set<String> =
        setOf(
            "from",
            "to",
            "broker",
            "data-root",
            "bars",
            "no-fetch",
            "allow-incomplete",
            "report-dir",
            "json",
            "verbose",
            "commission-per-lot",
        )

    /** Flag/option name to one-line description. */
    val descriptions: Map<String, String> =
        mapOf(
            "from" to "window start, e.g. --from 2025-09-01 (required)",
            "to" to "window end, exclusive, e.g. --to 2025-10-01 (required)",
            "starting-balance" to "simulated starting capital, e.g. --starting-balance 25000 (default 10000)",
            "symbols" to "comma list overriding the strategy's streams, e.g. --symbols BACKTEST:EURUSD",
            "data-root" to "tick store location (default ~/.qkt/data)",
            "hub-root" to "HUB: stream store location (default follows the data root)",
            "fetcher" to "tick fetcher, only dukascopy is supported (default auto-fetch)",
            "fetcher-script" to "script backing --fetcher dukascopy",
            "instruments" to "instruments.yaml path (default <data-root>/instruments.yaml)",
            "broker" to "paper (default, mid-price fills) or mt5-sim (spread + slippage)",
            "config" to "qkt.config.yaml path (default ./qkt.config.yaml)",
            "bar-tf" to "replay timeframe with --bars, e.g. --bar-tf 15m (must divide the strategy timeframe)",
            "fx-symbol" to "symbol used for currency conversion when the quote differs from the account",
            "account-currency" to "booking currency code, e.g. --account-currency USD",
            "fx-missing-policy" to "warn or fail on a missing FX rate (default fail)",
            "fx-source" to "FX rate source for conversion",
            "seed" to "deterministic seed, e.g. --seed 7 (default random)",
            "execution" to "execution preset name from config, e.g. --execution mt5-realistic",
            "execution-latency" to "order delay model, e.g. --execution-latency fixed:100ms",
            "order-spacing" to "minimum spacing between orders, e.g. --order-spacing 500ms",
            "stop-latency" to "stop-order delay, e.g. --stop-latency 300ms",
            "tp-fill" to "take-profit fill model: level or touch",
            "slippage" to "slippage model, e.g. --slippage fixed-points:3",
            "reject-every" to "chaos testing: reject every Nth order (default off)",
            "partial-fill" to "chaos testing: split fills into partials (default off)",
            "dataset" to "pinned dataset snapshot to replay instead of the live store",
            "position-mode" to "netting or hedging position model",
            "funding" to "perpetual funding: on (default) or off",
            "commission-per-lot" to "override every symbol's commission, e.g. --commission-per-lot 7 (account currency per 1.0 lot per side)",
            "swap-scale" to "scale overnight financing, e.g. --swap-scale 0 (swap-free) or 2 (double stress)",
            "param" to "override one PARAM/LET, e.g. --param fast=5 (repeatable; comma lists need sweep)",
            "report-dir" to "where to write the report bundle (default ~/.qkt/runs/<time>-<strategy>/)",
            "metrics-window" to "extra sub-window NAME=FROM..TO to report beside global (repeatable)",
            "oos-split" to "out-of-sample split as a fraction or date, e.g. --oos-split 0.3",
            "rank" to "sweep ranking metric: sharpe, calmar, profitFactor, totalPnL or winRate",
            "parallelism" to "sweep workers, e.g. --parallelism 4 (default 1)",
            "scenarios" to "scenario file for sweep fan-out",
            "large-search-threshold" to "warn above this sweep combo count",
            "train" to "walkforward training window, e.g. --train 90d",
            "test" to "walkforward test window, e.g. --test 30d",
            "step" to "walkforward step, e.g. --step 30d",
            "topN" to "walkforward survivors per fold, e.g. --topN 3",
            "no-fetch" to "never download; fail on missing days instead",
            "bars" to "replay from built bars: faster, approximate fills",
            "allow-incomplete" to "run on whatever days exist, gaps and all",
            "tick-fills" to "with --bars, resolve fills from ticks (needs single timeframe, no latency)",
            "enforce-live-breakers" to "halt replay at live runaway thresholds (default observe-only)",
            "chaos" to "seeded stress preset; cannot combine with --execution",
            "json" to "single-line JSON for tools (stdout stays pipe-clean)",
            "debug" to "full output plus stack traces on errors",
            "verbose" to "full output plus engine logs (default is the short summary)",
            "no-report" to "write no report bundle to disk",
        )
}
