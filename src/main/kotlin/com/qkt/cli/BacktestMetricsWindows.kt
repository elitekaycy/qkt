package com.qkt.cli

import com.qkt.backtest.Backtest
import com.qkt.backtest.BacktestResult
import com.qkt.backtest.MetricsWindow
import java.time.Instant

/**
 * Parses the sub-window reporting flags of `qkt backtest` (#1276):
 *
 *  - `--metrics-window name=from..to` (repeatable): an explicit `[from, to)` window, dates or
 *    ISO instants, clipped to the run's `--from`/`--to`.
 *  - `--oos-split <fraction|date>`: splits the run into `in_sample` and `out_of_sample` at a
 *    fraction of its span (`0.2` = the last 20% is out of sample) or at an instant.
 */
internal object BacktestMetricsWindows {
    /** Run [backtest] to the end, reporting the windows [args] declare over the run's [from]..[to]. */
    fun run(
        backtest: Backtest,
        args: Args,
        from: Instant,
        to: Instant,
    ): BacktestResult {
        val engine = backtest.toEngine()
        engine.declareMetricsWindows(parse(args, from, to))
        return engine.runToEnd()
    }

    fun parse(
        args: Args,
        from: Instant,
        to: Instant,
    ): List<MetricsWindow> {
        val windows = ArrayList<MetricsWindow>()
        for (spec in args.options("metrics-window")) {
            val eq = spec.indexOf('=')
            val range = spec.indexOf("..")
            require(eq > 0 && range > eq + 1 && range + 2 < spec.length) {
                "bad --metrics-window '$spec'; expected NAME=FROM..TO"
            }
            val name = spec.substring(0, eq).trim()
            val start = BacktestContext.parseInstant(spec.substring(eq + 1, range).trim())
            val end = BacktestContext.parseInstant(spec.substring(range + 2).trim())
            val clippedFrom = maxOf(start, from)
            val clippedTo = minOf(end, to)
            require(clippedFrom < clippedTo) { "--metrics-window '$name' lies outside the run's --from/--to" }
            windows.add(MetricsWindow(name, clippedFrom.toEpochMilli(), endMs(clippedTo, to)))
        }
        args.option("oos-split")?.let { raw ->
            val splitMs =
                raw.toDoubleOrNull()?.let { fraction ->
                    require(fraction > 0.0 && fraction < 1.0) { "--oos-split fraction must be in (0, 1): $raw" }
                    val span = to.toEpochMilli() - from.toEpochMilli()
                    to.toEpochMilli() - (span * fraction).toLong()
                } ?: BacktestContext.parseInstant(raw).toEpochMilli()
            require(splitMs > from.toEpochMilli() && splitMs < to.toEpochMilli()) {
                "--oos-split $raw must fall strictly inside the run's --from/--to"
            }
            windows.add(MetricsWindow("in_sample", from.toEpochMilli(), splitMs))
            windows.add(MetricsWindow("out_of_sample", splitMs, endMs(to, to)))
        }
        require(windows.map { it.name }.toSet().size == windows.size) {
            "metrics window names must be unique: ${windows.map { it.name }}"
        }
        return windows
    }

    /**
     * Windows are half-open, but the run itself reports the bar that closes exactly at `--to`
     * (`global` has no upper bound), so a window ending at the run end takes that sample too.
     */
    private fun endMs(
        end: Instant,
        runEnd: Instant,
    ): Long = if (end == runEnd) end.toEpochMilli() + 1L else end.toEpochMilli()
}
