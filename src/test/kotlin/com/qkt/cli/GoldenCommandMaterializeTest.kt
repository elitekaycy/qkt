package com.qkt.cli

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenCommandMaterializeTest {
    @Test
    fun `materialize merges the same bar from the tick aggregator and the stream feed when only volume differs`(
        @TempDir tmp: Path,
    ) {
        // Live records a closed 1m bar twice: the tick aggregator's CandleEvent carries the
        // number of ticks it saw as volume, the gateway's StreamCandleEvent carries the venue
        // figure (often 0 on a quote feed). Same OHLC, same quotes, different volume. That is
        // one bar, not a conflict, and a stacking session's capture must stay replayable.
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1100,"seq":1,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:EURUSD","tick":{"timestampMs":10000,"price":"1.162355","bid":"1.16224","ask":"1.16247"}}
            {"v":1,"ts":1200,"seq":2,"eventType":"com.qkt.events.CandleEvent","symbol":"EXNESS:EURUSD","candle":{"startTimeMs":0,"endTimeMs":60000,"open":"1.162355","high":"1.162355","low":"1.162355","close":"1.16235500","volume":"1.00000000","bid":"1.16224000","ask":"1.16247000"}}
            {"v":1,"ts":1201,"seq":3,"eventType":"com.qkt.events.StreamCandleEvent","broker":"EXNESS","timeframe":"1m","symbol":"EXNESS:EURUSD","candle":{"startTimeMs":0,"endTimeMs":60000,"open":"1.16235500","high":"1.16235500","low":"1.16235500","close":"1.16235500","volume":"0.00000000","bid":"1.16224000","ask":"1.16247000"}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """{"v":1,"ts":1100,"method":"GET","path":"/account","responseCode":200}""" + "\n",
        )
        val output = tmp.resolve("volume-golden.zip")
        val code =
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "capture",
                        "--session",
                        "alpha",
                        "--state-dir",
                        tmp.toString(),
                        "--out",
                        output.toString(),
                        "--read-only",
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)

        val replayRoot = tmp.resolve("volume-replay")
        val materializeCode =
            GoldenCommand(
                Args(arrayOf("golden", "materialize", "--bundle", output.toString(), "--out", replayRoot.toString())),
            ).run()

        assertThat(materializeCode).isEqualTo(ExitCodes.SUCCESS)
        // one bar, carrying the stream feed's provenance (the higher priority) and its volume
        val bars = Files.readString(replayRoot.resolve("bars/EXNESS/EURUSD/1m/1970-01-01.csv"))
        assertThat(bars.lines().filter { it.startsWith("0,") }).hasSize(1)
        assertThat(bars).contains("0,1.16235500,1.16235500,1.16235500,1.16235500,0.00000000")
    }

    @Test
    fun `materialize merges a captured bar with the quote-less candle rehydrated from warmup ticks`(
        @TempDir tmp: Path,
    ) {
        // When warmup ticks overlap the live session, the same bar exists twice: once as captured
        // by the venue, once rebuilt from those ticks. Warmup ticks carry no bid/ask, so the
        // rebuilt candle has no quotes to compare -- which must not read as a conflict, or an
        // otherwise sound live capture cannot be replayed at all. OHLC still has to agree.
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"seq":1,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:EURUSD","sourceTimeframeMs":60000,"tick":{"timestampMs":0,"price":"1.162355"}}
            {"v":1,"ts":1001,"seq":2,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:EURUSD","sourceTimeframeMs":60000,"tick":{"timestampMs":59999,"price":"1.162355","volume":"1"}}
            {"v":1,"ts":1100,"seq":3,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:EURUSD","tick":{"timestampMs":60000,"price":"1.162355","bid":"1.16224","ask":"1.16247"}}
            {"v":1,"ts":1200,"seq":4,"eventType":"com.qkt.events.CandleEvent","symbol":"EXNESS:EURUSD","candle":{"startTimeMs":0,"endTimeMs":60000,"open":"1.162355","high":"1.162355","low":"1.162355","close":"1.162355","volume":"1","bid":"1.16224000","ask":"1.16247000"}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """{"v":1,"ts":1100,"method":"GET","path":"/account","responseCode":200}""" + "\n",
        )
        val output = tmp.resolve("warmup-golden.zip")
        assertThat(
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "capture",
                        "--session",
                        "alpha",
                        "--state-dir",
                        tmp.toString(),
                        "--out",
                        output.toString(),
                        "--read-only",
                    ),
                ),
            ).run(),
        ).isEqualTo(ExitCodes.SUCCESS)

        val replayRoot = tmp.resolve("warmup-replay")
        assertThat(
            GoldenCommand(
                Args(arrayOf("golden", "materialize", "--bundle", output.toString(), "--out", replayRoot.toString())),
            ).run(),
        ).isEqualTo(ExitCodes.SUCCESS)
        val bars = Files.readString(replayRoot.resolve("bars/EXNESS/EURUSD/1m/1970-01-01.csv"))
        assertThat(bars.lines().filter { it.startsWith("0,") }).hasSize(1)
    }

    @Test
    fun `materialize rejects a golden bundle with a modified engine entry`(
        @TempDir tmp: Path,
    ) {
        val bundle = createValidGolden(tmp)
        FileSystems.newFileSystem(bundle, emptyMap<String, Any>()).use { zip ->
            Files.writeString(
                zip.getPath("/engine/audit-2026-08-09.jsonl"),
                "{}\n",
                StandardOpenOption.APPEND,
            )
        }
        val output = tmp.resolve("replay-data")

        val code =
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "materialize",
                        "--bundle",
                        bundle.toString(),
                        "--out",
                        output.toString(),
                    ),
                ),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(output).doesNotExist()
    }

    private fun createValidGolden(tmp: Path): Path {
        val state = tmp.resolve("state")
        val audit = state.resolve("audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = state.resolve("mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"seq":1,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:XAUUSD","tick":{"timestampMs":1000,"price":"2000"}}
            {"v":1,"ts":2000,"seq":2,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","fill":{"brokerOrderId":"42"}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """
            {"v":1,"ts":1500,"method":"POST","path":"/order","engineOrderId":"o-1","responseCode":200}
            """.trimIndent() + "\n",
        )
        val bundle = tmp.resolve("golden.zip")
        val code =
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "capture",
                        "--session",
                        "alpha",
                        "--state-dir",
                        tmp.toString(),
                        "--out",
                        bundle.toString(),
                    ),
                ),
            ).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        return bundle
    }
}
