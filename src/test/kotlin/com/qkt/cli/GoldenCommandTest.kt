package com.qkt.cli

import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenCommandTest {
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

    @Test
    fun `capture fails closed without gateway evidence`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"seq":1,"eventType":"com.qkt.events.TickEvent","symbol":"X","tick":{"timestampMs":1000,"price":"1"}}
            {"v":1,"ts":2000,"seq":2,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","fill":{}}
            """.trimIndent() + "\n",
        )

        val code =
            GoldenCommand(
                Args(arrayOf("golden", "capture", "--session", "alpha", "--state-dir", tmp.toString())),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `capture rejects gateway traffic that belongs to another order`(
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
            {"v":1,"ts":2000,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"alpha-order","fill":{}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """
            {"v":1,"ts":1500,"method":"POST","path":"/order","idempotencyKey":"other-order",
            "engineOrderId":"other-order","responseCode":200}
            """.trimIndent().replace("\n", "") + "\n",
        )

        val code =
            GoldenCommand(
                Args(arrayOf("golden", "capture", "--session", "alpha", "--state-dir", tmp.toString())),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `capture fails closed when the journal reports dropped records`(
        @TempDir tmp: Path,
    ) {
        val auditDir = tmp.resolve("state/audit-journal/alpha")
        val audit = auditDir.resolve("audit-1970-01-01.jsonl")
        val transport = tmp.resolve("state/mt5-transport-journal/demo/transport-1970-01-01.jsonl")
        auditDir.let(Files::createDirectories)
        transport.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"eventType":"com.qkt.events.TickEvent","symbol":"X","tick":{"timestampMs":1000,"price":"1"}}
            {"v":1,"ts":2000,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","fill":{}}
            """.trimIndent() + "\n",
        )
        Files.writeString(
            transport,
            """
            {"v":1,"ts":1500,"method":"POST","path":"/order","idempotencyKey":"o-1",
            "engineOrderId":"o-1","responseCode":200}
            """.trimIndent().replace("\n", "") + "\n",
        )
        Files.writeString(auditDir.resolve("audit-1970-01-01.dropped"), "1\n")

        val code =
            GoldenCommand(
                Args(arrayOf("golden", "capture", "--session", "alpha", "--state-dir", tmp.toString())),
            ).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `capture rejects warmup ticks and candles without structured market data`(
        @TempDir tmp: Path,
    ) {
        val audit = tmp.resolve("state/audit-journal/alpha/audit-2026-08-09.jsonl")
        audit.parent.let(Files::createDirectories)
        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"eventType":"com.qkt.events.TickEvent","symbol":"X","tick":{"timestampMs":1000,"price":"1"}}
            {"v":1,"ts":1100,"eventType":"com.qkt.events.WarmupTickEvent","symbol":"X","payload":"WarmupTickEvent(...)"}
            {"v":1,"ts":2000,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","fill":{}}
            """.trimIndent() + "\n",
        )

        val unstructuredWarmup =
            GoldenCommand(
                Args(arrayOf("golden", "capture", "--session", "alpha", "--state-dir", tmp.toString())),
            ).run()

        assertThat(unstructuredWarmup).isEqualTo(ExitCodes.USER_ERROR)

        Files.writeString(
            audit,
            """
            {"v":1,"ts":1000,"eventType":"com.qkt.events.TickEvent","symbol":"X","tick":{"timestampMs":1000,"price":"1"}}
            {"v":1,"ts":1100,"eventType":"com.qkt.events.CandleEvent","symbol":"X","payload":"CandleEvent(...)"}
            {"v":1,"ts":2000,"eventType":"com.qkt.events.BrokerEvent.OrderFilled","orderId":"o-1","fill":{}}
            """.trimIndent() + "\n",
        )

        val unstructuredCandle =
            GoldenCommand(
                Args(arrayOf("golden", "capture", "--session", "alpha", "--state-dir", tmp.toString())),
            ).run()

        assertThat(unstructuredCandle).isEqualTo(ExitCodes.USER_ERROR)
    }
}
