package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * A coin buy whose fee the venue takes in the coin (Alpaca, measured 2026-10-28: 0.0002 BTC bought, 0.0001995 held):
 * the order completes on the venue's quantity, and the engine books what the holding received.
 */
class GatewayInKindFeeTest {
    private val listing =
        listOf(
            WireInstrument("BTCUSD", "spot", "USD", "1", "0.000000001", "0.000000001", "0.000012504"),
            WireInstrument("BTC-PERPETUAL", "perpetual", "BTC", "1", "0.5", "0.0001", "0.0001"),
            WireInstrument("AAPL", "equity", "USD", "1", "0.01", "1", "1"),
        )
    private val symbols = GatewaySymbols("ALPACA:").apply { updateListing(listing) }

    private fun translator() = GatewayEventTranslator(symbols) { "strat" }

    private fun fill(
        id: String,
        code: String,
        side: String,
        quantity: String,
        vararg costs: Pair<String, String>,
    ) = WireFill(
        "c1",
        "9",
        id,
        code,
        side,
        quantity,
        "80000",
        5,
        costs.map { (amount, ccy) ->
            WireCost("commission", amount, ccy)
        },
    )

    @Test
    fun `a coin pair's base asset is its code without the quote, for spot only`() {
        assertThat(symbols.baseAsset("ALPACA:BTCUSD")).isEqualTo("BTC")
        assertThat(symbols.baseAsset("ALPACA:BTC_PERPETUAL")).isNull()
        assertThat(symbols.baseAsset("ALPACA:AAPL")).isNull()
        val deribit =
            GatewaySymbols("DERIBIT:").apply {
                updateListing(listOf(WireInstrument("BTC_USDC", "spot", "USDC", "1", "1", "0.0001", "0.0001")))
            }
        assertThat(deribit.baseAsset("DERIBIT:BTC_USDC")).isEqualTo("BTC")
    }

    @Test
    fun `a buy paid in the coin completes the order and books the quantity received`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0002")) }
        val events = t.fill(fill("f1", "BTCUSD", "buy", "0.0002", "0.0000005" to "BTC"))
        val filled = events.single() as BrokerEvent.OrderFilled
        assertThat(filled.quantity).isEqualByComparingTo("0.0001995")
        assertThat(
            filled.typedVenueCosts
                .single()
                .amount.currency,
        ).isEqualTo("BTC")
    }

    @Test
    fun `partial fills carry the received quantity and its running total`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0004")) }
        val first =
            t
                .fill(
                    fill("f1", "BTCUSD", "buy", "0.0002", "0.0000005" to "BTC"),
                ).single() as BrokerEvent.OrderPartiallyFilled
        assertThat(first.quantity).isEqualByComparingTo("0.0001995")
        assertThat(first.cumulativeFilled).isEqualByComparingTo("0.0001995")
        val last = t.fill(fill("f2", "BTCUSD", "buy", "0.0002", "0.0000005" to "BTC")).single()
        assertThat(last).isInstanceOf(BrokerEvent.OrderFilled::class.java)
        assertThat((last as BrokerEvent.OrderFilled).quantity).isEqualByComparingTo("0.0001995")
    }

    @Test
    fun `a sale whose fee is in the coin gives up the fee too, and a fee in the quote changes nothing`() {
        val sale = translator().apply { expect("c1", BigDecimal("0.0002")) }
        val sold =
            sale
                .fill(
                    fill("f1", "BTCUSD", "sell", "0.0002", "0.0000005" to "BTC"),
                ).single() as BrokerEvent.OrderFilled
        assertThat(sold.quantity).isEqualByComparingTo("0.0002005")
        val quoteFee = translator().apply { expect("c1", BigDecimal("0.0002")) }
        val bought =
            quoteFee
                .fill(
                    fill("f1", "BTCUSD", "buy", "0.0002", "0.04" to "USD"),
                ).single() as BrokerEvent.OrderFilled
        assertThat(bought.quantity).isEqualByComparingTo("0.0002")
    }

    @Test
    fun `a contract margined in the coin books its fee as cash, never as quantity`() {
        val t = translator().apply { expect("c1", BigDecimal("10")) }
        val filled =
            t
                .fill(
                    fill("f1", "BTC-PERPETUAL", "buy", "10", "0.0000005" to "BTC"),
                ).single() as BrokerEvent.OrderFilled
        assertThat(filled.quantity).isEqualByComparingTo("10")
    }

    @Test
    fun `a fee in the coin as large as the fill is refused`() {
        assertThat(
            GatewayInKindFees.received(Side.BUY, BigDecimal("0.0002"), listOf(), "BTC"),
        ).isEqualByComparingTo("0.0002")
        val t = translator().apply { expect("c1", BigDecimal("0.0002")) }
        assertThatThrownBy { t.fill(fill("f1", "BTCUSD", "buy", "0.0002", "0.0002" to "BTC")) }
            .isInstanceOf(GatewayProtocolException::class.java)
    }

    @Test
    fun `a rebate paid in the coin adds to the holding`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0002")) }
        val filled =
            t
                .fill(
                    fill("f1", "BTCUSD", "buy", "0.0002", "-0.0000001" to "BTC"),
                ).single() as BrokerEvent.OrderFilled
        assertThat(filled.quantity).isEqualByComparingTo("0.0002001")
    }

    @Test
    fun `a venue's terminal filled below the order's quantity completes on the fill that reaches it`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0002")) }

        t.order(venueOrder("filled", filled = "0.0001995"))
        val events = t.fill(fill("f1", "BTCUSD", "buy", "0.0001995", "0.04" to "USD"))

        val filled = events.single() as BrokerEvent.OrderFilled
        assertThat(filled.quantity).isEqualByComparingTo("0.0001995")
    }

    @Test
    fun `a venue's terminal filled heard after its last fill ends the order with what filled`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0002")) }
        val first = t.fill(fill("f1", "BTCUSD", "buy", "0.0001995", "0.04" to "USD"))
        assertThat(first.single()).isInstanceOf(BrokerEvent.OrderPartiallyFilled::class.java)

        val end = t.order(venueOrder("filled", filled = "0.0001995"))

        assertThat(end).isInstanceOf(BrokerEvent.OrderCancelled::class.java)
        assertThat((end as BrokerEvent.OrderCancelled).reason).contains("filled at the venue")
        assertThat(t.order(venueOrder("filled", filled = "0.0001995"))).isNull()
    }

    @Test
    fun `a whole fill reported filled ends once, on the fill`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0002")) }

        assertThat(t.order(venueOrder("filled", filled = "0.0002"))).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(
            t.fill(fill("f1", "BTCUSD", "buy", "0.0002")).single(),
        ).isInstanceOf(BrokerEvent.OrderFilled::class.java)
        assertThat(t.order(venueOrder("filled", filled = "0.0002"))).isNull()
    }

    @Test
    fun `a restored order counts its coin-fee fills as the venue's quantity, so its last fill completes it`() {
        val t = translator().apply { expect("c1", BigDecimal("0.0004"), alreadyFilled = BigDecimal("0.0001995")) }
        t.replayed(fill("f1", "BTCUSD", "buy", "0.0002", "0.0000005" to "BTC"))

        val events = t.fill(fill("f2", "BTCUSD", "buy", "0.0002", "0.0000005" to "BTC"))

        assertThat(events.single()).isInstanceOf(BrokerEvent.OrderFilled::class.java)
        assertThat((events.single() as BrokerEvent.OrderFilled).quantity).isEqualByComparingTo("0.0001995")
    }

    private fun venueOrder(
        status: String,
        filled: String,
    ) = WireOrder(
        "c1",
        "9",
        "BTCUSD",
        "buy",
        "market",
        "0.0002",
        null,
        null,
        "gtc",
        false,
        status,
        filled,
        null,
        null,
        1,
        2,
    )
}
