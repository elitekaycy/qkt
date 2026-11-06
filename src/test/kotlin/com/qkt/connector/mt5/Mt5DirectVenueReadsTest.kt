package com.qkt.connector.mt5

import com.qkt.candles.TimeWindow
import com.qkt.common.Side
import com.qkt.connectivity.DirectPosition
import com.qkt.connectivity.VenueRefusal
import com.qkt.connector.mt5.Mt5RoutedGateway.Companion.deal
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** `qkt bot` reads of one MT5 login: account, positions, orders, deals, quotes and bars. */
class Mt5DirectVenueReadsTest {
    private val gateway = Mt5RoutedGateway()
    private val profile =
        MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0, httpTimeoutMs = 2_000)
    private val venue = Mt5DirectVenue.of(profile)

    @AfterEach
    fun stop() = gateway.close()

    private fun position(ticket: String) =
        DirectPosition(
            ticket = ticket,
            symbol = "XAUUSDm",
            side = "BUY",
            lots = BigDecimal.ONE,
            entry = BigDecimal.ONE,
            current = null,
            sl = null,
            tp = null,
            profit = null,
            swap = null,
            openedAtMs = null,
            comment = null,
        )

    @Test
    fun `the account carries margin, profit, login and the hedging mode`() {
        gateway.on(
            "/account",
            """{"login":42,"server":"Exness-MT5Trial9","balance":1000,"equity":1010,"currency":"USD",""" +
                """"leverage":200,""" +
                """"margin_mode":2,"margin":50,"margin_free":960,"margin_level":2020,"profit":10}""",
        )

        val account = venue.account()!!

        assertThat(account.equity).isEqualByComparingTo("1010")
        assertThat(account.balance).isEqualByComparingTo("1000")
        assertThat(account.margin).isEqualByComparingTo("50")
        assertThat(account.freeMargin).isEqualByComparingTo("960")
        assertThat(account.marginLevel).isEqualByComparingTo("2020")
        assertThat(account.openProfit).isEqualByComparingTo("10")
        assertThat(account.leverage).isEqualTo(200L)
        assertThat(account.login).isEqualTo("42")
        assertThat(account.server).isEqualTo("Exness-MT5Trial9")
        assertThat(account.hedging).isTrue()
    }

    @Test
    fun `every read is null when the venue cannot be read`() {
        assertThat(venue.account()).isNull()
        assertThat(venue.positions()).isNull()
        assertThat(venue.pendingOrders()).isNull()
        assertThat(venue.deals(0L, 1L)).isNull()
        assertThat(venue.tick("EXNESS:XAUUSD")).isNull()
    }

    @Test
    fun `positions name their side from the MT5 type`() {
        gateway.on(
            "/get_positions",
            "[" + Mt5RoutedGateway.position(7, "0.10", type = 0, comment = "a") + "," +
                Mt5RoutedGateway.position(8, "0.20", priceOpen = "2410", type = 1) + "]",
        )

        val positions = venue.positions()!!

        assertThat(positions.map { it.ticket to it.side }).containsExactly("7" to "BUY", "8" to "SELL")
        assertThat(positions[1].lots).isEqualByComparingTo("0.20")
        assertThat(positions[1].entry).isEqualByComparingTo("2410")
        assertThat(positions[0].comment).isEqualTo("a")
    }

    @Test
    fun `pending orders keep their ticket, type, price and protection`() {
        gateway.on(
            "/orders",
            """[{"ticket":55,"symbol":"XAUUSDm","type_str":"BUY_LIMIT","volume_current":"0.3","price_open":"2390",""" +
                """"sl":"2380","tp":"2420","magic":10001,"comment":"dsl-x"}]""",
        )

        val order = venue.pendingOrders()!!.single()

        assertThat(order.ticket).isEqualTo("55")
        assertThat(order.type).isEqualTo("BUY_LIMIT")
        assertThat(order.lots).isEqualByComparingTo("0.3")
        assertThat(order.price).isEqualByComparingTo("2390")
        assertThat(order.sl).isEqualByComparingTo("2380")
        assertThat(order.tp).isEqualByComparingTo("2420")
        assertThat(order.comment).isEqualTo("dsl-x")
    }

    @Test
    fun `deals read their side and whether they opened or closed`() {
        gateway.on(
            "/history_deals_get",
            "[" + deal(1, 9, entry = 0, volume = "0.1", price = "2400", comment = "in") + "," +
                deal(2, 9, entry = 1, volume = "0.1", price = "2410", type = 1, commission = "-0.5", timeMs = 77) +
                "]",
        )

        val deals = venue.deals(0L, 1_000L)!!

        assertThat(deals.map { Triple(it.dealTicket, it.side, it.entry) })
            .containsExactly(Triple("1", "BUY", "IN"), Triple("2", "SELL", "OUT"))
        assertThat(deals[1].positionTicket).isEqualTo("9")
        assertThat(deals[1].commission).isEqualByComparingTo("-0.5")
        assertThat(deals[1].timeMs).isEqualTo(77L)
        assertThat(deals[0].comment).isEqualTo("in")
    }

    @Test
    fun `a quote is read on the broker symbol`() {
        gateway.on("/symbol_info_tick/XAUUSDm", """{"bid":2400.1,"ask":2400.3,"time_msc":5000}""")

        val tick = venue.tick("EXNESS:XAUUSD")!!

        assertThat(tick.bid).isEqualByComparingTo("2400.1")
        assertThat(tick.ask).isEqualByComparingTo("2400.3")
        assertThat(tick.timeMs).isEqualTo(5_000L)
    }

    @Test
    fun `bars over-fetch three times the count and keep the newest`() {
        val now = 1_800_000_000_000L / 60_000L * 60_000L
        gateway.on(
            "/fetch_data_range",
            (4 downTo 1).joinToString(",", "[", "]") { i ->
                """{"open":$i,"high":$i,"low":$i,"close":$i,"time":${(now - i * 60_000L) / 1000}}"""
            },
        )

        val bars = venue.bars("EXNESS:XAUUSD", TimeWindow(60_000L), count = 2, nowMs = now)

        assertThat(bars.map { it.close.toInt() }).containsExactly(2, 1)
        assertThat(gateway.calls("/fetch_data_range").single()).contains("symbol=XAUUSDm")
    }

    @Test
    fun `facts tolerate a venue with no symbol info and no account`() {
        gateway.on("/symbol_info_tick/XAUUSDm", """{"bid":1,"ask":2,"time":1}""")

        val facts = venue.facts("EXNESS:XAUUSD")

        assertThat(facts.bid).isEqualByComparingTo("1")
        assertThat(facts.equity).isNull()
        assertThat(facts.accountCurrency).isNull()
        assertThat(facts.contractSize).isNull()
        assertThat(facts.digits).isNull()
    }

    @Test
    fun `a blank account currency is reported as unknown`() {
        gateway.on("/symbol_info_tick/XAUUSDm", """{"bid":1,"ask":2,"time":1}""")
        gateway.on("/account", """{"balance":1,"equity":1,"currency":""}""")

        assertThat(venue.facts("EXNESS:XAUUSD").accountCurrency).isNull()
    }

    @Test
    fun `an OCO pair is refused for one-shot placement without reaching the venue`() {
        val leg = { id: String, side: Side ->
            OrderRequest.Stop(id, "EXNESS:XAUUSD", side, BigDecimal("0.1"), BigDecimal("2400"), TimeInForce.GTC, 1L)
        }
        val oco =
            OrderRequest.StandaloneOCO(
                id = "oco",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = BigDecimal("0.1"),
                leg1 = leg("a", Side.BUY),
                leg2 = leg("b", Side.SELL),
                timeInForce = TimeInForce.GTC,
                timestamp = 1L,
            )

        assertThatThrownBy { venue.place(oco, null, null, "oco") }
            .isInstanceOf(VenueRefusal::class.java)
            .hasMessageContaining("composite")
        assertThat(gateway.calls("/order")).isEmpty()
    }

    @Test
    fun `a refused cancel, a bare retcode and a ticket that is not a number are reported`() {
        gateway.on("/orders/55", "")
        gateway.on("/close_position", """{"result":{"retcode":10016,"order":0,"deal":0,"price":0,"comment":""}}""")

        val cancel = venue.cancel("55")
        assertThat(cancel.ok).isFalse()
        assertThat(cancel.error).isEqualTo("venue refused the cancel")
        assertThat(venue.close(position("42"), null, "c").error).isEqualTo("retcode 10016")
        assertThatThrownBy { venue.modify(position("x1"), null, null) }.hasMessageContaining("invalid MT5 ticket 'x1'")
    }
}
