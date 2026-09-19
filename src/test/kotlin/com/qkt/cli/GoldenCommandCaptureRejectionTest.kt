package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenCommandCaptureRejectionTest {
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
