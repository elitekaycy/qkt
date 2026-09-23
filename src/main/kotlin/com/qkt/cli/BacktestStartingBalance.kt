package com.qkt.cli

import java.math.BigDecimal

/** Where a backtest's starting balance came from. */
enum class StartingBalanceSource {
    /** `--starting-balance` on the command line. */
    FLAG,

    /** `starting_balance` in the resolved config — the balance the live daemon uses. */
    CONFIG,

    /** Neither was set; the backtest CLI default. */
    DEFAULT,
}

/** A resolved backtest starting balance and the setting it came from. */
data class ResolvedStartingBalance(
    val amount: BigDecimal,
    val source: StartingBalanceSource,
) {
    /** One stderr line naming the balance and its source. */
    fun notice(): String =
        when (source) {
            StartingBalanceSource.FLAG -> "starting balance ${amount.toPlainString()} from --starting-balance"
            StartingBalanceSource.CONFIG -> "starting balance ${amount.toPlainString()} from config starting_balance"
            StartingBalanceSource.DEFAULT -> "starting balance ${amount.toPlainString()} (backtest default)"
        }
}

/**
 * Resolves a single-strategy backtest's starting balance and prints where it came from: the
 * `--starting-balance` flag when given, else a positive `starting_balance` from [cfg] (the
 * balance the live daemon sizes its drawdown halts on), else `10000`. Reading the config keeps
 * a backtest's halts and percent sizing on the same balance the daemon uses for the same config.
 */
fun backtestStartingBalance(
    args: Args,
    cfg: Config,
): BigDecimal =
    resolveBacktestStartingBalance(args.option("starting-balance"), cfg.startingBalance)
        .also { System.err.println("qkt: ${it.notice()}") }
        .amount

/** Pure resolution behind [backtestStartingBalance]; see its rules. */
fun resolveBacktestStartingBalance(
    flag: String?,
    configBalance: BigDecimal,
): ResolvedStartingBalance =
    when {
        flag != null -> ResolvedStartingBalance(BigDecimal(flag), StartingBalanceSource.FLAG)
        configBalance.signum() > 0 -> ResolvedStartingBalance(configBalance, StartingBalanceSource.CONFIG)
        else -> ResolvedStartingBalance(BigDecimal("10000"), StartingBalanceSource.DEFAULT)
    }
