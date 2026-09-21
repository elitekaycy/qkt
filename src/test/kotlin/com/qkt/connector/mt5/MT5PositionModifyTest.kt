package com.qkt.connector.mt5

import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5PositionModifyTest {
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = "http://127.0.0.1:1")
    private val books = MT5BrokerState(profile)
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal? = BigDecimal("2400.00")
        }
    private val modify =
        MT5PositionModify(
            profile,
            MT5Client(profile.gatewayUrl, profile.serverTimeZone, httpTimeoutMs = 500L, retryAttempts = 0),
            prices,
            MT5Symbol(profile.symbolPolicy),
            books,
        )

    private fun stopMoved(
        from: String,
        to: String,
    ) = BrokerEvent.PositionProtectionChanged(
        broker = "exness",
        symbol = "EXNESS:XAUUSD",
        ticket = "42",
        oldStopLoss = BigDecimal(from),
        newStopLoss = BigDecimal(to),
        oldTakeProfit = BigDecimal.ZERO,
        newTakeProfit = BigDecimal.ZERO,
    )

    @Test
    fun `a ticket that is not a number is refused without touching the venue`() {
        val ack = modify.modifyPosition("abc", BigDecimal("2390"), null)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isEqualTo("modifyPosition: bad ticket abc")
    }

    @Test
    fun `a stop inside the symbol freeze distance is refused locally`() {
        // Freeze level 50 points x 0.01 = 0.50 away from 2400.00; 2399.70 is only 0.30 away.
        books.positionBook.track(42L, MT5TicketMeta("entry-1", "gold_trend"), "EXNESS:XAUUSD", 0L)
        books.symbolMeta["XAUUSDm"] = symbolInfo(tradeFreezeLevel = 50)

        val ack = modify.modifyPosition("42", BigDecimal("2399.70"), null)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).startsWith("modify inside tradeFreezeLevel for EXNESS:XAUUSD")
        assertThat(books.expectedProtectionByTicket).isEmpty()
    }

    @Test
    fun `the engine's own stop move is expected once, at venue precision`() {
        books.expectedProtectionByTicket[42L] = MT5PositionProtection(BigDecimal("2398.504"), null)

        assertThat(modify.isExpectedProtectionChange(stopMoved("2395.00", "2398.50"))).isTrue()
        assertThat(modify.isExpectedProtectionChange(stopMoved("2395.00", "2398.50"))).isFalse()
    }

    @Test
    fun `a stop moved to a level the engine did not ask for is out of band`() {
        books.expectedProtectionByTicket[42L] = MT5PositionProtection(BigDecimal("2398.50"), null)

        assertThat(modify.isExpectedProtectionChange(stopMoved("2395.00", "2391.00"))).isFalse()
        assertThat(books.expectedProtectionByTicket).containsKey(42L)
    }

    @Test
    fun `a modify the venue never accepted leaves nothing expected`() {
        val ack = modify.modifyPosition("42", BigDecimal("2398.50"), null)

        assertThat(ack.accepted).isFalse()
        assertThat(modify.isExpectedProtectionChange(stopMoved("2395.00", "2398.50"))).isFalse()
    }

    private fun symbolInfo(tradeFreezeLevel: Int) =
        MT5SymbolInfo(
            ask = BigDecimal("2400.10"),
            bid = BigDecimal("2399.90"),
            digits = 2,
            point = BigDecimal("0.01"),
            tradeStopsLevel = 0,
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            contractSize = BigDecimal("100"),
            tradeFreezeLevel = tradeFreezeLevel,
        )
}
