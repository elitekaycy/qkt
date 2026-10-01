package com.qkt.connector.gateway

import com.qkt.accounting.CostKind
import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
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
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * An option contract through a real VGP gateway, only when `QKT_VGP_OPTION` (a qkt option symbol,
 * `DERIBIT:BTC_USDC_2OCT26_84000_C`) and `QKT_VGP_OPTION_QUANTITY` (its smallest size) are set, with the
 * account settings of [LiveVenueGatewayTest]. It buys at the ask and sells at the bid with marketable
 * IOC limits, and checks both fills come back with the venue's commission and the position ends flat.
 */
@EnabledIfEnvironmentVariable(named = "QKT_VGP_OPTION", matches = ".+")
class LiveVenueGatewayOptionTest {
    private val symbol = System.getenv("QKT_VGP_OPTION")
    private val quantity = BigDecimal(System.getenv("QKT_VGP_OPTION_QUANTITY") ?: "0")

    @Test
    fun `an option is bought at the ask and sold at the bid through the gateway, each fill with its commission`() {
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
            account.verify()
            val bus = EventBus(SystemClock(), MonotonicSequenceGenerator())
            val events = CopyOnWriteArrayList<BrokerEvent>()
            bus.subscribe<BrokerEvent.OrderFilled> { events += it }
            bus.subscribe<BrokerEvent.OrderRejected> { events += it }
            bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
            val flat =
                object : PositionProvider {
                    override fun positionFor(symbol: String): Position? = null

                    override fun allPositions() = emptyMap<String, Position>()
                }
            val broker = account.orderEntry(bus, SystemClock(), MarketPriceTracker(), flat, "live-option")

            fun trade(
                side: Side,
                n: Int,
            ): BrokerEvent.OrderFilled {
                val feed = account.marketData!!.liveTicks(listOf(symbol))
                val tick = generateSequence { feed.next() }.first { it.bid != null && it.ask != null }
                feed.close()
                val price = if (side == Side.BUY) tick.ask!! else tick.bid!!
                val stamp = System.currentTimeMillis()
                broker.submit(
                    OrderRequest.Limit(
                        "live-opt-$n-$stamp",
                        symbol,
                        side,
                        quantity,
                        price,
                        TimeInForce.IOC,
                        stamp,
                        "live-option",
                    ),
                )
                val before = events.size
                await { events.size > before }
                return events.last() as? BrokerEvent.OrderFilled
                    ?: error("option ${side.name} did not fill: ${events.last()}")
            }

            val bought = trade(Side.BUY, 1)
            val sold = trade(Side.SELL, 2)

            listOf(bought, sold).forEach { fill ->
                assertThat(fill.quantity).isEqualByComparingTo(quantity)
                assertThat(fill.typedVenueCosts.map { it.kind }).containsExactly(CostKind.COMMISSION)
            }
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
