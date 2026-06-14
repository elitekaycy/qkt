package com.qkt.lsp

/**
 * Curated hover documentation for qkt's vocabulary: one short markdown block per indicator,
 * function, and common keyword, each leading with the call signature.
 *
 * Indicator and function entries are keyed by their registry name (upper case) and must stay
 * exhaustive — `DocsDriftTest` fails if a registry name has no entry here, so adding an
 * indicator to qkt forces adding its documentation. Constant hovers are derived from the
 * registered value, so they need no entry.
 */
object QktDocs {
    /** Markdown hover for the indicator [name] (case-insensitive), or null if unknown. */
    fun indicator(name: String): String? = INDICATORS[name.uppercase()]

    /** Markdown hover for the function [name] (case-insensitive), or null if unknown. */
    fun function(name: String): String? = FUNCTIONS[name.uppercase()]

    /** Markdown hover for the keyword [name] (case-insensitive), or null if unknown. */
    fun keyword(name: String): String? = KEYWORDS[name.uppercase()]

    private fun doc(
        signature: String,
        description: String,
    ): String = "```qkt\n$signature\n```\n$description"

    private val INDICATORS: Map<String, String> =
        mapOf(
            "EMA" to
                doc(
                    "ema(value, period)",
                    "Exponential moving average. Weights recent bars more; reacts faster than SMA.",
                ),
            "SMA" to
                doc("sma(value, period)", "Simple moving average. Equal weighting over the window; smooth but slower."),
            "WMA" to
                doc(
                    "wma(value, period)",
                    "Weighted moving average. Linear weights — recent bars count more than SMA, less than EMA.",
                ),
            "DEMA" to
                doc("dema(value, period)", "Double exponential moving average. Less lag than EMA at the same period."),
            "TEMA" to doc("tema(value, period)", "Triple exponential moving average. Even less lag than DEMA."),
            "HMA" to
                doc(
                    "hma(value, period)",
                    "Hull moving average. Low lag and smooth, built from weighted moving averages.",
                ),
            "RSI" to
                doc(
                    "rsi(value, period)",
                    "Relative Strength Index, bounded 0-100. Below 30 oversold, above 70 overbought.",
                ),
            "ATR" to
                doc(
                    "atr(stream, period)",
                    "Average True Range (volatility). Pass the stream, not a field — it needs high/low/close.",
                ),
            "STDDEV" to doc("stddev(value, period)", "Rolling sample standard deviation over the window."),
            "VARIANCE" to
                doc("variance(value, period)", "Rolling sample variance over the window (standard deviation squared)."),
            "ZSCORE" to
                doc(
                    "zscore(value, period)",
                    "Standardized distance of the value from its rolling mean, in standard deviations.",
                ),
            "REGRESSION_SLOPE" to
                doc(
                    "regression_slope(value, period)",
                    "Slope of the least-squares line fitted over the window — trend direction and steepness.",
                ),
            "CORRELATION" to
                doc(
                    "correlation(value1, value2, period)",
                    "Pearson correlation between two series over the window, in [-1, 1].",
                ),
            "BETA" to
                doc("beta(value, benchmark, period)", "Beta of the series against a benchmark series over the window."),
            "WILLIAMS_R" to
                doc("williams_r(stream, period)", "Williams %R momentum oscillator, bounded -100 to 0. Candle-fed."),
            "CCI" to
                doc(
                    "cci(stream, period)",
                    "Commodity Channel Index — deviation from the typical-price moving average. Candle-fed.",
                ),
            "STOCH_K" to
                doc(
                    "stoch_k(stream, k_period, d_period)",
                    "Stochastic %K — close relative to the recent high/low range. Candle-fed.",
                ),
            "STOCH_D" to
                doc(
                    "stoch_d(stream, k_period, d_period)",
                    "Stochastic %D — the smoothed (signal) line of %K. Candle-fed.",
                ),
            "OBV" to
                doc(
                    "obv(stream)",
                    "On-Balance Volume — running volume total signed by each bar's direction. Candle-fed.",
                ),
            "KELTNER_UPPER" to
                doc("keltner_upper(stream, period, atr_mult)", "Upper Keltner channel: EMA plus atr_mult times ATR."),
            "KELTNER_MIDDLE" to
                doc(
                    "keltner_middle(stream, period, atr_mult)",
                    "Middle Keltner channel: the EMA of the typical price.",
                ),
            "KELTNER_LOWER" to
                doc("keltner_lower(stream, period, atr_mult)", "Lower Keltner channel: EMA minus atr_mult times ATR."),
            "PLUS_DI" to
                doc(
                    "plus_di(stream, period)",
                    "+DI, the positive directional indicator of the ADX system. Candle-fed.",
                ),
            "MINUS_DI" to
                doc(
                    "minus_di(stream, period)",
                    "-DI, the negative directional indicator of the ADX system. Candle-fed.",
                ),
            "ADX" to
                doc(
                    "adx(stream, period)",
                    "Average Directional Index — trend strength regardless of direction. Candle-fed.",
                ),
            "MACD" to
                doc(
                    "macd(value, fast, slow, signal)",
                    "MACD line: fast EMA minus slow EMA. Common settings (12, 26, 9).",
                ),
            "MACD_SIGNAL" to
                doc(
                    "macd_signal(value, fast, slow, signal)",
                    "MACD signal line: the EMA of the MACD line over `signal` bars.",
                ),
            "MACD_HIST" to doc("macd_hist(value, fast, slow, signal)", "MACD histogram: MACD line minus signal line."),
            "BOLLINGER_UPPER" to
                doc(
                    "bollinger_upper(value, period, stddev)",
                    "Upper Bollinger band: SMA plus stddev standard deviations.",
                ),
            "BOLLINGER_MIDDLE" to
                doc("bollinger_middle(value, period, stddev)", "Middle Bollinger band: the SMA over the window."),
            "BOLLINGER_LOWER" to
                doc(
                    "bollinger_lower(value, period, stddev)",
                    "Lower Bollinger band: SMA minus stddev standard deviations.",
                ),
            "VWAP" to
                doc(
                    "vwap(stream, period)",
                    "Volume-weighted average price over the last `period` ticks. Pass the stream — it needs volume.",
                ),
            "HIGHEST" to
                doc(
                    "highest(value, period)",
                    "Highest value over the `period` bars BEFORE the current one (excludes the evaluating bar).",
                ),
            "LOWEST" to
                doc(
                    "lowest(value, period)",
                    "Lowest value over the `period` bars BEFORE the current one (excludes the evaluating bar).",
                ),
        )

    private val FUNCTIONS: Map<String, String> =
        mapOf(
            "ABS" to doc("abs(x)", "Absolute value of x."),
            "SQRT" to doc("sqrt(x)", "Square root of x. Null when x is negative."),
            "LOG" to doc("log(x)", "Natural logarithm of x. Null when x is zero or negative."),
            "EXP" to doc("exp(x)", "e raised to the power x."),
            "POW" to doc("pow(base, exponent)", "base raised to exponent."),
            "MIN" to doc("min(a, b, ...)", "Smallest of two or more values."),
            "MAX" to doc("max(a, b, ...)", "Largest of two or more values."),
            "CALENDAR_WINDOW" to
                doc(
                    "calendar_window(startMonth, startDay, endMonth, endDay)",
                    "True while the current UTC date is inside the annual window, inclusive. " +
                        "Wraps the year end when the start is later than the end, e.g. " +
                        "`calendar_window(12, 1, 1, 31)` is Dec 1 - Jan 31.",
                ),
            "SESSION_WINDOW" to
                doc(
                    "session_window(startHour, startMinute, endHour, endMinute)",
                    "True while the current UTC time-of-day is inside the daily window, inclusive. " +
                        "Wraps midnight when the start is later than the end, e.g. " +
                        "`session_window(0, 30, 1, 30)` is 00:30-01:30 UTC.",
                ),
        )

    private val KEYWORDS: Map<String, String> =
        mapOf(
            "STRATEGY" to "Declares a single strategy file: `STRATEGY <name> VERSION <n>`.",
            "PORTFOLIO" to "Declares a portfolio that composes imported strategies: `PORTFOLIO <name> VERSION <n>`.",
            "VERSION" to "Schema/strategy version in the header, e.g. `VERSION 1`.",
            "SYMBOLS" to "Block declaring the streams a strategy trades: `<alias> = <BROKER>:<SYMBOL> EVERY <tf>`.",
            "DEFAULTS" to "Block setting default order/sizing/bracket behavior applied to every rule.",
            "LET" to "Names a reusable expression: `LET name = <expr>`. Evaluated per bar.",
            "PARAM" to "Declares a tunable literal parameter overridable from the CLI: `PARAM name = <literal>`.",
            "RULES" to "Block holding the `WHEN ... THEN ...` trading rules.",
            "SCHEDULE" to "Time-triggered action block: `SCHEDULE AT 09:00 UTC THEN ...`.",
            "WHEN" to "Condition of a rule: `WHEN <condition> THEN <action>`.",
            "THEN" to "Separates a rule's condition from the action it fires.",
            "EVERY" to "Stream bar interval in `SYMBOLS`, e.g. `EVERY 1m`, `EVERY 5m`, `EVERY 1h`.",
            "BUY" to "Action: open or add to a long position. `BUY <alias> SIZING <expr>`.",
            "SELL" to "Action: open or add to a short position. `SELL <alias> SIZING <expr>`.",
            "CLOSE" to "Action: close the position on a stream. `CLOSE <alias>`.",
            "FLATTEN" to "Action: close every open position for the strategy.",
            "SIZING" to "Position size for an entry: `SIZING <units>`, `SIZING RISK $ <amount>`, or `SIZING <pct>`.",
            "RISK" to "Risk-based sizing: `SIZING RISK $ <amount>` sizes so the stop loses that amount.",
            "BRACKET" to "Attaches a stop loss and/or take profit to an entry.",
            "CROSSES" to "Crossover condition: `<a> CROSSES ABOVE <b>` / `CROSSES BELOW <b>`.",
            "ABOVE" to "Direction for `CROSSES`: the left side rises through the right side.",
            "BELOW" to "Direction for `CROSSES`: the left side falls through the right side.",
            "BETWEEN" to "Range test: `<expr> BETWEEN <lo> AND <hi>`.",
            "AND" to "Boolean conjunction in a condition.",
            "OR" to "Boolean disjunction in a condition.",
            "NOT" to "Boolean negation in a condition.",
            "IS" to "Null test: `<expr> IS NULL` / `<expr> IS NOT NULL`.",
            "NULL" to "The missing/undefined value. Indicators return it during warmup.",
        )
}
