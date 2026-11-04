package com.qkt.connector.gateway

import com.qkt.common.FixedClock
import com.qkt.connector.mt5.MT5TransportJournal
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A gateway leg's REST exchanges and stream events land in its account's transport journal, keyed to engine orders. */
class GatewayTransportJournalTest {
    private fun waitUntil(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a submit and the events it causes are journaled with the engine order id, never the key`(
        @TempDir tmp: Path,
    ) {
        val fake = FakeGateway(listOf("BTC_USDC_PERPETUAL"))
        val journal = MT5TransportJournal(tmp, "PAPER", FixedClock(1_790_835_377_133L))
        val events = CopyOnWriteArrayList<WireEvent>()
        val stream = GatewayStream(fake.url, "the-key", { events += it }, {}, { _, _ -> }, journal = journal)
        try {
            stream.start()
            waitUntil { fake.streams == 1 }
            val client = GatewayClient(fake.url, "the-key", journal = journal)

            client.submit(
                WireSubmit("dsl-s--4.mg8q2kv1", "BTC_USDC_PERPETUAL", "buy", "market", "0.001", null, null, "ioc"),
            )
            waitUntil { events.isNotEmpty() }
        } finally {
            stream.stop()
            journal.close()
            fake.shutdown()
        }

        val lines = Files.readAllLines(tmp.resolve("PAPER/transport-2026-10-01.jsonl"))
        val submit = lines.single { "\"method\":\"POST\"" in it }
        assertThat(submit)
            .contains("\"path\":\"/v1/orders\"")
            .contains("\"engineOrderId\":\"dsl-s--4\"")
            .contains("\"responseCode\":201")
            .contains("dsl-s--4.mg8q2kv1")
        val event = lines.first { "\"method\":\"EVENT\"" in it }
        assertThat(event).contains("\"path\":\"/v1/stream\"").contains("\"engineOrderId\":\"dsl-s--4\"")
        assertThat(lines).noneMatch { "the-key" in it }
    }
}
