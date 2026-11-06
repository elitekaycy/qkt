package com.qkt.broker.continuous

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LegFeesTest {
    private fun commission(
        amount: String,
        currency: String,
    ) = VenueCost(CostKind.COMMISSION, MoneyAmount(BigDecimal(amount), currency), 0L)

    private fun fill(vararg fees: Pair<String, String>) =
        BrokerEvent.OrderFilled(
            clientOrderId = "roll:x:open",
            brokerOrderId = "v",
            symbol = "BINANCE_UM:BTCUSDT_241227",
            side = Side.BUY,
            price = BigDecimal("63800"),
            quantity = BigDecimal("0.01"),
            typedVenueCosts = fees.map { (amount, ccy) -> commission(amount, ccy) },
        )

    @Test
    fun `a leg's venue fees in its settlement currency add up`() {
        assertThat(fill("0.10" to "USDT", "0.05" to "USDT").venueFeesIn("usdt")).isEqualByComparingTo("0.15")
        assertThat(fill().venueFeesIn("USDT")).isEqualByComparingTo("0")
    }

    @Test
    fun `a leg fee in another currency is refused`() {
        assertThatThrownBy { fill("0.10" to "BNB").venueFeesIn("USDT") }
            .hasMessage("fee on BINANCE_UM:BTCUSDT_241227 is in BNB, not USDT")
    }
}
