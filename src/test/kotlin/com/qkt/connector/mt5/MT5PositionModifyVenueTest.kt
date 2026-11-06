package com.qkt.connector.mt5

import com.qkt.broker.SubmitAck
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Stop and target moves on ticket 42 as the venue answers them. */
class MT5PositionModifyVenueTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val books = MT5BrokerState(profile)
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String): BigDecimal? = BigDecimal("2400.00")
        }

    private fun modify(url: String = gateway.url) =
        MT5PositionModify(
            profile,
            MT5Client(url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0),
            prices,
            MT5Symbol(profile.symbolPolicy),
            books,
        )

    @AfterEach
    fun stop() = gateway.close()

    private fun done() = reply(10009, "")

    private fun reply(
        retcode: Int,
        comment: String,
    ) = gateway.on(
        "/modify_sl_tp",
        """{"result":{"retcode":$retcode,"order":0,"deal":0,"price":0,"comment":"$comment"}}""",
    )

    private fun trackWithProtection() {
        books.positionBook.track(
            42L,
            MT5TicketMeta("entry-1", "gold", MT5PositionProtection(BigDecimal("2390"), BigDecimal("2450"))),
            "EXNESS:XAUUSD",
            0L,
        )
    }

    private fun async(
        target: MT5PositionModify,
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck {
        val result = CompletableFuture<SubmitAck>()
        target.modifyPositionAsync(ticket, sl, tp) { result.complete(it) }
        return result.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `an accepted stop move keeps the target the position already had`() {
        trackWithProtection()
        done()

        val ack = modify().modifyPosition("42", BigDecimal("2395"), null)

        assertThat(ack.accepted).isTrue()
        assertThat(ack.rejectReason).isNull()
        assertThat(books.positionBook.meta(42L)?.protection)
            .isEqualTo(MT5PositionProtection(BigDecimal("2395"), BigDecimal("2450")))
        assertThat(gateway.requests.single().second).contains("\"position\":42").contains("\"sl\":2395")
    }

    @Test
    fun `a venue rejection forgets the expected level and reports the venue's reason`() {
        reply(10016, "Invalid stops")

        val ack = modify().modifyPosition("42", null, BigDecimal("2450"))

        assertThat(ack.accepted).isFalse()
        assertThat(books.expectedProtectionByTicket).isEmpty()
    }

    @Test
    fun `an asynchronous move lands on the position once the venue accepts it`() {
        trackWithProtection()
        done()

        val ack = async(modify(), "42", null, BigDecimal("2460"))

        assertThat(ack.accepted).isTrue()
        assertThat(books.positionBook.meta(42L)?.protection)
            .isEqualTo(MT5PositionProtection(BigDecimal("2390"), BigDecimal("2460")))
        assertThat(books.expectedProtectionByTicket[42L]).isEqualTo(MT5PositionProtection(null, BigDecimal("2460")))
    }

    @Test
    fun `an asynchronous move refuses a bad ticket and a level inside the freeze distance locally`() {
        trackWithProtection()
        books.symbolMeta["XAUUSDm"] = freezeLevel(50)

        assertThat(async(modify(), "abc", null, null).rejectReason).isEqualTo("modifyPosition: bad ticket abc")
        assertThat(async(modify(), "42", null, BigDecimal("2400.20")).rejectReason)
            .startsWith("modify inside tradeFreezeLevel")
        assertThat(gateway.requests).isEmpty()
    }

    @Test
    fun `a level outside the freeze distance goes to the venue`() {
        trackWithProtection()
        books.symbolMeta["XAUUSDm"] = freezeLevel(50)
        done()

        assertThat(modify().modifyPosition("42", BigDecimal("2399.00"), BigDecimal("2401.00")).accepted).isTrue()
    }

    @Test
    fun `an asynchronous move that cannot even be sent is refused and nothing stays expected`() {
        val ack = async(modify(url = "not a url"), "42", BigDecimal("2395"), null)

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).isNotBlank()
        assertThat(books.expectedProtectionByTicket).isEmpty()
    }

    @Test
    fun `a protection change on a ticket that is not a number was never the engine's`() {
        val event =
            BrokerEvent.PositionProtectionChanged(
                broker = "exness",
                symbol = "EXNESS:XAUUSD",
                ticket = "n/a",
                oldStopLoss = BigDecimal.ONE,
                newStopLoss = BigDecimal.TEN,
                oldTakeProfit = BigDecimal.ZERO,
                newTakeProfit = BigDecimal.ZERO,
            )

        assertThat(modify().isExpectedProtectionChange(event)).isFalse()
    }

    @Test
    fun `a target move the engine asked for is expected even when the stop is unchanged`() {
        books.expectedProtectionByTicket[42L] = MT5PositionProtection(null, BigDecimal("2460"))
        val event =
            BrokerEvent.PositionProtectionChanged(
                broker = "exness",
                symbol = "EXNESS:XAUUSD",
                ticket = "42",
                oldStopLoss = BigDecimal("2390"),
                newStopLoss = BigDecimal("2390"),
                oldTakeProfit = BigDecimal("2450"),
                newTakeProfit = BigDecimal("2460"),
            )

        assertThat(modify().isExpectedProtectionChange(event)).isTrue()
        assertThat(books.expectedProtectionByTicket).isEmpty()
    }

    private fun freezeLevel(points: Int) =
        MT5SymbolInfo(
            ask = BigDecimal("2400.10"),
            bid = BigDecimal("2399.90"),
            digits = 2,
            point = BigDecimal("0.01"),
            tradeStopsLevel = 0,
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            contractSize = BigDecimal("100"),
            tradeFreezeLevel = points,
        )
}
