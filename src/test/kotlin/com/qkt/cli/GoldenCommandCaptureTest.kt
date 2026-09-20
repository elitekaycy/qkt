package com.qkt.cli

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.marketdata.store.BinaryBarStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenCommandCaptureTest {
    @Test
    fun `capture exports checksummed ticks fills orders and gateway exchanges`(
        @TempDir tmp: Path,
    ) {
        val state = tmp.resolve("state")
        val audit = state.resolve("audit-journal/alpha/audit-2026-08-09.jsonl")
        val orders = state.resolve("journal/alpha/journal-2026-08-09.jsonl")
        val transport = state.resolve("mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        orders.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"seq":1,"eventType":"com.qkt.events.TickEvent","symbol":"EXNESS:XAUUSD","tick":{"timestampMs":1000,"price":"2000","bid":"1999","ask":"2001"}}
            {"v":1,"ts":1100,"seq":2,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"EXNESS:XAUUSD","tick":{"timestampMs":900,"price":"1998","bid":"1997","ask":"1999"}}
            {"v":1,"ts":1200,"seq":3,"eventType":"com.qkt.events.CandleEvent","symbol":"EXNESS:XAUUSD","candle":{"startTimeMs":0,"endTimeMs":1000,"open":"1990","high":"2005","low":"1985","close":"2000","volume":"10","bid":"1999","ask":"2001"}}
            {"v":1,"ts":2000,"seq":4,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","symbol":"EXNESS:XAUUSD","fill":{"side":"BUY","price":"2001","quantity":"0.1","brokerOrderId":"42","partial":false}}
            """.trimIndent() + "\n",
        )
        Files.writeString(orders, """{"ts":1500,"kind":"submitted","id":"o-1"}""" + "\n")
        Files.writeString(
            transport,
            """
            {"v":1,"ts":1600,"seq":1,"profile":"demo","method":"POST","path":"/order","idempotencyKey":"mt5-placement-1","engineOrderId":"o-1",
            "requestBody":"{}","responseCode":200,
            "responseBody":"{\\\"result\\\":{\\\"retcode\\\":10009}}","durationMs":5}
            """.trimIndent().replace("\n", "") + "\n",
        )
        val output = tmp.resolve("golden.zip")

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
                    ),
                ),
                clock = FixedClock(1_754_740_800_000L),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        ZipFile(output.toFile()).use { zip ->
            assertThat(zip.getEntry("engine/audit-2026-08-09.jsonl")).isNotNull()
            assertThat(zip.getEntry("orders/journal-2026-08-09.jsonl")).isNotNull()
            assertThat(zip.getEntry("gateway/demo/transport-2026-08-09.jsonl")).isNotNull()
            val manifest =
                zip
                    .getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader()
                    .readText()
            val root = Json.parseToJsonElement(manifest).jsonObject
            assertThat(root["kind"]!!.jsonPrimitive.content).isEqualTo("MT5_GOLDEN_CAPTURE")
            assertThat(root["schemaVersion"]!!.jsonPrimitive.content).isEqualTo("2")
            assertThat(root["captureGitSha"]!!.jsonPrimitive.content).isEqualTo(BuildInfo.GIT_SHA)
            assertThat(root).doesNotContainKeys("gitSha", "qktVersion")
            assertThat(root["counts"].toString())
                .contains("\"ticks\":1")
                .contains("\"warmupTicks\":1")
                .contains("\"candles\":1")
                .contains("\"fills\":1")
                .contains("\"gatewayExchanges\":1")
                .contains("\"linkedPlacements\":1")
            assertThat(root["entries"].toString()).contains("\"sha256\"")
        }

        val replayRoot = tmp.resolve("replay-data")
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
                clock = FixedClock(1_754_740_800_000L),
            ).run()

        assertThat(materializeCode).isEqualTo(ExitCodes.SUCCESS)
        assertThat(Files.readString(replayRoot.resolve("symbols/XAUUSD/1970-01-01.csv")))
            .startsWith("timestamp,symbol,price,volume,bid,ask,bidVolume,askVolume\n")
            .contains("900,XAUUSD,1998,,1997,1999,,")
            .contains("1000,XAUUSD,2000,,1999,2001,,")
        assertThat(replayRoot.resolve("symbols/XAUUSD/manifest.json")).exists()
        assertThat(Files.readString(replayRoot.resolve("bars/EXNESS/XAUUSD/1s/1970-01-01.csv")))
            .contains("0,1990,2005,1985,2000,10")
        val binaryCandle =
            BinaryBarStore(replayRoot)
                .readDay("EXNESS", "XAUUSD", TimeWindow.ONE_SECOND, java.time.LocalDate.EPOCH)
                .single()
        assertThat(binaryCandle.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(binaryCandle.close).isEqualByComparingTo("2000")
        val replayManifest =
            Json.parseToJsonElement(Files.readString(replayRoot.resolve("golden-replay-manifest.json"))).jsonObject
        assertThat(replayManifest["sourceCaptureGitSha"]!!.jsonPrimitive.content).isEqualTo(BuildInfo.GIT_SHA)
        assertThat(replayManifest["counts"].toString())
            .contains("\"ticks\":1")
            .contains("\"warmupTicks\":1")
            .contains("\"candles\":1")
    }

    @Test
    fun `capture links a legacy transport exchange through the venue order ticket`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"eventType":"com.qkt.events.TickEvent","symbol":"X","tick":{"timestampMs":1000,"price":"1"}}
            {"v":1,"ts":2000,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","fill":{"brokerOrderId":"42"}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """
            {"v":1,"ts":1500,"method":"POST","path":"/order","idempotencyKey":"mt5-placement-1",
            "responseCode":200,"responseBody":"{\"result\":{\"order\":42}}"}
            """.trimIndent().replace("\n", "") + "\n",
        )
        val output = tmp.resolve("legacy-golden.zip")

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
                    ),
                ),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }
}
