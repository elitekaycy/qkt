package com.qkt.connector.gateway

import com.qkt.bus.EventBus
import com.qkt.candles.TimeWindow
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.common.TimeRange
import com.qkt.connectivity.AccountConfig
import com.qkt.connectivity.AccountDirectory
import com.qkt.connectivity.ConnectorContext
import com.qkt.connectivity.ConnectorRegistry
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * qkt against a real VGP gateway, only when `QKT_VGP_URL` (and `QKT_VGP_TOKEN`) are set: a running
 * `qkt-venue-gateway` whose adapter is `QKT_VGP_ADAPTER` (default `paper`, on Deribit's live public
 * data; `deribit` for a Deribit testnet account) and whose login is `QKT_VGP_LOGIN`. It verifies the
 * account, reads live ticks and closed bars, and round-trips a small market order through the real
 * connector.
 */
@EnabledIfEnvironmentVariable(named = "QKT_VGP_URL", matches = ".+")
class LiveVenueGatewayTest {
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"

    @Test
    fun `a real gateway verifies, streams prices and bars, and fills an order both ways`() {
        val settings =
            mapOf(
                "type" to "gateway",
                "gateway_url" to System.getenv("QKT_VGP_URL"),
                "api_key" to "env:QKT_VGP_TOKEN",
                "expected_adapter" to (System.getenv("QKT_VGP_ADAPTER") ?: "paper"),
                "expected_account_login" to (System.getenv("QKT_VGP_LOGIN") ?: "paper-1"),
                "expected_trade_mode" to "demo",
            )
        val accounts =
            AccountDirectory.open(
                listOf(AccountConfig("deribit", "gateway", settings)),
                ConnectorRegistry.discover(),
                ConnectorContext(null, System.getenv(), SystemClock()),
            )
        try {
            val account = accounts.accounts.single()
            assertThat(account.verify().accountId).isEqualTo(settings["expected_account_login"])

            val feed = account.marketData!!.liveTicks(listOf(symbol))
            val tick = feed.next()
            feed.close()
            assertThat(tick!!.symbol).isEqualTo(symbol)
            assertThat(tick.price.signum()).isPositive()

            val now = Instant.now()
            val bars =
                account.marketData!!
                    .bars(
                        symbol,
                        TimeWindow.ONE_MINUTE,
                        TimeRange(now.minusSeconds(600), now),
                    ).toList()
            assertThat(bars).isNotEmpty()
            assertThat(bars.last().endTime).isLessThanOrEqualTo(now.toEpochMilli())

            val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
            val events = CopyOnWriteArrayList<BrokerEvent>()
            bus.subscribe<BrokerEvent.OrderAccepted> { events += it }
            bus.subscribe<BrokerEvent.OrderFilled> { events += it }
            bus.subscribe<BrokerEvent.OrderRejected> { events += it }
            val flat =
                object : PositionProvider {
                    override fun positionFor(symbol: String): Position? = null

                    override fun allPositions() = emptyMap<String, Position>()
                }
            val broker = account.orderEntry(bus, SystemClock(), MarketPriceTracker(), flat, "live-venue-gateway")
            val stamp = System.currentTimeMillis()
            broker.submit(
                OrderRequest.Market(
                    "live-buy-$stamp",
                    symbol,
                    Side.BUY,
                    BigDecimal("0.001"),
                    TimeInForce.GTC,
                    stamp,
                    "live-venue-gateway",
                ),
            )
            await { events.any { it is BrokerEvent.OrderFilled } || events.any { it is BrokerEvent.OrderRejected } }
            val bought = events.filterIsInstance<BrokerEvent.OrderFilled>().single()
            assertThat(bought.quantity).isEqualByComparingTo("0.001")
            assertThat(bought.price.signum()).isPositive()

            broker.submit(
                OrderRequest.Market(
                    "live-sell-$stamp",
                    symbol,
                    Side.SELL,
                    BigDecimal("0.001"),
                    TimeInForce.GTC,
                    stamp + 1,
                    "live-venue-gateway",
                ),
            )
            await { events.filterIsInstance<BrokerEvent.OrderFilled>().size == 2 }
            assertThat(broker.getOpenPositions()[symbol].orEmpty().sumOf { it.quantity }).isEqualByComparingTo("0")
            broker.shutdown()
        } finally {
            accounts.close()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(50)
        }
    }
}
