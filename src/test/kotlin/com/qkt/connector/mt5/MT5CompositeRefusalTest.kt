package com.qkt.connector.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** An OCO of a buy stop and a sell stop refused whole, before or during placement. */
class MT5CompositeRefusalTest {
    private val gateway = Mt5RoutedGateway()
    private val profile = MT5DefaultProfiles.exness.copy(gatewayUrl = gateway.url, retryAttempts = 0)
    private val clock = FixedClock(1_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val books = MT5BrokerState(profile)
    private val client = MT5Client(gateway.url, MT5ServerTimeZone.UTC, httpTimeoutMs = 2_000L, retryAttempts = 0)
    private val symbol = MT5Symbol(profile.symbolPolicy)
    private val composite =
        MT5CompositePlacement(
            profile,
            client,
            bus,
            clock,
            MT5PlacementPreparation(profile, client, null, symbol, books.symbolMeta),
            SequentialIdGenerator(prefix = "t"),
            books,
            MT5BrokerEvents(profile, bus, clock),
            MT5PendingFills(profile, bus, clock, symbol, books, MT5PartialEntries(books, bus, clock)),
        )
    private val rejected = mutableListOf<BrokerEvent.OrderRejected>()
    private val request =
        OrderRequest.Market("oco-1", "EXNESS:XAUUSD", Side.BUY, BigDecimal("0.10"), TimeInForce.GTC, 1L, "gold")

    init {
        bus.subscribe<BrokerEvent.OrderRejected> { rejected.add(it) }
    }

    @AfterEach
    fun stop() = gateway.close()

    private fun leg(
        id: String,
        type: String,
        volume: String = "0.10",
    ) = MT5OrderRequest(
        symbol = "XAUUSDm",
        volume = BigDecimal(volume),
        type = type,
        price = BigDecimal("2450"),
        magic = 10001,
        comment = "oco:oco-1/$id",
    )

    private fun oco(vararg legs: MT5OrderRequest) = MT5Translation.Composite(legs.toList(), "oco-1")

    private fun result(
        retcode: Int,
        order: Long,
    ) = """{"result":{"retcode":$retcode,"order":$order,"deal":0,"price":0,"comment":""}}"""

    @Test
    fun `a leg the venue rules refuse rejects the pair before anything is sent`() {
        books.symbolMeta["XAUUSDm"] =
            MT5SymbolInfo(
                ask = BigDecimal("2400.2"),
                bid = BigDecimal("2400.0"),
                digits = 2,
                point = BigDecimal("0.01"),
                tradeStopsLevel = 0,
                volumeMin = BigDecimal("0.50"),
                volumeStep = BigDecimal("0.01"),
                contractSize = BigDecimal("100"),
            )

        val ack = composite.submitComposite(request, oco(leg("buy", "BUY_STOP"), leg("sell", "SELL_STOP")))

        assertThat(ack.accepted).isFalse()
        assertThat(rejected.single().reason).startsWith("OCO leg oco:oco-1/buy: quantized volume below venue volumeMin")
        assertThat(gateway.requests.filter { it.first.startsWith("POST /order") }).isEmpty()
    }

    @Test
    fun `a failed rollback cancel still rejects the pair and forgets the placed leg`() {
        gateway.on("/symbol_info/XAUUSDm", "<html>")
        gateway.on("/order", result(10009, 9001L), result(10019, 0L))
        gateway.drop("/orders/9001")

        val ack = composite.submitComposite(request, oco(leg("buy", "BUY_STOP"), leg("sell", "SELL_STOP")))

        assertThat(ack.accepted).isFalse()
        assertThat(rejected.single().reason).isEqualTo("OCO leg oco:oco-1/sell: retcode=10019")
        assertThat(books.pendingBook.ticketOf("buy")).isNull()
        assertThat(gateway.calls("/orders/9001")).isNotEmpty()
    }
}
