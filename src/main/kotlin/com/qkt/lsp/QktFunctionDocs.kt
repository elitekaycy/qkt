package com.qkt.lsp

/**
 * Hover documentation for the scalar functions and the parser's call-shaped clock predicates, keyed by their
 * registry name (upper case). [QktDocs] serves them; `DocsDriftTest` keeps them exhaustive both ways.
 */
internal object QktFunctionDocs {
    val entries: Map<String, String> =
        mapOf(
            "ABS" to doc("abs(x)", "Absolute value of x."),
            "ROUND_TO" to
                doc(
                    "round_to(x, step)",
                    "Round x to the nearest multiple of step (a price grid). e.g. round_to(2347, 25) = 2350. " +
                        "Pair with mod(x, step) for distance to the nearest round figure.",
                ),
            "SQRT" to doc("sqrt(x)", "Square root of x. Null when x is negative."),
            "LOG" to doc("log(x)", "Natural logarithm of x. Null when x is zero or negative."),
            "EXP" to doc("exp(x)", "e raised to the power x."),
            "POW" to doc("pow(base, exponent)", "base raised to exponent."),
            "MIN" to doc("min(a, b, ...)", "Smallest of two or more values."),
            "MAX" to doc("max(a, b, ...)", "Largest of two or more values."),
            "RANK_OF" to
                doc(
                    "rank_of(self, other, ...)",
                    "Cross-sectional rank of the first value among all the values (1 = highest). " +
                        "List one expression per stream to rank a cross-section, e.g. " +
                        "`rank_of(a.mom, b.mom, c.mom) == 1` is true on the strongest stream and " +
                        "`<= 2` selects the top two. Ties share the top rank.",
                ),
            "MOD" to
                doc(
                    "mod(a, b)",
                    "Floored modulo `a - b*floor(a/b)`; for a positive step it lands in [0, b). Null when b is 0.",
                ),
            "FLOOR" to doc("floor(x)", "Largest integer not greater than x."),
            "CEIL" to doc("ceil(x)", "Smallest integer not less than x."),
            "ROUND" to doc("round(x)", "Nearest integer, halves rounded away from zero."),
            "NORMALIZE" to
                doc(
                    "normalize(self, other, ...)",
                    "Min-max scaled score of the first value among all the values, in [0, 1]. " +
                        "e.g. normalize(3, 1, 5) = 0.5. Returns 0 when all values are equal. " +
                        "Use softmax for probability weights that sum to 1.",
                ),
            "NORM_CDF" to
                doc(
                    "norm_cdf(x)",
                    "Standard normal cumulative probability of x, in [0, 1]: norm_cdf(0) = 0.5, " +
                        "norm_cdf(1.96) ≈ 0.975. e.g. the fair value of \"settles at or above K\" is " +
                        "norm_cdf((ref - K) / (vol * sqrt(t))).",
                ),
            "SOFTMAX" to
                doc(
                    "softmax(self, other, ...)",
                    "Softmax probability weight of the first value among all the values, in (0, 1). " +
                        "Symmetric calls across streams sum to 1, making it useful for regime " +
                        "probability weighting. Numerically stable for large inputs.",
                ),
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
            "LAST_TRADING_DAY_OF_MONTH" to
                doc(
                    "last_trading_day_of_month()",
                    "True on the last trading day of the current UTC month — the last weekday " +
                        "(Mon-Fri). Isolates month-end flow without hard-coding dates. Takes no arguments; " +
                        "does not consult an exchange holiday calendar.",
                ),
        )

    private fun doc(
        signature: String,
        description: String,
    ): String = "```qkt\n$signature\n```\n$description"
}
