package com.qkt.cli.audit

import com.qkt.cli.ExitCodes
import com.qkt.connector.mt5.MT5Client
import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.tv.TradingViewMarketSource
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.concurrent.atomic.AtomicReference

private val MC = MathContext(8, RoundingMode.HALF_EVEN)

/**
 * Samples the latest TradingView tick against the MT5 mid every [pollMs] for [durationSeconds] and
 * reports the absolute price drift distribution (mean, median, p95, max).
 */
internal fun runTradingViewDriftAudit(
    client: MT5Client,
    symbol: String,
    brokerSymbol: String,
    durationSeconds: Long,
    pollMs: Long,
    jsonOutput: Boolean,
    outPath: String?,
): Int {
    val tvLatest = AtomicReference<Tick?>(null)
    val tvSource = TradingViewMarketSource.connect()
    val tvFeed = tvSource.liveTicks(listOf(symbol))

    val tvThread =
        Thread({
            while (!Thread.currentThread().isInterrupted) {
                val t = tvFeed.next() ?: break
                if (t.symbol == symbol) tvLatest.set(t)
            }
        }, "qkt-audit-tv-feed")
    tvThread.isDaemon = true
    tvThread.start()

    val samples = mutableListOf<Sample>()
    val deadline = System.currentTimeMillis() + durationSeconds * 1000L
    try {
        while (System.currentTimeMillis() < deadline) {
            val tvTick = tvLatest.get()
            val mt5Tick = client.getTick(brokerSymbol)
            if (tvTick != null && mt5Tick != null) {
                val tvMid = tvTick.price
                val mt5Mid = mt5Tick.bid.add(mt5Tick.ask).divide(BigDecimal("2"), MC)
                samples.add(Sample(absDiff = tvMid.subtract(mt5Mid).abs()))
            }
            Thread.sleep(pollMs)
        }
    } finally {
        runCatching { tvFeed.close() }
        tvThread.interrupt()
    }

    if (samples.isEmpty()) {
        println("no samples captured (TV feed may not have produced ticks for $symbol)")
        return ExitCodes.USER_ERROR
    }

    val sortedDiffs = samples.map { it.absDiff }.sorted()
    val mean =
        sortedDiffs
            .reduce { a, b -> a.add(b) }
            .divide(BigDecimal(sortedDiffs.size), MC)
    val median = sortedDiffs[sortedDiffs.size / 2]
    val p95 = sortedDiffs[(sortedDiffs.size * 95 / 100).coerceAtMost(sortedDiffs.size - 1)]
    val max = sortedDiffs.last()

    val json =
        """{"symbol":"$symbol","samples":${samples.size},""" +
            """"mean_abs_diff":"${mean.toPlainString()}",""" +
            """"median_abs_diff":"${median.toPlainString()}",""" +
            """"p95_abs_diff":"${p95.toPlainString()}",""" +
            """"max_abs_diff":"${max.toPlainString()}"}"""

    if (jsonOutput) {
        println(json)
    } else {
        println("samples:        ${samples.size}")
        println("mean abs diff:  ${mean.toPlainString()}")
        println("median abs diff:${median.toPlainString()}")
        println("p95 abs diff:   ${p95.toPlainString()}")
        println("max abs diff:   ${max.toPlainString()}")
    }

    persistAuditJson(outPath, json)

    return ExitCodes.SUCCESS
}

private data class Sample(
    val absDiff: BigDecimal,
)
