package com.qkt.cli.bot

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

private const val GOLD = "EXNESS:XAUUSD"

/**
 * A live record over 36 ticks 5 s apart (bid and ask 0.05 either side of mid): a buy arriving at 42 s and a
 * close at 97 s, with [fills] (side, price, ms after the first tick) as the venue booked them.
 */
internal fun liveRecord(
    tmp: Path,
    fills: List<Triple<String, String, Long>>,
    symbol: String = GOLD,
): Path {
    val t0 =
        java.time.Instant
            .parse("2026-07-09T12:18:30Z")
            .toEpochMilli()
    val dir = tmp.resolve("live")
    val argv = listOf("start", "--symbols", symbol, "--tf", "1m", "--identities", "brain", "--run", "r1", "--json")
    com.qkt.trade.session.writeSessionMeta(
        dir,
        // The pipeline hands the session ticks before it records its start (live: 142 ticks, 26 s).
        com.qkt.trade.session
            .SessionMeta("r1", "live", listOf("brain"), argv, t0 + 12_000L),
    )
    com.qkt.trade.session.LiveTickLog(dir.resolve("ticks.csv")).use { log ->
        (0 until 36).forEach { i ->
            val mid = BigDecimal("2400.00").add(BigDecimal("0.10").multiply(BigDecimal(i)))
            log.append(
                com.qkt.marketdata.Tick(
                    symbol,
                    mid,
                    t0 + i * 5_000L,
                    bid = mid - BigDecimal("0.05"),
                    ask = mid + BigDecimal("0.05"),
                ),
            )
        }
    }
    val journal =
        com.qkt.trade.session
            .IntentJournal(dir.resolve("intents.jsonl"))
    val header = "STRATEGY bot VERSION 1\nSYMBOLS\n    x = $symbol EVERY 1m\nRULES\n    WHEN true\n    THEN "
    // Live, an intent fires on the first tick stamped after it arrived: the buy on tick 9 (mid 2400.90) at its
    // ask, the close on tick 20 (mid 2402.00) at its bid. Its tickSeq lags (ticks the engine had not yet run).
    journal.intent("int-1", "brain", header + "BUY x SIZING 0.01\n", t0 + 42_000L, t0 + 42_000L, 7L)
    journal.intent("int-2", "brain", header + "CLOSE x\n", t0 + 97_000L, t0 + 97_000L, 17L)
    journal.finish(t0 + 150_000L, 30L)
    Files.write(
        dir.resolve("fills.jsonl"),
        fills.map { (side, price, at) ->
            """{"identity":"brain","orderId":"o","symbol":"$symbol","side":"$side","qty":0.01,"price":$price,""" +
                """"timeMs":${t0 + at}}"""
        },
    )
    // When the venue booked each fill, as the live fates record it.
    fills.forEachIndexed { i, (_, _, at) ->
        Files.writeString(
            dir.resolve("intents.jsonl"),
            """{"type":"fate","intentId":"int-${i + 1}","identity":"brain","state":"filled","orderIds":[],""" +
                """"reason":null,"atMs":${t0 + at},"compiled":true}""" + "\n",
            java.nio.file.StandardOpenOption.APPEND,
        )
    }
    return dir
}
