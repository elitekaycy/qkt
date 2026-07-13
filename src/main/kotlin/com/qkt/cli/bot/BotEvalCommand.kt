package com.qkt.cli.bot

import com.qkt.candles.TimeWindow
import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.common.SystemClock
import com.qkt.dsl.stdlib.IndicatorInput
import com.qkt.dsl.stdlib.IndicatorRegistry
import com.qkt.indicators.Indicator
import com.qkt.marketdata.Candle
import com.qkt.trade.BotGateway
import java.math.BigDecimal

/**
 * `qkt bot eval "ema(21)" BROKER:SYMBOL --tf 1h [--count 500]` — evaluates one
 * registry indicator over bars fetched from the broker, the AI's pre-trade analysis
 * primitive. e.g. `bot eval "rsi(14)" EXNESS:XAUUSD --tf 1h --json` prints the
 * current value plus readiness metadata.
 */
class BotEvalCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) { execute(json) }
    }

    private fun execute(json: Boolean): Int {
        val expr = args.requirePositional(0, "indicator expression, e.g. \"ema(21)\"")
        val symbol = args.requirePositional(1, "BROKER:SYMBOL")
        val window = TimeWindow.parse(args.requireOption("tf"))
        val count = args.option("count")?.toIntOrNull() ?: 500
        val bars = gatewayBars(symbol, window, count)
        val result = evalIndicator(expr, bars)
        if (json) {
            println(
                jsonObj(
                    "ok" to (result.value != null),
                    "expression" to expr,
                    "symbol" to symbol,
                    "tf" to window.toString(),
                    "value" to result.value,
                    "isReady" to result.isReady,
                    "warmupBars" to result.warmupBars,
                    "barsUsed" to bars.size,
                    "lastBarStart" to bars.lastOrNull()?.startTime,
                    "lastClose" to bars.lastOrNull()?.close,
                    "error" to
                        if (result.value ==
                            null
                        ) {
                            "indicator not ready: needs ${result.warmupBars} bars, got ${bars.size}"
                        } else {
                            null
                        },
                ),
            )
            return if (result.value != null) ExitCodes.SUCCESS else ExitCodes.USER_ERROR
        }
        return if (result.value != null) {
            println("$expr on $symbol $window = ${result.value.toPlainString()} (over ${bars.size} bars)")
            ExitCodes.SUCCESS
        } else {
            botFail(false, "indicator not ready: needs ${result.warmupBars} bars, got ${bars.size}")
        }
    }

    private fun gatewayBars(
        symbol: String,
        window: TimeWindow,
        count: Int,
    ): List<Candle> = BotGateway.forSymbol(botConfig(args), symbol).bars(symbol, window, count, SystemClock().now())
}

/** Result of evaluating one indicator over a bar series. [value] is null until warm. */
internal data class BotEvalResult(
    val value: BigDecimal?,
    val isReady: Boolean,
    val warmupBars: Int,
)

/**
 * Parses `name(arg, ...)` (numeric literal args), creates the indicator from
 * [IndicatorRegistry], feeds every bar (closes for numeric-series indicators, whole
 * candles for candle-series like ATR), and returns the final value. Two-series
 * indicators (CORRELATION, BETA) are rejected — there is only one bar feed one-shot.
 */
internal fun evalIndicator(
    expr: String,
    bars: List<Candle>,
): BotEvalResult {
    val match =
        Regex("^\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)\\s*$").find(expr)
            ?: error("expression must be indicator(args), e.g. ema(21); got '$expr'")
    val (name, rawArgs) = match.destructured
    val spec =
        IndicatorRegistry.spec(name.uppercase())
            ?: error("unknown indicator '$name'; see the DSL indicator reference")
    require(spec.seriesCount == 1) { "two-series indicators like ${spec.name} are not supported one-shot" }
    val constArgs =
        rawArgs
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.toBigDecimalOrNull() ?: error("indicator args must be numeric literals, got '$it'") }
    val output = IndicatorRegistry.create(spec.name, constArgs)
    when (spec.inputKind) {
        IndicatorInput.NUMERIC_SERIES -> {
            @Suppress("UNCHECKED_CAST")
            val ind = output as Indicator<BigDecimal>
            bars.forEach { ind.update(it.close) }
        }
        IndicatorInput.CANDLE_SERIES -> {
            @Suppress("UNCHECKED_CAST")
            val ind = output as Indicator<Candle>
            bars.forEach { ind.update(it) }
        }
        else ->
            error("indicator ${spec.name} needs a ${spec.inputKind} feed, which is not available one-shot")
    }
    return BotEvalResult(value = output.value(), isReady = output.isReady, warmupBars = output.warmupBars)
}
