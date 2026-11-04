package com.qkt.cli.golden

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.GoldenCommand
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat

/**
 * Captures [audit] (engine journal lines of session `alpha`) into a golden bundle with one MT5 exchange
 * at [transportMs], materializes it, and returns the replay data root.
 */
internal fun captureAndMaterialize(
    tmp: Path,
    audit: List<String>,
    transportMs: Long = 1_100,
): Path {
    val journal = tmp.resolve("state/audit-journal/alpha/audit-1970-01-01.jsonl")
    val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-1970-01-01.jsonl")
    Files.createDirectories(journal.parent)
    Files.createDirectories(transport.parent)
    Files.writeString(journal, audit.joinToString("\n", postfix = "\n"))
    val exchange = """{"v":1,"ts":$transportMs,"method":"GET","path":"/account","responseCode":200}"""
    Files.writeString(transport, exchange + "\n")
    val bundle = tmp.resolve("golden.zip")
    val capture =
        arrayOf(
            "golden",
            "capture",
            "--session",
            "alpha",
            "--state-dir",
            tmp.toString(),
            "--out",
            bundle.toString(),
            "--read-only",
        )
    assertThat(GoldenCommand(Args(capture)).run()).isEqualTo(ExitCodes.SUCCESS)
    val root = tmp.resolve("replay")
    val materialize = arrayOf("golden", "materialize", "--bundle", bundle.toString(), "--out", root.toString())
    assertThat(GoldenCommand(Args(materialize)).run()).isEqualTo(ExitCodes.SUCCESS)
    return root
}

/** A live tick journal line. */
internal fun journalTick(
    seq: Long,
    ms: Long,
    symbol: String,
    price: String = "100",
): String =
    """{"v":1,"ts":$ms,"seq":$seq,"eventType":"com.qkt.events.TickEvent","symbol":"$symbol",""" +
        """"tick":{"timestampMs":$ms,"price":"$price","bid":"$price","ask":"$price"}}"""

/** A tick-aggregated bar journal line. */
internal fun journalCandle(
    seq: Long,
    symbol: String,
    startMs: Long,
    endMs: Long,
): String =
    """{"v":1,"ts":$endMs,"seq":$seq,"eventType":"com.qkt.events.CandleEvent","symbol":"$symbol",""" +
        """"candle":${candleBlock(startMs, endMs)}}"""

/** A strategy candle evaluation journal line. */
internal fun journalEvaluation(
    seq: Long,
    symbol: String,
    broker: String,
    startMs: Long,
    endMs: Long,
): String =
    """{"v":1,"ts":$endMs,"seq":$seq,"eventType":"com.qkt.events.StrategyCandleEvaluatedEvent",""" +
        """"strategyId":"alpha","symbol":"$symbol","alias":"m1","broker":"$broker","timeframe":"1m",""" +
        """"rulesEvaluated":1,"candle":${candleBlock(startMs, endMs)}}"""

private fun candleBlock(
    startMs: Long,
    endMs: Long,
): String =
    """{"startTimeMs":$startMs,"endTimeMs":$endMs,"open":"100","high":"100","low":"100","close":"100",""" +
        """"volume":"1"}"""
