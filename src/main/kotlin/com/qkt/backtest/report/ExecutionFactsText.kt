package com.qkt.backtest.report

import com.qkt.backtest.BrokerKind
import com.qkt.backtest.ExecutionFacts
import com.qkt.backtest.PerformanceReport
import com.qkt.backtest.SlippageSpec

/**
 * The plain-language description of a run's [ExecutionFacts] that the console summary, `--verbose` and
 * report.html all print (#1437): one labelled line per fact, each stating what the run did, e.g.
 * `Fills: recorded bid/ask from ticks`, `Slippage: 5 points against each market and stop fill`.
 */
object ExecutionFactsText {
    /** The assumption lines, as (label, text), in print order; only facts that apply to the run. */
    fun lines(f: ExecutionFacts): List<Pair<String, String>> =
        buildList {
            if (f.brokerSymbols.isNotEmpty() || (f.futures.isEmpty() && f.options.isEmpty())) add("Fills" to fills(f))
            slippage(f)?.let { add("Slippage" to it) }
            f.partialFillFraction?.let {
                val pct = HumanFormat.percent(it)
                add("Partial fills" to "each order fills in two slices, $pct first")
            }
            f.rejectEvery?.let {
                add(
                    "Rejections" to
                        if (it ==
                            1
                        ) {
                            "every order is rejected"
                        } else {
                            "every ${ordinal(it)} order is rejected"
                        },
                )
            }
            if (f.latencyMs > 0L) add("Latency" to "orders reach the venue ${f.latencyMs} ms after they are sent")
            if (f.stopLatencyMs > 0L) add("Stop delay" to "a triggered stop fills ${f.stopLatencyMs} ms later")
            if (f.futures.isNotEmpty()) {
                add("Futures" to "exchange simulator: executable price, root fees per fill, rolls booked as roll costs")
            }
            if (f.options.isNotEmpty()) {
                add("Options" to "next chain snapshot's bid/ask, capped venue fees, cash settlement at delivery")
            }
            if (f.barsWithoutTicks.isNotEmpty()) {
                val counts =
                    f.barsWithoutTicks
                        .toSortedMap()
                        .entries
                        .joinToString(", ") { "${it.key} ${it.value}" }
                add("Bars without ticks" to "$counts; these filled at the bar's own prices")
            }
        }

    /** [facts] as `  Label:   text`, the labels padded to one column of at least [minWidth]. */
    fun padded(
        facts: List<Pair<String, String>>,
        minWidth: Int = 12,
    ): List<String> {
        val width = maxOf(minWidth, (facts.maxOfOrNull { it.first.length } ?: 0) + 2)
        return facts.map { (label, text) -> "  ${"$label:".padEnd(width)}$text" }
    }

    /** Where the spread in the fills came from, e.g. `spread from ticks`; null when the fills carry none. */
    fun spread(f: ExecutionFacts): String? {
        if (f.broker == BrokerKind.PAPER) return null
        val q = f.quotes ?: return if (f.recorded) null else "spread from the data"
        val parts =
            listOfNotNull(
                "spread from ticks".takeIf { q.recorded > 0 },
                "spread from instruments.yaml".takeIf { q.venueSpread > 0 },
                "synthetic ${f.syntheticSpreadPoints}-point spread".takeIf { q.synthetic > 0 },
            )
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /**
     * The costs the run paid: the spread its fills carried and every cost actually incurred, e.g.
     * `spread from ticks, commission 262.99 USD, swap 319.33 USD`, or what was absent when nothing was.
     */
    fun costs(
        f: ExecutionFacts,
        g: PerformanceReport,
        currency: String?,
    ): String {
        val paid = incurred(g, currency)
        val spread = spread(f)
        if (spread == null && paid.isEmpty()) return "none modeled (no spread, commission or swap)"
        if (paid.isEmpty()) return "$spread; no commission or swap"
        return listOfNotNull(spread).plus(paid).joinToString(", ")
    }

    /** Each non-zero cost as `name amount`: commission, swap, rolls, funding. */
    fun incurred(
        g: PerformanceReport,
        currency: String?,
    ): List<String> =
        listOf(
            "commission" to g.commissionPaid,
            "swap" to g.swapPaid,
            "rolls" to g.rollCostsPaid,
            "funding" to g.fundingPaid,
        ).filter { it.second.signum() != 0 }
            .map { (name, amount) -> "$name ${HumanFormat.money(amount, currency, signed = false)}" }

    private fun fills(f: ExecutionFacts): String {
        if (f.broker == BrokerKind.PAPER) {
            val ticks = f.brokerSymbols - f.barLevelSymbols
            return when {
                f.barLevelSymbols.isEmpty() -> "middle price, no spread"
                ticks.isEmpty() -> "bar prices, at order levels, no spread"
                else -> {
                    val bars = f.barLevelSymbols.sorted().joinToString()
                    "middle price on ticks, order levels on bars ($bars), no spread"
                }
            }
        }
        val q = f.quotes
        if (!f.recorded || q == null) {
            return "bid/ask from the data, or a synthetic ${f.syntheticSpreadPoints}-point spread where it has none"
        }
        val parts =
            listOfNotNull(
                "recorded bid/ask from ticks".takeIf { q.recorded > 0 },
                "spread from instruments.yaml (${f.spreadModels.joinToString()})".takeIf { q.venueSpread > 0 },
                (
                    "a synthetic ${f.syntheticSpreadPoints}-point spread around the middle price, " +
                        "where the data had no bid/ask"
                ).takeIf { q.synthetic > 0 },
            )
        return if (parts.isEmpty()) "none (no order filled)" else parts.joinToString("; ")
    }

    private fun slippage(f: ExecutionFacts): String? {
        if (!f.recorded) return null
        val paperOnly = f.broker == BrokerKind.PAPER && f.futures.isEmpty()
        if (!f.slips) return "none"
        if (paperOnly) return "none (the paper broker does not slip fills; --broker mt5-sim does)"
        val amount =
            when (f.slippage) {
                SlippageSpec.FIXED_POINTS -> "${f.slippagePoints} points"
                SlippageSpec.UNIFORM_RANDOM -> "a random 0 to ${f.slippagePoints} points (seeded)"
                else ->
                    f.instrumentSlippagePoints
                        .filterValues { it > 0 }
                        .toSortedMap()
                        .entries
                        .joinToString(
                            ", ",
                            postfix = " (instruments.yaml slippagePoints)",
                        ) { "${it.key} ${it.value} points" }
            }
        val where = if (f.broker == BrokerKind.PAPER) " on futures" else ""
        return "$amount against each market and stop fill$where"
    }

    private fun ordinal(n: Int): String =
        when {
            n % 100 in 11..13 -> "${n}th"
            n % 10 == 1 -> "${n}st"
            n % 10 == 2 -> "${n}nd"
            n % 10 == 3 -> "${n}rd"
            else -> "${n}th"
        }
}
