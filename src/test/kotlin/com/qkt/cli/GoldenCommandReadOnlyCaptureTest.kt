package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenCommandReadOnlyCaptureTest {
    @Test
    fun `read only capture exports replayable market data without fills`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"seq":1,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:XAUUSD","sourceTimeframeMs":300000,"tick":{"timestampMs":0,"price":"1998"}}
            {"v":1,"ts":1001,"seq":2,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:XAUUSD","sourceTimeframeMs":300000,"tick":{"timestampMs":75000,"price":"2005"}}
            {"v":1,"ts":1002,"seq":3,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:XAUUSD","sourceTimeframeMs":300000,"tick":{"timestampMs":150000,"price":"1990"}}
            {"v":1,"ts":1003,"seq":4,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:XAUUSD","sourceTimeframeMs":300000,"tick":{"timestampMs":299999,"price":"2000","volume":"10"}}
            {"v":1,"ts":1100,"seq":5,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:XAUUSD","tick":{"timestampMs":310000,"price":"2001"}}
            {"v":1,"ts":1200,"seq":6,"eventType":"com.qkt.events.CandleEvent","symbol":"EXNESS:XAUUSD","candle":{"startTimeMs":300000,"endTimeMs":360000,"open":"2000","high":"2001","low":"1999","close":"2001","volume":"1"}}
            {"v":1,"ts":1201,"seq":7,"eventType":"com.qkt.events.StreamCandleEvent","broker":"EXNESS","timeframe":"5m","symbol":"EXNESS:XAUUSD","candle":{"startTimeMs":300000,"endTimeMs":600000,"open":"2000","high":"2005","low":"1995","close":"2001","volume":"5"}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """{"v":1,"ts":1100,"method":"GET","path":"/account","responseCode":200}""" + "\n",
        )
        val output = tmp.resolve("read-only-golden.zip")

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
        ZipFile(output.toFile()).use { zip ->
            val manifest =
                zip
                    .getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader()
                    .readText()
            val root = Json.parseToJsonElement(manifest).jsonObject
            assertThat(root["captureMode"]!!.jsonPrimitive.content).isEqualTo("READ_ONLY")
            assertThat(root["counts"].toString())
                .contains("\"ticks\":1")
                .contains("\"warmupTicks\":4")
                .contains("\"streamCandles\":1")
                .contains("\"fills\":0")
                .contains("\"mutations\":0")
        }

        val replayRoot = tmp.resolve("read-only-replay")
        val materializeCode =
            GoldenCommand(
                Args(
                    arrayOf(
                        "golden",
                        "materialize",
                        "--bundle",
                        output.toString(),
                        "--out",
                        replayRoot.toString(),
                    ),
                ),
            ).run()

        assertThat(materializeCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(Files.readString(replayRoot.resolve("bars/EXNESS/XAUUSD/1m/1970-01-01.csv")))
            .contains("300000,2000,2001,1999,2001,1")
        assertThat(Files.readString(replayRoot.resolve("bars/EXNESS/XAUUSD/5m/1970-01-01.csv")))
            .contains("0,1998,2005,1990,2000,10")
            .contains("300000,2000,2005,1995,2001,5")
        val replayManifest =
            Json.parseToJsonElement(Files.readString(replayRoot.resolve("golden-replay-manifest.json"))).jsonObject
        assertThat(replayManifest["timeframes"].toString()).contains("1m", "5m")
        assertThat(replayManifest["materializedBars"].toString())
            .contains("CAPTURED_CANDLE_EVENT")
            .contains("CAPTURED_STREAM_CANDLE_EVENT")
            .contains("REHYDRATED_WARMUP_TICKS")
    }

    @Test
    fun `read only capture rejects a mutating gateway exchange`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """{"v":1,"ts":1000,"eventType":"com.qkt.events.TickEvent","symbol":"X","tick":""" +
                """{"timestampMs":1000,"price":"1"}}""" +
                "\n",
        )
        Files.writeString(
            transport,
            """{"v":1,"ts":1000,"method":"POST","path":"/order","responseCode":200}""" + "\n",
        )

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
                        "--read-only",
                    ),
                ),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }
}
