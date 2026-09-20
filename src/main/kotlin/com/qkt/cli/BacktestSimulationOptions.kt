package com.qkt.cli

import com.qkt.accounting.AccountCurrency
import com.qkt.accounting.AccountingConfig
import com.qkt.accounting.FxMissingPolicy
import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionPreset
import com.qkt.backtest.ExecutionSimulationConfig
import com.qkt.backtest.SlippageSpec
import com.qkt.broker.TakeProfitFill
import com.qkt.cli.BacktestContext.Companion.SetupError
import java.math.BigDecimal

/** Reads the accounting and execution-simulation settings of a backtest from its flags and config. */
internal object BacktestSimulationOptions {
    fun accountingConfig(
        args: Args,
        cfg: Config,
    ): AccountingConfig {
        val cliSymbols =
            args.options("fx-symbol").associate { token ->
                val eq = token.indexOf('=')
                if (eq <= 0 || eq == token.lastIndex) {
                    throw SetupError("bad --fx-symbol '$token'; expected PAIR=QKT_SYMBOL")
                }
                token.substring(0, eq).trim() to token.substring(eq + 1).trim()
            }
        return AccountingConfig(
            accountCurrency =
                AccountCurrency(
                    args.option("account-currency")
                        ?: cfg.accountCurrency,
                ),
            missingPolicy =
                FxMissingPolicy.fromConfig(
                    args.option("fx-missing-policy")
                        ?: cfg.accountingConfig.missingPolicy.name
                            .lowercase(),
                ),
            source =
                args.option("fx-source")
                    ?: cfg.accountingConfig.source,
            symbols = cfg.accountingConfig.symbols + cliSymbols,
        )
    }

    fun executionConfig(
        args: Args,
        cfg: Config,
        brokerKind: BrokerKind,
    ): ExecutionSimulationConfig {
        val seed = args.option("seed")?.toLongOrNull() ?: cfg.execution["seed"]?.toLongOrNull()
        if (args.flag("chaos") && args.option("execution") != null) {
            throw SetupError("--chaos cannot be combined with --execution")
        }
        val preset =
            if (args.flag("chaos")) {
                ExecutionPreset.STRESS
            } else {
                (args.option("execution") ?: cfg.execution["preset"])
                    ?.let(ExecutionPreset::fromConfig)
            }
        var result =
            if (preset != null) {
                ExecutionSimulationConfig.defaultsFor(preset, seed)
            } else {
                ExecutionSimulationConfig.forBrokerKind(brokerKind).copy(seed = seed)
            }
        (args.option("execution-latency") ?: cfg.execution["latency"])?.let {
            result = result.copy(latencyMs = parseLatencyMs(it))
        }
        (args.option("stop-latency") ?: cfg.execution["stop_latency"])?.let {
            result = result.copy(stopLatencyMs = parseLatencyMs(it))
        }
        (args.option("tp-fill") ?: cfg.execution["tp_fill"])?.let {
            result = result.copy(takeProfitFill = parseTakeProfitFill(it))
        }
        // The live daemon reads the same key, so quiet bars close at the same moment (#1138).
        result = result.copy(candleCloseGraceMs = cfg.candleCloseGraceMs)
        (args.option("slippage") ?: cfg.execution["slippage"])?.let {
            val (spec, points) = parseSlippage(it)
            result = result.copy(slippage = spec, slippagePoints = points)
        }
        (args.option("reject-every") ?: cfg.execution["reject_every"])?.let {
            result = result.copy(rejectEvery = it.toIntOrNull() ?: throw SetupError("bad reject_every '$it'"))
        }
        (args.option("partial-fill") ?: cfg.execution["partial_fill"])?.let {
            result = result.copy(partialFillFraction = BigDecimal(it))
        }
        // CLI runs default to the production venue model (#1071): both live hosts are
        // hedging MT5 accounts, so research, gates, and replay grade hedging books
        // unless the operator explicitly selects netting.
        val positionModeRaw =
            args.option("position-mode") ?: cfg.execution["position_mode"] ?: "hedging"
        positionModeRaw.let {
            result =
                result.copy(
                    positionMode =
                        when (it.trim().lowercase()) {
                            "netting" -> com.qkt.broker.PositionAccountingMode.NETTING
                            "hedging" -> com.qkt.broker.PositionAccountingMode.HEDGING
                            else -> throw SetupError(
                                "unknown position mode '$it' (valid: netting, hedging)",
                            )
                        },
                )
        }
        return result
    }

    private fun parseTakeProfitFill(raw: String): TakeProfitFill =
        try {
            TakeProfitFill.fromConfig(raw)
        } catch (e: IllegalStateException) {
            throw SetupError(e.message ?: "bad tp_fill '$raw'")
        }

    private fun parseLatencyMs(raw: String): Long {
        val trimmed = raw.trim().lowercase().removePrefix("fixed:")
        val millis =
            when {
                trimmed.endsWith("ms") -> trimmed.removeSuffix("ms")
                trimmed.endsWith("s") ->
                    return (trimmed.removeSuffix("s").toBigDecimal() * BigDecimal("1000")).toLong()
                else -> trimmed
            }
        return millis.toLongOrNull() ?: throw SetupError("bad execution latency '$raw'")
    }

    private fun parseSlippage(raw: String): Pair<SlippageSpec, Int> {
        val trimmed = raw.trim().lowercase()
        return when {
            trimmed == "zero" || trimmed == "none" -> SlippageSpec.ZERO to 0
            trimmed == "instrument" || trimmed == "instrument:slippagepoints" -> SlippageSpec.INSTRUMENT to 0
            trimmed.startsWith("fixed-points:") ->
                SlippageSpec.FIXED_POINTS to parsePoints(raw, trimmed.substringAfter(':'))
            trimmed.startsWith("fixed:") ->
                SlippageSpec.FIXED_POINTS to parsePoints(raw, trimmed.substringAfter(':'))
            trimmed.startsWith("uniform-random:") ->
                SlippageSpec.UNIFORM_RANDOM to parsePoints(raw, trimmed.substringAfter(':'))
            trimmed.startsWith("uniform:") ->
                SlippageSpec.UNIFORM_RANDOM to parsePoints(raw, trimmed.substringAfter(':'))
            else -> throw SetupError("bad slippage '$raw' (valid: zero, instrument, fixed-points:N, uniform:N)")
        }
    }

    private fun parsePoints(
        raw: String,
        points: String,
    ): Int =
        points.toIntOrNull()
            ?: throw SetupError("bad slippage '$raw': points must be an integer")
}
