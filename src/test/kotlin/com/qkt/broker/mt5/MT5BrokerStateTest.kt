package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [MT5Broker.accountState], [MT5Broker.deals], and [MT5Broker.positionTickets] mapping. */
class MT5BrokerStateTest {
    private lateinit var server: MockWebServer
    private lateinit var broker: MT5Broker

    // Per-path response factories so poller/recovery reads at construction time and the
    // surface under test don't race on a FIFO queue. Tests override before/after newBroker().
    private var accountResponse: () -> MockResponse = { MockResponse().setResponseCode(404) }
    private var positionsResponse: () -> MockResponse = { MockResponse().setBody("[]") }
    private var dealsResponse: () -> MockResponse = { MockResponse().setResponseCode(404) }

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/get_positions") -> positionsResponse()
                        path.startsWith("/orders") -> MockResponse().setBody("[]")
                        path.startsWith("/account") -> accountResponse()
                        path.startsWith("/history_deals_get") -> dealsResponse()
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
    }

    @AfterEach
    fun teardown() {
        broker.shutdown()
        server.shutdown()
    }

    private fun newBroker(): MT5Broker {
        val clock = FixedClock(time = 1_700_000_000_000L)
        val profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                httpTimeoutMs = 2000,
                retryAttempts = 0,
                pollIntervalMs = 100_000,
            )
        return MT5Broker(profile, EventBus(clock, MonotonicSequenceGenerator()), clock)
    }

    @Test
    fun `accountState maps the venue account snapshot`() {
        accountResponse = {
            MockResponse().setBody(
                """{"balance":7824.05,"equity":7676.54,"currency":"USD","leverage":100,"margin_mode":2,""" +
                    """"margin":540.97,"margin_free":7135.57,"margin_level":1419.03,"profit":-147.51,""" +
                    """"login":435898347,"server":"Exness-MT5Trial9","name":"qkt-hedge-straddle"}""",
            )
        }
        broker = newBroker()
        val state = broker.accountState()!!
        assertThat(state.broker).isEqualTo("EXNESS")
        assertThat(state.currency).isEqualTo("USD")
        assertThat(state.balance).isEqualByComparingTo("7824.05")
        assertThat(state.equity).isEqualByComparingTo("7676.54")
        assertThat(state.margin).isEqualByComparingTo("540.97")
        assertThat(state.marginFree).isEqualByComparingTo("7135.57")
        assertThat(state.openProfit).isEqualByComparingTo("-147.51")
        assertThat(state.marginLevel).isEqualByComparingTo("1419.03")
        assertThat(state.login).isEqualTo(435898347L)
        assertThat(state.server).isEqualTo("Exness-MT5Trial9")
        assertThat(state.name).isEqualTo("qkt-hedge-straddle")
    }

    @Test
    fun `accountState returns null when the gateway read fails`() {
        accountResponse = { MockResponse().setResponseCode(500).setBody("boom") }
        broker = newBroker()
        assertThat(broker.accountState()).isNull()
    }

    @Test
    fun `deals maps venue deals to qkt symbols, sides, and entry names`() {
        dealsResponse = {
            MockResponse().setBody(
                """[{"ticket":456,"order":789,"position_id":123,"symbol":"XAUUSDm","type":0,"entry":0,""" +
                    """"volume":"0.01","price":"2300.5","profit":"0","commission":"-0.07","swap":"0",""" +
                    """"fee":"0","magic":10001,"comment":"dsl-hedge_straddle","time_msc":1700040000000},""" +
                    """{"ticket":457,"order":790,"position_id":123,"symbol":"XAUUSDm","type":1,"entry":1,""" +
                    """"volume":"0.01","price":"2310.2","profit":"9.7","commission":"-0.07","swap":"-0.12",""" +
                    """"fee":"0","magic":10001,"comment":"dsl-hedge_straddle","time_msc":1700050000000}]""",
            )
        }
        broker = newBroker()
        val deals = broker.deals(from = 1_699_900_000_000L, to = 1_700_086_400_000L)
        assertThat(deals).hasSize(2)
        val opened = deals[0]
        assertThat(opened.broker).isEqualTo("EXNESS")
        assertThat(opened.dealTicket).isEqualTo("456")
        assertThat(opened.orderTicket).isEqualTo("789")
        assertThat(opened.positionTicket).isEqualTo("123")
        assertThat(opened.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(opened.side).isEqualTo(Side.BUY)
        assertThat(opened.entry).isEqualTo("IN")
        assertThat(opened.qty).isEqualByComparingTo("0.01")
        assertThat(opened.price).isEqualByComparingTo("2300.5")
        val closed = deals[1]
        assertThat(closed.side).isEqualTo(Side.SELL)
        assertThat(closed.entry).isEqualTo("OUT")
        assertThat(closed.profit).isEqualByComparingTo("9.7")
        assertThat(closed.commission).isEqualByComparingTo("-0.07")
        assertThat(closed.swap).isEqualByComparingTo("-0.12")
        assertThat(closed.magic).isEqualTo(10001)
        assertThat(closed.comment).isEqualTo("dsl-hedge_straddle")
        // time_msc is venue-clock millis; the exness profile is UTC+2.
        assertThat(closed.ts).isEqualTo(1_700_050_000_000L - 2L * 3600L * 1000L)
    }

    @Test
    fun `deals excludes balance operations from range queries`() {
        dealsResponse = {
            MockResponse().setBody(
                """[{"ticket":1,"order":0,"position_id":0,"symbol":"","type":2,"entry":0,""" +
                    """"volume":"0","price":"0","profit":"10000","commission":"0","swap":"0",""" +
                    """"fee":"0","magic":0,"comment":"deposit","time_msc":1700000000000},""" +
                    """{"ticket":456,"order":789,"position_id":123,"symbol":"XAUUSDm","type":0,"entry":0,""" +
                    """"volume":"0.01","price":"2300.5","profit":"0","commission":"-0.07","swap":"0",""" +
                    """"fee":"0","magic":10001,"comment":"dsl-hedge_straddle","time_msc":1700040000000}]""",
            )
        }
        broker = newBroker()
        val deals = broker.deals(from = 1_699_900_000_000L, to = 1_700_086_400_000L)
        assertThat(deals).hasSize(1)
        assertThat(deals[0].dealTicket).isEqualTo("456")
        assertThat(deals[0].side).isEqualTo(Side.BUY)
    }

    @Test
    fun `deals returns empty when the gateway read fails`() {
        dealsResponse = { MockResponse().setResponseCode(500).setBody("boom") }
        broker = newBroker()
        assertThat(broker.deals(from = 0L, to = 1L)).isEmpty()
    }

    @Test
    fun `positionTickets maps open tickets with broker-valued profit and swap`() {
        positionsResponse = {
            MockResponse().setBody(
                """[{"ticket":2832831596,"symbol":"XAUUSDm","type":1,"volume":"0.01",""" +
                    """"price_open":"2300.5","price_current":"2310.2","sl":"0","tp":"0",""" +
                    """"profit":"-9.3","swap":"-0.12","magic":10001,"time_msc":1700000000000,""" +
                    """"comment":"dsl-hedge_straddle"}]""",
            )
        }
        broker = newBroker()
        val tickets = broker.positionTickets()
        assertThat(tickets).hasSize(1)
        val t = tickets[0]
        assertThat(t.ticket).isEqualTo("2832831596")
        assertThat(t.symbol).isEqualTo("EXNESS:XAUUSD")
        assertThat(t.side).isEqualTo(Side.SELL)
        assertThat(t.qty).isEqualByComparingTo("0.01")
        assertThat(t.entryPrice).isEqualByComparingTo("2300.5")
        assertThat(t.currentPrice!!).isEqualByComparingTo("2310.2")
        assertThat(t.profit!!).isEqualByComparingTo("-9.3")
        assertThat(t.swap!!).isEqualByComparingTo("-0.12")
        assertThat(t.openedAt).isEqualTo(1_700_000_000_000L - 2L * 3600L * 1000L)
        assertThat(t.comment).isEqualTo("dsl-hedge_straddle")
    }

    @Test
    fun `positionTickets leaves currentPrice null when the gateway omits price_current`() {
        positionsResponse = {
            MockResponse().setBody(
                """[{"ticket":1,"symbol":"XAUUSDm","type":0,"volume":"0.01","price_open":"2300.5",""" +
                    """"sl":"0","tp":"0","profit":"1.2","magic":10001,"time_msc":1700000000000}]""",
            )
        }
        broker = newBroker()
        assertThat(broker.positionTickets().single().currentPrice).isNull()
    }

    @Test
    fun `positionTickets throws when the gateway read fails`() {
        // Not empty-on-failure: the state poller prunes ticket attributions to this
        // list, so a transient outage reading as "no positions" would wipe them.
        broker = newBroker()
        positionsResponse = { MockResponse().setResponseCode(500).setBody("boom") }
        val err = runCatching { broker.positionTickets() }.exceptionOrNull()
        assertThat(err).isNotNull
        assertThat(err!!.message).contains("gateway read failed")
    }
}
