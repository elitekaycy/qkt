package com.qkt.backtest

/**
 * Selects which simulated broker backs a [Backtest] run.
 *
 * `PAPER` is the historical default — `PaperBroker` with no slippage, no spread,
 * no rounding. `MT5_SIM` opts in to [com.qkt.broker.MT5BrokerSimulator], which
 * mirrors MT5 venue quantization, spread, and ask/bid fill rules. See
 * `docs/parity/backtest-vs-live.md` for the fidelity contract each closes.
 */
enum class BrokerKind {
    PAPER,
    MT5_SIM,
}
