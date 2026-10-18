package com.qkt.cli

/**
 * The lines `qkt run` prints as it starts: what runs, how the paper broker fills, and what it subscribed to.
 * The paper broker is a middle-price model, as the backtest's `--broker paper` is (#1454), so a paper run
 * reproduces that backtest fill for fill; the line says so, and names the backtest that prices the spread.
 */
internal fun printRunBanner(
    strategy: String,
    version: Int,
    feedSymbols: List<String>,
) {
    println("[INFO] qkt ${BuildInfo.VERSION} — strategy $strategy v$version — paper-trading")
    println(
        "[INFO] paper fills: the middle price, no spread or slippage (a backtest with --broker mt5-sim prices the spread)",
    )
    println("[INFO] subscribed: ${feedSymbols.joinToString(", ")}")
}
