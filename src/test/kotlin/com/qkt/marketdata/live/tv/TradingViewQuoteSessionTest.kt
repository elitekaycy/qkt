package com.qkt.marketdata.live.tv

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TradingViewQuoteSessionTest {
    private fun loadFrames(resource: String): Sequence<TradingViewFrame> {
        val text =
            File("src/test/resources/$resource").readText()
        return text
            .lines()
            .filter { it.isNotBlank() }
            .map { TradingViewFrame.parse(it) }
            .asSequence()
    }

    @Test
    fun `qsd frames produce ticks`() {
        val ws = FakeTradingViewWebSocket()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val session = TradingViewQuoteSession(ws, clock = clock, sessionIdGenerator = { "qs_test" })

        val captured = mutableListOf<Tick>()
        session.subscribe(
            symbols = listOf("OANDA:EURUSD"),
            onTick = { tick -> captured.add(tick) },
            onError = {},
            onDisconnect = {},
        )

        ws.replay(loadFrames("tv-fixtures/quote-session-eurusd.jsonl"))

        assertThat(captured).hasSize(3)
        assertThat(captured.map { it.symbol }).allMatch { it == "OANDA:EURUSD" }
        assertThat(captured[0].price).isEqualByComparingTo(Money.of("1.10010"))
        assertThat(captured[1].price).isEqualByComparingTo(Money.of("1.10020"))
        assertThat(captured[2].price).isEqualByComparingTo(Money.of("1.10018"))
    }

    @Test
    fun `tick carries last known bid and ask when the frame omits them`() {
        val ws = FakeTradingViewWebSocket()
        val clock = FixedClock(time = 1_700_000_000_000L)
        val session = TradingViewQuoteSession(ws, clock = clock, sessionIdGenerator = { "qs_test" })

        val captured = mutableListOf<Tick>()
        session.subscribe(
            symbols = listOf("OANDA:EURUSD"),
            onTick = { tick -> captured.add(tick) },
            onError = {},
            onDisconnect = {},
        )

        ws.replay(loadFrames("tv-fixtures/quote-session-eurusd.jsonl"))

        assertThat(captured[1].bid).isEqualByComparingTo(Money.of("1.10005"))
        assertThat(captured[1].ask).isEqualByComparingTo(Money.of("1.10015"))
        assertThat(captured[2].bid).isEqualByComparingTo(Money.of("1.10013"))
        assertThat(captured[2].ask).isEqualByComparingTo(Money.of("1.10023"))
    }

    @Test
    fun `subscribe sends create + set_fields + add_symbols in order`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD", "BINANCE:BTCUSDT"), {}, {}, {})

        assertThat(ws.commandsSent.map { it.first }).containsExactly(
            "quote_create_session",
            "quote_set_fields",
            "quote_add_symbols",
        )
        assertThat(ws.commandsSent[0].second).isEqualTo(listOf("qs_test"))
        assertThat(ws.commandsSent[2].second).isEqualTo(
            listOf("qs_test", "OANDA:EURUSD", "BINANCE:BTCUSDT"),
        )
    }

    @Test
    fun `unsubscribe sends quote_remove_symbols`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD"), {}, {}, {})
        ws.commandsSent.clear()

        session.unsubscribe(listOf("OANDA:EURUSD"))
        assertThat(ws.commandsSent).hasSize(1)
        assertThat(ws.commandsSent.single().first).isEqualTo("quote_remove_symbols")
    }

    @Test
    fun `onConnected after initial subscribe does not double-send commands`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD"), {}, {}, {})
        val afterSubscribe = ws.commandsSent.size

        ws.simulateConnect()

        assertThat(ws.commandsSent.size)
            .withFailMessage(
                "onConnected without a prior disconnect must not re-send subscribe commands; " +
                    "got ${ws.commandsSent.size - afterSubscribe} extra: " +
                    "${ws.commandsSent.drop(afterSubscribe).map { it.first }}",
            ).isEqualTo(afterSubscribe)
    }

    @Test
    fun `reconnect re-issues subscribe commands`() {
        val ws = FakeTradingViewWebSocket()
        val session = TradingViewQuoteSession(ws, clock = FixedClock(time = 0L), sessionIdGenerator = { "qs_test" })
        session.subscribe(listOf("OANDA:EURUSD"), {}, {}, {})
        val initialCount = ws.commandsSent.size

        ws.simulateDisconnect("transient network error")
        ws.simulateConnect()

        assertThat(ws.commandsSent.size).isGreaterThan(initialCount)
        val resubscribeMethods = ws.commandsSent.drop(initialCount).map { it.first }
        assertThat(resubscribeMethods).contains("quote_add_symbols")
    }
}
