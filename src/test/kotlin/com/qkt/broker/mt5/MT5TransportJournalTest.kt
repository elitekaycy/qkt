package com.qkt.broker.mt5

import com.qkt.common.FixedClock
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MT5TransportJournalTest {
    @Test
    fun `successful snapshot read keeps the exchange but elides the body`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"balance":"10000","equity":"10001","currency":"USD","login":7,"server":"demo"}""",
            ),
        )
        server.start()
        val journal = MT5TransportJournal(tmp, "demo/profile", FixedClock(1_700_000_000_000L))
        try {
            val client =
                MT5Client(
                    gatewayUrl = server.url("/").toString().removeSuffix("/"),
                    serverTimeZone = MT5ServerTimeZone.UTC,
                    apiKey = "do-not-record-this",
                    transportJournal = journal,
                )

            assertThat(client.getAccount()?.currency).isEqualTo("USD")
        } finally {
            journal.close()
            server.shutdown()
        }

        val line = Files.readString(tmp.resolve("demo_profile/transport-2023-11-14.jsonl"))
        assertThat(line)
            .contains("\"method\":\"GET\"")
            .contains("\"path\":\"/account\"")
            .contains("\"responseCode\":200")
            .contains("\"responseBytes\":")
            .doesNotContain("responseBody")
            .doesNotContain("do-not-record-this")
    }

    @Test
    fun `failed read keeps the response body for diagnosis`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer()
        repeat(4) { server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"terminal gone"}""")) }
        server.start()
        val journal = MT5TransportJournal(tmp, "demo", FixedClock(1_700_000_000_000L))
        try {
            val client =
                MT5Client(
                    gatewayUrl = server.url("/").toString().removeSuffix("/"),
                    serverTimeZone = MT5ServerTimeZone.UTC,
                    transportJournal = journal,
                )
            runCatching { client.getAccount() }
        } finally {
            journal.close()
            server.shutdown()
        }

        val line = Files.readString(tmp.resolve("demo/transport-2023-11-14.jsonl"))
        assertThat(line)
            .contains("\"responseCode\":500")
            .contains("terminal gone")
    }

    @Test
    fun `order exchange records the engine id separately from the placement id`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"result":{"retcode":10009,"order":42,"deal":43,"price":"1.1","comment":"ok"}}""",
            ),
        )
        server.start()
        val journal = MT5TransportJournal(tmp, "demo", FixedClock(1_700_000_000_000L))
        try {
            val client =
                MT5Client(
                    gatewayUrl = server.url("/").toString().removeSuffix("/"),
                    serverTimeZone = MT5ServerTimeZone.UTC,
                    transportJournal = journal,
                )

            client.placeOrder(
                MT5OrderRequest(
                    symbol = "EURUSDm",
                    volume = BigDecimal("0.01"),
                    type = "BUY",
                    magic = 7,
                    comment = "decorated-comment",
                    clientOrderId = "mt5-placement-1",
                    engineOrderId = "dsl-alpha--1",
                ),
            )
        } finally {
            journal.close()
            server.shutdown()
        }

        val line = Files.readString(tmp.resolve("demo/transport-2023-11-14.jsonl"))
        assertThat(line)
            .contains("\"idempotencyKey\":\"mt5-placement-1\"")
            .contains("\"engineOrderId\":\"dsl-alpha--1\"")
            .contains("\"requestBody\":")
            .contains("\\\"retcode\\\":10009")
    }
}
